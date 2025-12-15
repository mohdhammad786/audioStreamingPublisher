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
    private var rtmpConnection = RTMPConnection()
    private var rtmpStream: RTMPStream!
    private var url: String? = nil
    private var name: String? = nil
    private var retries: Int = 0
    private let myDelegate = AudioStreamingQoSDelegate()
    
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
            result(error)
        }
        rtmpStream = RTMPStream(connection: rtmpConnection)
        rtmpStream.attachAudio(AVCaptureDevice.default(for: AVMediaType.audio)) { error in
            print("Got error in attachAudio: ")
            print(error)
            result(error)
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
    }
    
    
    public func start(url: String, result: @escaping FlutterResult) {
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
            rtmpStream.publish(name)
            retries = 0
            break
        case RTMPConnection.Code.connectFailed.rawValue, RTMPConnection.Code.connectClosed.rawValue:
            guard retries <= 3 else {
                if SwiftFlutterAudioStreamingPlugin.eventSink != nil {
                    SwiftFlutterAudioStreamingPlugin.eventSink!(["event" : "error",
                           "errorDescription" : "connection failed " + e.type.rawValue])
                }
                return
            }
            retries += 1
            Thread.sleep(forTimeInterval: pow(2.0, Double(retries)))
            rtmpConnection.connect(url!)
            if SwiftFlutterAudioStreamingPlugin.eventSink != nil {
                SwiftFlutterAudioStreamingPlugin.eventSink!(["event" : "rtmp_retry",
                           "errorDescription" : "connection failed " + e.type.rawValue])
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
        guard retries <= 3 else {
            if SwiftFlutterAudioStreamingPlugin.eventSink != nil {
                SwiftFlutterAudioStreamingPlugin.eventSink!(["event" : "rtmp_stopped",
                       "errorDescription" : "rtmp disconnected"])
            }
            return
        }
        retries += 1
        Thread.sleep(forTimeInterval: pow(2.0, Double(retries)))
        rtmpConnection.connect(url!)
        if SwiftFlutterAudioStreamingPlugin.eventSink != nil {
            SwiftFlutterAudioStreamingPlugin.eventSink!(["event" : "rtmp_retry",
                   "errorDescription" : "rtmp disconnected"])
        }
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
        rtmpConnection.close()
        deactivateAudioSession()  // Mic indicator gone
    }

    public func dispose(){
        deactivateAudioSession()  // Final cleanup
        rtmpStream = nil
        rtmpConnection = RTMPConnection()
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

        // Method 4: Try to set category and check if it fails (might indicate phone call)
        do {
            // Try to activate the session - this will fail if phone is in use
            try audioSession.setActive(true, options: [])
            // If we get here, no phone call is active
            // Deactivate immediately since we were just testing
            try? audioSession.setActive(false, options: [])
            return false
        } catch let error as NSError {
            // Error code 561017449 ('!int') means interruption (likely phone call)
            if error.code == 561017449 {
                print("Phone call detected: AVAudioSession interruption error")
                return true
            }
            print("AVAudioSession error (not phone call): \(error)")
            return false
        }
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
