package com.tttocklll.watermark_kit.video

/**
 * Even-distribution frame decimation — single source of truth shared by the
 * surface decode loop AND the ByteBuffer fallback loop in
 * [VideoWatermarkProcessor], mirroring the pure-Dart seam
 * (`lib/src/video_decimation.dart`) and the iOS implementation so the three
 * paths cannot diverge.
 *
 * Hardcoded constants for this step (Step 4 may later parameterize via Pigeon).
 */
internal object Decimation {
  const val TARGET_FPS = 30
  const val TARGET_DURATION_SEC = 15
  const val SKIP_THRESHOLD_SEC = 15.0

  /** Synthesized output frame interval in nanoseconds (30fps). */
  const val FRAME_INTERVAL_NS = 1_000_000_000L / TARGET_FPS

  /** True when a source of [sourceDurationSec] seconds should be decimated. */
  fun shouldDecimate(
    sourceDurationSec: Double,
    skipThresholdSec: Double = SKIP_THRESHOLD_SEC
  ): Boolean = sourceDurationSec > skipThresholdSec

  /** Target output frame count (= [TARGET_FPS] * [TARGET_DURATION_SEC] = 450). */
  fun targetFrameCount(
    targetFps: Int = TARGET_FPS,
    targetDurationSec: Int = TARGET_DURATION_SEC
  ): Int = targetFps * targetDurationSec

  /**
   * Evenly-spaced source-frame indices to KEEP when decimating [totalFrames]
   * decoded frames to [target] output frames: `round(i * totalFrames / target)`
   * for `i in [0, target)`, clamped to `[0, totalFrames)`, deduped, ascending.
   * Length is `min(target, totalFrames)`. Identical math to the Dart seam.
   */
  fun selectEvenIndices(totalFrames: Int, target: Int): IntArray {
    if (totalFrames <= 0 || target <= 0) return IntArray(0)
    if (totalFrames <= target) return IntArray(totalFrames) { it }

    val kept = ArrayList<Int>(target)
    var previous = -1
    for (i in 0 until target) {
      var idx = Math.round(i.toLong() * totalFrames / target.toDouble()).toInt()
      if (idx >= totalFrames) idx = totalFrames - 1
      if (idx < 0) idx = 0
      if (idx == previous) continue
      kept.add(idx)
      previous = idx
    }
    return kept.toIntArray()
  }
}

/**
 * Per-compose decimation decision, computed once up front and shared by both
 * native decode loops.
 *
 * - [active] = false  -> keep every frame (source <= ~15s); existing behaviour.
 * - [active] = true   -> keep only the frames whose decoded index is in
 *   [keptSet]; synthesize a 30fps PTS from [DecimationState.keptIndex].
 */
internal class DecimationPlan private constructor(
  val active: Boolean,
  val totalFrames: Int,
  val target: Int,
  private val keptSet: HashSet<Int>
) {
  /** Whether the decoded frame at [decodedIndex] should be rendered/encoded. */
  fun keep(decodedIndex: Int): Boolean = !active || keptSet.contains(decodedIndex)

  companion object {
    /** Plan that keeps every frame (no decimation). */
    fun keepAll(): DecimationPlan =
      DecimationPlan(active = false, totalFrames = 0, target = 0, keptSet = HashSet())

    /**
     * Build a plan from a pre-counted [totalFrames] and the source duration.
     * Returns a keep-all plan when the source is short or the count is unusable.
     */
    fun build(totalFrames: Int, sourceDurationSec: Double): DecimationPlan {
      if (totalFrames <= 0 || !Decimation.shouldDecimate(sourceDurationSec)) {
        return keepAll()
      }
      val target = Decimation.targetFrameCount()
      if (totalFrames <= target) return keepAll()
      val indices = Decimation.selectEvenIndices(totalFrames, target)
      val set = HashSet<Int>(indices.size * 2)
      for (idx in indices) set.add(idx)
      return DecimationPlan(active = true, totalFrames = totalFrames, target = target, keptSet = set)
    }
  }
}

/** Mutable counter of kept frames, shared so synthesized PTS stays monotonic. */
internal class DecimationState {
  /** Number of frames already kept; also the synthesized-PTS frame number. */
  var keptIndex: Int = 0
    private set

  /** Synthesized 30fps presentation time in nanoseconds for the current kept frame. */
  fun nextPresentationTimeNs(): Long = keptIndex * Decimation.FRAME_INTERVAL_NS

  fun advance() { keptIndex++ }
}
