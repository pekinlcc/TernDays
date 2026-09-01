# TernDays iOS

SwiftUI 实现，与 Android 版共用同一套计天算法、离线城市库（`TernDays/cities.tsv`）与页面设计。

> ⚠️ 本目录在无 Mac 的环境中编写，**尚未经 Xcode 编译与真机验证**，遇到编译问题欢迎提 issue。

## 构建

1. 需要 macOS + **Xcode 16 及以上**（工程使用了新的目录同步格式，Xcode 会自动纳入 `TernDays/` 下的全部源码与资源）
2. 打开 `ios/TernDays.xcodeproj`
3. 在 Signing & Capabilities 里选择你自己的开发者 Team（Bundle ID 默认 `app.terndays.ios`，可改）
4. 连接 iPhone，Run

## iOS 的打卡策略（与 Android 的差异）

iOS 不允许应用在后台精确定时执行任务，因此打卡通过多路兜底组合完成：

| 途径 | 说明 |
| --- | --- |
| 显著位置变化（SLC） | 「始终允许」定位下，设备明显移动时系统唤醒应用，若在打卡窗口内且缺记录则就地记录 |
| BGAppRefreshTask | 系统择机唤醒（倾向于你常用应用的时间段），窗口内补打 |
| 本地通知 | 每天 07:00 / 17:00 提醒，点开应用即完成补打 |
| 前台补打 | 任何时候打开应用，处于窗口内且缺记录就自动补打 |

补捕窗口与 Android 相同：早点 07:00–11:59、晚点 17:00–23:59；同一天同一时段只保留最早一条记录。
因此 iOS 的实际打卡时间可能偏离整点更多（明细中会标注「延迟」），但城市级统计通常不受影响。

## 目录结构

```
TernDays/
  Core/       计天算法、打卡窗口规则、离线城市匹配、CSV/XLSX 导出（与 android/core 逐一对应）
  Data/       本地 JSON 存储、城市库加载
  Punch/      PunchManager：定位权限、SLC、BGTask、本地通知
  UI/         首页 / 城市日历详情 / 导出 / 设置(补记) / 引导
  cities.tsv  离线城市库（tools/build_city_dataset.py 生成）
```
