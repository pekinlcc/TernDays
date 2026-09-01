#!/usr/bin/env bash
# TernDays 安卓真机调试脚本 —— 在「你自己的电脑」上运行（云端会话摸不到 USB）。
# 前提：装了 adb（platform-tools），手机开了「USB 调试」并已授权此电脑。
#
# 用法：
#   ./tools/debug_android.sh                 # 只做检查 + 触发打卡 + 看日志
#   ./tools/debug_android.sh TernDays-v0.4.apk   # 先安装 APK 再做上述动作
set -u
PKG=app.terndays
SVC=$PKG/app.terndays.android.punch.PunchService

step() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }

step "设备连接检查"
adb get-state >/dev/null 2>&1 || { echo "没检测到设备：确认 USB 调试已开且已授权（adb devices）"; exit 1; }
adb devices -l | sed -n '2p'

if [ "${1:-}" != "" ]; then
  step "安装 APK：$1"
  adb install -r "$1"
fi

step "授权（个别项在部分 ROM 上会失败，忽略即可，去应用里手动给）"
adb shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION 2>/dev/null || echo "  - 前台定位：请在应用内授权"
adb shell pm grant $PKG android.permission.ACCESS_COARSE_LOCATION 2>/dev/null || true
adb shell pm grant $PKG android.permission.ACCESS_BACKGROUND_LOCATION 2>/dev/null || echo "  - 后台定位：请在系统设置改为「始终允许」"
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell appops set $PKG SCHEDULE_EXACT_ALARM allow 2>/dev/null || true
adb shell dumpsys deviceidle whitelist +$PKG >/dev/null 2>&1 && echo "  - 已加入电池优化白名单"

step "启动应用（首次会打「首点」）"
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 2

step "当前排定的打卡闹钟（应能看到 $PKG 的 RTC_WAKEUP 项）"
adb shell dumpsys alarm | grep -B1 -A3 -i "$PKG" | head -30 || echo "  没找到——打开应用一次后重试"

step "手动触发一次打卡（前台服务取一次定位）"
adb shell am start-foreground-service -n "$SVC" 2>/dev/null \
  || adb shell am startservice -n "$SVC" 2>/dev/null \
  || echo "  触发失败（部分 ROM 限制 shell 启动服务）：改为打开应用等它自动补打"

step "应用版本与进程"
adb shell dumpsys package $PKG | grep -E "versionName|versionCode" | head -2
adb shell pidof $PKG || echo "  （进程未在运行）"

step "实时日志（Ctrl+C 退出；把这里的输出发回给 Claude 分析）"
PID=$(adb shell pidof -s $PKG | tr -d '\r')
if [ -n "$PID" ]; then
  adb logcat --pid="$PID" -v time
else
  adb logcat -v time | grep -iE "terndays|AndroidRuntime|ActivityManager.*$PKG"
fi
