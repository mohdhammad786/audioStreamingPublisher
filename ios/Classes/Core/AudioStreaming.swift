import Flutter
import UIKit
import AVFoundation
import Accelerate
import CoreMotion
import HaishinKit
import os
import ReplayKit
import VideoToolbox

public class AudioStreaming {
    // MARK: - Dependencies (Injected Components)
    // Internal access for extensions
    internal let stateMachine: StreamStateMachine
    internal let phoneMonitor: PhoneCallMonitor
    internal let networkMonitor: NetworkMonitor
    internal let interruptionManager: InterruptionManager
    internal let reconnectionManager: ReconnectionManager
    
    // NEW: Extracted Services
    internal var rtmpService: RtmpServiceProtocol
    internal let audioSessionManager: AudioSessionManagerProtocol
    internal let eventEmitter: StreamEventEmitterProtocol
    internal var notificationObserver: SystemNotificationObserverProtocol

    // MARK: - Context & State
    internal var streamingContext = StreamingContext()
    internal let stateLock = NSRecursiveLock()
    private let previousRunDiagnostics: [String: Any]
    private var didEmitDiagnostics = false
    
    // MARK: - Initialization
    
    init(
        stateMachine: StreamStateMachine,
        phoneMonitor: PhoneCallMonitor,
        networkMonitor: NetworkMonitor,
        interruptionManager: InterruptionManager,
        reconnectionManager: ReconnectionManager,
        rtmpService: RtmpServiceProtocol,
        audioSessionManager: AudioSessionManagerProtocol,
        eventEmitter: StreamEventEmitterProtocol,
        notificationObserver: SystemNotificationObserverProtocol
    ) {
        previousRunDiagnostics = DiagnosticsStore.beginSession()
        DiagnosticsStore.append("AudioStreaming.init")
        self.stateMachine = stateMachine
        self.phoneMonitor = phoneMonitor
        self.networkMonitor = networkMonitor
        self.interruptionManager = interruptionManager
        self.reconnectionManager = reconnectionManager
        self.audioSessionManager = audioSessionManager
        self.rtmpService = rtmpService
        self.eventEmitter = eventEmitter
        self.notificationObserver = notificationObserver
        
        // Finish setup
        self.rtmpService.delegate = self
        setupDelegates()
    }
    
    // MARK: - Setup
    private func setupDelegates() {
        phoneMonitor.setDelegate(self)
        networkMonitor.setDelegate(self)
        interruptionManager.setDelegate(self)
        reconnectionManager.setDelegate(self)
        notificationObserver.delegate = self
        stateMachine.addObserver(self)
    }

    public func setEventSink(_ sink: @escaping FlutterEventSink) {
        eventEmitter.setEventSink(sink)
    }
    
    // MARK: - Configuration
    public func prepare(bitrate: Int?, sampleRate: Int?, isStereo: Bool?) {
        rtmpService.updateSettings(bitrate: bitrate, sampleRate: sampleRate, isStereo: isStereo)
    }

    public func setup(result: @escaping FlutterResult) {
        DiagnosticsStore.append("setup called")
        emitDiagnosticsIfNeeded()

        // Check if there's an active phone call before setup
        if phoneMonitor.isPhoneCallActive {
            result(FlutterError(
                code: "PHONE_CALL_ACTIVE",
                message: "Cannot initialize streaming during an active phone call",
                details: nil
            ))
            return
        }

        audioSessionManager.configureAudioSession { [weak self] success, error in
            guard let self = self else { return }
            
            guard success else {
                result(FlutterError(
                    code: "AUDIO_SESSION_ERROR",
                    message: "Failed to configure audio session: \(error?.localizedDescription ?? "Unknown error")",
                    details: nil
                ))
                return
            }

            self.rtmpService.attachAudio { [weak self] attachSuccess, attachError in
                guard let self = self else { return }

                if let attachError = attachError {
                    result(FlutterError(
                        code: "AUDIO_ATTACH_ERROR",
                        message: "Failed to attach audio device: \(attachError.localizedDescription)",
                        details: nil
                    ))
                    return
                }
                
                // Start monitoring
                self.phoneMonitor.startMonitoring()
                result(nil)
            }
        }

        // Start system notification observation
        notificationObserver.startObserving()
    }
    
