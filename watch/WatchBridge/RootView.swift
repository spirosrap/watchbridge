import SwiftUI

struct RootView: View {
    @EnvironmentObject private var model: BridgeModel

    var body: some View {
        TabView {
            StatusView()
            PairView()
            RecentView()
        }
        .tabViewStyle(.verticalPage)
    }
}

struct StatusView: View {
    @EnvironmentObject private var model: BridgeModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Label("WatchBridge", systemImage: "applewatch.radiowaves.left.and.right")
                    .font(.headline)
                statusLine(ok: !model.token.isEmpty,
                           text: model.token.isEmpty ? "Waiting for push token…" : "Push token ready")
                statusLine(ok: model.alertsAllowed,
                           text: model.alertsAllowed ? "Alerts allowed" : "Alerts not allowed")
                if let err = model.registrationError {
                    Text(err).font(.footnote).foregroundStyle(.red)
                }
                if let d = model.tokenDate {
                    Text("Registered \(d.formatted(date: .abbreviated, time: .shortened))")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Text("Received: \(model.recent.count)")
                    .font(.footnote).foregroundStyle(.secondary)
                Button("Re-register") { model.reregister() }
                    .font(.footnote)
                Text("Swipe up for the pairing code.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .navigationTitle("Status")
    }

    private func statusLine(ok: Bool, text: String) -> some View {
        HStack(spacing: 6) {
            Image(systemName: ok ? "checkmark.circle.fill" : "circle")
                .foregroundStyle(ok ? Color.accentColor : .secondary)
            Text(text).font(.footnote)
        }
    }
}

struct PairView: View {
    @EnvironmentObject private var model: BridgeModel

    var body: some View {
        GeometryReader { geo in
            VStack(spacing: 6) {
                if model.token.isEmpty {
                    Text("No token yet").font(.footnote)
                    Button("Register") { model.reregister() }.font(.footnote)
                } else {
                    QRCodeView(text: model.token)
                        .frame(width: geo.size.width, height: geo.size.width)
                    Text("Scan with WatchBridge on the phone")
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .ignoresSafeArea(edges: .bottom)
    }
}

struct RecentView: View {
    @EnvironmentObject private var model: BridgeModel

    var body: some View {
        List {
            if model.recent.isEmpty {
                Text("No notifications received yet.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            ForEach(model.recent) { n in
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text(n.app.isEmpty ? n.title : n.app).font(.footnote.bold())
                        Spacer()
                        Text(n.date, style: .time).font(.system(size: 10)).foregroundStyle(.secondary)
                    }
                    if !n.app.isEmpty && !n.title.isEmpty {
                        Text(n.title).font(.footnote)
                    }
                    if !n.body.isEmpty {
                        Text(n.body).font(.system(size: 12)).foregroundStyle(.secondary).lineLimit(4)
                    }
                }
            }
            if !model.recent.isEmpty {
                Button("Clear") { model.clearRecent() }.font(.footnote)
            }
        }
        .navigationTitle("Recent")
    }
}
