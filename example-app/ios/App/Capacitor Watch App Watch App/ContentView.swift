//
//  ContentView.swift
//  Capacitor Watch App Watch App
//
//  Created by Michał Tremblay on 06/01/2026.
//

import SwiftUI
import CapgoWatchSDK

struct ContentView: View {
    @ObservedObject var connector = WatchConnector.shared
    @State private var messageLog: [LogEntry] = []
    @State private var messageCounter = 0

    var body: some View {
        NavigationStack {
            List {
                // Status Section
                Section {
                    StatusRow(
                        icon: connector.isReachable ? "iphone.radiowaves.left.and.right" : "iphone.slash",
                        title: "Phone",
                        status: connector.isReachable ? "Connected" : "Disconnected",
                        color: connector.isReachable ? .green : .red
                    )
                    StatusRow(
                        icon: connector.isActivated ? "checkmark.circle.fill" : "xmark.circle",
                        title: "Session",
                        status: connector.isActivated ? "Active" : "Inactive",
                        color: connector.isActivated ? .green : .orange
                    )
                } header: {
                    Text("Status")
                }

                // Send Messages Section
                Section {
                    Button {
                        sendSimpleMessage()
                    } label: {
                        Label("Send Simple Message", systemImage: "paperplane")
                    }
                    .disabled(!connector.isReachable)

                    Button {
                        sendMessageWithReply()
                    } label: {
                        Label("Send with Reply", systemImage: "arrowshape.turn.up.left")
                    }
                    .disabled(!connector.isReachable)

                    Button {
                        sendPingMessage()
                    } label: {
                        Label("Ping Phone", systemImage: "antenna.radiowaves.left.and.right")
                    }
                    .disabled(!connector.isReachable)
                } header: {
                    Text("Send to Phone")
                }

                // Context Section
                Section {
                    Button {
                        updateContext()
                    } label: {
                        Label("Update Context", systemImage: "arrow.triangle.2.circlepath")
                    }

                    if !connector.applicationContext.isEmpty {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Received Context:")
                                .font(.caption2)
                                .foregroundColor(.secondary)
                            Text(formatDict(connector.applicationContext))
                                .font(.caption2)
                                .foregroundColor(.primary)
                        }
                        .padding(.vertical, 2)
                    }
                } header: {
                    Text("Application Context")
                }

                // Last Message Section
                Section {
                    if !connector.lastMessage.isEmpty {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Last Received:")
                                .font(.caption2)
                                .foregroundColor(.secondary)
                            Text(formatDict(connector.lastMessage))
                                .font(.caption2)
                                .foregroundColor(.primary)
                        }
                        .padding(.vertical, 2)
                    } else {
                        Text("No messages yet")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                } header: {
                    Text("Received Messages")
                }

                // Log Section
                Section {
                    if messageLog.isEmpty {
                        Text("No activity yet")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    } else {
                        ForEach(messageLog.reversed()) { entry in
                            LogEntryView(entry: entry)
                        }
                    }

                    if !messageLog.isEmpty {
                        Button(role: .destructive) {
                            messageLog.removeAll()
                        } label: {
                            Label("Clear Log", systemImage: "trash")
                        }
                    }
                } header: {
                    Text("Activity Log")
                }
            }
            .navigationTitle("Debug")
        }
    }

    // MARK: - Actions

    private func sendSimpleMessage() {
        messageCounter += 1
        let message: [String: Any] = [
            "action": "test",
            "source": "watch",
            "counter": messageCounter,
            "timestamp": Date().timeIntervalSince1970
        ]

        addLog(.sent, "Sending message #\(messageCounter)")

        connector.sendMessage(message, replyHandler: nil) { error in
            addLog(.error, "Failed: \(error.localizedDescription)")
        }
    }

    private func sendMessageWithReply() {
        messageCounter += 1
        let message: [String: Any] = [
            "action": "requestData",
            "source": "watch",
            "counter": messageCounter,
            "timestamp": Date().timeIntervalSince1970
        ]

        addLog(.sent, "Request #\(messageCounter) with reply")

        connector.sendMessage(message) { reply in
            let replyStr = formatDict(reply)
            addLog(.received, "Reply: \(replyStr)")
        } errorHandler: { error in
            addLog(.error, "Failed: \(error.localizedDescription)")
        }
    }

    private func sendPingMessage() {
        let message: [String: Any] = [
            "action": "ping",
            "source": "watch",
            "timestamp": Date().timeIntervalSince1970
        ]

        addLog(.sent, "Ping sent")

        connector.sendMessage(message) { reply in
            if let pong = reply["pong"] as? Bool, pong {
                addLog(.received, "Pong received!")
            } else {
                addLog(.received, "Reply: \(formatDict(reply))")
            }
        } errorHandler: { error in
            addLog(.error, "Ping failed: \(error.localizedDescription)")
        }
    }

    private func updateContext() {
        messageCounter += 1
        let context: [String: Any] = [
            "watchUpdate": messageCounter,
            "batteryLevel": getBatteryLevel(),
            "timestamp": Date().timeIntervalSince1970
        ]

        do {
            try connector.updateApplicationContext(context)
            addLog(.sent, "Context updated: #\(messageCounter)")
        } catch {
            addLog(.error, "Context failed: \(error.localizedDescription)")
        }
    }

    // MARK: - Helpers

    private func addLog(_ type: LogEntry.LogType, _ message: String) {
        DispatchQueue.main.async {
            let entry = LogEntry(type: type, message: message)
            messageLog.append(entry)
            // Keep only last 20 entries
            if messageLog.count > 20 {
                messageLog.removeFirst()
            }
        }
    }

    private func formatDict(_ dict: [String: Any]) -> String {
        dict.map { "\($0.key): \($0.value)" }.joined(separator: ", ")
    }

    private func getBatteryLevel() -> Int {
        // Return a mock battery level for demo
        return Int.random(in: 50...100)
    }
}

// MARK: - Supporting Views

struct StatusRow: View {
    let icon: String
    let title: String
    let status: String
    let color: Color

    var body: some View {
        HStack {
            Image(systemName: icon)
                .foregroundColor(color)
                .frame(width: 24)
            Text(title)
            Spacer()
            Text(status)
                .font(.caption)
                .foregroundColor(color)
        }
    }
}

struct LogEntry: Identifiable {
    let id = UUID()
    let type: LogType
    let message: String
    let time = Date()

    enum LogType {
        case sent, received, error

        var icon: String {
            switch self {
            case .sent: return "arrow.up.circle"
            case .received: return "arrow.down.circle"
            case .error: return "exclamationmark.triangle"
            }
        }

        var color: Color {
            switch self {
            case .sent: return .blue
            case .received: return .green
            case .error: return .red
            }
        }
    }
}

struct LogEntryView: View {
    let entry: LogEntry

    private var timeString: String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        return formatter.string(from: entry.time)
    }

    var body: some View {
        HStack(alignment: .top, spacing: 6) {
            Image(systemName: entry.type.icon)
                .foregroundColor(entry.type.color)
                .font(.caption)
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.message)
                    .font(.caption2)
                    .lineLimit(2)
                Text(timeString)
                    .font(.system(size: 9))
                    .foregroundColor(.secondary)
            }
        }
    }
}

#Preview {
    ContentView()
}
