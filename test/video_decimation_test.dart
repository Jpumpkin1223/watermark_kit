// Pure-Dart unit tests for the even-distribution decimation seam.
//
// Runnable with `dart test` (no Flutter dependency) because the seam under
// test imports no Flutter libraries.
import 'package:test/test.dart';
import 'package:watermark_kit/src/video_decimation.dart';

void main() {
  group('shouldDecimate', () {
    test('14.9s -> no decimation', () {
      expect(shouldDecimate(14.9), isFalse);
    });

    test('15.0s boundary -> no decimation (strictly greater)', () {
      expect(shouldDecimate(15.0), isFalse);
    });

    test('16.0s -> decimate', () {
      expect(shouldDecimate(16.0), isTrue);
    });

    test('custom threshold respected', () {
      expect(shouldDecimate(20.0, skipThresholdSec: 25), isFalse);
      expect(shouldDecimate(30.0, skipThresholdSec: 25), isTrue);
    });
  });

  group('targetFrameCount', () {
    test('defaults to 450 (30fps x 15s)', () {
      expect(targetFrameCount(), 450);
    });

    test('honors overrides', () {
      expect(targetFrameCount(targetFps: 24, targetDurationSec: 10), 240);
    });
  });

  group('selectEvenIndices', () {
    const target = 450;

    test('count == min(target, totalFrames) for typical long source', () {
      final kept = selectEvenIndices(1500, target);
      expect(kept.length, target);
    });

    test('count == min(target, totalFrames) when totalFrames < target', () {
      final kept = selectEvenIndices(449, target);
      expect(kept.length, 449);
      // Every frame kept, in order.
      expect(kept, List<int>.generate(449, (i) => i));
    });

    test('totalFrames == target keeps every frame', () {
      final kept = selectEvenIndices(450, target);
      expect(kept.length, 450);
      expect(kept, List<int>.generate(450, (i) => i));
    });

    test('totalFrames == target + 1 -> 450 kept, deduped', () {
      final kept = selectEvenIndices(451, target);
      expect(kept.length, target);
      // No duplicates.
      expect(kept.toSet().length, kept.length);
    });

    test('ascending, in-range, deduped for several totals', () {
      for (final total in [1500, 900, 451, 450, 449, 451, 30000]) {
        final kept = selectEvenIndices(total, target);
        // In range.
        for (final idx in kept) {
          expect(idx, greaterThanOrEqualTo(0));
          expect(idx, lessThan(total));
        }
        // Strictly ascending (implies no duplicates).
        for (var i = 1; i < kept.length; i++) {
          expect(kept[i], greaterThan(kept[i - 1]));
        }
        // Count.
        expect(kept.length, total <= target ? total : target);
      }
    });

    test('even spacing: max gap <= 2x mean gap (long source)', () {
      for (final total in [1500, 900, 5000, 30000]) {
        final kept = selectEvenIndices(total, target);
        expect(kept.length, greaterThan(1));
        final meanGap = total / kept.length;
        var maxGap = 0;
        for (var i = 1; i < kept.length; i++) {
          final gap = kept[i] - kept[i - 1];
          if (gap > maxGap) maxGap = gap;
        }
        expect(
          maxGap.toDouble(),
          lessThanOrEqualTo(2 * meanGap),
          reason: 'total=$total maxGap=$maxGap meanGap=$meanGap',
        );
      }
    });

    test('first kept index is 0 (no front bias)', () {
      expect(selectEvenIndices(1500, target).first, 0);
    });

    test('degenerate inputs return empty', () {
      expect(selectEvenIndices(0, target), isEmpty);
      expect(selectEvenIndices(1500, 0), isEmpty);
      expect(selectEvenIndices(-5, target), isEmpty);
    });
  });
}
