package io._3ch.common.domain

import java.util.regex.Matcher

/** dat / subject に保存済みの文字参照を、React のテキスト描画用に一度だけ戻す。 */
object SubjectText {
  private val entity = "&(?:amp|lt|gt|quot|apos|#[0-9]+|#[xX][0-9a-fA-F]+);".r

  def decode(value: String): String = {
    entity.replaceAllIn(value, matched => {
      val token = matched.matched
      val decoded = token match {
        case "&amp;" => "&"
        case "&lt;" => "<"
        case "&gt;" => ">"
        case "&quot;" => "\""
        case "&apos;" => "'"
        case _ =>
          val number = token.substring(2, token.length - 1)
          val parsed = scala.util.Try {
            if number.startsWith("x") || number.startsWith("X") then Integer.parseInt(number.drop(1), 16)
            else Integer.parseInt(number, 10)
          }.toOption.filter(n => n > 0 && n <= 0x10ffff && !(n >= 0xd800 && n <= 0xdfff))
          parsed.fold(token)(n => new String(Character.toChars(n)))
      }
      Matcher.quoteReplacement(decoded)
    })
  }
}
