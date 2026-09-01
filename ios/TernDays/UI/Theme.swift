import SwiftUI
import UIKit

/// 设计稿配色(浅色见 design 目录,深色对应 DarkAlt 方向)。
/// 全部为动态色:系统切换深浅模式时自动生效,调用点无需改动;
/// 系统 List/导航栏的底色本就自适应,与这套动态色在深色下保持一体。
enum Td {
    static let ink = Color(light: 0x1F2B38, dark: 0xE8EEF3)
    static let muted = Color(light: 0x5F6F7E, dark: 0xA8B6C2)
    static let faint = Color(light: 0x7A8894, dark: 0x7E8C99)
    static let border = Color(light: 0xE4E9EE, dark: 0x2C3A4A)
    static let divider = Color(light: 0xEEF2F5, dark: 0x26313E)
    static let bg = Color(light: 0xF4F6F8, dark: 0x141C26)
    static let surface = Color(light: 0xFFFFFF, dark: 0x1D2937)
    static let accent = Color(light: 0x2E7FA8, dark: 0x7CC0E8)
    static let accentDeep = Color(light: 0x1F6289, dark: 0xA5D4EE)
    static let accentSoft = Color(light: 0xDEEDF5, dark: 0x24435A)
    static let accentFaintBg = Color(light: 0xEAF3F8, dark: 0x1E3648)
    static let warmSoft = Color(light: 0xF6ECDD, dark: 0x3D3122)
    static let warmDeep = Color(light: 0x8A5F22, dark: 0xE0B570)
    /// 强调色按钮上的文字:深色模式强调色变浅,配深墨字才有对比
    static let onAccent = Color(light: 0xFFFFFF, dark: 0x10222E)
    /// 装饰性箭头等次要图形
    static let chevron = Color(light: 0xC3CCD4, dark: 0x57667A)
}

extension Color {
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }

    /// 深浅自适应色
    init(light: UInt32, dark: UInt32) {
        self.init(UIColor { trait in
            let hex = trait.userInterfaceStyle == .dark ? dark : light
            return UIColor(
                red: CGFloat((hex >> 16) & 0xFF) / 255,
                green: CGFloat((hex >> 8) & 0xFF) / 255,
                blue: CGFloat(hex & 0xFF) / 255,
                alpha: 1
            )
        })
    }
}

struct TdCard<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        content
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Td.surface)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .shadow(color: Color.black.opacity(0.06), radius: 2, y: 1)
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
