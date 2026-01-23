import Foundation
import HaishinKit
import AVFoundation

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
