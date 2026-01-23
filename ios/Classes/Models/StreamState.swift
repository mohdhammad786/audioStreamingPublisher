import Foundation

/// Represents the various states of the RTMP Audio Stream
public enum StreamState: String {
    case idle           // No active stream
    case connecting     // Initiating RTMP connection
    case streaming      // Actively streaming audio
    case interrupted    // Paused due to interruption
    case reconnecting   // Attempting to reconnect
    case failed         // Fatal error or timeout

    var description: String {
        self.rawValue.uppercased()
    }
}

/// Protocol for observing stream state changes
public protocol StreamStateObserver: AnyObject {
    func streamStateDidChange(from oldState: StreamState, to newState: StreamState)
}

/// State machine for managing stream state transitions
public protocol StreamStateMachine {
    var currentState: StreamState { get }
    var previousState: StreamState { get }

    func transitionTo(_ newState: StreamState) -> Bool
    func canTransition(from: StreamState, to: StreamState) -> Bool
    func addObserver(_ observer: StreamStateObserver)
    func removeObserver(_ observer: StreamStateObserver)
}

/// Thread-safe implementation of StreamStateMachine
public class StreamStateMachineImpl: StreamStateMachine {
    private let lock = NSLock()
    private var _currentState: StreamState = .idle
    private var _previousState: StreamState = .idle
    private var observers: [WeakObserverWrapper] = []

    public init() {}

    public var currentState: StreamState {
        lock.lock()
        defer { lock.unlock() }
        return _currentState
    }

    public var previousState: StreamState {
        lock.lock()
        defer { lock.unlock() }
        return _previousState
    }

    public func transitionTo(_ newState: StreamState) -> Bool {
        lock.lock()
        let oldState = _currentState

        guard canTransition(from: oldState, to: newState) else {
            lock.unlock()
            print("❌ Invalid state transition: \(oldState.description) -> \(newState.description)")
            return false
        }

        _previousState = oldState
        _currentState = newState

        // Capture observers before unlocking
        let currentObservers = observers.compactMap { $0.observer }
        lock.unlock()

        print("✅ State: \(oldState.description) -> \(newState.description)")

        // Notify observers outside of lock
        currentObservers.forEach { observer in
            observer.streamStateDidChange(from: oldState, to: newState)
        }

        return true
    }

    public func canTransition(from: StreamState, to: StreamState) -> Bool {
        switch (from, to) {
        case (.idle, .connecting): return true
        case (.connecting, .streaming): return true
        case (.connecting, .failed): return true
        case (.connecting, .interrupted): return true
        case (.streaming, .interrupted): return true
        case (.streaming, .failed): return true
        case (.streaming, .idle): return true
        case (.interrupted, .reconnecting): return true
        case (.interrupted, .failed): return true
        case (.interrupted, .idle): return true
        case (.reconnecting, .streaming): return true
        case (.reconnecting, .interrupted): return true
        case (.reconnecting, .failed): return true
        case (.reconnecting, .idle): return true
        case (.failed, .idle): return true
        case (let s1, let s2) where s1 == s2: return true
        default: return false
        }
    }

    public func addObserver(_ observer: StreamStateObserver) {
        lock.lock()
        defer { lock.unlock() }

        // Remove any nil references first
        observers.removeAll { $0.observer == nil }

        // Add new observer if not already present
        if !observers.contains(where: { $0.observer === observer }) {
            observers.append(WeakObserverWrapper(observer: observer))
        }
    }

    public func removeObserver(_ observer: StreamStateObserver) {
        lock.lock()
        defer { lock.unlock() }
        observers.removeAll { $0.observer === observer || $0.observer == nil }
    }

    // Weak wrapper to prevent retain cycles
    private class WeakObserverWrapper {
        weak var observer: StreamStateObserver?

        init(observer: StreamStateObserver) {
            self.observer = observer
        }
    }
}
