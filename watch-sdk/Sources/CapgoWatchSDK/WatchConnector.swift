import Foundation
import WatchConnectivity
import Combine

/// Protocol for handling messages from the phone
public protocol WatchConnectorDelegate: AnyObject {
    /// Called when a message is received from the phone
    func didReceiveMessage(_ message: [String: Any])

    /// Called when a message requiring a reply is received from the phone
    func didReceiveMessage(_ message: [String: Any], replyHandler: @escaping ([String: Any]) -> Void)

    /// Called when the application context is updated from the phone
    func didReceiveApplicationContext(_ context: [String: Any])

    /// Called when user info is received from the phone
    func didReceiveUserInfo(_ userInfo: [String: Any])

    /// Called when reachability changes
    func reachabilityDidChange(_ isReachable: Bool)

    /// Called when activation completes
    func activationDidComplete(with state: WCSessionActivationState, error: Error?)
}

/// Default implementations for optional delegate methods
public extension WatchConnectorDelegate {
    func didReceiveMessage(_ message: [String: Any]) {}
    func didReceiveMessage(_ message: [String: Any], replyHandler: @escaping ([String: Any]) -> Void) {
        replyHandler([:])
    }
    func didReceiveApplicationContext(_ context: [String: Any]) {}
    func didReceiveUserInfo(_ userInfo: [String: Any]) {}
    func reachabilityDidChange(_ isReachable: Bool) {}
    func activationDidComplete(with state: WCSessionActivationState, error: Error?) {}
}

/// Main class for watch-side communication with the iPhone app.
/// Use this in your watchOS app to communicate with the Capacitor plugin on the phone.
@available(watchOS 9.0, iOS 15.0, *)
public final class WatchConnector: NSObject, ObservableObject {

    /// Shared singleton instance
    public static let shared = WatchConnector()

    /// Delegate for receiving messages and events
    public weak var delegate: WatchConnectorDelegate?

    /// Published property for SwiftUI - whether the phone is reachable
    @Published public private(set) var isReachable: Bool = false

    /// Published property for SwiftUI - whether the session is activated
    @Published public private(set) var isActivated: Bool = false

    /// Published property for SwiftUI - the latest received message
    @Published public private(set) var lastMessage: [String: Any] = [:]

    /// Published property for SwiftUI - the current application context
    @Published public private(set) var applicationContext: [String: Any] = [:]

    /// The underlying WCSession
    private var session: WCSession?

    private override init() {
        super.init()
    }

    // MARK: - Public Methods

    /// Activate the watch connectivity session.
    /// Call this when your watch app starts, typically in your App's init or onAppear.
    public func activate() {
        guard WCSession.isSupported() else {
            print("[CapgoWatchSDK] WCSession is not supported on this device")
            return
        }

        session = WCSession.default
        session?.delegate = self
        session?.activate()
        print("[CapgoWatchSDK] WCSession activation requested")
    }

    /// Send a message to the phone.
    /// The phone must be reachable for this to succeed.
    /// - Parameter message: The data to send
    /// - Parameter replyHandler: Optional handler for the phone's reply
    /// - Parameter errorHandler: Optional handler for errors
    public func sendMessage(
        _ message: [String: Any],
        replyHandler: (([String: Any]) -> Void)? = nil,
        errorHandler: ((Error) -> Void)? = nil
    ) {
        guard let session = session, session.isReachable else {
            errorHandler?(WatchConnectorError.phoneNotReachable)
            return
        }

        session.sendMessage(message, replyHandler: replyHandler) { error in
            print("[CapgoWatchSDK] Error sending message: \(error.localizedDescription)")
            errorHandler?(error)
        }
    }

    /// Send a message to the phone and wait for a reply.
    /// - Parameter message: The data to send
    /// - Returns: The reply from the phone
    /// - Throws: WatchConnectorError if the phone is not reachable or the send fails
    @available(watchOS 9.0, iOS 15.0, *)
    public func sendMessage(_ message: [String: Any]) async throws -> [String: Any] {
        guard let session = session, session.isReachable else {
            throw WatchConnectorError.phoneNotReachable
        }

        return try await withCheckedThrowingContinuation { continuation in
            session.sendMessage(message, replyHandler: { reply in
                continuation.resume(returning: reply)
            }, errorHandler: { error in
                continuation.resume(throwing: error)
            })
        }
    }

