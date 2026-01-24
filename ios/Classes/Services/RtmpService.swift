import Foundation
import HaishinKit
import AVFoundation

protocol RtmpServiceDelegate: AnyObject {
    func rtmpStatusReceived(code: String, description: String)
    func rtmpErrorReceived(code: String, description: String)
}

protocol RtmpServiceProtocol: AnyObject {
    var delegate: RtmpServiceDelegate? { get set }
    func connect(url: String)
    func publish(_ name: String)
    func close()
    func mute()
    func unmute()
    func updateSettings(bitrate: Int?, sampleRate: Int?, isStereo: Bool?)
    func attachAudio(completion: @escaping (Bool, Error?) -> Void)
    func detachAudio(completion: (() -> Void)?)
    func forceRelease()
    func reinitialize()
}

class RtmpService: RtmpServiceProtocol {
    // MARK: - Properties
    private var rtmpConnection: RTMPConnection?
    private var rtmpStream: RTMPStream?
    weak var delegate: RtmpServiceDelegate?
    private let myDelegate = AudioStreamingQoSDelegate() // Assuming this exists or needs to be moved/shared
    
    private let operationQueue = DispatchQueue(label: "com.audiostreaming.rtmp", qos: .userInitiated)
    private var isAudioAttached = false
    
    // Configuration
    var bitrate: Int = 32 * 1000
    var sampleRate: Double = 44100
    var isStereo: Bool = true
    
    // MARK: - Init
    init(delegate: RtmpServiceDelegate? = nil) {
        self.delegate = delegate
        operationQueue.sync {
            initializeHaishinKit()
        }
    }
    
    private func initializeHaishinKit() {
        removeListeners()
        rtmpStream?.delegate = nil
        rtmpStream = nil
        rtmpConnection = nil
        isAudioAttached = false

        let connection = RTMPConnection()
        rtmpConnection = connection
        rtmpStream = RTMPStream(connection: connection)
        rtmpStream?.delegate = myDelegate
        
        setupListeners()
    }
    
    deinit {
        removeListeners()
    }
    
    private func setupListeners() {
        rtmpConnection?.addEventListener(.rtmpStatus, selector: #selector(rtmpStatusHandler), observer: self)
        rtmpConnection?.addEventListener(.ioError, selector: #selector(rtmpErrorHandler), observer: self)
    }
    
    private func removeListeners() {
        if let connection = rtmpConnection {
            connection.removeEventListener(.rtmpStatus, selector: #selector(rtmpStatusHandler), observer: self)
            connection.removeEventListener(.ioError, selector: #selector(rtmpErrorHandler), observer: self)
        }
    }
    
    // MARK: - Public Methods
    
    func connect(url: String) {
        operationQueue.async { [weak self] in
            self?.rtmpConnection?.connect(url)
        }
    }
    
    func publish(_ name: String) {
        operationQueue.async { [weak self] in
            self?.rtmpStream?.publish(name)
        }
    }
    
    func close() {
        operationQueue.async { [weak self] in
            self?.rtmpConnection?.close()
        }
    }
    
    func mute() {
        operationQueue.async { [weak self] in
            self?.rtmpStream?.audioSettings[.muted] = true
        }
    }
    
    func unmute() {
        operationQueue.async { [weak self] in
            self?.rtmpStream?.audioSettings[.muted] = false
        }
    }
    
    func updateSettings(bitrate: Int?, sampleRate: Int?, isStereo: Bool?) {
        operationQueue.async { [weak self] in
            guard let self = self else { return }
            if let bitrate = bitrate { self.bitrate = bitrate }
            if let sampleRate = sampleRate { self.sampleRate = Double(sampleRate) }
            if let isStereo = isStereo { self.isStereo = isStereo }
            
            guard let stream = self.rtmpStream else { return }
            
            stream.audioSettings = [
                .muted: false,
                .bitrate: self.bitrate,
            ]
            
            stream.recorderSettings = [
                AVMediaType.audio: [
                    AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
                    AVSampleRateKey: self.sampleRate,
                    AVNumberOfChannelsKey: self.isStereo ? 2 : 1,
                ],
            ]
            print("✅ RtmpService: Audio settings updated: Bitrate=\(self.bitrate), SampleRate=\(self.sampleRate), Stereo=\(self.isStereo)")
        }
    }
    
    // MARK: - Lifecycle Management (Media Services)
    
    func forceRelease() {
        // CRITICAL: Forcefully release references WITHOUT calling cleanup methods.
        // When Media Services are lost, the underlying C++ objects are already dead.
        // Calling methods on them (like .close() or .dispose()) causes a crash.
        operationQueue.async { [weak self] in
            guard let self = self else { return }
            print("☠️ RtmpService: Force releasing HaishinKit objects")
            self.removeListeners()
            self.rtmpStream?.delegate = nil
            self.rtmpStream = nil
            self.rtmpConnection = nil
            self.isAudioAttached = false
        }
    }
    
    func reinitialize() {
        operationQueue.async { [weak self] in
            guard let self = self else { return }
            print("🔄 RtmpService: Re-initializing HaishinKit objects")
            self.initializeHaishinKit()
            
            self.updateSettings(bitrate: self.bitrate, sampleRate: Int(self.sampleRate), isStereo: self.isStereo)
        }
    }
    
    // MARK: - Audio Attachment Logic (Moved from AudioStreaming)
    
    func attachAudio(completion: @escaping (Bool, Error?) -> Void) {
        operationQueue.async { [weak self] in
            guard let self = self else {
                DispatchQueue.main.async { completion(false, nil) }
                return
            }
            
            if self.isAudioAttached {
                print("⚠️ RtmpService: Audio already attached")
                DispatchQueue.main.async { completion(true, nil) }
                return
            }
            
            guard let audioDevice = AVCaptureDevice.default(for: AVMediaType.audio) else {
                let error = NSError(domain: "RtmpService", code: -2, userInfo: [NSLocalizedDescriptionKey: "No audio device available"])
                DispatchQueue.main.async { completion(false, error) }
                return
            }
            
            self.rtmpStream?.attachAudio(audioDevice)
            
            self.isAudioAttached = true
            
            print("✅ RtmpService: Audio attached successfully")
            DispatchQueue.main.async { completion(true, nil) }
        }
    }
    
    func detachAudio(completion: (() -> Void)? = nil) {
        operationQueue.async { [weak self] in
            guard let self = self else {
                DispatchQueue.main.async { completion?() }
                return
            }
            
            guard self.isAudioAttached else {
                DispatchQueue.main.async { completion?() }
                return
            }
            
            self.rtmpStream?.attachAudio(nil)
            self.isAudioAttached = false
            
            print("✅ RtmpService: Audio detached successfully")
            DispatchQueue.main.async { completion?() }
        }
    }
    
    // MARK: - Event Handlers
    @objc private func rtmpStatusHandler(_ notification: Notification) {
        let e = Event.from(notification)
        guard let data: ASObject = e.data as? ASObject,
              let code: String = data["code"] as? String else {
            return
        }
        delegate?.rtmpStatusReceived(code: code, description: code)
    }

    @objc private func rtmpErrorHandler(_ notification: Notification) {
        let e = Event.from(notification)
        let description = e.type.rawValue
        delegate?.rtmpErrorReceived(code: "error", description: description)
    }
}
