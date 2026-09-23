import CryptoKit
import Foundation

/// 换手机数据迁移(与 Android :core Migration.kt 同一套格式与协议)。
/// 交换 JSON 与本地 Codable 天然对齐:LocalDate 编码为 "yyyy-MM-dd"、Slot 用 MORNING/EVENING/EXTRA。
struct MigrationPayload: Codable {
    let app: String
    let format: Int
    let datasetVersion: Int
    let exportedAtMs: Int64
    let punches: [Punch]
    let overrides: [DayOverride]
}

enum MigrationCodec {
    static let appName = "TernDays"
    /// v2 新增 override.scope(半天更正)。仅当数据里真有半天更正才标 2,老版本仍可接收全天数据。
    static let format = 2

    static func toJson(datasetVersion: Int, exportedAtMs: Int64, punches: [Punch], overrides: [DayOverride]) throws -> Data {
        let needsV2 = overrides.contains { $0.scope != .full }
        return try JSONEncoder().encode(
            MigrationPayload(
                app: appName, format: needsV2 ? 2 : 1, datasetVersion: datasetVersion,
                exportedAtMs: exportedAtMs, punches: punches, overrides: overrides
            )
        )
    }

    static func parse(_ data: Data) throws -> MigrationPayload {
        let payload: MigrationPayload
        do {
            payload = try JSONDecoder().decode(MigrationPayload.self, from: data)
        } catch {
            throw MigrationError.badData("数据解析失败,可能已损坏")
        }
        guard payload.app == appName else { throw MigrationError.badData("不是 TernDays 的迁移数据") }
        guard payload.format >= 1 && payload.format <= format else {
            throw MigrationError.badData("数据格式版本 \(payload.format) 不受支持,请先升级本机应用")
        }
        return payload
    }
}

enum MigrationError: LocalizedError {
    case badData(String)
    var errorDescription: String? {
        switch self {
        case .badData(let m): return m
        }
    }
}

/// AES-256-GCM,密文布局 = 12B nonce + 密文 + 16B tag(CryptoKit combined 格式,与 Android 兼容)。
enum MigrationCrypto {
    static let keyBytes = 32

    static func newKey() -> Data {
        SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) }
    }

    static func seal(key: Data, plain: Data) throws -> Data {
        guard let combined = try AES.GCM.seal(plain, using: SymmetricKey(data: key)).combined else {
            throw MigrationError.badData("加密失败")
        }
        return combined
    }

    static func open(key: Data, blob: Data) throws -> Data {
        do {
            return try AES.GCM.open(AES.GCM.SealedBox(combined: blob), using: SymmetricKey(data: key))
        } catch {
            throw MigrationError.badData("解密失败:密钥不匹配或数据被篡改")
        }
    }

    static func fingerprint(key: Data) -> Data { Data(SHA256.hash(data: key)) }
}

/// 二维码内容与二进制传输协议常量(与 Android 逐字节一致,见 :core MigrationLink 注释)。
enum MigrationLink {
    static let schemePrefix = "terndays://migrate?"
    static let magicHello = Data("TERNMIG1".utf8)
    static let magicDone = Data("TERNDONE".utf8)
    static let maxBlobBytes = 32 * 1024 * 1024

    struct Link {
        let addresses: [String]
        let port: UInt16
        let key: Data
    }

    static func build(addresses: [String], port: UInt16, key: Data) -> String {
        let k = key.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        return schemePrefix + "v=1&a=" + addresses.joined(separator: ",") + "&p=\(port)&k=" + k
    }

