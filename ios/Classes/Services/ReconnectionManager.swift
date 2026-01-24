import Foundation
import AVFoundation
import HaishinKit

/// Configuration for reconnection behavior
public struct ReconnectionConfig {
    let maxRetries: Int
    let exponentialBackoff: Bool

    public static let `default` = ReconnectionConfig(
        maxRetries: 6,
        exponentialBackoff: true
    )
}

/// Protocol for managing stream reconnections
public protocol ReconnectionManager {
    var isRetrying: Bool { get }
    var retryCount: Int { get }

    func reconnect(url: String, streamName: String)
    func cancelReconnection()
    func resetRetryCount()
    func setDelegate(_ delegate: ReconnectionManagerDelegate?)
    func shouldRetry(error: String) -> Bool
    func scheduleRetry(url: String, completion: @escaping () -> Void)
    func notifySuccess()
    func notifyFailure(error: String)
}

/// Delegate for reconnection events
public protocol ReconnectionManagerDelegate: AnyObject {
    func reconnectionWillBegin(url: String, streamName: String)
    func reconnectionDidSucceed()
    func reconnectionDidFail(error: String)
    func reconnectionRetrying(attempt: Int, delay: TimeInterval)
}

/// Implementation of reconnection management
public class ReconnectionManagerImpl: ReconnectionManager {
    // MARK: - Properties
    private let config: ReconnectionConfig
    private var _isRetrying: Bool = false
    private var _retryCount: Int = 0
    private weak var delegate: ReconnectionManagerDelegate?
    private let lock = NSLock()
    private var scheduledRetryWorkItem: DispatchWorkItem?

    public var isRetrying: Bool {
        lock.lock()
        defer { lock.unlock() }
        return _isRetrying
    }

    public var retryCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return _retryCount
    }

    // MARK: - Initialization
    public init(config: ReconnectionConfig = .default) {
        self.config = config
    }

    // MARK: - ReconnectionManager Implementation
    public func reconnect(url: String, streamName: String) {
        lock.lock()
        guard !_isRetrying else {
            lock.unlock()
            print("🔄 ReconnectionManager: Reconnection already in progress")
            return
        }
        lock.unlock()

        print("🔄 ReconnectionManager: Starting reconnection to \(url)/\(streamName)")
        delegate?.reconnectionWillBegin(url: url, streamName: streamName)
    }

    public func cancelReconnection() {
        lock.lock()
        _isRetrying = false
        let workItem = scheduledRetryWorkItem
        scheduledRetryWorkItem = nil
        lock.unlock()
        workItem?.cancel()
        print("🔄 ReconnectionManager: Cancelled reconnection")
    }

    public func resetRetryCount() {
        lock.lock()
        _retryCount = 0
        lock.unlock()
    }

    public func setDelegate(_ delegate: ReconnectionManagerDelegate?) {
        self.delegate = delegate
    }

    // MARK: - Retry Management
    public func shouldRetry(error: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }

        guard _retryCount < config.maxRetries else {
            print("🔄 ReconnectionManager: Max retries (\(config.maxRetries)) reached")
            return false
        }

        return true
    }

    public func scheduleRetry(url: String, completion: @escaping () -> Void) {
        lock.lock()
        guard !_isRetrying else {
            lock.unlock()
            print("🔄 ReconnectionManager: Retry already in progress, skipping")
            return
        }

        _retryCount += 1
        _isRetrying = true
        let previousWorkItem = scheduledRetryWorkItem
        scheduledRetryWorkItem = nil

        let delay: TimeInterval
        if config.exponentialBackoff {
            delay = pow(2.0, Double(_retryCount))
        } else {
            delay = 2.0
        }

        let attempt = _retryCount
        lock.unlock()

        previousWorkItem?.cancel()

        print("🔄 ReconnectionManager: Scheduling retry attempt \(attempt) after \(delay)s")
        delegate?.reconnectionRetrying(attempt: attempt, delay: delay)

        var workItem: DispatchWorkItem?
        workItem = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            guard let workItem = workItem, !workItem.isCancelled else { return }

            self.lock.lock()
            self._isRetrying = false
            if self.scheduledRetryWorkItem === workItem {
                self.scheduledRetryWorkItem = nil
            }
            self.lock.unlock()

            completion()
        }

        lock.lock()
        scheduledRetryWorkItem = workItem
        lock.unlock()

        if let workItem = workItem {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: workItem)
        }
    }

    public func notifySuccess() {
        cancelReconnection()
        lock.lock()
        _retryCount = 0
        _isRetrying = false
        lock.unlock()

        delegate?.reconnectionDidSucceed()
    }

    public func notifyFailure(error: String) {
        cancelReconnection()
        lock.lock()
        _isRetrying = false
        lock.unlock()

        delegate?.reconnectionDidFail(error: error)
    }
}
