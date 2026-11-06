package io._3ch.common.domain.account

import java.net.IDN
import java.text.Normalizer
import java.util.Locale
import scala.util.Try

/** 認証入力の Unicode 正規化と国際化ドメイン名の ASCII 化 */
private[account] object AccountText {
  def nfc(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)

  /** ドメインを punycode の ASCII 小文字にする。変換できなければ None */
  def asciiDomain(domain: String): Option[String] = Try(IDN.toASCII(domain, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT)).toOption
}
