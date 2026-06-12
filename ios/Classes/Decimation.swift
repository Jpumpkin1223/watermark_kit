import Foundation

/// Even-distribution frame decimation — mirrors the pure-Dart seam
/// (`lib/src/video_decimation.dart`) and the Android `Decimation` object so the
/// three paths cannot diverge.
///
/// Hardcoded constants for this step (Step 4 may later parameterize via Pigeon).
enum Decimation {
  static let targetFps = 30
  static let targetDurationSec = 15
  static let skipThresholdSec: Double = 15

  /// True when a source of `sourceDurationSec` seconds should be decimated.
  static func shouldDecimate(_ sourceDurationSec: Double,
                             skipThresholdSec: Double = skipThresholdSec) -> Bool {
    return sourceDurationSec > skipThresholdSec
  }

  /// Target output frame count (= targetFps * targetDurationSec = 450).
  static func targetFrameCount(targetFps: Int = targetFps,
                               targetDurationSec: Int = targetDurationSec) -> Int {
    return targetFps * targetDurationSec
  }

  /// Evenly-spaced source-frame indices to KEEP when decimating `totalFrames`
  /// decoded frames to `target` output frames: `round(i * totalFrames / target)`
  /// for `i in [0, target)`, clamped to `[0, totalFrames)`, deduped, ascending.
  /// Length is `min(target, totalFrames)`. Identical math to the Dart seam.
  static func selectEvenIndices(totalFrames: Int, target: Int) -> [Int] {
    if totalFrames <= 0 || target <= 0 { return [] }
    if totalFrames <= target { return Array(0..<totalFrames) }

    var kept = [Int]()
    kept.reserveCapacity(target)
    var previous = -1
    for i in 0..<target {
      var idx = Int((Double(i) * Double(totalFrames) / Double(target)).rounded())
      if idx >= totalFrames { idx = totalFrames - 1 }
      if idx < 0 { idx = 0 }
      if idx == previous { continue }
      kept.append(idx)
      previous = idx
    }
    return kept
  }
}

/// Per-compose decimation decision, computed once up front.
///
/// - `active == false` -> keep every frame (source <= ~15s); existing behaviour.
/// - `active == true`  -> keep only frames whose decoded index is in `keptSet`;
///   synthesize a 30fps PTS from the kept-frame counter.
struct DecimationPlan {
  let active: Bool
  let target: Int
  private let keptSet: Set<Int>

  /// Whether the decoded frame at `decodedIndex` should be rendered/appended.
  func keep(_ decodedIndex: Int) -> Bool {
    return !active || keptSet.contains(decodedIndex)
  }

  static func keepAll() -> DecimationPlan {
    return DecimationPlan(active: false, target: 0, keptSet: [])
  }

  /// Build a plan from a pre-counted `totalFrames` and the source duration.
  static func build(totalFrames: Int, sourceDurationSec: Double) -> DecimationPlan {
    if totalFrames <= 0 || !Decimation.shouldDecimate(sourceDurationSec) {
      return keepAll()
    }
    let target = Decimation.targetFrameCount()
    if totalFrames <= target { return keepAll() }
    let indices = Decimation.selectEvenIndices(totalFrames: totalFrames, target: target)
    return DecimationPlan(active: true, target: target, keptSet: Set(indices))
  }
}
