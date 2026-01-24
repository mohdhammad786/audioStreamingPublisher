import Foundation

/// Configuration for interruption timeouts
public struct InterruptionConfig {
    let phoneCallTimeout: TimeInterval
    let networkTimeout: TimeInterval
    let systemTimeout: TimeInterval

    public static let `default` = InterruptionConfig(
        phoneCallTimeout: 30.0,
        networkTimeout: 30.0,
        systemTimeout: 30.0
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
    private var timerSource: InterruptionSource?
    
    // Professional Stack-based storage
    private var interruptions: [Interruption] = []
    
    private weak var delegate: InterruptionManagerDelegate?
    private let lock = NSLock()

    public var currentSource: InterruptionSource {
        lock.lock()
        defer { lock.unlock() }
        
        // Priority Logic: System/Phone > Network
        if interruptions.contains(where: { $0.source == .phoneCall }) {
            return .phoneCall
        }
        if interruptions.contains(where: { $0.source == .systemResource }) {
            return .systemResource
        }
        if interruptions.contains(where: { $0.source == .network }) {
            return .network
        }
        return .none
    }

    public var hasNetworkLossDuringPhoneCall: Bool {
        lock.lock()
        defer { lock.unlock() }
        let hasNetwork = interruptions.contains { $0.source == .network }
        let hasSystem = interruptions.contains { $0.source == .phoneCall || $0.source == .systemResource }
        return hasNetwork && hasSystem
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
        
        // 1. Create specific interruption object
        let interruption: Interruption
        switch source {
        case .phoneCall: interruption = PhoneCallInterruption()
        case .network: interruption = NetworkInterruption()
        case .systemResource: interruption = SystemResourceInterruption()
        case .none: return
        }
        
        // 2. Add to stack if not present
        if !interruptions.contains(where: { $0.source == source }) {
            interruptions.append(interruption)
            print("⏸️ InterruptionManager: Added \(interruption) - Stack: \(interruptions.map { $0.source })")
        } else {
             print("⏸️ InterruptionManager: \(source) already in stack - ignoring duplicate")
        }
        
        updateTimerState()
    }

    public func handleInterruptionEnded(source: InterruptionSource) {
        lock.lock()
        defer { lock.unlock() }

        // 1. Remove from stack
        let initialCount = interruptions.count
        interruptions.removeAll { $0.source == source }
        
        if interruptions.count < initialCount {
            print("⏸️ InterruptionManager: Removed \(source) - Stack: \(interruptions.map { $0.source })")
        } else {
            print("⏸️ InterruptionManager: Attempted to remove \(source) but it was not in stack")
        }

        updateTimerState()
    }

    public func cancelTimer() {
        lock.lock()
        defer { lock.unlock() }
        internalCancelTimer()
    }

    private func internalCancelTimer(clearDeadline: Bool = true) {
        if interruptionTimer != nil {
            print("⏸️ InterruptionManager: Cancelling timer")
            interruptionTimer?.cancel()
            interruptionTimer = nil
            timerSource = nil
            if clearDeadline {
                interruptionDeadline = nil
                interruptionStartedAt = nil
            }
        }
    }

    public func setDelegate(_ delegate: InterruptionManagerDelegate?) {
        lock.lock()
        self.delegate = delegate
        lock.unlock()
    }

    // MARK: - Internal Methods
    public func setNetworkLostDuringPhoneCall(_ value: Bool) {
        // Deprecated: Logic is now handled by stack state
        // For backward compatibility, we could manually add a network interruption,
        // but it's better to let the caller use handleInterruptionBegan(.network)
        if value {
             handleInterruptionBegan(source: .network)
        } else {
             handleInterruptionEnded(source: .network)
        }
    }

    public func setCurrentSource(_ source: InterruptionSource) {
        // Deprecated: Source is determined by stack priority
        // We simulate this by ensuring the requested source is in the stack
        handleInterruptionBegan(source: source)
    }

    public func clearAllInterruptions() {
        lock.lock()
        defer { lock.unlock() }
        interruptions.removeAll()
        internalCancelTimer()
        print("⏸️ InterruptionManager: Cleared all interruptions")
    }

    // MARK: - Private Methods
    private func updateTimerState() {
        // Assume lock is held
        
        // Determine effective source based on priority logic (same as currentSource)
        let effectiveSource: InterruptionSource
        if interruptions.contains(where: { $0.source == .phoneCall }) {
            effectiveSource = .phoneCall
        } else if interruptions.contains(where: { $0.source == .systemResource }) {
            effectiveSource = .systemResource
        } else if interruptions.contains(where: { $0.source == .network }) {
            effectiveSource = .network
        } else {
            effectiveSource = .none
        }
        
        // Timer Logic
        switch effectiveSource {
        case .none:
            // No interruptions -> Stop timer
            internalCancelTimer()
            
        case .phoneCall:
             // Phone Call -> Use phoneCallTimeout
            let timeout = config.phoneCallTimeout
            if interruptionDeadline == nil || interruptionTimer == nil || timerSource != .phoneCall {
                if interruptionTimer != nil && timerSource != .phoneCall {
                    internalCancelTimer(clearDeadline: false)
                }
                print("⏸️ InterruptionManager: Starting timer for Phone Call (\(timeout)s)")
                startTimer(for: .phoneCall, timeout: timeout)
            }
             
        case .systemResource:
            // System Resource (e.g. Camera) -> Use systemTimeout
            let timeout = config.systemTimeout
            if interruptionDeadline == nil || interruptionTimer == nil || timerSource != .systemResource {
                if interruptionTimer != nil && timerSource != .systemResource {
                    internalCancelTimer(clearDeadline: false)
                }
                print("⏸️ InterruptionManager: Starting timer for System Resource (\(timeout)s)")
                startTimer(for: .systemResource, timeout: timeout)
            }

        case .network:
            // Finite timeout -> Ensure timer is running
            let timeout = config.networkTimeout
            if interruptionDeadline == nil || interruptionTimer == nil || timerSource != .network {
                if interruptionTimer != nil && timerSource != .network {
                    internalCancelTimer(clearDeadline: false)
                }
                print("⏸️ InterruptionManager: Starting timer for Network (\(timeout)s)")
                startTimer(for: .network, timeout: timeout)
            }
        }
    }

    private func startTimer(for source: InterruptionSource, timeout: TimeInterval) {
        let now = Date()
        if interruptionDeadline == nil {
            interruptionStartedAt = now
            interruptionDeadline = now.addingTimeInterval(timeout)
        }
        
        guard let deadline = interruptionDeadline else { return }
        
        let remaining = max(0, (deadline.timeIntervalSince(now)))

        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.main)
        timer.schedule(deadline: .now() + remaining)

        timer.setEventHandler { [weak self] in
            guard let self = self else { return }
            self.lock.lock()
            let shouldFire: Bool
            if let deadline = self.interruptionDeadline {
                shouldFire = !self.interruptions.isEmpty && Date() >= deadline
            } else {
                shouldFire = false
            }

            self.internalCancelTimer()
            self.lock.unlock()
            
            guard shouldFire else { return }

            print("⏸️ InterruptionManager: Timeout expired")
            self.delegate?.interruptionTimedOut(source: source)
        }

        timerSource = source
        interruptionTimer = timer
        timer.resume()

        print("⏸️ InterruptionManager: Timer scheduled - remaining: \(Int(remaining))s")
    }
    
    public func remainingSeconds() -> Int {
        lock.lock()
        defer { lock.unlock() }
        guard let deadline = interruptionDeadline else { return 0 }
        let remaining = max(0, Int(deadline.timeIntervalSince(Date())))
        return remaining
    }
}
