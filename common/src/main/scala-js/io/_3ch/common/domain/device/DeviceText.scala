package io._3ch.common.domain.device

/** 端末情報をロケールに依存しないJavaScriptの規則で小文字化する */
private[device] object DeviceText {
  def lowercase(value: String): String = value.toLowerCase
}
