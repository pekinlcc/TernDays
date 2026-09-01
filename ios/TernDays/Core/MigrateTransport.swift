import Foundation
import Network
import WidgetKit

/// 旧手机侧:一次性局域网服务。协议与 Android 逐字节一致:
/// 客户端 → "TERNMIG1"(8B)+sha256(key)(32B);服务端 → 4B 大端长度 + AES-GCM 密文;
/// 客户端 → "TERNDONE"(8B)+4B 大端导入条数。指纹不匹配直接断开;成功一次即停止。
final class MigrateSendServer {
    private var listener: NWListener?
    private var stopped = false
    private let queue = DispatchQueue(label: "app.terndays.migrate.server")

    var onReady: ((String) -> Void)?
    var onStatus: ((String) -> Void)?
    var onDone: ((Int) -> Void)?
    var onError: ((String) -> Void)?

    func start() {
        queue.async { [weak self] in self?.startLocked() }
    }

    private func startLocked() {
        do {
            let json = try MigrationCodec.toJson(
                datasetVersion: Cities.datasetVersion,
                exportedAtMs: Int64(Date().timeIntervalSince1970 * 1000),
                punches: DataStore.shared.allPunches(),
                overrides: DataStore.shared.allOverrides()
            )
            let key = MigrationCrypto.newKey()
            let blob = try MigrationCrypto.seal(key: key, plain: json)
            let fingerprint = MigrationCrypto.fingerprint(key: key)

            let addresses = Self.siteLocalAddresses()
            guard !addresses.isEmpty else {
                emitError("本机没有局域网地址:请先连接 Wi-Fi(或开启热点让新手机连接)")
                return
            }
            let listener = try NWListener(using: .tcp)
            self.listener = listener
            listener.stateUpdateHandler = { [weak self] state in
                guard let self else { return }
                switch state {
                case .ready:
                    if let port = listener.port?.rawValue {
                        let qr = MigrationLink.build(addresses: addresses, port: port, key: key)
                        DispatchQueue.main.async { self.onReady?(qr) }
                    }
                case .failed(let e):
                    self.emitError("启动迁移服务失败:\(e.localizedDescription)")
                default: break
                }
            }
            listener.newConnectionHandler = { [weak self] conn in
                self?.handle(conn, blob: blob, fingerprint: fingerprint)
            }
            listener.start(queue: queue)
        } catch {
            emitError("准备迁移数据失败:\(error.localizedDescription)")
        }
    }

    private func handle(_ conn: NWConnection, blob: Data, fingerprint: Data) {
        conn.start(queue: queue)
        let helloLen = MigrationLink.magicHello.count + fingerprint.count
        conn.receive(minimumIncompleteLength: helloLen, maximumLength: helloLen) { [weak self] data, _, _, _ in
            guard let self, !self.stopped else { conn.cancel(); return }
            guard let data, data.count == helloLen,
                  data.prefix(MigrationLink.magicHello.count) == MigrationLink.magicHello,
                  data.suffix(fingerprint.count) == fingerprint else {
                conn.cancel() // 陌生连接:丢弃,继续等真正的新手机
                return
            }
            DispatchQueue.main.async { self.onStatus?("新手机已连接,正在传输…") }
            var lenBE = UInt32(blob.count).bigEndian
            var out = Data(bytes: &lenBE, count: 4)
            out.append(blob)
            conn.send(content: out, completion: .contentProcessed { [weak self] err in
                guard let self else { return }
                if err != nil {
                    DispatchQueue.main.async { self.onStatus?("连接中断,可让新手机重新扫码") }
                    conn.cancel()
                    return
                }
                let doneLen = MigrationLink.magicDone.count + 4
                conn.receive(minimumIncompleteLength: doneLen, maximumLength: doneLen) { data, _, _, _ in
                    conn.cancel()
                    guard let data, data.count == doneLen,
                          data.prefix(MigrationLink.magicDone.count) == MigrationLink.magicDone else {
                        DispatchQueue.main.async { self.onStatus?("连接中断,可让新手机重新扫码") }
                        return
                    }
                    let count = data.suffix(4).reduce(0) { ($0 << 8) | Int($1) }
                    self.stopListening()
                    DispatchQueue.main.async { self.onDone?(count) }
                }
            })
        }
    }