    // MARK: - Streaming Control
    public func start(url: String, result: @escaping FlutterResult) {
        DiagnosticsStore.append("start requested url=\(url)")
        guard stateMachine.currentState == .idle else {
            print("Cannot start - stream is in state: \(stateMachine.currentState.description)")
            if stateMachine.currentState == .interrupted || stateMachine.currentState == .reconnecting {
                result(FlutterError(
                    code: "INTERRUPTED_STATE",
                    message: "Cannot start new stream while previous stream is interrupted or reconnecting. Call stop() first.",
                    details: nil
                ))
            } else {
                result(nil)
            }
            return
        }

        if phoneMonitor.isPhoneCallActive {
            result(FlutterError(
                code: "PHONE_CALL_ACTIVE",
                message: "Cannot start streaming during an active phone call",
                details: nil
            ))
            return
        }

        let uri = URL(string: url)
        stateLock.lock()
        self.streamingContext.name = uri?.pathComponents.last
        var urlParts = url.components(separatedBy: "/")
        urlParts.removeLast()
        let connectUrl = urlParts.joined(separator: "/")
        self.streamingContext.url = connectUrl
        stateLock.unlock()
        
        reconnectionManager.resetRetryCount()
        interruptionManager.clearAllInterruptions()

        DispatchQueue.main.async {
            _ = self.stateMachine.transitionTo(.connecting)
            self.networkMonitor.startMonitoring()
            self.rtmpService.connect(url: connectUrl)
            result(nil)
        }
    }

    public func stop() {
        DiagnosticsStore.append("stop called")
        DiagnosticsStore.markGracefulEnd()
        StreamingContext.clearPersistedInterruption()
        networkMonitor.stopMonitoring()
        reconnectionManager.cancelReconnection() // Ensure no pending retries fire

        // Safe audio detachment via Service
        rtmpService.detachAudio { [weak self] in
            guard let self = self else { return }
            
            DispatchQueue.main.async {
                _ = self.stateMachine.transitionTo(.idle)
                self.interruptionManager.clearAllInterruptions()
                self.stateLock.lock()
                self.streamingContext.clear()
                self.stateLock.unlock()
                self.rtmpService.close()
                self.audioSessionManager.deactivateAudioSession()
            }
        }
    }

    public func mute() {
        rtmpService.mute()
    }

    public func unmute() {
        rtmpService.unmute()
    }

    public func dispose() {
        DiagnosticsStore.append("dispose called")
        DiagnosticsStore.markGracefulEnd()
        StreamingContext.clearPersistedInterruption()
        interruptionManager.clearAllInterruptions()
        networkMonitor.stopMonitoring()
        phoneMonitor.stopMonitoring()
        notificationObserver.stopObserving()
        
        // Synchronous cleanup attempt (simplified for service)
        rtmpService.detachAudio(completion: nil)
        audioSessionManager.deactivateAudioSession()
    }

    private func emitDiagnosticsIfNeeded() {
        guard !didEmitDiagnostics else { return }
        didEmitDiagnostics = true

        var details = previousRunDiagnostics
        details["logLines"] = DiagnosticsStore.readLines()
        details["currentState"] = stateMachine.currentState.rawValue
        details["processUptimeSeconds"] = ProcessInfo.processInfo.systemUptime
        details["appState"] = UIApplication.shared.applicationState.rawValue
        if let interruptionAgeSeconds = StreamingContext.loadPersistedInterruptionAgeSeconds() {
            details["persistedInterruptionAgeSeconds"] = interruptionAgeSeconds
        }
        if let interruptionSourceRaw = StreamingContext.loadPersistedInterruptionSourceRaw() {
            details["persistedInterruptionSource"] = interruptionSourceRaw
        }
        if let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String {
            details["appVersion"] = version
        }
        if let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String {
            details["appBuild"] = build
        }

        eventEmitter.sendEvent(
            event: "diagnostic_last_run",
            message: "diagnostics",
            details: details
        )
    }
    
    // MARK: - Internal Helpers
    
