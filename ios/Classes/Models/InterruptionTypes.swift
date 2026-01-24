import Foundation

/// Types of interruption sources
public enum InterruptionSource {
    case none
    case phoneCall
    case network
    case systemResource // e.g. Camera, Siri, Alarm, Other App
}

// MARK: - Interruption Models

/// Base class for all interruptions
public class Interruption: Equatable, CustomStringConvertible {
    public let source: InterruptionSource
    public let timestamp: Date
    public let id: UUID

    public init(source: InterruptionSource) {
        self.source = source
        self.timestamp = Date()
        self.id = UUID()
    }
    
    public static func == (lhs: Interruption, rhs: Interruption) -> Bool {
        return lhs.source == rhs.source
    }
    
    public var description: String {
        return "[\(source)] started at \(timestamp)"
    }
}

/// Specific interruption types
public class PhoneCallInterruption: Interruption {
    public init() { super.init(source: .phoneCall) }
}

public class NetworkInterruption: Interruption {
    public init() { super.init(source: .network) }
}

public class SystemResourceInterruption: Interruption {
    public init() { super.init(source: .systemResource) }
}

/// Protocol for interruption events
public protocol InterruptionEventDelegate: AnyObject {
    func interruptionBegan(source: InterruptionSource)
    func interruptionEnded(source: InterruptionSource)
    func interruptionTimedOut(source: InterruptionSource)
}
