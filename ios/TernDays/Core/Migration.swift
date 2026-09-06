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

    /// 迁移只走局域网:二维码里的地址必须是私网/链路本地地址（与 :core MigrationLink.isLanAddress 对齐）。
    static func isLanAddress(_ address: String) -> Bool {
        let a = String(address.trimmingCharacters(in: .whitespaces).split(separator: "%").first ?? "")
        if a.isEmpty { return false }
        if a.contains(":") {
            let lower = a.lowercased()
            return lower == "::1" || lower.hasPrefix("fe80:") || lower.hasPrefix("fd") || lower.hasPrefix("fc")
        }
        let parts = a.split(separator: ".").map { Int($0) }
        guard parts.count == 4, !parts.contains(where: { $0 == nil }) else { return false }
        let n = parts.map { $0! }
        guard !n.contains(where: { $0 < 0 || $0 > 255 }) else { return false }
        if n[0] == 10 || n[0] == 127 { return true }
        if n[0] == 192 && n[1] == 168 { return true }
        if n[0] == 172 && (16...31).contains(n[1]) { return true }
        if n[0] == 169 && n[1] == 254 { return true }
        return false
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