    internal func reconnectStream() {
        stateLock.lock()
        let url = streamingContext.savedUrl
        let name = streamingContext.savedName
        let requiresRtmpReinitialize = streamingContext.requiresRtmpReinitialize
        if requiresRtmpReinitialize {
            streamingContext.requiresRtmpReinitialize = false
        }
        stateLock.unlock()

        guard let savedUrl = url, let savedName = name else {
            print("❌ No saved connection info - cannot reconnect")
            stateLock.lock()
            streamingContext.reconnectionSource = .none
            stateLock.unlock()
            _ = stateMachine.transitionTo(.idle)
            return
        }

        guard stateMachine.currentState == .reconnecting else {
            print("❌ Not in reconnecting state (current: \(stateMachine.currentState.description))")
            return
        }

        print("🔄 Reconnecting to: \(savedUrl)/\(savedName)")
        DiagnosticsStore.append("reconnectStream begin requiresRtmpReinitialize=\(requiresRtmpReinitialize) url=\(savedUrl)")

        if requiresRtmpReinitialize {
            DiagnosticsStore.append("reconnectStream reinitialize rtmp")
            rtmpService.reinitialize()
        }

        // 1. Force close the existing connection immediately
        self.rtmpService.close()
        
        // 2. Detach audio
        rtmpService.detachAudio { [weak self] in
            guard let self = self else { return }
            
            DispatchQueue.main.async {
                // 3. Ensure connection is fully closed
                self.rtmpService.close()
                
                // Re-setup audio session with retry logic
                self.audioSessionManager.activateAudioSessionWithRetry(attempt: 0, maxAttempts: 5) { [weak self] success in
                    guard let self = self else { return }

                    guard self.stateMachine.currentState == .reconnecting else {
                        print("⚠️ Reconnection aborted - state changed to \(self.stateMachine.currentState.description)")
                        DiagnosticsStore.append("reconnectStream aborted state=\(self.stateMachine.currentState.rawValue)")
                        return
                    }

                    guard success else {
                        print("❌ Failed to activate audio session after retries")
                        DiagnosticsStore.append("reconnectStream audio session activation failed")
                        _ = self.stateMachine.transitionTo(.failed)
                        self.sendEvent(event: "error", message: "Audio session activation failed after phone call")
                        return
                    }

                    // CRITICAL FIX: Safe re-attachment
                    self.rtmpService.attachAudio { [weak self] attachSuccess, attachError in
                        guard let self = self else { return }
                        
                        if let attachError = attachError {
                            print("❌ Failed to reattach audio: \(attachError)")
                            DiagnosticsStore.append("reconnectStream attachAudio failed")
                            _ = self.stateMachine.transitionTo(.failed)
                            self.sendEvent(event: "error", message: "Failed to attach audio device")
                            return
                        }
                        
                        print("🔄 Connecting to RTMP server...")
                        DiagnosticsStore.append("reconnectStream connecting rtmp")
                        self.rtmpService.connect(url: savedUrl)
                    }
                }
            }
        }
    }

    internal func beginInterruption(source: InterruptionSource) {
        print("🔄 Beginning interruption for \(source)")
        DiagnosticsStore.append("beginInterruption source=\(source) streamState=\(stateMachine.currentState.rawValue)")
        reconnectionManager.cancelReconnection()
        
        stateLock.lock()
        streamingContext.saveCurrent(source: source)
        stateLock.unlock()

        guard stateMachine.transitionTo(.interrupted) else {
            print("❌ Failed to transition to interrupted state")
            return
        }

        StreamingContext.persistInterruptionBegan(source: source)

        if source == .systemResource {
            stateLock.lock()
            streamingContext.requiresRtmpReinitialize = true
            stateLock.unlock()
            DiagnosticsStore.append("beginInterruption systemResource forceRelease only (skip detachAudio)")
            rtmpService.forceRelease()
            audioSessionManager.deactivateAudioSession()
        } else {
            // Safe cleanup via service
            rtmpService.detachAudio { [weak self] in
                guard let self = self else { return }
                
                DispatchQueue.main.async {
                    self.rtmpService.close()
                    self.audioSessionManager.deactivateAudioSession()
                }
            }
        }

        var event = ""
        var message = ""
        
        switch source {
        case .phoneCall:
            event = "audio_interrupted"
            message = "Stream interrupted by phone call"
        case .systemResource:
             event = "audio_interrupted"
             message = "Stream interrupted by system resource (e.g. Camera)"
        case .network:
            event = "network_interrupted"
            message = "Stream interrupted by network loss"
        case .none:
            return
        }

        sendEvent(event: event, message: message)
        print("📢 Sent interruption event: \(event)")
        DiagnosticsStore.append("interruption event sent event=\(event)")

        interruptionManager.handleInterruptionBegan(source: source)

        if source == .network && networkMonitor.isNetworkAvailable {
            print("🌐 Network already available - scheduling proactive recovery")
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
                self?.handleNetworkAvailable()
            }
        }
    }

    internal func endInterruption(source: InterruptionSource) {
        guard stateMachine.currentState == .interrupted else {
            return
        }
        DiagnosticsStore.append("endInterruption source=\(source)")

        StreamingContext.clearPersistedInterruption()
        interruptionManager.handleInterruptionEnded(source: source)
        
        reconnectionManager.resetRetryCount()
        
        _ = stateMachine.transitionTo(.reconnecting)
        reconnectStream()
    }

    internal func sendEvent(event: String, message: String) {
        var details: [String: Any]? = nil
        if self.stateMachine.currentState == .interrupted {
             let remaining = (self.interruptionManager as? InterruptionManagerImpl)?.remainingSeconds() ?? 0
             details = ["remainingSeconds": remaining]
        }
        eventEmitter.sendEvent(event: event, message: message, details: details)
    }
}
