package com.tttocklll.watermark_kit

import android.content.Context
import com.tttocklll.watermark_kit.video.VideoWatermarkProcessor
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.concurrent.thread
import org.mockito.Mockito

internal class WatermarkKitPluginTest {
  @Test
  fun apiConstruction_doesNotStartVideoWorker() {
    val context = Mockito.mock(Context::class.java)
    val messenger = Mockito.mock(BinaryMessenger::class.java)

    Mockito.mockConstruction(VideoWatermarkProcessor::class.java).use { processors ->
      val api = WatermarkApiImpl(context, messenger)
      api.cancel("not-started")

      assertEquals(0, processors.constructed().size)
    }
  }

  @Test
  fun threeComposeCalls_reuseOneVideoWorker() {
    val context = Mockito.mock(Context::class.java)
    val messenger = Mockito.mock(BinaryMessenger::class.java)

    Mockito.mockConstruction(VideoWatermarkProcessor::class.java).use { processors ->
      val api = WatermarkApiImpl(context, messenger)

      repeat(3) { index ->
        api.composeVideo(videoRequest("task-$index")) { }
      }

      assertEquals(1, processors.constructed().size)
      val starts = Mockito.mockingDetails(processors.constructed().single()).invocations
        .count { it.method.name == "start" }
      assertEquals(3, starts)
    }
  }

  @Test
  fun dispose_stopsInitializedVideoWorkerAndRejectsLaterCompose() {
    val context = Mockito.mock(Context::class.java)
    val messenger = Mockito.mock(BinaryMessenger::class.java)

    Mockito.mockConstruction(VideoWatermarkProcessor::class.java).use { processors ->
      val api = WatermarkApiImpl(context, messenger)
      api.composeVideo(videoRequest("before-detach")) { }
      val processor = processors.constructed().single()

      api.dispose()
      api.dispose()

      val disposals = Mockito.mockingDetails(processor).invocations
        .count { it.method.name == "dispose" }
      assertEquals(1, disposals)

      var detachedResult: Result<ComposeVideoResult>? = null
      api.composeVideo(videoRequest("after-detach")) { detachedResult = it }
      assertTrue(detachedResult?.isFailure == true)
      assertEquals(1, processors.constructed().size)
    }
  }

  @Test
  fun completionAfterDispose_isNotDeliveredToDetachedEngine() {
    val context = Mockito.mock(Context::class.java)
    val messenger = Mockito.mock(BinaryMessenger::class.java)

    Mockito.mockConstruction(VideoWatermarkProcessor::class.java).use { processors ->
      val api = WatermarkApiImpl(context, messenger)
      var delivered = false
      api.composeVideo(videoRequest("in-flight")) { delivered = true }
      val startInvocation = Mockito.mockingDetails(processors.constructed().single()).invocations
        .single { it.method.name == "start" }
      @Suppress("UNCHECKED_CAST")
      val onCompleted = startInvocation.arguments[2] as (ComposeVideoResult) -> Unit

      api.dispose()
      onCompleted(videoResult("in-flight"))

      assertEquals(false, delivered)
    }
  }

  @Test
  fun dispose_waitsForInFlightCompletionDelivery() {
    val context = Mockito.mock(Context::class.java)
    val messenger = Mockito.mock(BinaryMessenger::class.java)

    Mockito.mockConstruction(VideoWatermarkProcessor::class.java).use { processors ->
      val callbackEntered = CountDownLatch(1)
      val releaseCallback = CountDownLatch(1)
      val disposeFinished = CountDownLatch(1)
      val api = WatermarkApiImpl(context, messenger)
      api.composeVideo(videoRequest("in-flight")) {
        callbackEntered.countDown()
        releaseCallback.await(2, TimeUnit.SECONDS)
      }
      val startInvocation = Mockito.mockingDetails(processors.constructed().single()).invocations
        .single { it.method.name == "start" }
      @Suppress("UNCHECKED_CAST")
      val onCompleted = startInvocation.arguments[2] as (ComposeVideoResult) -> Unit

      val completionThread = thread { onCompleted(videoResult("in-flight")) }
      assertTrue(callbackEntered.await(2, TimeUnit.SECONDS))
      val disposeThread = thread {
        api.dispose()
        disposeFinished.countDown()
      }

      assertFalse(
        disposeFinished.await(100, TimeUnit.MILLISECONDS),
        "dispose must not return while a callback is being delivered",
      )
      releaseCallback.countDown()
      assertTrue(disposeFinished.await(2, TimeUnit.SECONDS))
      completionThread.join()
      disposeThread.join()
    }
  }

  @Test
  fun engineDetach_unregistersPigeonApiAndDisposesEngineApi() {
    val binding = Mockito.mock(FlutterPlugin.FlutterPluginBinding::class.java)
    val context = Mockito.mock(Context::class.java)
    val messenger = Mockito.mock(BinaryMessenger::class.java)
    Mockito.`when`(binding.applicationContext).thenReturn(context)
    Mockito.`when`(binding.binaryMessenger).thenReturn(messenger)

    Mockito.mockConstruction(WatermarkApiImpl::class.java).use { apis ->
      val plugin = WatermarkKitPlugin()
      plugin.onAttachedToEngine(binding)
      val api = apis.constructed().single()

      plugin.onDetachedFromEngine(binding)

      assertTrue(
        Mockito.mockingDetails(api).invocations.any { it.method.name == "dispose" },
        "engine API must be disposed on detach",
      )
      val unregisteredChannels = Mockito.mockingDetails(messenger).invocations
        .filter { it.method.name == "setMessageHandler" && it.arguments.getOrNull(1) == null }
        .mapNotNull { it.arguments.firstOrNull() as? String }
        .toSet()
      assertTrue(
        unregisteredChannels.containsAll(
          listOf(
            "dev.flutter.pigeon.watermark_kit.WatermarkApi.composeImage",
            "dev.flutter.pigeon.watermark_kit.WatermarkApi.composeText",
            "dev.flutter.pigeon.watermark_kit.WatermarkApi.composeVideo",
            "dev.flutter.pigeon.watermark_kit.WatermarkApi.cancel",
          ),
        ),
        "all Pigeon handlers must be unregistered on detach",
      )
    }
  }

  @Test
  fun onMethodCall_getPlatformVersion_returnsExpectedValue() {
    val plugin = WatermarkKitPlugin()

    val call = MethodCall("getPlatformVersion", null)
    val mockResult: MethodChannel.Result = Mockito.mock(MethodChannel.Result::class.java)
    plugin.onMethodCall(call, mockResult)

    Mockito.verify(mockResult).success("Android " + android.os.Build.VERSION.RELEASE)
  }

  private fun videoRequest(taskId: String) = ComposeVideoRequest(
    taskId = taskId,
    inputVideoPath = "/tmp/input.mp4",
    outputVideoPath = "/tmp/output-$taskId.mp4",
    anchor = Anchor.BOTTOM_RIGHT,
    margin = 0.0,
    marginUnit = MeasureUnit.PX,
    offsetX = 0.0,
    offsetY = 0.0,
    offsetUnit = MeasureUnit.PX,
    widthPercent = 0.2,
    opacity = 1.0,
    codec = VideoCodec.H264,
  )

  private fun videoResult(taskId: String) = ComposeVideoResult(
    taskId = taskId,
    outputVideoPath = "/tmp/output-$taskId.mp4",
    width = 64,
    height = 64,
    durationMs = 1000,
    codec = VideoCodec.H264,
  )
}
