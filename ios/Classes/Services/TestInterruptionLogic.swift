import Foundation

// --- MOCK / COPIED DEFINITIONS ---

public enum InterruptionSource {
    case none
    case phoneCall
    case network
    case systemResource
}

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
        return "[\(source)]"
    }
}

public class PhoneCallInterruption: Interruption {
    public init() { super.init(source: .phoneCall) }
}
public class NetworkInterruption: Interruption {
    public init() { super.init(source: .network) }
}
public class SystemResourceInterruption: Interruption {
    public init() { super.init(source: .systemResource) }
}

public struct InterruptionConfig {
    let phoneCallTimeout: TimeInterval
    let networkTimeout: TimeInterval
    public static let `default` = InterruptionConfig(phoneCallTimeout: 0.0, networkTimeout: 2.0)
}

public protocol InterruptionManagerDelegate: AnyObject {
    func interruptionTimedOut(source: InterruptionSource)
}

// --- COPIED IMPLEMENTATION (Simplified for Test) ---

public class InterruptionManagerImpl {
    private let config: InterruptionConfig
    private var interruptions: [Interruption] = []
    
    public var currentSource: InterruptionSource {
        if interruptions.contains(where: { $0.source == .phoneCall }) { return .phoneCall }
        if interruptions.contains(where: { $0.source == .systemResource }) { return .systemResource }
        if interruptions.contains(where: { $0.source == .network }) { return .network }
        return .none
    }

    public init(config: InterruptionConfig = .default) {
        self.config = config
    }

    public func handleInterruptionBegan(source: InterruptionSource) {
        let interruption: Interruption
        switch source {
        case .phoneCall: interruption = PhoneCallInterruption()
        case .network: interruption = NetworkInterruption()
        case .systemResource: interruption = SystemResourceInterruption()
        case .none: return
        }
        
        if !interruptions.contains(where: { $0.source == source }) {
            interruptions.append(interruption)
        }
    }

    public func handleInterruptionEnded(source: InterruptionSource) {
        interruptions.removeAll { $0.source == source }
    }
}

// --- TEST SCENARIOS ---

func testStackLogic() {
    let manager = InterruptionManagerImpl()
    
    print("Test 1: Single Network Interruption")
    manager.handleInterruptionBegan(source: .network)
    assert(manager.currentSource == .network, "Should be Network")
    
    print("Test 2: Nested Phone Interruption (Network -> Phone)")
    manager.handleInterruptionBegan(source: .phoneCall)
    assert(manager.currentSource == .phoneCall, "Should be PhoneCall (Priority)")
    
    print("Test 3: Network Ends during Phone")
    manager.handleInterruptionEnded(source: .network)
    assert(manager.currentSource == .phoneCall, "Should still be PhoneCall")
    
    print("Test 4: Phone Ends")
    manager.handleInterruptionEnded(source: .phoneCall)
    assert(manager.currentSource == .none, "Should be None")
    
    print("Test 5: Nested System -> Network")
    manager.handleInterruptionBegan(source: .systemResource)
    assert(manager.currentSource == .systemResource, "Should be System")
    manager.handleInterruptionBegan(source: .network)
    assert(manager.currentSource == .systemResource, "Should still be System (Priority)")
    
    print("Test 6: System Ends")
    manager.handleInterruptionEnded(source: .systemResource)
    assert(manager.currentSource == .network, "Should revert to Network")
    
    print("✅ All Stack Logic Tests Passed")
}

testStackLogic()
