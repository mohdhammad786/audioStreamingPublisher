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
    private let stateMachine: StreamStateMachine
    private let phoneMonitor: PhoneCallMonitor
    private let networkMonitor: NetworkMonitor
    private let interruptionManager: InterruptionManager
    private let reconnectionManager: ReconnectionManager

    // MARK: - RTMP Properties
    private var rtmpConnection: RTMPConnection
    private var rtmpStream: RTMPStream?
    private var url: String? = nil
    private var name: String? = nil
    private let myDelegate = AudioStreamingQoSDelegate()
    private var eventSink: FlutterEventSink?

    // MARK: - Connection State
    private var savedUrl: String?
    private var savedName: String?
    private var reconnectionSource: InterruptionSource = .none
    private let stateLock = NSRecursiveLock()
    
    // MARK: - Audio Synchronization - CRITICAL FIX
    private let audioQueue = DispatchQueue(label: "com.audiostreaming.audio", qos: .userInitiated)
    private var isAudioAttached = false
    private var isConfiguringAudio = false  // NEW: Prevent concurrent configuration
    private let audioLock = NSLock()  // NEW: Dedicated lock for audio operations

    // MARK: - Initialization
    public init(
        stateMachine: StreamStateMachine = StreamStateMachineImpl(),
        phoneMonitor: PhoneCallMonitor = PhoneCallMonitorImpl(),
        networkMonitor: NetworkMonitor = NetworkMonitorImpl(),
        interruptionManager: InterruptionManager = InterruptionManagerImpl(),
        reconnectionManager: ReconnectionManager = ReconnectionManagerImpl(),
        rtmpConnection: RTMPConnection = RTMPConnection(),
        rtmpStream: RTMPStream? = nil
    ) {
        self.stateMachine = stateMachine
        self.phoneMonitor = phoneMonitor
        self.networkMonitor = networkMonitor
        self.interruptionManager = interruptionManager
        self.reconnectionManager = reconnectionManager
        self.rtmpConnection = rtmpConnection
        self.rtmpStream = rtmpStream

        setupDelegates()
    }

    // MARK: - Setup
    private func setupDelegates() {
        phoneMonitor.setDelegate(self)
        networkMonitor.setDelegate(self)
        interruptionManager.setDelegate(self)
        reconnectionManager.setDelegate(self)
        stateMachine.addObserver(self)
    }

    public func setEventSink(_ sink: @escaping FlutterEventSink) {
        self.eventSink = sink
    }

    public func setup(result: @escaping FlutterResult) {
        // Check if there's an active phone call before setup
        if phoneMonitor.isPhoneCallActive {
            result(FlutterError(
                code: "PHONE_CALL_ACTIVE",
                message: "Cannot initialize streaming during an active phone call",
                details: nil
            ))
            return
        }

        // CRITICAL FIX: Configure audio session with proper error handling
        configureAudioSession { [weak self] success, error in
            guard let self = self else { return }
            
            guard success else {
                result(FlutterError(
                    code: "AUDIO_SESSION_ERROR",
                    message: "Failed to configure audio session: \(error?.localizedDescription ?? "Unknown error")",
                    details: nil
                ))
                return
            }

            if self.rtmpStream == nil {
                self.rtmpStream = RTMPStream(connection: self.rtmpConnection)
            }

            guard let rtmpStream = self.rtmpStream else {
                result(FlutterError(
                    code: "STREAM_INIT_ERROR",
                    message: "Failed to initialize RTMP stream",
                    details: nil
                ))
                return
            }

            // CRITICAL FIX: Safe audio attachment with proper guards
            self.safeAttachAudio(to: rtmpStream) { attachSuccess, attachError in
                if let attachError = attachError {
                    result(FlutterError(
                        code: "AUDIO_ATTACH_ERROR",
                        message: "Failed to attach audio device: \(attachError.localizedDescription)",
                        details: nil
                    ))
                    return
                }
                
                // Configure stream settings AFTER successful attachment
                rtmpStream.audioSettings = [
                    .muted: false,
                    .bitrate: 32 * 1000,
                ]

                rtmpStream.recorderSettings = [
                    AVMediaType.audio: [
                        AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
                        AVSampleRateKey: 0,
                        AVNumberOfChannelsKey: 0,
                    ],
                ]

                // Start monitoring
                self.phoneMonitor.startMonitoring()

                result(nil)
            }
        }

        // Register for AVAudioSession interruptions
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleInterruption(_:)),
            name: AVAudioSession.interruptionNotification,
            object: AVAudioSession.sharedInstance()
        )
        
        // Register for App Lifecycle to handle stuck interruptions
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleApplicationDidBecomeActive),
            name: UIApplication.didBecomeActiveNotification,
            object: nil
        )
    }
    
    // MARK: - CRITICAL FIX: Safe Audio Configuration
    
    /// Safely configures audio session with proper error handling
    private func configureAudioSession(completion: @escaping (Bool, Error?) -> Void) {
        audioQueue.async { [weak self] in
            guard let self = self else {
                DispatchQueue.main.async { completion(false, nil) }
                return
            }
            
            let session = AVAudioSession.sharedInstance()
            do {
                if #available(iOS 10.0, *) {
                    try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth])
                } else {
                    session.perform(NSSelectorFromString("setCategory:withOptions:error:"), with: AVAudioSession.Category.playAndRecord, with: [
                        AVAudioSession.CategoryOptions.allowBluetooth,
                        AVAudioSession.CategoryOptions.defaultToSpeaker]
                    )
                    try session.setMode(.default)
                }
                try session.setActive(true)
                
                print("✅ Audio session configured successfully")
                DispatchQueue.main.async { completion(true, nil) }
            } catch {
                print("❌ Audio session configuration failed: \(error)")
                DispatchQueue.main.async { completion(false, error) }
            }
        }
    }
    
    /// Safely attaches audio with guards against concurrent operations
    private func safeAttachAudio(to stream: RTMPStream, completion: @escaping (Bool, Error?) -> Void) {
        audioLock.lock()
        
        // CRITICAL: Prevent concurrent audio configuration
        guard !isConfiguringAudio else {
            audioLock.unlock()
            print("⚠️ Audio configuration already in progress")
            DispatchQueue.main.async {
                completion(false, NSError(domain: "AudioStreaming", code: -1, userInfo: [NSLocalizedDescriptionKey: "Audio configuration in progress"]))
            }
            return
        }
        
        guard !isAudioAttached else {
            audioLock.unlock()
            print("⚠️ Audio already attached")
            DispatchQueue.main.async { completion(true, nil) }
            return
        }
        
        isConfiguringAudio = true
        audioLock.unlock()
        
        audioQueue.async { [weak self] in
            guard let self = self else {
                DispatchQueue.main.async { completion(false, nil) }
                return
            }
            
            // Get audio device
            guard let audioDevice = AVCaptureDevice.default(for: AVMediaType.audio) else {
                self.audioLock.lock()
                self.isConfiguringAudio = false
                self.audioLock.unlock()
                
                let error = NSError(domain: "AudioStreaming", code: -2, userInfo: [NSLocalizedDescriptionKey: "No audio device available"])
                DispatchQueue.main.async { completion(false, error) }
                return
            }
            
            // Attach with error handling
            stream.attachAudio(audioDevice) { error in
                self.audioLock.lock()
                self.isConfiguringAudio = false
                self.audioLock.unlock()
                
                DispatchQueue.main.async {
                    print("❌ Failed to attach audio: \(error)")
                    completion(false, error)
                }
                return
            }
            
            // Wait for attachment to complete (HaishinKit internal processing)
            Thread.sleep(forTimeInterval: 0.2)
            
            self.audioLock.lock()
            self.isAudioAttached = true
            self.isConfiguringAudio = false
            self.audioLock.unlock()
            
            print("✅ Audio attached successfully")
            DispatchQueue.main.async { completion(true, nil) }
        }
    }
    
    /// Safely detaches audio with proper synchronization
    private func safeDetachAudio(from stream: RTMPStream?, completion: (() -> Void)? = nil) {
        audioLock.lock()
        
        guard isAudioAttached else {
            audioLock.unlock()
            completion?()
            return
        }
        
        // Wait if configuration is in progress
        while isConfiguringAudio {
            audioLock.unlock()
            Thread.sleep(forTimeInterval: 0.05)
            audioLock.lock()
        }
        
        isConfiguringAudio = true
        audioLock.unlock()
        
        audioQueue.async { [weak self] in
            guard let self = self else {
                completion?()
                return
            }
            
            stream?.attachAudio(nil)
            
            // Wait for detachment to complete
            Thread.sleep(forTimeInterval: 0.2)
            
            self.audioLock.lock()
            self.isAudioAttached = false
            self.isConfiguringAudio = false
            self.audioLock.unlock()
            
            print("✅ Audio detached successfully")
            completion?()
        }
    }

    // MARK: - Interruption Handling
    @objc private func handleInterruption(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let typeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeValue) else {
            return
        }

        print("🎧 AVAudioSession interruption: \(type == .began ? "BEGAN" : "ENDED")")

        switch type {
        case .began:
            print("🎧 Audio interruption began - treating as phone call (PRIMARY)")
            handlePhoneInterruptionBegan()
        case .ended:
            if let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt {
                let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
                if options.contains(.shouldResume) {
                    print("🎧 Audio interruption ended - should resume")
                    handlePhoneInterruptionEnded()
                } else {
                     print("🎧 Audio interruption ended - resume option missing, checking if we can resume anyway")
                     // Some apps/scenarios don't set shouldResume but we should try if we are active
                     if UIApplication.shared.applicationState == .active {
                         handlePhoneInterruptionEnded()
                     }
                }
            }
        @unknown default:
            break
        }
    }
    
    @objc private func handleApplicationDidBecomeActive() {
        print("📱 Application did become active")
        
        // Fix for Issue 2: Interruption state stuck after camera/phone usage
        // If we are stuck in interrupted state (PhoneCall) but the app is now active,
        // force a check to see if we should resume.
        
        guard stateMachine.currentState == .interrupted else { return }
        
        stateLock.lock()
        let currentSource = interruptionManager.currentSource
        stateLock.unlock()
        
        if currentSource == .phoneCall {
            print("📱 Active while interrupted by PhoneCall - forcing resume check")
            // We can assume if we are active, the camera/phone call UI is gone.
            // However, we should be careful about actual phone calls.
            // But 'phoneMonitor' handles actual GSM calls.
            // 'InterruptionManager' source .phoneCall is also used for AVAudioSession interruptions (Camera).
            
            // Double check if an actual phone call is active
            if phoneMonitor.isPhoneCallActive {
                print("📱 Actual phone call still active - ignoring")
                return
            }
            
            // If no actual phone call, it was likely Camera or other audio interruption that is now over.
            handlePhoneInterruptionEnded()
        }
    }

    // MARK: - Streaming Control
    public func start(url: String, result: @escaping FlutterResult) {
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

        rtmpConnection.addEventListener(.rtmpStatus, selector: #selector(rtmpStatusHandler), observer: self)
        rtmpConnection.addEventListener(.ioError, selector: #selector(rtmpErrorHandler), observer: self)

        let uri = URL(string: url)
        self.name = uri?.pathComponents.last
        var bits = url.components(separatedBy: "/")
        bits.removeLast()
        self.url = bits.joined(separator: "/")
        rtmpStream?.delegate = myDelegate
        reconnectionManager.resetRetryCount()
        interruptionManager.clearAllInterruptions()

        DispatchQueue.main.async {
            _ = self.stateMachine.transitionTo(.connecting)
            self.networkMonitor.startMonitoring()
            self.rtmpConnection.connect(self.url ?? "")
            result(nil)
        }
    }

    public func stop() {
        rtmpConnection.removeEventListener(.rtmpStatus, selector: #selector(rtmpStatusHandler), observer: self)
        rtmpConnection.removeEventListener(.ioError, selector: #selector(rtmpErrorHandler), observer: self)

        networkMonitor.stopMonitoring()

        // CRITICAL FIX: Safe audio detachment
        safeDetachAudio(from: rtmpStream) { [weak self] in
            guard let self = self else { return }
            
            DispatchQueue.main.async {
                _ = self.stateMachine.transitionTo(.idle)
                self.interruptionManager.clearAllInterruptions()
                self.savedUrl = nil
                self.savedName = nil
                self.rtmpConnection.close()
                self.deactivateAudioSession()
            }
        }
    }

    public func dispose() {
        interruptionManager.clearAllInterruptions()
        networkMonitor.stopMonitoring()
        phoneMonitor.stopMonitoring()
        NotificationCenter.default.removeObserver(self)
        
        // CRITICAL FIX: Synchronous cleanup
        let semaphore = DispatchSemaphore(value: 0)
        safeDetachAudio(from: rtmpStream) {
            semaphore.signal()
        }
        _ = semaphore.wait(timeout: .now() + 2.0)
        
        deactivateAudioSession()
        rtmpStream = nil
        rtmpConnection = RTMPConnection()
    }

    // MARK: - RTMP Event Handlers
    @objc private func rtmpStatusHandler(_ notification: Notification) {
        let e = Event.from(notification)
        guard let data: ASObject = e.data as? ASObject,
              let code: String = data["code"] as? String else {
            return
        }

        print("RTMP Status: \(code)")

        switch code {
        case RTMPConnection.Code.connectSuccess.rawValue:
            handleConnectionSuccess()
        case RTMPConnection.Code.connectFailed.rawValue,
             RTMPConnection.Code.connectClosed.rawValue:
            handleConnectionFailure(event: e)
        default:
            break
        }
    }

    @objc private func rtmpErrorHandler(_ notification: Notification) {
        let e = Event.from(notification)
        print("RTMP Error: \(e.type.rawValue)")

        let description = e.type.rawValue
        if isNetworkRelatedError(description: description) {
            let isOffline = !networkMonitor.isNetworkAvailable
            if isOffline {
                print("Network error confirmed offline - treating as network interruption")
                handleNetworkLost()
                return
            }
        }

        handleConnectionFailure(event: e)
    }

    // MARK: - Connection Success/Failure
    private func handleConnectionSuccess() {
        if stateMachine.currentState == .interrupted {
            print("Connection success arrived but we are INTERRUPTED - ignoring")
            rtmpConnection.close()
            return
        }

        guard stateMachine.currentState == .connecting || stateMachine.currentState == .reconnecting else {
            print("Connection success arrived but state is \(stateMachine.currentState.description) - closing zombie")
            rtmpConnection.close()
            return
        }

        interruptionManager.clearAllInterruptions()
        let wasReconnecting = (stateMachine.currentState == .reconnecting)
        reconnectionManager.resetRetryCount()

        let streamName = savedName ?? name
        guard let streamName = streamName else {
            print("No stream name available")
            return
        }

        rtmpStream?.publish(streamName)
        _ = stateMachine.transitionTo(.streaming)

        if wasReconnecting {
            reconnectionManager.notifySuccess()

            stateLock.lock()
            let source = reconnectionSource
            reconnectionSource = .none
            savedUrl = nil
            savedName = nil
            stateLock.unlock()

            let event = source == .phoneCall ? "audio_resumed" : "network_resumed"
            let message = source == .phoneCall ?
                "Stream resumed after phone call" :
                "Stream resumed after network recovery"

            sendEvent(event: event, message: message)
            print("📢 Sent resume event: \(event)")
        }
    }

    private func handleConnectionFailure(event: Event) {
        let description = event.type.rawValue
        print("❌ Connection failure: \(description)")

        if stateMachine.currentState == .streaming {
            print("Connection failed while streaming - treating as interruption")
            beginInterruption(source: .network)
            return
        }
        
        // CRITICAL FIX: Ignore connection failures/closures while in interrupted state.
        // We are waiting for network/phone to resolve, or for the interruption timer to expire.
        // Retrying here would cause a loop of attempts while the network is down.
        if stateMachine.currentState == .interrupted {
            print("Connection failed/closed while interrupted - ignoring (waiting for recovery)")
            return
        }

        guard reconnectionManager.shouldRetry(error: description) else {
            print("Max retries reached - giving up")
            // Fix for Issue 3: Stale state
            // Explicitly transition to failed and send stopped event so UI updates
            _ = stateMachine.transitionTo(.failed)
            sendEvent(event: "rtmp_stopped", message: "Connection failed after retries: \(description)")
            
            // Also ensure we clean up resources
            rtmpConnection.close()
            savedUrl = nil
            savedName = nil
            return
        }

        reconnectionManager.scheduleRetry(url: url ?? "") { [weak self] in
            guard let self = self else { return }

            if self.stateMachine.currentState == .interrupted {
                print("🔄 Transitioning from INTERRUPTED to RECONNECTING for retry")
                _ = self.stateMachine.transitionTo(.reconnecting)
            }

            guard self.stateMachine.currentState == .connecting || 
                  self.stateMachine.currentState == .reconnecting || 
                  self.stateMachine.currentState == .streaming else {
                print("Retry aborted - invalid state: \(self.stateMachine.currentState.description)")
                return
            }

            self.rtmpConnection.connect(self.url ?? "")
        }
    }

    // MARK: - Reconnection
    private func reconnectStream() {
        stateLock.lock()
        let url = savedUrl
        let name = savedName
        stateLock.unlock()

        guard let savedUrl = url, let savedName = name else {
            print("❌ No saved connection info - cannot reconnect")
            stateLock.lock()
            reconnectionSource = .none
            stateLock.unlock()
            _ = stateMachine.transitionTo(.idle)
            return
        }

        guard stateMachine.currentState == .reconnecting else {
            print("❌ Not in reconnecting state (current: \(stateMachine.currentState.description))")
            return
        }

        print("🔄 Reconnecting to: \(savedUrl)/\(savedName)")

        // CRITICAL FIX: Safe cleanup before reconnection
        safeDetachAudio(from: rtmpStream) { [weak self] in
            guard let self = self else { return }
            
            DispatchQueue.main.async {
                self.rtmpConnection.close()
                
                // Re-setup audio session with retry logic
                self.activateAudioSessionWithRetry { [weak self] success in
                    guard let self = self else { return }

                    guard self.stateMachine.currentState == .reconnecting else {
                        print("⚠️ Reconnection aborted - state changed to \(self.stateMachine.currentState.description)")
                        return
                    }

                    guard success else {
                        print("❌ Failed to activate audio session after retries")
                        _ = self.stateMachine.transitionTo(.failed)
                        self.sendEvent(event: "error", message: "Audio session activation failed after phone call")
                        return
                    }

                    // CRITICAL FIX: Safe re-attachment
                    guard let rtmpStream = self.rtmpStream else {
                        print("❌ RTMP stream is nil")
                        _ = self.stateMachine.transitionTo(.failed)
                        return
                    }
                    
                    self.safeAttachAudio(to: rtmpStream) { [weak self] attachSuccess, attachError in
                        guard let self = self else { return }
                        
                        if let attachError = attachError {
                            print("❌ Failed to reattach audio: \(attachError)")
                            _ = self.stateMachine.transitionTo(.failed)
                            self.sendEvent(event: "error", message: "Failed to attach audio device")
                            return
                        }
                        
                        print("🔄 Connecting to RTMP server...")
                        self.rtmpConnection.connect(savedUrl)
                    }
                }
            }
        }
    }

    private func activateAudioSessionWithRetry(attempt: Int = 0, maxAttempts: Int = 5, completion: @escaping (Bool) -> Void) {
        let session = AVAudioSession.sharedInstance()

        do {
            try session.setActive(true)
            print("✅ Audio session activated successfully (attempt \(attempt + 1))")
            completion(true)
        } catch {
            guard stateMachine.currentState == .reconnecting || stateMachine.currentState == .connecting else {
                print("⚠️ Aborting audio session retry - state is \(stateMachine.currentState.description)")
                completion(false)
                return
            }

            if attempt < maxAttempts {
                let delay = pow(2.0, Double(attempt)) * 0.1
                print("⚠️ Audio session activation failed (attempt \(attempt + 1)/\(maxAttempts)): \(error)")
                print("🔄 Retrying in \(Int(delay * 1000))ms...")

                DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
                    self?.activateAudioSessionWithRetry(attempt: attempt + 1, maxAttempts: maxAttempts, completion: completion)
                }
            } else {
                print("❌ Audio session activation failed after \(maxAttempts) attempts: \(error)")
                completion(false)
            }
        }
    }

    // MARK: - Helper Methods
    private func isNetworkRelatedError(description: String) -> Bool {
        let keywords = [
            "network", "timeout", "unreachable", "connection refused",
            "no route", "socket", "broken pipe", "failed to connect",
            "host", "resolve", "dns", "ioexception",
            "software", "abort", "connection reset"
        ]
        return keywords.contains { description.lowercased().contains($0) }
    }

    private func deactivateAudioSession() {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setActive(false, options: .notifyOthersOnDeactivation)
        } catch {
            print("Error deactivating AVAudioSession: \(error)")
        }
    }

    private func sendEvent(event: String, message: String) {
        DispatchQueue.main.async { [weak self] in
            self?.eventSink?([
                "event": event,
                "errorDescription": message
            ])
        }
    }

    // MARK: - Audio I/O
    public func pauseVideoStreaming() {
        rtmpStream?.paused = true
    }

    public func resumeVideoStreaming() {
        rtmpStream?.paused = false
    }

    public func isPaused() -> Bool {
        return rtmpStream?.paused ?? false
    }

    public func mute() {
        rtmpStream?.audioSettings = [
            .muted: true,
            .bitrate: 32 * 1000,
        ]
    }

    public func unmute() {
        rtmpStream?.audioSettings = [
            .muted: false,
            .bitrate: 32 * 1000,
        ]
    }

    public func appendAudioBuffer(_ buffer: CMSampleBuffer) {
        rtmpStream?.appendSampleBuffer(buffer, withType: .audio)
    }
}

// MARK: - PhoneCallMonitorDelegate
extension AudioStreaming: PhoneCallMonitorDelegate {
    public func phoneCallDidBegin() {
        handlePhoneInterruptionBegan()
    }

    public func phoneCallDidEnd() {
        handlePhoneInterruptionEnded()
    }

    private func handlePhoneInterruptionBegan() {
        print("📞 Phone Call Interruption Began")

        if stateMachine.currentState == .interrupted && interruptionManager.currentSource == .network {
            print("Switching from network to phone interruption")
            interruptionManager.cancelTimer()
            interruptionManager.setCurrentSource(.phoneCall)
            interruptionManager.handleInterruptionBegan(source: .phoneCall)
            sendEvent(event: "audio_interrupted", message: "Phone call started during network interruption")
            return
        }

        if stateMachine.currentState == .reconnecting && interruptionManager.currentSource == .network {
            print("Phone call during network reconnection")
            rtmpConnection.close()
            _ = stateMachine.transitionTo(.interrupted)
            interruptionManager.setCurrentSource(.phoneCall)
            interruptionManager.handleInterruptionBegan(source: .phoneCall)
            sendEvent(event: "audio_interrupted", message: "Phone call interrupted reconnection")
            return
        }

        interruptionManager.setCurrentSource(.phoneCall)
        beginInterruption(source: .phoneCall)
    }

    private func handlePhoneInterruptionEnded() {
        print("📞 Phone Call Interruption Ended")

        stateLock.lock()
        defer { stateLock.unlock() }

        guard interruptionManager.currentSource == .phoneCall else {
            print("⚠️ Phone interruption ended but current source is \(interruptionManager.currentSource)")
            return
        }

        interruptionManager.handleInterruptionEnded(source: .phoneCall)
        
        if interruptionManager.currentSource == .network {
            print("🌐 Phone ended but network lost - switching to network interruption (Scenario 3)")
            reconnectionSource = .network
            sendEvent(event: "network_interrupted", message: "Network unavailable after phone call ended")
            return
        }

        endInterruption(source: .phoneCall)
    }
}

// MARK: - NetworkMonitorDelegate
extension AudioStreaming: NetworkMonitorDelegate {
    public func networkBecameUnavailable() {
        handleNetworkLost()
    }

    public func networkBecameAvailable() {
        handleNetworkAvailable()
    }

    private func handleNetworkLost() {
        print("🌐 Network Lost")

        if interruptionManager.currentSource == .phoneCall {
            interruptionManager.setNetworkLostDuringPhoneCall(true)
            return
        }

        if stateMachine.currentState == .reconnecting && savedUrl != nil {
            print("Network lost during reconnection")
            rtmpConnection.close()
            _ = stateMachine.transitionTo(.interrupted)
            interruptionManager.setCurrentSource(.network)
            interruptionManager.handleInterruptionBegan(source: .network)
            sendEvent(event: "network_interrupted", message: "Network lost during reconnection")
            return
        }

        guard stateMachine.currentState == .streaming || stateMachine.currentState == .connecting else {
            return
        }

        interruptionManager.setCurrentSource(.network)
        beginInterruption(source: .network)
    }

    private func handleNetworkAvailable() {
        print("🌐 Network Available")

        guard stateMachine.currentState == .interrupted else {
            print("🌐 Network available but not in interrupted state (current: \(stateMachine.currentState.description))")
            return
        }

        stateLock.lock()
        defer { stateLock.unlock() }

        let currentSource = interruptionManager.currentSource

        if currentSource == .phoneCall {
            if interruptionManager.hasNetworkLossDuringPhoneCall {
                print("📞 Network came back during phone call - clearing flag, will reconnect after call")
                interruptionManager.setNetworkLostDuringPhoneCall(false)
            }
            return
        }

        guard currentSource == .network else {
            print("⚠️ Network available but current source is \(currentSource) - cannot reconnect")
            return
        }

        print("🌐 Ending network interruption - will reconnect")
        endInterruption(source: .network)
    }
}

// MARK: - InterruptionManagerDelegate
extension AudioStreaming: InterruptionManagerDelegate {
    public func interruptionTimedOut(source: InterruptionSource) {
        print("⏱️ Interruption timeout for \(source)")

        guard stateMachine.currentState == .interrupted else {
            print("⚠️ Timeout but not in interrupted state")
            return
        }

        interruptionManager.setNetworkLostDuringPhoneCall(false)
        reconnectionSource = .none

        _ = stateMachine.transitionTo(.failed)
        savedUrl = nil
        savedName = nil
        sendEvent(event: "rtmp_stopped", message: "Stream stopped due to prolonged interruption")
        print("📢 Sent rtmp_stopped event due to timeout")
    }
}

// MARK: - ReconnectionManagerDelegate
extension AudioStreaming: ReconnectionManagerDelegate {
    public func reconnectionWillBegin(url: String, streamName: String) {
        print("🔄 Reconnection will begin")
    }

    public func reconnectionDidSucceed() {
        print("🔄 Reconnection succeeded")
    }

    public func reconnectionDidFail(error: String) {
        print("🔄 Reconnection failed: \(error)")
    }

    public func reconnectionRetrying(attempt: Int, delay: TimeInterval) {
        sendEvent(event: "rtmp_retry", message: "Retrying connection (attempt \(attempt))")
    }
}

// MARK: - StreamStateObserver
extension AudioStreaming: StreamStateObserver {
    public func streamStateDidChange(from oldState: StreamState, to newState: StreamState) {
        print("🔄 State changed: \(oldState.description) -> \(newState.description)")
    }
}

// MARK: - Common Interruption Logic
extension AudioStreaming {
    private func beginInterruption(source: InterruptionSource) {
        guard stateMachine.currentState == .streaming || stateMachine.currentState == .connecting else {
            print("⚠️ Interruption requested but not streaming (state: \(stateMachine.currentState.description))")
            return
        }

        stateLock.lock()
        reconnectionSource = source
        savedUrl = self.url
        savedName = self.name
        stateLock.unlock()

        guard stateMachine.transitionTo(.interrupted) else {
            print("❌ Failed to transition to interrupted state")
            return
        }

        // CRITICAL FIX: Safe cleanup during interruption
        safeDetachAudio(from: rtmpStream) { [weak self] in
            guard let self = self else { return }
            
            DispatchQueue.main.async {
                self.rtmpConnection.close()
                self.deactivateAudioSession()
            }
        }

        let event = source == .phoneCall ? "audio_interrupted" : "network_interrupted"
        let message = source == .phoneCall ? "Stream interrupted by phone call" : "Stream interrupted by network loss"

        sendEvent(event: event, message: message)
        print("📢 Sent interruption event: \(event)")

        interruptionManager.handleInterruptionBegan(source: source)

        if source == .network && networkMonitor.isNetworkAvailable {
            print("🌐 Network already available - scheduling proactive recovery")
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
                self?.handleNetworkAvailable()
            }
        }
    }

    private func endInterruption(source: InterruptionSource) {
        guard stateMachine.currentState == .interrupted else {
            return
        }

        interruptionManager.handleInterruptionEnded(source: source)
        _ = stateMachine.transitionTo(.reconnecting)
        reconnectStream()
    }
}

// MARK: - QoS Delegate
class AudioStreamingQoSDelegate: RTMPStreamDelegate {
    let minBitrate: UInt32 = 300 * 1024
    let maxBitrate: UInt32 = 2500 * 1024
    let incrementBitrate: UInt32 = 512 * 1024

    func getVideoBitrate() -> UInt32 {
        return 0
    }

    func getAudioBitrate() -> UInt32 {
        return 32 * 1000
    }

    func rtmpStream(_ stream: RTMPStream, didPublishInsufficientBW connection: RTMPConnection) {}
    
    func rtmpStream(_ stream: RTMPStream, didPublishSufficientBW connection: RTMPConnection) {}
    
    func rtmpStream(_ stream: RTMPStream, didStatics connection: RTMPConnection) {}
    
    func rtmpStreamDidClear(_ stream: RTMPStream) {}
    
    func rtmpStream(_ stream: RTMPStream, didOutput audio: AVAudioBuffer, presentationTimeStamp: CMTime) {}
    
    func rtmpStream(_ stream: RTMPStream, didOutput video: CMSampleBuffer) {}
}