    /// 迁移只走局域网:二维码里的地址必须是私网/链路本地地址（与 :core MigrationLink.isLanAddress 逐条对齐）。
    /// IPv6 必须能完整解析成数字字面量:"fd:x.attacker.example" 这种带冒号的主机名一律拒绝。
    static func isLanAddress(_ address: String) -> Bool {
        let trimmed = address.trimmingCharacters(in: .whitespaces)
        let pieces = trimmed.split(separator: "%", maxSplits: 1, omittingEmptySubsequences: false)
        let a = String(pieces.first ?? "")
        let zone = pieces.count > 1 ? String(pieces[1]) : nil
        if a.isEmpty { return false }
        if a.contains(":") {
            guard let h = parseIPv6(a) else { return false }
            if let zone, !isZoneId(zone) { return false }
            return (h.prefix(7).allSatisfy { $0 == 0 } && h[7] == 1) // ::1
                || (h[0] & 0xFE00) == 0xFC00 // 唯一本地地址 fc00::/7
                || (h[0] & 0xFFC0) == 0xFE80 // 链路本地 fe80::/10
        }
        if zone != nil { return false }
        guard let n = parseIPv4(a) else { return false }
        if n[0] == 10 || n[0] == 127 { return true }
        if n[0] == 192 && n[1] == 168 { return true }
        if n[0] == 172 && (16...31).contains(n[1]) { return true }
        if n[0] == 169 && n[1] == 254 { return true }
        return false
    }

    private static func isZoneId(_ z: String) -> Bool {
        (1...32).contains(z.count) && z.unicodeScalars.allSatisfy {
            CharacterSet.alphanumerics.contains($0) && $0.isASCII || "_.-".unicodeScalars.contains($0)
        }
    }

    /// 严格的点分十进制:四段、每段 1–3 位纯数字、0...255。
    private static func parseIPv4(_ s: String) -> [Int]? {
        let parts = s.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4 else { return nil }
        var out: [Int] = []
        for p in parts {
            guard (1...3).contains(p.count), p.allSatisfy({ ("0"..."9").contains($0) }),
                  let v = Int(p), v <= 255 else { return nil }
            out.append(v)
        }
        return out
    }

    /// RFC 4291 文本形式 → 8 个 16 位分组;支持 "::" 压缩与末尾内嵌 IPv4。
    private static func parseIPv6(_ s: String) -> [Int]? {
        let allowed = Set("0123456789abcdefABCDEF:.")
        guard s.allSatisfy({ allowed.contains($0) }) else { return nil }
        let halves = s.components(separatedBy: "::")
        guard halves.count <= 2 else { return nil }
        func groups(_ part: String, allowV4Tail: Bool) -> [Int]? {
            if part.isEmpty { return [] }
            let items = part.split(separator: ":", omittingEmptySubsequences: false)
            var out: [Int] = []
            for (i, g) in items.enumerated() {
                if g.contains(".") {
                    guard allowV4Tail, i == items.count - 1, let v4 = parseIPv4(String(g)) else { return nil }
                    out.append((v4[0] << 8) | v4[1])
                    out.append((v4[2] << 8) | v4[3])
                } else {
                    guard (1...4).contains(g.count), let v = Int(g, radix: 16) else { return nil }
                    out.append(v)
                }
            }
            return out
        }
        if halves.count == 1 {
            guard let all = groups(s, allowV4Tail: true), all.count == 8 else { return nil }
            return all
        }
        guard let head = groups(halves[0], allowV4Tail: false),
              let tail = groups(halves[1], allowV4Tail: true),
              head.count + tail.count <= 7 else { return nil }
        return head + Array(repeating: 0, count: 8 - head.count - tail.count) + tail
    }

    static func parse(_ text: String) -> Link? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard t.hasPrefix(schemePrefix) else { return nil }
        var params: [String: String] = [:]
        for pair in t.dropFirst(schemePrefix.count).split(separator: "&") {
            if let eq = pair.firstIndex(of: "=") {
                params[String(pair[..<eq])] = String(pair[pair.index(after: eq)...])
            }
        }
        guard params["v"] == "1",
              let a = params["a"],
              let p = params["p"], let port = UInt16(p), port > 0,
              let k = params["k"] else { return nil }
        let addresses = a.split(separator: ",").map(String.init).filter { !$0.isEmpty && isLanAddress($0) }
        guard !addresses.isEmpty else { return nil }
        var b64 = k.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while b64.count % 4 != 0 { b64 += "=" }
        guard let key = Data(base64Encoded: b64), key.count == MigrationCrypto.keyBytes else { return nil }
        return Link(addresses: addresses, port: port, key: key)
    }
}
