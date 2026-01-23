import Foundation

/// Configuration for interruption timeouts
public struct InterruptionConfig {
    let phoneCallTimeout: TimeInterval
    let networkTimeout: TimeInterval

    public static let `default` = InterruptionConfig(
        phoneCallTimeout: 30.0,
        networkTimeout: 30.0
    )
}

/// Protocol for managing interruptions
public protocol InterruptionManager {
    var currentSource: InterruptionSource { get }
    var hasNetworkLossDuringPhoneCall: Bool { get }

    func handleInterruptionBegan(source: InterruptionSource)
    func handleInterruptionEnded(source: InterruptionSource)
    func cancelTimer()
    func setDelegate(_ delegate: InterruptionManagerDelegate?)
    func setNetworkLostDuringPhoneCall(_ value: Bool)
    func setCurrentSource(_ source: InterruptionSource)
    func clearAllInterruptions()
}

/// Delegate for interruption manager events
public protocol InterruptionManagerDelegate: AnyObject {
    func interruptionTimedOut(source: InterruptionSource)
}

/// Implementation of interruption management with timer handling
public class InterruptionManagerImpl: InterruptionManager {
    // MARK: - Properties
    private let config: InterruptionConfig
    private var interruptionTimer: DispatchSourceTimer?
    private var interruptionStartedAt: Date?
    private var interruptionDeadline: Date?
    private var _currentSource: InterruptionSource = .none
    private var _networkLostDuringPhoneCall: Bool = false
    private weak var delegate: InterruptionManagerDelegate?
    private let lock = NSLock()

    public var currentSource: InterruptionSource {
        lock.lock()
        defer { lock.unlock() }
        return _currentSource
    }

    public var hasNetworkLossDuringPhoneCall: Bool {
        lock.lock()
        defer { lock.unlock() }
        return _networkLostDuringPhoneCall
    }

    // MARK: - Initialization
    public init(config: InterruptionConfig = .default) {
        self.config = config
    }

    deinit {
        cancelTimer()
    }

    // MARK: - InterruptionManager Implementation
    public func handleInterruptionBegan(source: InterruptionSource) {
        lock.lock()
        defer { lock.unlock() }
        
        let previousSource = _currentSource
        _currentSource = source

        // Handle network loss during phone call
        if source == .network && previousSource == .phoneCall {
            _networkLostDuringPhoneCall = true
            print("⏸️ InterruptionManager: Network lost during phone call - flagged")
            return
        }

        if interruptionTimer == nil {
            interruptionStartedAt = Date()
            interruptionDeadline = Date().addingTimeInterval(config.networkTimeout)
            internalCancelTimer()
            startTimer(for: source)
        } else {
            print("⏸️ InterruptionManager: Interruption already in progress - keeping existing deadline")
        }
        print("⏸️ InterruptionManager: Interruption began - source: \(source)")
    }

    public func handleInterruptionEnded(source: InterruptionSource) {
        lock.lock()
        defer { lock.unlock() }

        guard _currentSource == source else {
            print("⏸️ InterruptionManager: Ignoring end for \(source) - current source is \(_currentSource)")
            return
        }

        // Check if network was lost during phone call (Scenario 3)
        if source == .phoneCall && _networkLostDuringPhoneCall {
            _networkLostDuringPhoneCall = false
            _currentSource = .network // Explicitly switch to network source
            print("⏸️ InterruptionManager: Phone ended but network lost - switching to network interruption")
            return
        }

        _currentSource = .none
        internalCancelTimer()
        interruptionStartedAt = nil
        interruptionDeadline = nil
        print("⏸️ InterruptionManager: Interruption ended - source: \(source)")
    }

    public func cancelTimer() {
        lock.lock()
        defer { lock.unlock() }
        internalCancelTimer()
    }

    private func internalCancelTimer() {
        interruptionTimer?.cancel()
        interruptionTimer = nil
    }

    public func setDelegate(_ delegate: InterruptionManagerDelegate?) {
        lock.lock()
        self.delegate = delegate
        lock.unlock()
    }

    // MARK: - Internal Methods
    public func setNetworkLostDuringPhoneCall(_ value: Bool) {
        lock.lock()
        _networkLostDuringPhoneCall = value
        lock.unlock()
    }

    public func setCurrentSource(_ source: InterruptionSource) {
        lock.lock()
        _currentSource = source
        lock.unlock()
    }

    public func clearAllInterruptions() {
        lock.lock()
        defer { lock.unlock() }
        _currentSource = .none
        _networkLostDuringPhoneCall = false
        internalCancelTimer()
        interruptionStartedAt = nil
        interruptionDeadline = nil
        print("⏸️ InterruptionManager: Cleared all interruptions and timers")
    }

    // MARK: - Private Methods
    private func startTimer(for source: InterruptionSource) {
        // Assume lock is already held by caller (handleInterruptionBegan or handleInterruptionEnded)
        let now = Date()
        if interruptionDeadline == nil {
            interruptionStartedAt = now
            interruptionDeadline = now.addingTimeInterval(config.networkTimeout)
        }
        let remaining = max(0, (interruptionDeadline!.timeIntervalSince(now)))

        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.main)
        timer.schedule(deadline: .now() + remaining)

        timer.setEventHandler { [weak self] in
            guard let self = self else { return }
            self.lock.lock()
            let sourceToNotify = self._currentSource
            self.internalCancelTimer()
            self.interruptionStartedAt = nil
            self.interruptionDeadline = nil
            self.lock.unlock()
            
            print("⏸️ InterruptionManager: Timeout expired for \(sourceToNotify)")
            self.delegate?.interruptionTimedOut(source: sourceToNotify)
        }

        interruptionTimer = timer
        timer.resume()

        print("⏸️ InterruptionManager: Started timer for \(source) - remaining: \(Int(remaining))s")
    }
    
    public func remainingSeconds() -> Int {
        lock.lock()
        defer { lock.unlock() }
        guard let deadline = interruptionDeadline else { return 0 }
        let remaining = max(0, Int(deadline.timeIntervalSince(Date())))
        return remaining
    }
}