    func stop() {
        queue.async { [weak self] in
            self?.stopped = true
            self?.stopListening()
        }
    }

    private func stopListening() {
        listener?.cancel()
        listener = nil
    }

    private func emitError(_ msg: String) {
        DispatchQueue.main.async { [weak self] in self?.onError?(msg) }
    }

    /// 本机可被局域网访问的 IPv4 地址,Wi-Fi(en0)/热点(bridge)优先。
    static func siteLocalAddresses() -> [String] {
        var found: [(name: String, ip: String)] = []
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0 else { return [] }
        defer { freeifaddrs(ifaddr) }
        var ptr = ifaddr
        while let p = ptr {
            defer { ptr = p.pointee.ifa_next }
            let ifa = p.pointee
            guard let sa = ifa.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET),
                  (ifa.ifa_flags & UInt32(IFF_UP)) != 0,
                  (ifa.ifa_flags & UInt32(IFF_LOOPBACK)) == 0 else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(sa, socklen_t(sa.pointee.sa_len), &host, socklen_t(host.count),
                              nil, 0, NI_NUMERICHOST) == 0 else { continue }
            let ip = String(cString: host)
            let parts = ip.split(separator: ".")
            let second = parts.count > 1 ? Int(parts[1]) ?? -1 : -1
            let isPrivate = ip.hasPrefix("192.168.") || ip.hasPrefix("10.")
                || (ip.hasPrefix("172.") && (16...31).contains(second))
            if isPrivate {
                found.append((String(cString: ifa.ifa_name), ip))
            }
        }
        return found
            .sorted { a, b in Self.rank(a.name) < Self.rank(b.name) }
            .map(\.ip)
            .reduce(into: [String]()) { acc, ip in if !acc.contains(ip) { acc.append(ip) } }
    }

    private static func rank(_ name: String) -> Int {
        if name.hasPrefix("en0") { return 0 }        // Wi-Fi
        if name.hasPrefix("bridge") { return 1 }     // 个人热点
        if name.hasPrefix("en") { return 2 }
        return 3
    }
}

/// 新手机侧:依次尝试各地址连接旧手机,拉取、解密、合并导入,并按本机城市库重解析。
enum MigrateImportClient {
    struct Outcome {
        let result: DataStore.MergeResult
        let remapped: Int
    }

    static func run(
        link: MigrationLink.Link,
        onStatus: @escaping (String) -> Void,
        onDone: @escaping (Outcome) -> Void,
        onError: @escaping (String) -> Void
    ) {
        tryAddress(link: link, index: 0, onStatus: onStatus, onDone: onDone, onError: onError)
    }

    private static func tryAddress(
        link: MigrationLink.Link,
        index: Int,
        onStatus: @escaping (String) -> Void,
        onDone: @escaping (Outcome) -> Void,
        onError: @escaping (String) -> Void
    ) {
        guard index < link.addresses.count else {
            DispatchQueue.main.async {
                onError("连不上旧手机。请确认:两台手机连着同一个 Wi-Fi(或本机连上旧手机的热点),旧手机的迁移页面还开着,然后重新扫码。")
            }
            return
        }
        let queue = DispatchQueue(label: "app.terndays.migrate.client")
        let conn = NWConnection(
            host: NWEndpoint.Host(link.addresses[index]),
            port: NWEndpoint.Port(rawValue: link.port)!,
            using: .tcp
        )
        var settled = false
        queue.asyncAfter(deadline: .now() + 6) {
            if !settled {
                settled = true
                conn.cancel()
                tryAddress(link: link, index: index + 1, onStatus: onStatus, onDone: onDone, onError: onError)
            }
        }
        conn.stateUpdateHandler = { state in
            switch state {
            case .ready:
                guard !settled else { return }
                settled = true
                transfer(conn: conn, link: link, queue: queue, onStatus: onStatus, onDone: onDone, onError: onError)
            case .failed, .cancelled:
                if !settled {
                    settled = true
                    conn.cancel()
                    tryAddress(link: link, index: index + 1, onStatus: onStatus, onDone: onDone, onError: onError)
                }
            default: break
            }
        }
        DispatchQueue.main.async { onStatus("正在连接旧手机…") }
        conn.start(queue: queue)
    }

