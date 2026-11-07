package io._3ch.common.admin

import scala.util.matching.Regex

/** dat の名前・メール・本文の欄と、管理画面で編集するテキストの相互変換 */
object ResEditText {
  // JavaScript の \s と同じ文字
  private val Space = "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"
  private val Break = new Regex(s"(?i) ?<br$Space*/?>(?: )?")
  private val Entity = new Regex("&(?:lt|gt|quot|apos|amp);")
  private val Entities = Map("&lt;" -> "<", "&gt;" -> ">", "&quot;" -> "\"", "&apos;" -> "'", "&amp;" -> "&")

  /** dat の欄を編集用のテキストにする。<br> を改行に、5 つの文字参照を文字に戻す */
  def toText(field: String): String = {
    val lines = Break.replaceAllIn(field, "\n")
    Entity.replaceAllIn(lines, m => Regex.quoteReplacement(Entities(m.matched)))
  }

  /** 編集用のテキストを dat の欄にする。&・<・>・" を文字参照にし、改行を <br> にする */
  def toField(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replaceAll("\r?\n", " <br> ")
}
