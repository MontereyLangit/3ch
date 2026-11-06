package io._3ch.common.domain.account

import scala.scalajs.js
import scala.util.Try

/** 認証入力の Unicode 正規化と国際化ドメイン名の ASCII 化 */
private[account] object AccountText {
  def nfc(value: String): String = value.asInstanceOf[js.Dynamic].normalize("NFC").asInstanceOf[String]

  /** ドメインを punycode の ASCII 小文字にする。変換できなければ None */
  def asciiDomain(domain: String): Option[String] =
    Try(js.Dynamic.newInstance(js.Dynamic.global.URL)("http://" + domain + "/").hostname.asInstanceOf[String]).toOption
}
