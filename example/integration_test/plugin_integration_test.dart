import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:watermark_kit/watermark_kit.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('getPlatformVersion test', (WidgetTester tester) async {
    final WatermarkKit plugin = WatermarkKit();
    final String? version = await plugin.getPlatformVersion();
    // The version string depends on the host platform running the test, so
    // just assert that some non-empty string is returned.
    expect(version?.isNotEmpty, true);
  });

  testWidgets('composes the same video three times sequentially', (
    WidgetTester tester,
  ) async {
    final tempDir = await Directory.systemTemp.createTemp('tom1152_');
    addTearDown(() async {
      if (await tempDir.exists()) await tempDir.delete(recursive: true);
    });

    final fixture = await rootBundle.load('assets/tom1152_input.mp4');
    final input = File('${tempDir.path}/input.mp4');
    await input.writeAsBytes(fixture.buffer.asUint8List(), flush: true);

    final plugin = WatermarkKit();
    for (var run = 1; run <= 3; run++) {
      final output = File('${tempDir.path}/output-$run.mp4');
      final task = await plugin.composeVideo(
        inputVideoPath: input.path,
        outputVideoPath: output.path,
        text: 'TOM-1152',
        margin: 2,
        widthPercent: 0.25,
        bitrateBps: 250000,
        maxFps: 5,
        maxLongSide: 64,
      );
      final result = await task.done.timeout(const Duration(minutes: 2));

      expect(result.path, output.path);
      expect(await output.exists(), true);
      expect(await output.length(), greaterThan(0));
      debugPrint('TOM1152_RUN_${run}_DONE bytes=${await output.length()}');
    }
  }, skip: !Platform.isAndroid);
}
