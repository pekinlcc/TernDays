import CoreLocation
import SwiftUI
import UIKit
import UserNotifications

struct OnboardingView: View {
    let onDone: () -> Void

    @ObservedObject private var punch = PunchManager.shared
    @State private var notifAsked = false
    // 「始终允许」的系统升级弹窗一辈子只出现一次:请求过之后按钮改跳系统设置,不做死按钮
    @State private var alwaysAsked = false

    private enum Step {
        case whenInUse, always, notify, done

        var button: String {
            switch self {
            case .whenInUse: return "允许定位"
            case .always: return "把定位改为「始终允许」"
            case .notify: return "允许打卡提醒通知"
            case .done: return "开始统计"
            }
        }
    }

    private var step: Step {
        switch punch.authStatus {
        case .notDetermined, .denied, .restricted: return .whenInUse
        case .authorizedWhenInUse: return .always
        case .authorizedAlways: return notifAsked ? .done : .notify
        @unknown default: return .whenInUse
        }
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                ZStack(alignment: .bottom) {
                    Circle().fill(Td.accentSoft).frame(width: 148, height: 148)
                    Image(systemName: "mappin.and.ellipse")
                        .font(.system(size: 56)).foregroundColor(Td.accent)
                        .padding(.bottom, 40)
                    HStack(spacing: 10) {
                        timeChip(icon: "sun.max", tint: Color(hex: 0xA9762F), text: "07:00")
                        timeChip(icon: "sunset", tint: Td.muted, text: "17:00")
                    }
                    .offset(y: 16)
                }
                .padding(.top, 40)

                Text("每天两次，自动记住\n你在哪座城市")
                    .font(.system(size: 23, weight: .bold)).foregroundColor(Td.ink)
                    .multilineTextAlignment(.center)
                    .padding(.top, 28)

                Text("每天早上 7:00 和下午 5:00 前后，TernDays 记录一次 GPS 定位，只保留“城市”级别的结果，用来统计你一年里在每座城市待了多少天。")
                    .font(.system(size: 14)).foregroundColor(Td.muted)
                    .multilineTextAlignment(.center)
                    .lineSpacing(6)
                    .padding(.horizontal, 32)
                    .padding(.top, 12)

                TdCard {
                    VStack(spacing: 0) {
                        bullet("location", "需要「始终允许」定位权限",
                               "让系统在移动时唤醒应用完成记录，不持续追踪",
                               done: punch.authStatus == .authorizedAlways)
                        Divider().overlay(Td.divider)
                        bullet("internaldrive", "数据只保存在手机本地", "无账号、不上传，删除应用即清除全部数据", done: nil)
                        Divider().overlay(Td.divider)
                        bullet("leaf", "几乎不耗电", "每天仅少量定位，没有后台常驻服务", done: nil)
                    }
                    .padding(.horizontal, 16)
                }
                .padding(.horizontal, 24)
                .padding(.top, 22)

                Button {
                    advance()
                } label: {
                    Text(step == .always && alwaysAsked ? "去系统设置改为「始终」" : step.button)
                        .font(.system(size: 15, weight: .semibold)).foregroundColor(Td.onAccent)
                        .frame(maxWidth: .infinity).frame(height: 52)
                        .background(RoundedRectangle(cornerRadius: 14).fill(Td.accent))
                }
                .padding(.horizontal, 24)
                .padding(.top, 24)

                if step == .always && alwaysAsked {
                    Text("系统不再重复弹窗:请在 设置 → TernDays → 位置 中选择「始终」")
                        .font(.system(size: 12)).foregroundColor(Td.muted)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 24)
                        .padding(.top, 8)
                }

                Button("稍后再说") { onDone() }
                    .font(.system(size: 14)).foregroundColor(Td.muted)
                    .padding(.top, 12)

                Text("iOS 不允许后台精确定时任务：实际通过位置变化唤醒、\n定时提醒和打开应用补打完成，时间会有浮动")
                    .font(.system(size: 11)).foregroundColor(Td.faint)
                    .multilineTextAlignment(.center)
                    .padding(.top, 14)
                    .padding(.bottom, 24)
            }
        }
        .background(Td.bg)
    }

    private func advance() {
        switch step {
        case .whenInUse:
            if punch.authStatus == .denied || punch.authStatus == .restricted {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            } else {
                punch.requestWhenInUse()
            }
        case .always:
            if alwaysAsked {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            } else {
                alwaysAsked = true
                punch.requestAlways()
            }
        case .notify:
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in
                DispatchQueue.main.async { notifAsked = true }
            }
        case .done:
            onDone()
        }
    }

    private func timeChip(icon: String, tint: Color, text: String) -> some View {
        HStack(spacing: 6) {
            Image(systemName: icon).font(.system(size: 12)).foregroundColor(tint)
            Text(text).font(.system(size: 13, weight: .semibold)).foregroundColor(Td.ink)
        }
        .padding(.horizontal, 13).padding(.vertical, 7)
        .background(Capsule().fill(Td.surface))
        .shadow(color: Td.ink.opacity(0.08), radius: 4, y: 2)
    }

    private func bullet(_ icon: String, _ title: String, _ sub: String, done: Bool?) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 16)).foregroundColor(Td.accentDeep)
                .frame(width: 36, height: 36)
                .background(RoundedRectangle(cornerRadius: 11).fill(Td.accentSoft))
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 14, weight: .semibold)).foregroundColor(Td.ink)
                Text(sub).font(.system(size: 12)).foregroundColor(Td.muted)
            }
            Spacer()
            if done == true {
                Image(systemName: "checkmark").font(.system(size: 13, weight: .bold)).foregroundColor(Td.accent)
            }
        }
        .padding(.vertical, 12)
    }
}
