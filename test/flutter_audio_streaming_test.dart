import 'package:flutter/services.dart';
import 'package:flutter_audio_streaming/flutter_audio_streaming.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  const MethodChannel channel =
      MethodChannel('plugins.flutter.io/flutter_audio_streaming');

  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
      return <String, dynamic>{};
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('stop is re-entrancy safe when listener calls stop()', () async {
    final controller = StreamingController();
    controller.value =
        controller.value.copyWith(isInitialized: true, isStreaming: true);

    controller.addListener(() {
      controller.stop();
    });

    await controller.stop();
    expect(controller.value.isStreaming, false);
    await controller.dispose();
  });
}
