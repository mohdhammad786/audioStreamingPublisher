import Foundation

/// Types of interruption sources
public enum InterruptionSource {
    case none
    case phoneCall
    case network
}

/// Protocol for interruption events
public protocol InterruptionEventDelegate: AnyObject {
    func interruptionBegan(source: InterruptionSource)
    func interruptionEnded(source: InterruptionSource)
    func interruptionTimedOut(source: InterruptionSource)
}
