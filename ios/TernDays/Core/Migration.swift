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
    static let format = 1

    static func toJson(datasetVersion: Int, exportedAtMs: Int64, punches: [Punch], overrides: [DayOverride]) throws -> Data {
        try JSONEncoder().encode(
            MigrationPayload(
                app: appName, format: format, datasetVersion: datasetVersion,
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
        let addresses = a.split(separator: ",").map(String.init).filter { !$0.isEmpty }
        guard !addresses.isEmpty else { return nil }
        var b64 = k.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while b64.count % 4 != 0 { b64 += "=" }
        guard let key = Data(base64Encoded: b64), key.count == MigrationCrypto.keyBytes else { return nil }
        return Link(addresses: addresses, port: port, key: key)
    }
}
