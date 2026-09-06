import Foundation

/// 桌面小组件的底面外观（与 Android :core WidgetStyle 用同一套 id）。
///
/// 三者共用同一套信息层级（年份眉题 + Top 3 城市三行等权重），只换底面与文字配色。
enum WidgetStyle: String, CaseIterable {
    /// 素面：白 / #1C1C1E 实心底，与系统自带的数据类小组件同质
    case plain = "PLAIN"
    /// 系统材质：iOS 17+ 用真实系统材质，壁纸透过来由系统实时模糊（iOS 16 回落到素面）
    case material = "MATERIAL"
    /// 品牌渐变：一个色相、两个色阶的竖向渐变，文字全白
    case gradient = "GRADIENT"

    static let `default`: WidgetStyle = .plain

    /// 解析存储值；空、未知、更新版本写入的值一律回落到默认。
    static func from(_ id: String?) -> WidgetStyle {
        guard let id, let s = WidgetStyle(rawValue: id) else { return .default }
        return s
    }

    var label: String {
        switch self {
        case .plain: return "素面"
        case .material: return "系统材质"
        case .gradient: return "品牌渐变"
        }
    }

    var hint: String {
        switch self {
        case .plain: return "实心底面，和系统自带的小组件同质，放在任何壁纸上都稳。"
        case .material: return "壁纸透过来、由系统实时模糊（需要 iOS 17 及以上，更早的系统显示为素面）。"
        case .gradient: return "品牌色竖向渐变、文字全白，一眼认得出，也不挑壁纸。"
        }
    }

    /// 主应用与小组件扩展共享同一份偏好（App Group）。
    private static let key = "widgetStyle"

    private static var store: UserDefaults { UserDefaults(suiteName: AppGroup.id) ?? .standard }

    static var current: WidgetStyle {
        get { from(store.string(forKey: key)) }
        set { store.set(newValue.rawValue, forKey: key) }
    }
}
