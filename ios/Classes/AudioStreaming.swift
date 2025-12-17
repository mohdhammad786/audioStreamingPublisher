import Flutter
import UIKit
import AVFoundation
import Accelerate
import CoreMotion
import HaishinKit
import os
import ReplayKit
import VideoToolbox
import Network

public class AudioStreaming {
    private var rtmpConnection = RTMPConnection()
    private var rtmpStream: RTMPStream!
    private var url: String? = nil
    private var name: String? = nil
    private var retries: Int = 0
    private let myDelegate = AudioStreamingQoSDelegate()
    private var eventSink: FlutterEventSink?

    // Interruption handling properties
    private var interruptionTimer: DispatchSourceTimer?
    private var isInterrupted: Bool = false
    private var savedUrl: String?
    private var savedName: String?
    private let phoneInterruptionTimeout: TimeInterval = 30.0  // 30 seconds

    private let networkInterruptionTimeout: TimeInterval = 30.0  // 30 seconds
    private var isStreamingActive: Bool = false
    private var isRetryingConnection: Bool = false  // Prevents overlapping retry chains

    // Interruption source tracking
    private enum InterruptionSource {
        case none
        case phoneCall
        case network
    }
    private var currentInterruptionSource: InterruptionSource = .none
    private var networkLostDuringPhoneCall: Bool = false

    // Network monitoring properties
    private var networkMonitor: NWPathMonitor?
    private var networkQueue: DispatchQueue?

    public func setEventSink(_ sink: @escaping FlutterEventSink) {
        self.eventSink = sink
    }
    
