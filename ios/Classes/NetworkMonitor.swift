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
    private var wasAvailable: Bool? = nil  // Track previous state to avoid redundant callbacks

    public override init() {}

    public var isNetworkAvailable: Bool {
        return pathMonitor?.currentPath.status == .satisfied
    }

    // MARK: - NetworkMonitor Implementation
    public func startMonitoring() {
        monitorQueue = DispatchQueue(label: "NetworkMonitor.\(UUID().uuidString)")
        pathMonitor = NWPathMonitor()
        wasAvailable = nil  // Reset on start

        pathMonitor?.pathUpdateHandler = { [weak self] path in
            DispatchQueue.main.async {
                guard let self = self else { return }

                let isAvailable = path.status == .satisfied

                // Only fire callback if state ACTUALLY changed (avoid redundant callbacks)
                guard self.wasAvailable != isAvailable else {
                    print("🌐 NetworkMonitor: Status unchanged (\(isAvailable ? "available" : "unavailable")) - skipping")
                    return
                }

                self.wasAvailable = isAvailable

                if isAvailable {
                    // Small delay to let WiFi fully connect (iOS timing bug workaround)
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
                        guard let self = self else { return }
                        // Verify still available after delay
                        if self.pathMonitor?.currentPath.status == .satisfied {
                            print("🌐 NetworkMonitor: Network AVAILABLE (verified after 500ms)")
                            self.delegate?.networkBecameAvailable()
                        } else {
                            print("🌐 NetworkMonitor: Network became unavailable during verification")
                            self.wasAvailable = false
                        }
                    }
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
        wasAvailable = nil  // Reset
        print("🌐 NetworkMonitor: Stopped monitoring")
    }

    public func setDelegate(_ delegate: NetworkMonitorDelegate?) {
        self.delegate = delegate
    }
}
