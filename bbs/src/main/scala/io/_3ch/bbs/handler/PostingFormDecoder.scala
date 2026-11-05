package io._3ch.bbs.handler

import io._3ch.bbs.domain.posting.{FormSanitizer, PostingError}
import io._3ch.bbs.external.web.TextDecoder
import io._3ch.bbs.util.SjisEncoding
import io._3ch.common.domain.posting.PostForm

import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

/** bbs.cgi のフォーム本文を復号する。submit=書… なら UTF-8、それ以外は Monazilla の Shift_JIS とみなす */
object PostingFormDecoder {
  private val Submit = new js.RegExp("submit=([^&]+)")
  private val HexByte = new js.RegExp("^[0-9a-fA-F]{2}$")

  /** 拒否時は、その時点のフォームを添えて返す */
  def decode(body: Uint8Array, isMonazilla: Boolean): Either[(PostingError, PostForm), PostForm] = {
    val text = new TextDecoder("utf-8").decode(body)
    val empty = PostForm(isMonazilla = isMonazilla)
    val isSJIS = !text.toLowerCase.contains("submit=%e6%9b%b8")
    if (!Submit.test(text)) Left(PostingError.NoSubmit -> empty)
    else if (isSJIS && !isMonazilla) Left(PostingError.SjisFromWeb -> empty.copy(isSJIS = true))
    else {
      val fields = text.split("&", -1).foldLeft(Map.empty[String, String]) { (acc, pair) =>
        val parts = pair.split("=", -1)
        if (parts.length >= 2 && parts(0).nonEmpty) {
          val encoded = parts(1).replace("+", " ")
          val decoded = if (isSJIS) decodeSjis(encoded) else js.URIUtils.decodeURIComponent(encoded)
          acc.updated(parts(0), decoded)
        } else acc
      }
      Right(FormSanitizer.fromFields(fields, isSJIS, isMonazilla))
    }
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