    public func setup(result: @escaping FlutterResult){
        // Check if there's an active phone call before setup
        if isPhoneCallActive() {
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
            print("Got error in setup: ")
            print(error)
            result(FlutterError(
                code: "AUDIO_SESSION_ERROR",
                message: "Failed to configure audio session: \(error.localizedDescription)",
                details: nil
            ))
            return
        }
        rtmpStream = RTMPStream(connection: rtmpConnection)
        rtmpStream.attachAudio(AVCaptureDevice.default(for: AVMediaType.audio)) { error in
            print("Got error in attachAudio: ")
            print(error)
            result(FlutterError(
                code: "AUDIO_ATTACH_ERROR",
                message: "Failed to attach audio device: \(error.localizedDescription)",
                details: nil
            ))
        }
        rtmpStream.audioSettings = [
            .muted: false, // mute audio
            .bitrate: 32 * 1000,
        ]
        // "0" means the same of input
        rtmpStream.recorderSettings = [
            AVMediaType.audio: [
                AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
                AVSampleRateKey: 0,
                AVNumberOfChannelsKey: 0,
                // AVEncoderBitRateKey: 128000,
            ],
        ]
        result(nil)

        // Register for audio interruptions (phone calls, alarms, etc.)
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleInterruption(_:)),
            name: AVAudioSession.interruptionNotification,
            object: AVAudioSession.sharedInstance()
        )
    }

    @objc private func handleInterruption(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let typeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeValue) else {
            print("handleInterruption: Invalid notification")
            return
        }

        print("Audio interruption: \(type == .began ? "BEGAN" : "ENDED")")

        switch type {
        case .began:
            handlePhoneInterruptionBegan()

        case .ended:
            guard let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt else {
                print("Interruption ended but no options provided")
                return
            }
            let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)

            if options.contains(.shouldResume) {
                handlePhoneInterruptionEnded()
            } else {
                print("Interruption ended but cannot auto-resume")
            }

        @unknown default:
            break
        }
    }

    // Phone Call Interruption Handlers
    private func handlePhoneInterruptionBegan() {
        print("Phone Call Interruption Began")

        // If already interrupted by network, switch to phone (phone takes precedence)
        if isInterrupted && currentInterruptionSource == .network {
            print("Switching from network to phone interruption (phone takes precedence)")
            cancelInterruptionTimer()
            currentInterruptionSource = .phoneCall
            startInterruptionTimer()

            // Notify Flutter of source change
            DispatchQueue.main.async { [weak self] in
                self?.eventSink?([
                    "event": "audio_interrupted",
                    "errorDescription": "Phone call started during network interruption"
                ])
            }
            return
        }

        currentInterruptionSource = .phoneCall
        handleInterruptionBegan()
    }

    private func handlePhoneInterruptionEnded() {
        print("Phone Call Interruption Ended")

        // Only respond if interrupted by phone
        guard currentInterruptionSource == .phoneCall else {
            print("Ignoring phone end - not interrupted by phone (source=\(currentInterruptionSource))")
            return
        }
        
        // Check if network was lost during phone call
        if networkLostDuringPhoneCall {
            print("Phone ended but network still down - switching to network interruption")
            networkLostDuringPhoneCall = false

            // CRITICAL FIX: Verify network is ACTUALLY down right now
            // It might have recovered during the call
            let isCurrentlyOffline = networkMonitor?.currentPath.status != .satisfied
            if !isCurrentlyOffline {
                print("Actually, network is back up now - proceeding with reconnection")
                handleInterruptionEnded()
                return
            }

            // Network is still down, switch to network interruption
            currentInterruptionSource = .network
            startInterruptionTimer()  // Restart with network timeout (30s)

            // Notify Flutter
            DispatchQueue.main.async { [weak self] in
                self?.eventSink?([
                    "event": "network_interrupted",
                    "errorDescription": "Network loss detected during phone call"
                ])
            }
            return
        }

        handleInterruptionEnded()
    }

    // Network Interruption Handlers
    private func handleNetworkLost() {
        print("Network Lost Detected")

        // Ignore if already interrupted by phone (phone takes precedence)
        // CRITICAL FIX: But if we are trying to RECONNECT from a phone call (savedUrl != nil and !isInterrupted),
        // and network fails, we MUST switch to network interruption to avoid getting stuck.
        if currentInterruptionSource == .phoneCall {
            // Case 1: Reconnection Loop-hole (Call ended, trying to reconnect, but network failed)
            if savedUrl != nil && !isInterrupted {
                print("Network lost during reconnection from phone - switching to network interruption")
                currentInterruptionSource = .network
                handleInterruptionBegan()
                return
            }
            
            // Case 2: Network lost during active call
            print("Already interrupted by phone call, flagging network loss for later")
            networkLostDuringPhoneCall = true
            return
        }

        // We care if we are INTENDING to stream (active or reconnecting)
        // Do NOT rely on rtmpConnection.connected as it might already be false due to the error
        guard isStreamingActive || savedUrl != nil else {
            print("Not streaming or reconnecting (active=\(isStreamingActive)), ignoring network loss")
            return
        }

        currentInterruptionSource = .network
        handleInterruptionBegan()
    }

    private func handleNetworkAvailable() {
        print("Network Available")

        // Only respond if interrupted by network
        // Only respond if interrupted by network
        guard currentInterruptionSource == .network else {
            // CRITICAL FIX: If network comes back DURING a phone call, we must record that.
            // Otherwise, when call ends, we'll think network is still down and wait forever.
            if currentInterruptionSource == .phoneCall {
                print("Network recovered during phone call")
                networkLostDuringPhoneCall = false
            } else {
                print("Ignoring network available - not interrupted by network (source=\(currentInterruptionSource))")
            }
            return
        }

        handleInterruptionEnded()
    }

    // Common Interruption Handlers
    private func handleInterruptionBegan() {
        print("Interruption Began (Source: \(currentInterruptionSource))")

        guard !isInterrupted else {
            print("Already handling interruption")
            return
        }

        isInterrupted = true

        // 1. Store current connection info for reconnection
        savedUrl = self.url
        savedName = self.name

        // 2. Gracefully close stream and connection
        rtmpStream.attachAudio(nil) // Explicitly detach audio to ensure clean state
        rtmpConnection.close()
        deactivateAudioSession()

        // 3. Send appropriate event
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }

            let event = self.currentInterruptionSource == .phoneCall ?
                "audio_interrupted" : "network_interrupted"
            let message = self.currentInterruptionSource == .phoneCall ?
                "Phone call detected - stream paused temporarily" :
                "Network loss detected - stream paused temporarily"

            self.eventSink?([
                "event": event,
                "errorDescription": message
            ])
        }

        // 4. Start timeout timer
        startInterruptionTimer()
    }

    private func handleInterruptionEnded() {
        print("Interruption Ended (Source: \(currentInterruptionSource))")

        guard isInterrupted else {
            print("Not in interrupted state")
            return
        }

        // Cancel timeout timer
        cancelInterruptionTimer()

        print("Attempting reconnection")

        // Attempt to reconnect
        reconnectStream()
    }

    private func startInterruptionTimer() {
        cancelInterruptionTimer()  // Cancel any existing timer

        let timeout = currentInterruptionSource == .phoneCall ?
            phoneInterruptionTimeout : networkInterruptionTimeout

        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.main)
        timer.schedule(deadline: .now() + timeout)
        timer.setEventHandler { [weak self] in
            self?.handleInterruptionTimeout()
        }
        timer.resume()

        interruptionTimer = timer
        print("Interruption timeout timer started (\(timeout) seconds, source=\(currentInterruptionSource))")
    }

    private func cancelInterruptionTimer() {
        interruptionTimer?.cancel()
        interruptionTimer = nil
    }

    private func handleInterruptionTimeout() {
        print("Interruption timeout expired - giving up on reconnection")

        isInterrupted = false
        currentInterruptionSource = .none // Reset source
        savedUrl = nil
        savedName = nil

        // Send RTMP_STOPPED event (timeout expired)
        DispatchQueue.main.async { [weak self] in
            self?.eventSink?([
                "event": "rtmp_stopped",
                "errorDescription": "Stream stopped due to prolonged interruption"
            ])
        }
    }

    private func reconnectStream() {
        guard let savedUrl = savedUrl, let savedName = savedName else {
            print("No saved connection info for reconnection")
            isInterrupted = false
            return
        }

        print("Reconnecting to: \(savedUrl)/\(savedName)")

        // Re-setup audio session
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setActive(true)
            print("AVAudioSession reactivated successfully")
        } catch {
            print("Failed to reactivate audio session: \(error)")
            handleReconnectionFailure(error: "Audio session activation failed")
            return
        }

        // Re-attach audio device
        rtmpStream.attachAudio(AVCaptureDevice.default(for: AVMediaType.audio)) { [weak self] error in
            print("Failed to reattach audio: \(error)")
            self?.handleReconnectionFailure(error: "Failed to attach audio device")
        }

        // Reconnect RTMP
        rtmpConnection.connect(savedUrl)

        // The rtmpStatusHandler will receive .connectSuccess and call publish()
        // We need to modify rtmpStatusHandler to handle reconnection
        // NOTE: isInterrupted will be reset in rtmpStatusHandler AFTER connection succeeds

        print("Reconnection initiated")
    }

    private func handleReconnectionFailure(error: String) {
        print("Reconnection failed: \(error)")

        isInterrupted = false
        savedUrl = nil
        savedName = nil

        DispatchQueue.main.async { [weak self] in
            self?.eventSink?([
                "event": "error",
                "errorDescription": "Reconnection failed: \(error)"
            ])
        }
    }


    public func start(url: String, result: @escaping FlutterResult) {
        // Guard against double-start or zombie restarts
        if isStreamingActive {
            print("Already streaming, ignoring start request")
            result(nil)
            return
        }

        // Guard against starting while in interrupted/reconnecting state
        if isInterrupted || savedUrl != nil {
            print("Cannot start - stream is in interrupted/reconnecting state")
            result(FlutterError(
                code: "INTERRUPTED_STATE",
                message: "Cannot start new stream while previous stream is interrupted or reconnecting. Call stop() first.",
                details: nil
            ))
            return
        }

        // Check if there's an active phone call
        if isPhoneCallActive() {
            result(FlutterError(
                code: "PHONE_CALL_ACTIVE",
                message: "Cannot start streaming during an active phone call",
                details: nil
            ))
            return
        }

        rtmpConnection.addEventListener(.rtmpStatus, selector:#selector(rtmpStatusHandler), observer: self)
        rtmpConnection.addEventListener(.ioError, selector: #selector(rtmpErrorHandler), observer: self)

        let uri = URL(string: url)
        self.name = uri?.pathComponents.last
        var bits = url.components(separatedBy: "/")
        bits.removeLast()
        self.url = bits.joined(separator: "/")
        rtmpStream.delegate = myDelegate
        self.retries = 0

        // Run this on the ui thread.
        DispatchQueue.main.async {
            // CRITICAL: Set isStreamingActive BEFORE starting network monitor
            // to prevent race condition where network callbacks fire before flag is set
            self.isStreamingActive = true
            self.startNetworkMonitoring()
            self.rtmpConnection.connect(self.url ?? "frog")
            result(nil)
        }
    }
    
    
    
    @objc private func rtmpStatusHandler(_ notification: Notification) {
        let e = Event.from(notification)
        guard let data: ASObject = e.data as? ASObject, let code: String = data["code"] as? String else {
            return
        }
        print(e)
        
        switch code {
        case RTMPConnection.Code.connectSuccess.rawValue:
            // CRITICAL FIX: If we are currently interrupted (e.g. network lost again while connecting),
            // we MUST ignore this success event. Processing it would clear savedUrl/source and leave us broken.
            if isInterrupted {
                print("Connection success arrived but we are in Interrupted state - ignoring to preserve state")
                // Close the zombie connection to be safe
                rtmpConnection.close()
                return
            }

            // CRITICAL FIX: race condition check
            // If stop() was called while we were connecting, we must abort immediately.
            guard isStreamingActive else {
                print("Connection success arrived but streaming is no longer active - closing")
                rtmpConnection.close()
                return
            }

            retries = 0

            // CRITICAL FIX: Reset isInterrupted ONLY after successful connection
            // This prevents race conditions where network is lost during reconnection
            if isInterrupted {
                isInterrupted = false
                print("Reconnection successful - resetting isInterrupted flag")
            }

            // Determine stream name (use saved name if reconnecting)
            let streamName = savedName ?? name

            guard let streamName = streamName else {
                print("No stream name available for publishing")
                return
            }

            rtmpStream.publish(streamName)

            // If this was a reconnection, send appropriate RESUMED event
            if savedUrl != nil {
                print("Reconnection successful!")

                let event = currentInterruptionSource == .phoneCall ?
                    "audio_resumed" : "network_resumed"

                savedUrl = nil
                savedName = nil
                currentInterruptionSource = .none

                DispatchQueue.main.async { [weak self] in
                    self?.eventSink?([
                        "event": event,
                        "errorDescription": "Stream resumed after interruption"
                    ])
                }
            }
            break
        case RTMPConnection.Code.connectFailed.rawValue, RTMPConnection.Code.connectClosed.rawValue:
            // Check for network error first
            let description = e.type.rawValue
            
            // CRITICAL FIX: Only treat as "Network Lost" (passive wait) if the OS agrees network is down.
            // If OS says network is UP (satisfied), this is likely a server/socket glitch.
            // We should RETRY in that case, not give up and wait for a network event that won't come.
            let isOsOffline = networkMonitor?.currentPath.status != .satisfied
            if isNetworkRelatedError(description: description) && isOsOffline {
               print("RTSP failure appears network-related AND OS says offline -> triggering network interruption")
               handleNetworkLost()
               return
            }

            guard retries <= 3 else {
                if SwiftFlutterAudioStreamingPlugin.eventSink != nil {
                    SwiftFlutterAudioStreamingPlugin.eventSink!(["event" : "error",
                           "errorDescription" : "connection failed " + e.type.rawValue])
                }
                return
            }

            // CRITICAL FIX: Prevent overlapping retry chains
            guard !isRetryingConnection else {
                print("Retry already in progress, skipping duplicate retry")
                return
            }

            retries += 1
            isRetryingConnection = true

            // Blocking sleep on Main Thread creates deadlocks! Use asyncAfter instead.
            let delay = pow(2.0, Double(retries))
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
                guard let self = self else { return }

                self.isRetryingConnection = false

                // CRITICAL FIX: race condition check
                // Ensure we are still supposed to be streaming before retrying
                guard self.isStreamingActive else {
                    print("Retry aborted - streaming is no longer active")
                    return
                }

                // Don't retry if we've entered interruption mode (e.g. network lost during wait)
                if self.isInterrupted {
                     print("Retry aborted - entered interrupted state")
                     return
                }

                print("Retrying connection (attempt \(self.retries))...")
                self.rtmpConnection.connect(self.url ?? "")

                 if SwiftFlutterAudioStreamingPlugin.eventSink != nil {
                    SwiftFlutterAudioStreamingPlugin.eventSink!(["event" : "rtmp_retry",
                               "errorDescription" : "connection failed " + e.type.rawValue])
                }
            }
            break
        default:
            break
        }
    }
    
    
    @objc private func rtmpErrorHandler(_ notification: Notification) {
        if #available(iOS 10.0, *) {
            os_log("%s", notification.name.rawValue)
        }
        
        let description = notification.name.rawValue

        
        // CRITICAL FIX: Only trigger Network Lost if OS confirms offline.
        let isOsOffline = networkMonitor?.currentPath.status != .satisfied
        if isNetworkRelatedError(description: description) && isOsOffline {
           print("RTSP IO Error appears network-related AND OS says offline -> triggering network interruption")
           handleNetworkLost()
           return
        }

        guard retries <= 3 else {
            if SwiftFlutterAudioStreamingPlugin.eventSink != nil {
                SwiftFlutterAudioStreamingPlugin.eventSink!(["event" : "rtmp_stopped",
                       "errorDescription" : "rtmp disconnected"])
            }
            return
        }

        // CRITICAL FIX: Prevent overlapping retry chains
        guard !isRetryingConnection else {
            print("Retry already in progress, skipping duplicate retry")
            return
        }

        retries += 1
        isRetryingConnection = true

        let delay = pow(2.0, Double(retries))
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
             guard let self = self else { return }

             self.isRetryingConnection = false

             // CRITICAL FIX: race condition check
             guard self.isStreamingActive else {
                 print("Retry aborted - streaming is no longer active")
                 return
             }

             if self.isInterrupted { return }

             print("Retrying connection (attempt \(self.retries))...")
             self.rtmpConnection.connect(self.url ?? "")

             if SwiftFlutterAudioStreamingPlugin.eventSink != nil {
                SwiftFlutterAudioStreamingPlugin.eventSink!(["event" : "rtmp_retry",
                       "errorDescription" : "rtmp disconnected"])
            }
        }
    }

    private func isNetworkRelatedError(description: String) -> Bool {
        let keywords = [
            "network", "timeout", "unreachable", "connection refused",
            "no route", "socket", "broken pipe", "failed to connect",
            "host", "resolve", "dns", "ioexception",
            "software", "abort", "connection reset"
        ]
        let lowerDesc = description.lowercased()
        return keywords.contains { lowerDesc.contains($0) }
    }
    
    
    public func pauseVideoStreaming() {
        rtmpStream.paused = true
    }
    
    
    public func resumeVideoStreaming() {
        rtmpStream.paused = false
    }
    
    
    public func isPaused() -> Bool{
        return rtmpStream.paused
    }

    public func mute() {
        rtmpStream.audioSettings = [
            .muted: true,
            .bitrate: 32 * 1000,
        ]
    }

    public func unmute() {
        rtmpStream.audioSettings = [
            .muted: false,
            .bitrate: 32 * 1000,
        ]
    }

    public func addAudioData(buffer: CMSampleBuffer) {
        rtmpStream.audioSettings = [
            .muted: false, // mute audio
            .bitrate: 32 * 1000,
        ]
        rtmpStream.appendSampleBuffer(buffer, withType: .audio)
    }
    
    
    public func stop() {
        // Remove event listeners to prevent memory leaks
        rtmpConnection.removeEventListener(.rtmpStatus, selector: #selector(rtmpStatusHandler), observer: self)
        rtmpConnection.removeEventListener(.ioError, selector: #selector(rtmpErrorHandler), observer: self)

        stopNetworkMonitoring()

        // Explicitly detach audio device to ensure microphone is released
        rtmpStream?.attachAudio(nil)

        currentInterruptionSource = .none
        isInterrupted = false
        isStreamingActive = false
        networkLostDuringPhoneCall = false
        savedUrl = nil
        savedName = nil
        rtmpConnection.close()
        deactivateAudioSession()  // Mic indicator gone
    }

    public func dispose(){
        cancelInterruptionTimer()  // Cancel any pending timer
        stopNetworkMonitoring()  // Stop network monitoring
        NotificationCenter.default.removeObserver(self)  // Remove interruption observer
        deactivateAudioSession()  // Final cleanup
        rtmpStream = nil
        rtmpConnection = RTMPConnection()
    }

    // MARK: - Network Monitoring
    private func startNetworkMonitoring() {
        networkQueue = DispatchQueue(label: "NetworkMonitor")
        networkMonitor = NWPathMonitor()

        networkMonitor?.pathUpdateHandler = { [weak self] path in
            DispatchQueue.main.async {
                if path.status == .satisfied {
                    print("Network available")
                    self?.handleNetworkAvailable()
                } else {
                    print("Network lost")
                    self?.handleNetworkLost()
                }
            }
        }

        networkMonitor?.start(queue: networkQueue!)
        print("Network monitoring started")
    }

    private func stopNetworkMonitoring() {
        networkMonitor?.cancel()
        networkMonitor = nil
        networkQueue = nil
        print("Network monitoring stopped")
    }

    // MARK: - Private Helper
    private func deactivateAudioSession() {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setActive(false, options: .notifyOthersOnDeactivation)
            print("AVAudioSession deactivated successfully")
        } catch {
            print("Error deactivating AVAudioSession: \(error)")
        }
    }

    private func isPhoneCallActive() -> Bool {
        let audioSession = AVAudioSession.sharedInstance()

        // Method 1: Check if another app is using audio (like Phone app during a call)
        if audioSession.isOtherAudioPlaying {
            print("Phone call detected: isOtherAudioPlaying = true")
            return true
        }

        // Method 2: Check audio session mode for phone calls
        if audioSession.mode == .voiceChat || audioSession.mode == .videoChat {
            print("Phone call detected: mode = voice/video chat")
            return true
        }

        // Method 3: Check if audio route is to receiver (indicates phone call)
        let currentRoute = audioSession.currentRoute
        for output in currentRoute.outputs {
            if output.portType == .builtInReceiver {
                print("Phone call detected: output to built-in receiver")
                return true
            }
        }

        // Method 4: Check if other audio is in use
        // During a phone call, secondaryAudioShouldBeSilencedHint is true
        if #available(iOS 8.0, *) {
            if audioSession.secondaryAudioShouldBeSilencedHint {
                print("Phone call detected: secondaryAudioShouldBeSilencedHint = true")
                return true
            }
        }

        print("No phone call detected")
        return false
    }
}