    /// Update the application context to share with the phone.
    /// Only the latest context is kept.
    /// - Parameter context: The context data to sync
    public func updateApplicationContext(_ context: [String: Any]) throws {
        guard let session = session else {
            throw WatchConnectorError.sessionNotActivated
        }

        try session.updateApplicationContext(context)
    }

    /// Transfer user info to the phone.
    /// Transfers are queued and delivered in order.
    /// - Parameter userInfo: The user info to transfer
    /// - Returns: The transfer object for tracking
    @discardableResult
    public func transferUserInfo(_ userInfo: [String: Any]) -> WCSessionUserInfoTransfer? {
        return session?.transferUserInfo(userInfo)
    }

    /// Get the current session state
    public var activationState: WCSessionActivationState {
        return session?.activationState ?? .notActivated
    }

    /// Check if the phone is currently reachable
    public var phoneIsReachable: Bool {
        return session?.isReachable ?? false
    }

    /// Get the received application context from the phone
    public var receivedApplicationContext: [String: Any] {
        return session?.receivedApplicationContext ?? [:]
    }
}

// MARK: - WCSessionDelegate

@available(watchOS 9.0, iOS 15.0, *)
extension WatchConnector: WCSessionDelegate {

    public func session(
        _ session: WCSession,
        activationDidCompleteWith activationState: WCSessionActivationState,
        error: Error?
    ) {
        DispatchQueue.main.async {
            self.isActivated = activationState == .activated
            self.isReachable = session.isReachable
            self.applicationContext = session.receivedApplicationContext
        }

        if let error = error {
            print("[CapgoWatchSDK] Activation failed: \(error.localizedDescription)")
        } else {
            print("[CapgoWatchSDK] Activation completed with state: \(activationState.rawValue)")
        }

        delegate?.activationDidComplete(with: activationState, error: error)
    }

    public func sessionReachabilityDidChange(_ session: WCSession) {
        DispatchQueue.main.async {
            self.isReachable = session.isReachable
        }

        print("[CapgoWatchSDK] Reachability changed: \(session.isReachable)")
        delegate?.reachabilityDidChange(session.isReachable)
    }

    // MARK: - Receiving Messages

    public func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        DispatchQueue.main.async {
            self.lastMessage = message
        }

        print("[CapgoWatchSDK] Received message: \(message)")
        delegate?.didReceiveMessage(message)
    }

    public func session(
        _ session: WCSession,
        didReceiveMessage message: [String: Any],
        replyHandler: @escaping ([String: Any]) -> Void
    ) {
        DispatchQueue.main.async {
            self.lastMessage = message
        }

        print("[CapgoWatchSDK] Received message with reply: \(message)")
        delegate?.didReceiveMessage(message, replyHandler: replyHandler)
    }

    // MARK: - Application Context

    public func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        DispatchQueue.main.async {
            self.applicationContext = applicationContext
        }

        print("[CapgoWatchSDK] Received application context: \(applicationContext)")
        delegate?.didReceiveApplicationContext(applicationContext)
    }

    // MARK: - User Info

    public func session(_ session: WCSession, didReceiveUserInfo userInfo: [String: Any] = [:]) {
        print("[CapgoWatchSDK] Received user info: \(userInfo)")
        delegate?.didReceiveUserInfo(userInfo)
    }

    // MARK: - iOS only delegate methods (required for protocol conformance)

    #if os(iOS)
    public func sessionDidBecomeInactive(_ session: WCSession) {
        print("[CapgoWatchSDK] Session became inactive")
    }

    public func sessionDidDeactivate(_ session: WCSession) {
        print("[CapgoWatchSDK] Session deactivated")
        // Reactivate for multi-watch support
        session.activate()
    }
    #endif
}

// MARK: - Errors

/// Errors that can occur during watch communication
public enum WatchConnectorError: LocalizedError {
    case phoneNotReachable
    case sessionNotActivated
    case sendFailed(Error)

    public var errorDescription: String? {
        switch self {
        case .phoneNotReachable:
            return "iPhone is not reachable"
        case .sessionNotActivated:
            return "WCSession is not activated"
        case .sendFailed(let error):
            return "Failed to send message: \(error.localizedDescription)"
        }
    }
}
