/// Pure-Dart even-distribution frame decimation seam.
///
/// This file intentionally has NO Flutter imports so it can be exercised by a
/// plain `dart test` run. It is the single source of truth for the
/// keep/drop selection math; the native Android (`VideoWatermarkProcessor.kt`)
/// and iOS (`VideoWatermarkProcessor.swift`) loops mirror the SAME math so the
/// surface path, the Android ByteBuffer fallback path, and iOS can never
/// diverge.
///
/// Contract (Step 2 / M2):
///   - When the source is longer than ~15s, decimate to an evenly-sampled
///     ~450-frame (= [kTargetFps] x [kTargetDurationSec]) clip rendered at a
///     synthesized 30fps clock.
///   - When the source is <= ~15s, do NOT decimate (every frame kept).
library;

/// Synthesized output frame rate for the decimated clip. Hardcoded for this
/// step; Step 4 may later parameterize via Pigeon.
const int kTargetFps = 30;

/// Target output duration in seconds for the decimated clip. Hardcoded for this
/// step; Step 4 may later parameterize via Pigeon.
const int kTargetDurationSec = 15;

/// Sources at or below this many seconds are never decimated (matches the
/// existing speedup skip behaviour for short clips).
const double kSkipThresholdSec = 15;

/// True when a source of [sourceDurationSec] seconds should be decimated.
///
/// Strictly greater-than the threshold so a 15.0s clip is NOT decimated and a
/// 16.0s clip IS.
bool shouldDecimate(
  double sourceDurationSec, {
  double skipThresholdSec = kSkipThresholdSec,
}) {
  return sourceDurationSec > skipThresholdSec;
}

/// Target number of frames in the decimated output (= [targetFps] x
/// [targetDurationSec], default 450).
int targetFrameCount({
  int targetFps = kTargetFps,
  int targetDurationSec = kTargetDurationSec,
}) {
  return targetFps * targetDurationSec;
}

/// Evenly-spaced source-frame indices to KEEP when decimating [totalFrames]
/// decoded frames down to [target] output frames.
///
/// Index `i in [0, target)` maps to `round(i * totalFrames / target)`, then the
/// result is clamped to `[0, totalFrames)`, deduplicated, and returned in
/// ascending order. The returned length is `min(target, totalFrames)` (for
/// well-formed inputs), so when `totalFrames <= target` every frame is kept.
///
/// This is the AC2 anchor: count is `min(target, totalFrames)` and the spacing
/// is even (max gap <= ~2x the mean gap).
List<int> selectEvenIndices(int totalFrames, int target) {
  if (totalFrames <= 0 || target <= 0) return const <int>[];
  // When we want at least as many frames as exist, keep them all.
  if (totalFrames <= target) {
    return List<int>.generate(totalFrames, (i) => i);
  }

  final kept = <int>[];
  var previous = -1;
  for (var i = 0; i < target; i++) {
    var idx = (i * totalFrames / target).round();
    if (idx >= totalFrames) idx = totalFrames - 1;
    if (idx < 0) idx = 0;
    // Dedup while keeping ascending order. round() is monotonic in i, so a
    // collision can only repeat the previous value.
    if (idx == previous) continue;
    kept.add(idx);
    previous = idx;
  }
  return kept;
}