    private static func transfer(
        conn: NWConnection,
        link: MigrationLink.Link,
        queue: DispatchQueue,
        onStatus: @escaping (String) -> Void,
        onDone: @escaping (Outcome) -> Void,
        onError: @escaping (String) -> Void
    ) {
        let fail: (String) -> Void = { msg in
            conn.cancel()
            DispatchQueue.main.async { onError(msg) }
        }
        var hello = MigrationLink.magicHello
        hello.append(MigrationCrypto.fingerprint(key: link.key))
        conn.send(content: hello, completion: .contentProcessed { err in
            if err != nil { fail("传输中断,请重新扫码再试。"); return }
            DispatchQueue.main.async { onStatus("正在接收数据…") }
            conn.receive(minimumIncompleteLength: 4, maximumLength: 4) { lenData, _, _, _ in
                guard let lenData, lenData.count == 4 else { fail("传输中断,请重新扫码再试。"); return }
                let len = lenData.reduce(0) { ($0 << 8) | Int($1) }
                guard len > 0, len <= MigrationLink.maxBlobBytes else { fail("收到的数据长度异常,已中止"); return }
                receiveExactly(conn, total: len, buffer: Data()) { blob in
                    guard let blob else { fail("传输中断,请重新扫码再试。"); return }
                    do {
                        let payload = try MigrationCodec.parse(
                            try MigrationCrypto.open(key: link.key, blob: blob)
                        )
                        DispatchQueue.main.async { onStatus("正在合并导入…") }
                        let result = DataStore.shared.mergeImported(
                            punches: payload.punches, overrides: payload.overrides
                        )
                        var done = MigrationLink.magicDone
                        var countBE = UInt32(result.punchesAdded + result.overridesAdded).bigEndian
                        done.append(Data(bytes: &countBE, count: 4))
                        conn.send(content: done, completion: .contentProcessed { _ in conn.cancel() })

                        // 导入的记录可能来自不同版本的城市库:按本机库重解析(幂等)
                        var remapped = 0
                        if result.punchesAdded > 0 {
                            let m = Cities.matcher
                            remapped = DataStore.shared.remapCities { lat, lng in
                                m.nearest(lat: lat, lng: lng).map { ($0.cityKey, $0.cityName) }
                            }
                            WidgetCenter.shared.reloadAllTimelines()
                            NotificationCenter.default.post(name: .terndaysDataChanged, object: nil)
                        }
                        DispatchQueue.main.async { onDone(Outcome(result: result, remapped: remapped)) }
                    } catch {
                        fail(error.localizedDescription)
                    }
                }
            }
        })
    }

    private static func receiveExactly(_ conn: NWConnection, total: Int, buffer: Data, done: @escaping (Data?) -> Void) {
        let remaining = total - buffer.count
        if remaining <= 0 { done(buffer); return }
        conn.receive(minimumIncompleteLength: 1, maximumLength: remaining) { chunk, _, isComplete, err in
            guard let chunk, err == nil else { done(nil); return }
            var next = buffer
            next.append(chunk)
            if next.count >= total {
                done(next)
            } else if isComplete {
                done(nil)
            } else {
                receiveExactly(conn, total: total, buffer: next, done: done)
            }
        }
    }
}
