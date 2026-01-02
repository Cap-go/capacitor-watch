import Foundation
import Capacitor
import WatchConnectivity

/// Please read the Capacitor iOS Plugin Development Guide
/// here: https://capacitorjs.com/docs/plugins/ios
@objc(CapgoWatchPlugin)
public class CapgoWatchPlugin: CAPPlugin, CAPBridgedPlugin {
    private let pluginVersion: String = "8.0.1"
    public let identifier = "CapgoWatchPlugin"
    public let jsName = "CapgoWatch"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "sendMessage", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "updateApplicationContext", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "transferUserInfo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "replyToMessage", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getInfo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getPluginVersion", returnType: CAPPluginReturnPromise)
    ]

    private var sessionDelegate: WatchSessionDelegate?
    private var pendingReplies: [String: ([String: Any]) -> Void] = [:]
    private let replyLock = NSLock()

    override public func load() {
        guard WCSession.isSupported() else {
            CAPLog.print("[CapgoWatch] WatchConnectivity is not supported on this device")
            return
        }

        sessionDelegate = WatchSessionDelegate(plugin: self)
        WCSession.default.delegate = sessionDelegate
        WCSession.default.activate()
        CAPLog.print("[CapgoWatch] WatchConnectivity session activated")
    }

    @objc func sendMessage(_ call: CAPPluginCall) {
        guard WCSession.isSupported() else {
            call.reject("WatchConnectivity is not supported on this device")
            return
        }

        guard WCSession.default.activationState == .activated else {
            call.reject("WatchConnectivity session is not activated")
            return
        }

        guard WCSession.default.isReachable else {
            call.reject("Watch is not reachable")
            return
        }

        guard let data = call.getObject("data") else {
            call.reject("data is required")
            return
        }

        let message = convertToWatchMessage(data)

        WCSession.default.sendMessage(message, replyHandler: nil) { error in
            call.reject("Failed to send message: \(error.localizedDescription)")
        }

        call.resolve()
    }

    @objc func updateApplicationContext(_ call: CAPPluginCall) {
        guard WCSession.isSupported() else {
            call.reject("WatchConnectivity is not supported on this device")
            return
        }

        guard WCSession.default.activationState == .activated else {
            call.reject("WatchConnectivity session is not activated")
            return
        }

        guard let context = call.getObject("context") else {
            call.reject("context is required")
            return
        }

        let watchContext = convertToWatchMessage(context)

        do {
            try WCSession.default.updateApplicationContext(watchContext)
            call.resolve()
        } catch {
            call.reject("Failed to update application context: \(error.localizedDescription)")
        }
    }

    @objc func transferUserInfo(_ call: CAPPluginCall) {
        guard WCSession.isSupported() else {
            call.reject("WatchConnectivity is not supported on this device")
            return
        }

        guard WCSession.default.activationState == .activated else {
            call.reject("WatchConnectivity session is not activated")
            return
        }

        guard let userInfo = call.getObject("userInfo") else {
            call.reject("userInfo is required")
            return
        }

        let watchUserInfo = convertToWatchMessage(userInfo)
        WCSession.default.transferUserInfo(watchUserInfo)
        call.resolve()
    }

    @objc func replyToMessage(_ call: CAPPluginCall) {
        guard let callbackId = call.getString("callbackId") else {
            call.reject("callbackId is required")
            return
        }

        guard let data = call.getObject("data") else {
            call.reject("data is required")
            return
        }

        replyLock.lock()
        let replyHandler = pendingReplies.removeValue(forKey: callbackId)
        replyLock.unlock()

        guard let handler = replyHandler else {
            call.reject("Invalid or expired callbackId")
            return
        }

        let replyData = convertToWatchMessage(data)
        handler(replyData)
        call.resolve()
    }

    @objc func getInfo(_ call: CAPPluginCall) {
        let isSupported = WCSession.isSupported()

        var isPaired = false
        var isWatchAppInstalled = false
        var isReachable = false
        var activationState = 0

        if isSupported {
            isPaired = WCSession.default.isPaired
            isWatchAppInstalled = WCSession.default.isWatchAppInstalled
            isReachable = WCSession.default.isReachable
            activationState = WCSession.default.activationState.rawValue
        }

        call.resolve([
            "isSupported": isSupported,
            "isPaired": isPaired,
            "isWatchAppInstalled": isWatchAppInstalled,
            "isReachable": isReachable,
            "activationState": activationState
        ])
    }

    @objc func getPluginVersion(_ call: CAPPluginCall) {
        call.resolve(["version": pluginVersion])
    }

    // MARK: - Internal methods for delegate

    func storePendingReply(callbackId: String, handler: @escaping ([String: Any]) -> Void) {
        replyLock.lock()
        pendingReplies[callbackId] = handler
        replyLock.unlock()
    }

    // MARK: - Helper methods

    private func convertToWatchMessage(_ jsObject: JSObject) -> [String: Any] {
        var result: [String: Any] = [:]
        for (key, value) in jsObject {
            result[key] = convertJSValue(value)
        }
        return result
    }

    private func convertJSValue(_ value: Any) -> Any {
        if let dict = value as? JSObject {
            return convertToWatchMessage(dict)
        } else if let array = value as? JSArray {
            return array.map { convertJSValue($0) }
        } else {
            return value
        }
    }
}

