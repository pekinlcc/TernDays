package app.terndays.core

/**
 * 桌面小组件展示内容（纯函数，便于单测；Android/iOS 共用同一口径）。
 * 只保留最关键的信息：Top N 城市及天数。
 */
object WidgetSummary {

    data class CityLine(val name: String, val days: String)

    data class Model(
        val yearLabel: String,          // "2026 年"
        val topCities: List<CityLine>,  // 按天数降序，最多 topN 个
        /** 今年还空着、但往年有记录(典型:元旦凌晨):空态要说「今年的第一条会在下次打卡后出现」 */
        val newYearEmpty: Boolean = false,
    )

    fun build(stats: YearStats, topN: Int = 3): Model = Model(
        yearLabel = "${stats.year} 年",
        topCities = stats.cities.take(topN).map {
            CityLine(it.cityName, DayCounting.formatDays(it.days))
        },
        newYearEmpty = stats.cities.isEmpty() && (stats.trackingSince?.let { it.year < stats.year } == true),
    )

    /**
     * 小组件竖向放得下几行城市(1–3)。与 tools/gen_widget_layouts.py 的布局一致:
     * 上下内边距 32dp + 年份后最小间距 8dp + 行间 6dp 不随字号变;
     * 年份(12sp,约 14 行高)与每行(天数 19sp,约 22 行高)随字号缩放。
     * 此前把整个阈值乘 fontScale,连不随字号变的边距也一起放大,大字号下平白少一行。
     *
     * @param heightDp 启动器给的竖屏高度;0 = 没给尺寸,按标准 2×2 放满 3 行
     * @param fontScale 文字缩放(Android 14+ 为非线性缩放,调用方按 19sp 实测换算)
     */
    fun maxRows(heightDp: Int, fontScale: Float): Int {
        if (heightDp <= 0) return 3
        val s = fontScale.coerceIn(0.85f, 2f)
        fun need(n: Int) = 32 + 8 + 6 * (n - 1) + 14 * s + 22 * s * n
        return when {
            heightDp >= need(3) -> 3
            heightDp >= need(2) -> 2
            else -> 1
        }
    }
}
