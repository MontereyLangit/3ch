package io._3ch.bbs.domain.posting

import io._3ch.common.domain.posting.PostForm

import scala.scalajs.js
import scala.scalajs.js.JSStringOps.*

/** 復号済みのフォーム値を整形し、名前欄のコマンドを読む */
object FormSanitizer {
  private val Trip = new js.RegExp("^([^#]*?)(?<!&#[xX]?[0-9a-fA-F]{1,8});?#(.+)$")
  private val Replacements: Seq[(js.RegExp, String)] = Seq(
    "&(?!#[a-zA-Z0-9]+;)" -> "&amp;", "\"" -> "&quot;", "<" -> "&lt;", ">" -> "&gt;", "'" -> "&apos;", "\t" -> " ",
    "&#0*1[03];" -> "<br>", "&#[xX]0*[aAdD];" -> "<br>", "\r\n?|\n" -> "<br>", "[\\x00-\\x1F\\x7F]" -> ""
  ).map((pattern, replacement) => new js.RegExp(pattern, "g") -> replacement)

  /** HTML 用にエスケープする。名前欄の # 以降は捨て、!id・!no を読む */
  def fromFields(values: Map[String, String], isSJIS: Boolean = false, isMonazilla: Boolean = false): PostForm = {
    val fields = values.map { case (name, value) => name -> sanitize(value) }
    def field(name: String): String = fields.getOrElse(name, "")
    val form = PostForm(
      name = field("FROM").replace("★", "☆").replace("◆", "◇").replace("＃", "#"),
      mail = field("mail").replace("＃", "#"),
      message = field("MESSAGE"),
      subject = field("subject"),
      bbs = field("bbs"),
      key = field("key"),
      time = field("time"),
      submit = field("submit"),
      isSJIS = isSJIS,
      isMonazilla = isMonazilla
    )
    val trip = Trip.exec(form.name)
    val commands = NameCommands.parse(if (trip == null) form.name else trip(1).get)
    form.copy(name = commands.name, showId = commands.showId || field("showid").nonEmpty, noNusi = commands.noNusi)
  }

  /** HTML の特殊文字・改行・制御文字を置き換える */
  def sanitize(value: String): String =
    Replacements.foldLeft(value) { case (text, (pattern, replacement)) => text.jsReplace(pattern, replacement) }
}
