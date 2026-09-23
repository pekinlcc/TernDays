import CommonCrypto
import Foundation

/// 加密文件备份(全程离线),与 Android :core Backup 同一文件格式、同一派生口径:
/// 内容 = 迁移交换 JSON(MigrationCodec),口令经 PBKDF2-HMAC-SHA256 派生 256 位密钥,
/// 再用与迁移相同的 AES-GCM(MigrationCrypto,nonce‖密文‖tag)加密。
///
/// 文件格式:"TERNBAK1"(8B) + 迭代次数(4B 大端) + 盐(16B) + AES-GCM 密文。
/// 迭代次数写在文件头,以后调高也能读旧备份。口令一律按 UTF-8 字节参与派生,
/// 这样 Android(手写 PBKDF2)与 iOS(CommonCrypto)派生出的密钥逐字节相同。
enum Backup {

    static let magic = Data("TERNBAK1".utf8)
    static let defaultIterations = 310_000
    static let saltBytes = 16
    static let minPassphrase = 8
    static let fileExtension = "terndays"

    /// 文案可直接展示
    struct BackupError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    /// 口令长度按 UTF-16 计(与 Kotlin String.length 一致)
    static func isPassphraseLongEnough(_ passphrase: String) -> Bool {
        passphrase.utf16.count >= minPassphrase
    }

    static func seal(passphrase: String, plain: Data, iterations: Int = Backup.defaultIterations) throws -> Data {
        guard isPassphraseLongEnough(passphrase) else { throw BackupError(message: "口令至少 \(minPassphrase) 位") }
        var rng = SystemRandomNumberGenerator()
        let salt = Data((0..<saltBytes).map { _ in UInt8.random(in: UInt8.min...UInt8.max, using: &rng) })
        let key = try pbkdf2(password: Data(passphrase.utf8), salt: salt, iterations: iterations,
                             keyLength: MigrationCrypto.keyBytes)
        let blob = try MigrationCrypto.seal(key: key, plain: plain)
        let it = UInt32(iterations)
        var out = magic
        out.append(contentsOf: [UInt8((it >> 24) & 0xFF), UInt8((it >> 16) & 0xFF), UInt8((it >> 8) & 0xFF), UInt8(it & 0xFF)])
        out.append(salt)
        out.append(blob)
        return out
    }

    /// - Throws: BackupError(不是 TernDays 备份、口令不对,或文件损坏;文案可直接展示)
    static func open(passphrase: String, file: Data) throws -> Data {
        guard isBackup(file) else { throw BackupError(message: "这不是 TernDays 的备份文件") }
        let bytes = [UInt8](file)
        let base = magic.count
        let iterations = (Int(bytes[base]) << 24) | (Int(bytes[base + 1]) << 16)
            | (Int(bytes[base + 2]) << 8) | Int(bytes[base + 3])
        guard (1...10_000_000).contains(iterations) else { throw BackupError(message: "备份文件已损坏") }
        let saltStart = base + 4
        let salt = Data(bytes[saltStart..<(saltStart + saltBytes)])
        let blob = Data(bytes[(saltStart + saltBytes)...])
        let key = try pbkdf2(password: Data(passphrase.utf8), salt: salt, iterations: iterations,
                             keyLength: MigrationCrypto.keyBytes)
        do {
            return try MigrationCrypto.open(key: key, blob: blob)
        } catch {
            throw BackupError(message: "口令不对,或备份文件已损坏")
        }
    }

    static func isBackup(_ file: Data) -> Bool {
        file.count > magic.count + 4 + saltBytes + 28 && Data(file.prefix(magic.count)) == magic
    }

    /// RFC 8018 PBKDF2,PRF = HMAC-SHA256(CommonCrypto)。
    static func pbkdf2(password: Data, salt: Data, iterations: Int, keyLength: Int) throws -> Data {
        guard iterations > 0, keyLength > 0 else { throw BackupError(message: "备份文件已损坏") }
        let pw: [CChar] = password.map { CChar(bitPattern: $0) }
        let saltArr = [UInt8](salt)
        let pwLen = pw.count
        let saltLen = saltArr.count
        var derived = [UInt8](repeating: 0, count: keyLength)
        // 长度先取到局部常量:同一次调用里既 &derived 又读 derived.count 会触发独占访问冲突
        let status = CCKeyDerivationPBKDF(
            CCPBKDFAlgorithm(kCCPBKDF2),
            pw, pwLen,
            saltArr, saltLen,
            CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
            UInt32(iterations),
            &derived, keyLength
        )
        guard status == Int32(kCCSuccess) else { throw BackupError(message: "密钥派生失败") }
        return Data(derived)
    }
}
