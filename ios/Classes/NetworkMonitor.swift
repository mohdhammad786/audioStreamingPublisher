import Foundation
import Network

/// Protocol for network monitoring
public protocol NetworkMonitor {
    var isNetworkAvailable: Bool { get }
    func startMonitoring()
    func stopMonitoring()
    func setDelegate(_ delegate: NetworkMonitorDelegate?)
}

/// Delegate for network events
public protocol NetworkMonitorDelegate: AnyObject {
    func networkBecameAvailable()
    func networkBecameUnavailable()
}

/// Implementation of network monitoring using NWPathMonitor
public class NetworkMonitorImpl: NSObject, NetworkMonitor {
    // MARK: - Properties
    private var pathMonitor: NWPathMonitor?
    private var monitorQueue: DispatchQueue?
    private weak var delegate: NetworkMonitorDelegate?

    public override init() {}

    public var isNetworkAvailable: Bool {
        return pathMonitor?.currentPath.status == .satisfied
    }

    // MARK: - NetworkMonitor Implementation
    public func startMonitoring() {
        monitorQueue = DispatchQueue(label: "NetworkMonitor.\(UUID().uuidString)")
        pathMonitor = NWPathMonitor()

        pathMonitor?.pathUpdateHandler = { [weak self] path in
            DispatchQueue.main.async {
                guard let self = self else { return }

                if path.status == .satisfied {
                    print("🌐 NetworkMonitor: Network AVAILABLE")
                    self.delegate?.networkBecameAvailable()
                } else {
                    print("🌐 NetworkMonitor: Network UNAVAILABLE")
                    self.delegate?.networkBecameUnavailable()
                }
            }
        }

        pathMonitor?.start(queue: monitorQueue!)
        print("🌐 NetworkMonitor: Started monitoring")
    }

    public func stopMonitoring() {
        pathMonitor?.cancel()
        pathMonitor = nil
        monitorQueue = nil
        print("🌐 NetworkMonitor: Stopped monitoring")
    }

    public func setDelegate(_ delegate: NetworkMonitorDelegate?) {
        self.delegate = delegate
    }
}
