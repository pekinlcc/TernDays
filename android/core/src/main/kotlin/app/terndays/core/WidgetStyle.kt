package app.terndays.core

/**
 * 桌面小组件的底面外观（Android / iOS 用同一套 id，便于文档与后续迁移对齐）。
 *
 * 三者共用同一套信息层级（年份眉题 + Top 3 城市三行等权重），只换底面与文字配色：
 *  - [PLAIN]    素面：白 / #1C1C1E 实心底，与系统自带的数据类小组件同质，任何壁纸上都稳
 *  - [MATERIAL] 系统材质：iOS 17+ 用真实系统材质（壁纸透过来由系统实时模糊）；
 *               Android 小组件是静态快照，做不出真模糊，用 90% 不透明的中性底近似
 *  - [GRADIENT] 品牌渐变：一个色相、两个色阶的竖向渐变，文字全白，辨识度最高、不挑壁纸
 */
enum class WidgetStyle(val id: String) {
    PLAIN("PLAIN"),
    MATERIAL("MATERIAL"),
    GRADIENT("GRADIENT"),
    ;

    companion object {
        val DEFAULT = PLAIN

        /** 解析存储值；空、未知、旧版本写入的值一律回落到默认，绝不抛异常。 */
        fun from(id: String?): WidgetStyle = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
