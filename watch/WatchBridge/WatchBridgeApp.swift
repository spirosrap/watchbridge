import SwiftUI
import UserNotifications
import WatchKit

@main
struct WatchBridgeApp: App {
    @WKApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @StateObject private var model = BridgeModel.shared

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
        }
    }
}

/// Registers for remote notifications and records what arrives.
@MainActor
final class AppDelegate: NSObject, WKApplicationDelegate, UNUserNotificationCenterDelegate {
    func applicationDidFinishLaunching() {
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        let category = UNNotificationCategory(identifier: "PIXEL", actions: [], intentIdentifiers: [], options: [])
        center.setNotificationCategories([category])
        center.requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
            Task { @MainActor in
                BridgeModel.shared.alertsAllowed = granted
                WKApplication.shared().registerForRemoteNotifications()
            }
        }
    }

    func didRegisterForRemoteNotifications(withDeviceToken deviceToken: Data) {
        let hex = deviceToken.map { String(format: "%02x", $0) }.joined()
        BridgeModel.shared.setToken(hex)
    }

    func didFailToRegisterForRemoteNotificationsWithError(_ error: Error) {
        BridgeModel.shared.registrationError = error.localizedDescription
    }

    func didReceiveRemoteNotification(_ userInfo: [AnyHashable: Any]) async -> WKBackgroundFetchResult {
        BridgeModel.shared.record(userInfo: userInfo)
        return .newData
    }

    // Show the banner even when the app is in the foreground, and remember it.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                            willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        let content = notification.request.content
        await MainActor.run { BridgeModel.shared.record(content) }
        return [.banner, .sound, .list]
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                            didReceive response: UNNotificationResponse) async {
        let content = response.notification.request.content
        await MainActor.run { BridgeModel.shared.record(content) }
    }
}
