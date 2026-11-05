package io._3ch.bbs.domain.posting

import io._3ch.bbs.external.web.TextDecoder
import io._3ch.bbs.util.SjisEncoding
import io._3ch.common.domain.posting.PostForm

import scala.scalajs.js
import scala.scalajs.js.JSStringOps.*
import scala.scalajs.js.typedarray.Uint8Array

/** bbs.cgi の本文（`application/x-www-form-urlencoded`）を復号し、HTML 用にエスケープした [[PostForm]] にする。 */
object FormSanitizer {
  private val Submit = new js.RegExp("submit=([^&]+)")
  private val Trip = new js.RegExp("^([^#]*?)(?<!&#[xX]?[0-9a-fA-F]{1,8});?#(.+)$")
  private val HexByte = new js.RegExp("^[0-9a-fA-F]{2}$")
  private val Replacements: Seq[(js.RegExp, String)] = Seq(
    "&(?!#[a-zA-Z0-9]+;)" -> "&amp;", "\"" -> "&quot;", "<" -> "&lt;", ">" -> "&gt;", "'" -> "&apos;", "\t" -> " ",
    "&#0*1[03];" -> "<br>", "&#[xX]0*[aAdD];" -> "<br>", "\r\n?|\n" -> "<br>", "[\\x00-\\x1F\\x7F]" -> ""
  ).map((pattern, replacement) => new js.RegExp(pattern, "g") -> replacement)

  /**
    * 本文を復号する。Web ブラウザは `submit=書…`（UTF-8）、それ以外は Monazilla の Shift_JIS とみなす。
    * 名前欄の `#` 以降（旧トリップ）は捨て、`!id` / `!no` を読み取る。拒否時は空のフォームを添えて返す。
    */
  def decode(body: Uint8Array, isMonazilla: Boolean): Either[(PostingError, PostForm), PostForm] = {
    val text = new TextDecoder("utf-8").decode(body)
    val empty = PostForm(isMonazilla = isMonazilla)
    val isSJIS = !text.toLowerCase.contains("submit=%e6%9b%b8")
    if (!Submit.test(text)) Left(PostingError.NoSubmit -> empty)
    else if (isSJIS && !isMonazilla) Left(PostingError.SjisFromWeb -> empty.copy(isSJIS = true))
    else {
      val fields = text.split("&", -1).foldLeft(Map.empty[String, String]) { (acc, pair) =>
        val parts = pair.split("=", -1)
        if (parts.length >= 2 && parts(0).nonEmpty) acc.updated(parts(0), sanitize(parts(1).replace("+", " "), isSJIS)) else acc
      }
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
      Right(form.copy(name = commands.name, showId = commands.showId || field("showid").nonEmpty, noNusi = commands.noNusi))
    }
  }

  /** 1 個の値を復号し、HTML の特殊文字・改行・制御文字を置き換える。 */
  def sanitize(encoded: String, isSJIS: Boolean): String = {
    val decoded = if (isSJIS) decodeSjis(encoded) else js.URIUtils.decodeURIComponent(encoded)
    Replacements.foldLeft(decoded) { case (value, (pattern, replacement)) => value.jsReplace(pattern, replacement) }
  }

  private def decodeSjis(s: String): String = {
    val bytes = scala.collection.mutable.ArrayBuffer.empty[Int]
    var i = 0
    while (i < s.length) {
      if (s(i) == '%' && i + 2 < s.length && HexByte.test(s.substring(i + 1, i + 3))) {
        bytes += Integer.parseInt(s.substring(i + 1, i + 3), 16)
        i += 3
      } else {
        bytes += s(i).toInt
        i += 1
      }
    }
    val u8 = new Uint8Array(bytes.length)
    bytes.indices.foreach(j => u8(j) = bytes(j).toShort)
    SjisEncoding.decode(u8)
  }
}
