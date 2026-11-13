package io._3ch.common.domain.device

import java.util.Locale

/** 端末情報を実行環境の既定ロケールに依存せず小文字化する */
private[device] object DeviceText {
  def lowercase(value: String): String = value.toLowerCase(Locale.ROOT)
}
