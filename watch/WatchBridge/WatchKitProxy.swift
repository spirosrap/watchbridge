import WatchKit

/// Tiny indirection so the model does not import WatchKit everywhere.
enum WKApplicationProxy {
    static func registerForRemoteNotifications() {
        WKApplication.shared().registerForRemoteNotifications()
    }
}
