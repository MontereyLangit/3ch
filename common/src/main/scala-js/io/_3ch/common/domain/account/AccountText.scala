package io._3ch.common.domain.account

import scala.scalajs.js

/** 認証入力の Unicode 正規化 */
private[account] object AccountText {
  def nfc(value: String): String = value.asInstanceOf[js.Dynamic].normalize("NFC").asInstanceOf[String]
}
