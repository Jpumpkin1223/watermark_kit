package com.tttocklll.watermark_kit

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/** WatermarkKitPlugin */
class WatermarkKitPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {
  private var channel: MethodChannel? = null
  private var api: WatermarkApiImpl? = null

  override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    val methodChannel = MethodChannel(binding.binaryMessenger, "watermark_kit")
    methodChannel.setMethodCallHandler(this)
    channel = methodChannel

    // Register Pigeon Host API implementation
    val engineApi = WatermarkApiImpl(binding.applicationContext, binding.binaryMessenger)
    api = engineApi
    WatermarkApi.setUp(binding.binaryMessenger, engineApi)
  }

  override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
    when (call.method) {
      "getPlatformVersion" -> result.success("Android ${android.os.Build.VERSION.RELEASE}")
      else -> result.notImplemented()
    }
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    channel?.setMethodCallHandler(null)
    channel = null
    api?.dispose()
    api = null
    WatermarkApi.setUp(binding.binaryMessenger, null)
  }
}
