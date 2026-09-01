package app.terndays.android

import androidx.compose.runtime.mutableIntStateOf

/**
 * 极简数据变更总线:后台写库(自动打卡/补打/重解析/迁移导入)后 bump 一次,
 * 前台页面把 [version] 作为加载 key,数据就会即时刷新——
 * 修掉「Toast 说修正了 N 条,页面里还是旧数据」的矛盾。
 * Compose 快照状态允许任意线程写入。
 */
object DataBus {
    val version = mutableIntStateOf(0)

    fun bump() {
        version.intValue++
    }
}
