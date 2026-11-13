package io._3ch.common.domain.account

import java.text.Normalizer

/** 認証入力の Unicode 正規化 */
private[account] object AccountText {
  def nfc(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)
}
