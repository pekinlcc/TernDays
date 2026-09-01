import SwiftUI

/// 设计稿配色（见 design 目录）
enum Td {
    static let ink = Color(hex: 0x1F2B38)
    static let muted = Color(hex: 0x6B7A89)
    static let faint = Color(hex: 0x9AA7B4)
    static let border = Color(hex: 0xE4E9EE)
    static let divider = Color(hex: 0xEEF2F5)
    static let bg = Color(hex: 0xF4F6F8)
    static let surface = Color.white
    static let accent = Color(hex: 0x2E7FA8)
    static let accentDeep = Color(hex: 0x1F6289)
    static let accentSoft = Color(hex: 0xDEEDF5)
    static let accentFaintBg = Color(hex: 0xEAF3F8)
    static let warmSoft = Color(hex: 0xF6ECDD)
    static let warmDeep = Color(hex: 0x8A5F22)
}

extension Color {
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}

struct TdCard<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        content
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Td.surface)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .shadow(color: Td.ink.opacity(0.04), radius: 2, y: 1)
    }
}

struct TagView: View {
    let text: String
    let bg: Color
    let fg: Color

    var body: some View {
        Text(text)
            .font(.system(size: 11, weight: .semibold))
            .foregroundColor(fg)
            .padding(.horizontal, 9)
            .padding(.vertical, 4)
            .background(Capsule().fill(bg))
    }
}
