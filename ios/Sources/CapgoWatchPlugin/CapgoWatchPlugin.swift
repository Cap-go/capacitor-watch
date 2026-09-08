import Foundation
import Capacitor
import WatchConnectivity

/// Please read the Capacitor iOS Plugin Development Guide
/// here: https://capacitorjs.com/docs/plugins/ios
@objc(CapgoWatchPlugin)
public class CapgoWatchPlugin: CAPPlugin, CAPBridgedPlugin {
    private let pluginVersion: String = "8.1.3"
    public let identifier = "CapgoWatchPlugin"
    public let jsName = "CapgoWatch"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "sendMessage", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "updateApplicationContext", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "transferUserInfo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "replyToMessage", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getInfo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getReceivedState", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getPluginVersion", returnType: CAPPluginReturnPromise)
    ]

    private var sessionDelegate: WatchSessionDelegate?
    private var pendingReplies: [String: ([String: Any]) -> Void] = [:]
    private var pendingReplyTimers: [String: DispatchWorkItem] = [:]
    private let replyLock = NSLock()
    private let pendingReplyTtlSeconds: TimeInterval = 5 * 60

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

        let expectsReply = call.getBool("expectsReply") ?? false
        let message = CapgoWatchMessageConverter.convertToWatchMessage(data)

        if expectsReply {
            WCSession.default.sendMessage(message, replyHandler: { reply in
                let convertedReply = CapgoWatchMessageConverter.convertFromWatchMessage(reply)
                if convertedReply.isEmpty {
                    call.resolve(["reply": NSNull()])
                } else {
                    call.resolve(["reply": convertedReply])
                }
            }, errorHandler: { error in
                call.reject("Failed to send message: \(error.localizedDescription)")
            })
            return
        }

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

        let watchContext = CapgoWatchMessageConverter.convertToWatchMessage(context)

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

        let watchUserInfo = CapgoWatchMessageConverter.convertToWatchMessage(userInfo)
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
        pendingReplyTimers.removeValue(forKey: callbackId)?.cancel()
        replyLock.unlock()

        guard let handler = replyHandler else {
            call.reject("Invalid or expired callbackId")
            return
        }

        let replyData = CapgoWatchMessageConverter.convertToWatchMessage(data)
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

    @objc func getReceivedState(_ call: CAPPluginCall) {
        guard WCSession.isSupported() else {
            call.resolve(["context": NSNull()])
            return
        }

        let context = WCSession.default.receivedApplicationContext
        call.resolve(["context": CapgoWatchMessageConverter.convertFromWatchMessage(context)])
    }

    @objc func getPluginVersion(_ call: CAPPluginCall) {
        call.resolve(["version": pluginVersion])
    }

    // MARK: - Internal methods for delegate

    func storePendingReply(callbackId: String, handler: @escaping ([String: Any]) -> Void) {
        replyLock.lock()
        pendingReplies[callbackId] = handler
        pendingReplyTimers[callbackId]?.cancel()

        let workItem = DispatchWorkItem { [weak self] in
            self?.expirePendingReply(callbackId: callbackId)
        }
        pendingReplyTimers[callbackId] = workItem
        replyLock.unlock()

        DispatchQueue.main.asyncAfter(deadline: .now() + pendingReplyTtlSeconds, execute: workItem)
    }

    func expirePendingReply(callbackId: String) {
        replyLock.lock()
        let replyHandler = pendingReplies.removeValue(forKey: callbackId)
        pendingReplyTimers.removeValue(forKey: callbackId)
        replyLock.unlock()

        replyHandler?([:])
    }

    func notifyWatchEvent(_ eventName: String, data: [String: Any]) {
        notifyListeners(eventName, data: data, retainUntilConsumed: true)
    }

    deinit {
        replyLock.lock()
        let handlers = pendingReplies
        pendingReplies.removeAll()
        for (_, task) in pendingReplyTimers {
            task.cancel()
        }
        pendingReplyTimers.removeAll()
        replyLock.unlock()

        for (_, handler) in handlers {
            handler([:])
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
        ], retainUntilConsumed: true)
    }

    func sessionDidBecomeInactive(_ session: WCSession) {
        CAPLog.print("[CapgoWatch] Session became inactive")
    }

    func sessionDidDeactivate(_ session: WCSession) {
        CAPLog.print("[CapgoWatch] Session deactivated")
        WCSession.default.activate()
    }

    func sessionReachabilityDidChange(_ session: WCSession) {
        CAPLog.print("[CapgoWatch] Reachability changed: \(session.isReachable)")
        plugin?.notifyWatchEvent("reachabilityChanged", data: [
            "isReachable": session.isReachable
        ])
    }

    func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        CAPLog.print("[CapgoWatch] Received message: \(message)")
        plugin?.notifyWatchEvent("messageReceived", data: [
            "message": CapgoWatchMessageConverter.convertFromWatchMessage(message)
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
        plugin?.notifyWatchEvent("messageReceivedWithReply", data: [
            "message": CapgoWatchMessageConverter.convertFromWatchMessage(message),
            "callbackId": callbackId
        ])
    }

    func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        CAPLog.print("[CapgoWatch] Received application context: \(applicationContext)")
        plugin?.notifyWatchEvent("applicationContextReceived", data: [
            "context": CapgoWatchMessageConverter.convertFromWatchMessage(applicationContext)
        ])
    }

    func session(_ session: WCSession, didReceiveUserInfo userInfo: [String: Any] = [:]) {
        CAPLog.print("[CapgoWatch] Received user info: \(userInfo)")
        plugin?.notifyWatchEvent("userInfoReceived", data: [
            "userInfo": CapgoWatchMessageConverter.convertFromWatchMessage(userInfo)
        ])
    }
}

enum CapgoWatchMessageConverter {
    static func convertToWatchMessage(_ jsObject: JSObject) -> [String: Any] {
        var result: [String: Any] = [:]
        for (key, value) in jsObject {
            result[key] = convertJSValue(value)
        }
        return result
    }

    static func convertFromWatchMessage(_ message: [String: Any]) -> [String: Any] {
        var result: [String: Any] = [:]
        for (key, value) in message {
            result[key] = convertWatchValue(value)
        }
        return result
    }

    private static func convertJSValue(_ value: Any) -> Any {
        if let dict = value as? JSObject {
            return convertToWatchMessage(dict)
        } else if let array = value as? JSArray {
            return array.map { convertJSValue($0) }
        } else {
            return value
        }
    }

    private static func convertWatchValue(_ value: Any) -> Any {
        if let dict = value as? [String: Any] {
            return convertFromWatchMessage(dict)
        } else if let array = value as? [Any] {
            return array.map { convertWatchValue($0) }
        } else {
            return value
        }
    }
}