class AudioStreamingQoSDelegate: RTMPStreamDelegate {
    let minBitrate: UInt32 = 300 * 1024
    let maxBitrate: UInt32 = 2500 * 1024
    let incrementBitrate: UInt32 = 512 * 1024
    
    func rtmpStream(_ stream: RTMPStream, didPublishInsufficientBW connection: RTMPConnection){
        guard let videoBitrate = stream.videoSettings[.bitrate] as? UInt32 else { return }
        
        var newVideoBitrate = UInt32(videoBitrate / 2)
        if newVideoBitrate < minBitrate {
            newVideoBitrate = minBitrate
        }
        print("Insufficient: \(videoBitrate) -> \(newVideoBitrate)")
        stream.videoSettings[.bitrate] = newVideoBitrate
    }
    
    func rtmpStream(_ stream: RTMPStream, didPublishSufficientBW connection: RTMPConnection){
        guard let videoBitrate = stream.videoSettings[.bitrate] as? UInt32 else { return }
        
        var newVideoBitrate = videoBitrate + incrementBitrate
        if newVideoBitrate > maxBitrate {
            newVideoBitrate = maxBitrate
        }
        print("Sufficient: \(videoBitrate) -> \(newVideoBitrate)")
        stream.videoSettings[.bitrate] = newVideoBitrate
    }
    
    func rtmpStream(_ stream: RTMPStream, audio: AVAudioBuffer, presentationTimeStamp: CMTime){
    }
    
    func rtmpStream(_ stream: RTMPStream, didOutput video: CMSampleBuffer){
    }
    
    func rtmpStream(_ stream: RTMPStream, didStatics connection: RTMPConnection){
    }
    
    func rtmpStreamDidClear(_ stream: RTMPStream){
        print("StreamDidClear")
    }
}
