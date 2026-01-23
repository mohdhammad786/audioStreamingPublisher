import Foundation
import AVFoundation

protocol AudioSessionManagerProtocol: AnyObject {
    func configureAudioSession(completion: @escaping (Bool, Error?) -> Void)
    func deactivateAudioSession()
    func activateAudioSessionWithRetry(attempt: Int, maxAttempts: Int, completion: @escaping (Bool) -> Void)
}

class AudioSessionManager: AudioSessionManagerProtocol {
    private let audioQueue = DispatchQueue(label: "com.audiostreaming.session", qos: .userInitiated)

    func configureAudioSession(completion: @escaping (Bool, Error?) -> Void) {
        audioQueue.async {
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
                
                print("✅ AudioSessionManager: Session configured successfully")
                DispatchQueue.main.async { completion(true, nil) }
            } catch {
                print("❌ AudioSessionManager: Configuration failed: \(error)")
                DispatchQueue.main.async { completion(false, error) }
            }
        }
    }

    func deactivateAudioSession() {
        audioQueue.async {
            do {
                try AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
                print("✅ AudioSessionManager: Session deactivated")
            } catch {
                print("❌ AudioSessionManager: Deactivation failed: \(error)")
            }
        }
    }
    
    func activateAudioSessionWithRetry(attempt: Int = 0, maxAttempts: Int = 5, completion: @escaping (Bool) -> Void) {
        audioQueue.async { [weak self] in
            guard let self = self else { return }
            let session = AVAudioSession.sharedInstance()

            do {
                try session.setActive(true)
                print("✅ AudioSessionManager: Session activated successfully (attempt \(attempt + 1))")
                DispatchQueue.main.async { completion(true) }
            } catch {
                if attempt < maxAttempts {
                    let delay = pow(2.0, Double(attempt)) * 0.1
                    print("⚠️ AudioSessionManager: Activation failed (attempt \(attempt + 1)/\(maxAttempts)): \(error)")
                    print("🔄 Retrying in \(Int(delay * 1000))ms...")

                    DispatchQueue.global().asyncAfter(deadline: .now() + delay) {
                        self.activateAudioSessionWithRetry(attempt: attempt + 1, maxAttempts: maxAttempts, completion: completion)
                    }
                } else {
                    print("❌ AudioSessionManager: Activation failed after \(maxAttempts) attempts: \(error)")
                    DispatchQueue.main.async { completion(false) }
                }
            }
        }
    }
}
