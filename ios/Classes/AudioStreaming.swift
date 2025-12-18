import Flutter
import UIKit
import AVFoundation
import Accelerate
import CoreMotion
import HaishinKit
import os
import ReplayKit
import VideoToolbox

/// Main coordinator class for RTMP audio streaming
/// Follows Clean Architecture and SOLID principles
/// Coordinates between specialized components
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
    private let stateLock = NSLock()

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
        } catch {
            print("Got error in setup: \(error)")
            result(FlutterError(
                code: "AUDIO_SESSION_ERROR",
                message: "Failed to configure audio session: \(error.localizedDescription)",
                details: nil
            ))
            return
        }

        if self.rtmpStream == nil {
            self.rtmpStream = RTMPStream(connection: rtmpConnection)
        }

        guard let rtmpStream = rtmpStream else { return }

        rtmpStream.attachAudio(AVCaptureDevice.default(for: AVMediaType.audio)) { error in
            print("Failed to attach audio: \(error)")
            result(FlutterError(
                code: "AUDIO_ATTACH_ERROR",
                message: "Failed to attach audio device: \(error.localizedDescription)",
                details: nil
            ))
        }

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
        phoneMonitor.startMonitoring()

        result(nil)

        // Register for AVAudioSession interruptions (PRIMARY detection mechanism)
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleInterruption(_:)),
            name: AVAudioSession.interruptionNotification,
            object: AVAudioSession.sharedInstance()
        )
    }

    // MARK: - Interruption Handling (PRIMARY: AVAudioSession)
    @objc private func handleInterruption(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let typeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeValue) else {
            return
        }

        print("🎧 AVAudioSession interruption: \(type == .began ? "BEGAN" : "ENDED")")

        // PRIMARY phone detection mechanism (faster than CallKit)
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
                }
            }
        @unknown default:
            break
        }
    }

    // MARK: - Streaming Control
    public func start(url: String, result: @escaping FlutterResult) {
        // Guard against starting from non-idle state
        guard stateMachine.currentState == .idle else {
            print("Cannot start - stream is in state: \(stateMachine.currentState.description)")
            if stateMachine.currentState == .interrupted || stateMachine.currentState == .reconnecting {
                result(FlutterError(
                    code: "INTERRUPTED_STATE",
                    message: "Cannot start new stream while previous stream is interrupted or reconnecting. Call stop() first.",
                    details: nil
                ))
            } else {
                result(nil)  // Already streaming, ignore
            }
            return
        }

        // Check if there's an active phone call
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
        rtmpStream.delegate = myDelegate
        reconnectionManager.resetRetryCount()
        interruptionManager.clearAllInterruptions()

        DispatchQueue.main.async {
            // Transition to connecting state BEFORE starting network monitor
            _ = self.stateMachine.transitionTo(.connecting)
            self.networkMonitor.startMonitoring()
            self.rtmpConnection.connect(self.url ?? "")
            result(nil)
        }
    }

    public func stop() {
        // Remove event listeners
        rtmpConnection.removeEventListener(.rtmpStatus, selector: #selector(rtmpStatusHandler), observer: self)
        rtmpConnection.removeEventListener(.ioError, selector: #selector(rtmpErrorHandler), observer: self)

        // Stop monitoring
        networkMonitor.stopMonitoring()

        // Detach audio
        rtmpStream?.attachAudio(nil)

        // Reset state
        _ = stateMachine.transitionTo(.idle)
        interruptionManager.clearAllInterruptions()
        savedUrl = nil
        savedName = nil

        // Close connection
        rtmpConnection.close()
        deactivateAudioSession()
    }

    public func dispose() {
        interruptionManager.clearAllInterruptions()
        networkMonitor.stopMonitoring()
        phoneMonitor.stopMonitoring()
        NotificationCenter.default.removeObserver(self)
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
            break

        case RTMPConnection.Code.connectFailed.rawValue,
             RTMPConnection.Code.connectClosed.rawValue:
            handleConnectionFailure(event: e)
            break

        default:
            break
        }
    }

    @objc private func rtmpErrorHandler(_ notification: Notification) {
        let e = Event.from(notification)
        print("RTMP Error: \(e.type.rawValue)")

        // Check if network-related error
        let description = e.type.rawValue
        if isNetworkRelatedError(description: description) {
            let isOffline = !networkMonitor.isNetworkAvailable
            if isOffline {
                print("Network error confirmed offline - treating as network interruption")
                handleNetworkLost()
                return
            }
        }

        // Handle retry logic
        handleConnectionFailure(event: e)
    }

    // MARK: - Connection Success/Failure
    private func handleConnectionSuccess() {
        // Guard: Abort if interrupted during connection
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

        // CLEAR ANY PENDING INTERRUPTION TIMERS
        interruptionManager.clearAllInterruptions()

        let wasReconnecting = (stateMachine.currentState == .reconnecting)

        reconnectionManager.resetRetryCount()

        // Determine stream name
        let streamName = savedName ?? name
        guard let streamName = streamName else {
            print("No stream name available")
            return
        }

        // Publish BEFORE transitioning
        rtmpStream?.publish(streamName)

        // Transition to streaming
        _ = stateMachine.transitionTo(.streaming)

        // Send events AFTER state is stable
        if wasReconnecting {
            reconnectionManager.notifySuccess()

            // Use SAVED reconnection source, not current interruption source
            let event = reconnectionSource == .phoneCall ? "audio_resumed" : "network_resumed"
            let message = reconnectionSource == .phoneCall ?
                "Stream resumed after phone call" :
                "Stream resumed after network recovery"

            // Reset reconnection source
            reconnectionSource = .none
            savedUrl = nil
            savedName = nil

            sendEvent(event: event, message: message)
            print("📢 Sent resume event: \(event)")
        }
    }

    private func handleConnectionFailure(event: Event) {
        let description = event.type.rawValue
        print("❌ Connection failure: \(description)")

        // If we were streaming, this is an interruption!
        // This ensures the 30s timer starts even if NetworkMonitor hasn't fired yet
        if stateMachine.currentState == .streaming {
            print("Connection failed while streaming - treating as interruption")
            beginInterruption(source: .network)
            return
        }

        guard reconnectionManager.shouldRetry(error: description) else {
            print("Max retries reached - giving up")
            sendEvent(event: "error", message: "Connection failed after retries: \(description)")
            return
        }

        reconnectionManager.scheduleRetry(url: url ?? "") { [weak self] in
            guard let self = self else { return }

            // If we are interrupted, we need to transition to RECONNECTING first
            if self.stateMachine.currentState == .interrupted {
                print("🔄 Transitioning from INTERRUPTED to RECONNECTING for retry")
                _ = self.stateMachine.transitionTo(.reconnecting)
            }

            // Verify state before retrying
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
        guard let savedUrl = savedUrl, let savedName = savedName else {
            print("❌ No saved connection info - cannot reconnect")
            stateLock.lock()
            reconnectionSource = .none
            stateLock.unlock()
            _ = stateMachine.transitionTo(.idle)
            return
        }

        // Verify we're actually in reconnecting state
        guard stateMachine.currentState == .reconnecting else {
            print("❌ Not in reconnecting state (current: \(stateMachine.currentState.description))")
            return
        }

        print("🔄 Reconnecting to: \(savedUrl)/\(savedName)")

        // Clean slate (prevent zombie streams)
        rtmpStream?.attachAudio(nil)
        rtmpConnection.close()

        // Re-setup audio session with retry logic
        // iOS may not immediately release audio session after phone call ends
        activateAudioSessionWithRetry { [weak self] success in
            guard let self = self else { return }

            // Verify we're still in reconnecting state (not cancelled)
            guard self.stateMachine.currentState == .reconnecting else {
                print("⚠️ Reconnection cancelled - state changed to \(self.stateMachine.currentState.description)")
                return
            }

            guard success else {
                print("❌ Failed to activate audio session after retries")
                _ = self.stateMachine.transitionTo(.failed)
                self.sendEvent(event: "error", message: "Audio session activation failed after phone call")
                return
            }

            // Re-attach audio
            self.rtmpStream?.attachAudio(AVCaptureDevice.default(for: AVMediaType.audio)) { [weak self] error in
                print("❌ Failed to reattach audio: \(error)")
                _ = self?.stateMachine.transitionTo(.failed)
                self?.sendEvent(event: "error", message: "Failed to attach audio device")
                return
            }

            // Reconnect
            print("🔄 Connecting to RTMP server...")
            self.rtmpConnection.connect(savedUrl)
            print("Reconnection initiated")
        }
    }

    /// Activates audio session with retry logic to handle iOS timing issues
    /// iOS may not immediately release the audio session after a phone call ends
    private func activateAudioSessionWithRetry(attempt: Int = 0, maxAttempts: Int = 5, completion: @escaping (Bool) -> Void) {
        let session = AVAudioSession.sharedInstance()

        do {
            try session.setActive(true)
            print("✅ Audio session activated successfully (attempt \(attempt + 1))")
            completion(true)
        } catch {
            if attempt < maxAttempts {
                let delay = pow(2.0, Double(attempt)) * 0.05 // 50ms, 100ms, 200ms, 400ms, 800ms
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

    // MARK: - Audio I/O (Existing methods preserved)
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

        // Edge Case 1: Network interrupted, now phone rings
        if stateMachine.currentState == .interrupted && interruptionManager.currentSource == .network {
            print("Switching from network to phone interruption")
            interruptionManager.cancelTimer()
            interruptionManager.setCurrentSource(.phoneCall)
            interruptionManager.handleInterruptionBegan(source: .phoneCall)
            sendEvent(event: "audio_interrupted", message: "Phone call started during network interruption")
            return
        }

        // Edge Case 2: Reconnecting from network, phone rings
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

        // Check if network was lost during phone call
        if interruptionManager.hasNetworkLossDuringPhoneCall {
            print("🌐 Network was lost during phone call")
            interruptionManager.setNetworkLostDuringPhoneCall(false)

            // Verify network is ACTUALLY down RIGHT NOW
            if !networkMonitor.isNetworkAvailable {
                print("🌐 Network still down - switching to network interruption")
                interruptionManager.setCurrentSource(.network)
                reconnectionSource = .network // Update reconnection source
                interruptionManager.handleInterruptionBegan(source: .network)
                sendEvent(event: "network_interrupted", message: "Network unavailable after phone call ended")
                return
            } else {
                print("🌐 Network is back - proceeding to reconnect")
            }
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

        // Case 1: During phone call
        if interruptionManager.currentSource == .phoneCall {
            interruptionManager.setNetworkLostDuringPhoneCall(true)
            return
        }

        // Case 2: During reconnection
        if stateMachine.currentState == .reconnecting && savedUrl != nil {
            print("Network lost during reconnection")
            rtmpConnection.close()
            _ = stateMachine.transitionTo(.interrupted)
            interruptionManager.setCurrentSource(.network)
            interruptionManager.handleInterruptionBegan(source: .network)
            sendEvent(event: "network_interrupted", message: "Network lost during reconnection")
            return
        }

        // Case 3: During active stream
        guard stateMachine.currentState == .streaming || stateMachine.currentState == .connecting else {
            return
        }

        interruptionManager.setCurrentSource(.network)
        beginInterruption(source: .network)
    }

    private func handleNetworkAvailable() {
        print("🌐 Network Available")

        // Must be in interrupted state to reconnect
        guard stateMachine.currentState == .interrupted else {
            print("🌐 Network available but not in interrupted state (current: \(stateMachine.currentState.description))")
            return
        }

        stateLock.lock()
        defer { stateLock.unlock() }

        let currentSource = interruptionManager.currentSource

        // Handle network recovery during phone call (phone call takes priority)
        if currentSource == .phoneCall {
            if interruptionManager.hasNetworkLossDuringPhoneCall {
                print("📞 Network came back during phone call - clearing flag, will reconnect after call")
                interruptionManager.setNetworkLostDuringPhoneCall(false)
            }
            return
        }

        // Must be network interruption to proceed
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

        // Reset ALL flags to clean state
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
        // Can add additional logic here based on state changes
    }
}

// MARK: - Common Interruption Logic
extension AudioStreaming {
    private func beginInterruption(source: InterruptionSource) {
        guard stateMachine.currentState == .streaming || stateMachine.currentState == .connecting else {
            print("⚠️ Interruption requested but not streaming (state: \(stateMachine.currentState.description))")
            return
        }

        // Save reconnection source BEFORE state transition
        reconnectionSource = source

        guard stateMachine.transitionTo(.interrupted) else {
            print("❌ Failed to transition to interrupted state")
            return
        }

        // Store connection info
        savedUrl = self.url
        savedName = self.name

        // Close stream (prevent zombie)
        rtmpStream?.attachAudio(nil)
        rtmpConnection.close()
        deactivateAudioSession()

        // Send appropriate interruption event
        let event = source == .phoneCall ? "audio_interrupted" : "network_interrupted"
        let message = source == .phoneCall ? "Stream interrupted by phone call" : "Stream interrupted by network loss"

        sendEvent(event: event, message: message)
        print("📢 Sent interruption event: \(event)")

        // Start timer
        interruptionManager.handleInterruptionBegan(source: source)

        // PROACTIVE RECOVERY: If network is already available, trigger recovery immediately
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

// MARK: - QoS Delegate (Preserved from original)
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

    // MARK: - RTMPStreamDelegate Required Methods
    func rtmpStream(_ stream: RTMPStream, didPublishInsufficientBW connection: RTMPConnection) {
        // No video streaming, audio bitrate is fixed
    }

    func rtmpStream(_ stream: RTMPStream, didPublishSufficientBW connection: RTMPConnection) {
        // No video streaming, audio bitrate is fixed
    }

    func rtmpStream(_ stream: RTMPStream, didStatics connection: RTMPConnection) {
        // Statistics monitoring - not needed for audio-only streaming
    }

    func rtmpStreamDidClear(_ stream: RTMPStream) {
        // Stream cleared - not needed for audio-only streaming
    }

    func rtmpStream(_ stream: RTMPStream, didOutput audio: AVAudioBuffer, presentationTimeStamp: CMTime) {
        // Audio output monitoring - not needed
    }

    func rtmpStream(_ stream: RTMPStream, didOutput video: CMSampleBuffer) {
        // No video streaming
    }
}