// MARK: - WatchSessionDelegate

class WatchSessionDelegate: NSObject, WCSessionDelegate {
    private weak var plugin: CapgoWatchPlugin?

    init(plugin: CapgoWatchPlugin) {
        self.plugin = plugin
        super.init()
    }

    func session(
        _ session: WCSession,
        activationDidCompleteWith activationState: WCSessionActivationState,
        error: Error?
    ) {
        if let error = error {
            CAPLog.print("[CapgoWatch] Activation failed: \(error.localizedDescription)")
            return
        }

        CAPLog.print("[CapgoWatch] Activation completed with state: \(activationState.rawValue)")
        plugin?.notifyListeners("activationStateChanged", data: [
            "state": activationState.rawValue
        ])
    }

    func sessionDidBecomeInactive(_ session: WCSession) {
        CAPLog.print("[CapgoWatch] Session became inactive")
    }

    func sessionDidDeactivate(_ session: WCSession) {
        CAPLog.print("[CapgoWatch] Session deactivated")
        // Reactivate for multi-watch support
        WCSession.default.activate()
    }

    func sessionReachabilityDidChange(_ session: WCSession) {
        CAPLog.print("[CapgoWatch] Reachability changed: \(session.isReachable)")
        plugin?.notifyListeners("reachabilityChanged", data: [
            "isReachable": session.isReachable
        ])
    }

    // MARK: - Message receiving

    func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        CAPLog.print("[CapgoWatch] Received message: \(message)")
        plugin?.notifyListeners("messageReceived", data: [
            "message": message
        ])
    }

    func session(
        _ session: WCSession,
        didReceiveMessage message: [String: Any],
        replyHandler: @escaping ([String: Any]) -> Void
    ) {
        CAPLog.print("[CapgoWatch] Received message with reply: \(message)")
        let callbackId = UUID().uuidString

        plugin?.storePendingReply(callbackId: callbackId, handler: replyHandler)
        plugin?.notifyListeners("messageReceivedWithReply", data: [
            "message": message,
            "callbackId": callbackId
        ])
    }

    // MARK: - Application context

    func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        CAPLog.print("[CapgoWatch] Received application context: \(applicationContext)")
        plugin?.notifyListeners("applicationContextReceived", data: [
            "context": applicationContext
        ])
    }

    // MARK: - User info

    func session(_ session: WCSession, didReceiveUserInfo userInfo: [String: Any] = [:]) {
        CAPLog.print("[CapgoWatch] Received user info: \(userInfo)")
        plugin?.notifyListeners("userInfoReceived", data: [
            "userInfo": userInfo
        ])
    }
}
