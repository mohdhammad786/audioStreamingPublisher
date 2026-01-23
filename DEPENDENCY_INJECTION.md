# Dependency Injection Refactoring

This document outlines the changes made to introduce Manual Dependency Injection (DI) into the `flutter_audio_streaming` plugin for both Android and iOS platforms.

## Overview

The goal of this refactoring was to eliminate hardcoded dependencies within the core logic classes (`AudioStreaming`) and their helpers. This improves:
1.  **Testability**: Dependencies can be easily mocked in unit tests.
2.  **Maintainability**: Centralized dependency creation makes it easier to manage and swap implementations.
3.  **Decoupling**: Classes are no longer responsible for creating their own dependencies.

## Android Architecture

### 1. `DependencyFactory.kt`
A new factory class located at `android/src/main/kotlin/com/resideo/flutter_audio_streaming/di/DependencyFactory.kt` handles the creation and wiring of all dependencies.

```kotlin
class DependencyFactory(context: Context, messenger: DartMessenger) {
    fun createAudioStreaming(): AudioStreaming {
        // ... instantiates all services ...
        // ... wires up circular dependencies if needed ...
        return AudioStreaming(...)
    }
}
```

### 2. Constructor Injection
The `AudioStreaming` class now receives all its dependencies via the constructor.

```kotlin
class AudioStreaming(
    context: Context,
    private val streamingContext: StreamingContext,
    private val interruptionManager: InterruptionManager,
    // ... other dependencies ...
) { ... }
```

### 3. Service Refactoring
Services like `InterruptionManager` and `ReconnectionService` now use `lateinit var` properties for dependencies that would otherwise cause circular references (e.g., `delegate` or `mediator`). These are wired up in `AudioStreaming`'s `init` block or in the `DependencyFactory`.

### 4. Unit Tests
`AudioStreamingTest.kt` has been updated to use Mockito to mock all injected dependencies, ensuring `AudioStreaming` logic is tested in isolation.

## iOS Architecture

### 1. `DependencyFactory.swift`
A new factory class located at `ios/Classes/DI/DependencyFactory.swift` handles the creation of the object graph.

```swift
class DependencyFactory {
    func createAudioStreaming() -> AudioStreaming {
        // ... instantiates components ...
        return AudioStreaming(...)
    }
}
```

### 2. Initializer Injection
The `AudioStreaming` class now uses initializer injection for all its protocols/services.

```swift
init(
    stateMachine: StreamStateMachine,
    phoneMonitor: PhoneCallMonitor,
    // ...
) { ... }
```

### 3. Protocol-Based Services
All major components (NetworkMonitor, PhoneCallMonitor, RtmpService, etc.) are hidden behind protocols, allowing for easy mocking in Swift unit tests.

## Usage

The plugin entry points (`MethodCallHandlerImpl.kt` on Android and `SwiftFlutterAudioStreamingPlugin.swift` on iOS) now use the `DependencyFactory` to create the `AudioStreaming` instance when `initializeStreaming` is called.

```kotlin
// Android
val factory = DependencyFactory(activity, streamingMessenger!!)
audioStreaming = factory.createAudioStreaming()
```

```swift
// iOS
let factory = DependencyFactory()
audioStreaming = factory.createAudioStreaming()
```
