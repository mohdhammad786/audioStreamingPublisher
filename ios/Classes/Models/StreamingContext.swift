import Foundation

struct StreamingContext {
    // Current Active Config
    var url: String?
    var name: String?
    
    // Saved State for Reconnection
    var savedUrl: String?
    var savedName: String?
    var reconnectionSource: InterruptionSource = .none
    
    mutating func clear() {
        url = nil
        name = nil
        savedUrl = nil
        savedName = nil
        reconnectionSource = .none
    }
    
    mutating func saveCurrent(source: InterruptionSource) {
        savedUrl = url
        savedName = name
        reconnectionSource = source
    }
    
    mutating func restore() -> (url: String?, name: String?) {
        return (savedUrl, savedName)
    }
}
