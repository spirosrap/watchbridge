import Foundation
import UserNotifications

struct ReceivedNote: Identifiable, Codable, Equatable {
    var id = UUID()
    var date: Date
    var app: String
    var title: String
    var body: String
}

@MainActor
final class BridgeModel: ObservableObject {
    static let shared = BridgeModel()

    @Published var token: String = UserDefaults.standard.string(forKey: "deviceToken") ?? ""
    @Published var tokenDate: Date? = UserDefaults.standard.object(forKey: "deviceTokenDate") as? Date
    @Published var registrationError: String?
    @Published var alertsAllowed: Bool = true
    @Published private(set) var recent: [ReceivedNote] = BridgeModel.loadRecent()

    private static let recentKey = "recentNotes"
    private static let recentMax = 40

    func setToken(_ hex: String) {
        token = hex
        tokenDate = Date()
        registrationError = nil
        UserDefaults.standard.set(hex, forKey: "deviceToken")
        UserDefaults.standard.set(tokenDate, forKey: "deviceTokenDate")
    }

    func record(_ content: UNNotificationContent) {
        let app = (content.userInfo["app"] as? String) ?? content.subtitle
        append(ReceivedNote(date: Date(), app: app, title: content.title, body: content.body))
    }

    func record(userInfo: [AnyHashable: Any]) {
        guard let aps = userInfo["aps"] as? [String: Any],
              let alert = aps["alert"] as? [String: Any] else { return }
        let note = ReceivedNote(
            date: Date(),
            app: (userInfo["app"] as? String) ?? (alert["subtitle"] as? String) ?? "",
            title: alert["title"] as? String ?? "",
            body: alert["body"] as? String ?? ""
        )
        append(note)
    }

    private func append(_ note: ReceivedNote) {
        if let last = recent.first, last.title == note.title, last.body == note.body,
           note.date.timeIntervalSince(last.date) < 5 { return }
        recent.insert(note, at: 0)
        if recent.count > Self.recentMax { recent.removeLast(recent.count - Self.recentMax) }
        if let data = try? JSONEncoder().encode(recent) {
            UserDefaults.standard.set(data, forKey: Self.recentKey)
        }
    }

    func clearRecent() {
        recent = []
        UserDefaults.standard.removeObject(forKey: Self.recentKey)
    }

    func reregister() {
        registrationError = nil
        WKApplicationProxy.registerForRemoteNotifications()
    }

    private static func loadRecent() -> [ReceivedNote] {
        guard let data = UserDefaults.standard.data(forKey: recentKey),
              let notes = try? JSONDecoder().decode([ReceivedNote].self, from: data) else { return [] }
        return notes
    }
}
