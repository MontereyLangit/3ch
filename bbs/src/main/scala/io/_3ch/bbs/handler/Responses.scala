package io._3ch.bbs.handler

import io._3ch.bbs.external.web.{Response, ResponseInit, TextEncoder}
import io._3ch.bbs.util.SjisEncoding
import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

object Responses {
  def response(body: js.Any, status: Int = 200, headers: js.Dictionary[String] = js.Dictionary.empty[String]): Response = {
    val init = new ResponseInit {}
    init.status = status
    init.headers = headers.asInstanceOf[js.Object]
    new Response(body, init)
  }

  /** 本文を指定の文字コードで返す。extra は同名の既存ヘッダを置き換え、同名を複数並べれば複数出す */
  def plain(text: String, kind: String, encoding: String = "UTF-8", range: String = "", modified: js.Date = new js.Date(), since: String = "", extra: Seq[(String, String)] = Nil): Response = {
    if since.nonEmpty && modified.getTime() <= new js.Date(since).getTime() then
      return response(null, 304, js.Dictionary("Last-Modified" -> modified.toUTCString()))
    val sjis = encoding == "Shift_JIS"
    val bytes =
      if sjis then SjisEncoding.encode(text)
      else new TextEncoder().encode(text)
    val size = bytes.length
    val headers = js.Dictionary.empty[String]
    val result = if range.nonEmpty then {
      val matched = new js.RegExp("^bytes=(\\d*)-(\\d*)$").exec(range)
      if matched == null then return response("Bad Range Header", 400)
      val start = if matched(1).get.isEmpty then 0d else js.Dynamic.global.parseInt(matched(1), 10).asInstanceOf[Double]
      val end = if matched(2).get.isEmpty then size - 1d else js.Dynamic.global.parseInt(matched(2), 10).asInstanceOf[Double]
      if start >= size || start > end then return response("Requested Range Not Satisfiable", 416, js.Dictionary("Content-Range" -> s"bytes */$size"))
      val last = math.min(end, size - 1d).toInt
      val chunk = bytes.asInstanceOf[js.Dynamic].slice(start, last + 1).asInstanceOf[Uint8Array]
      headers("Content-Type") = if sjis then s"text/$kind;charset=Shift_JIS" else s"text/$kind; charset=UTF-8"
      headers("Last-Modified") = modified.toUTCString()
      headers("Content-Length") = chunk.length.toString
      headers("Content-Range") = s"bytes ${start.toInt}-$last/$size"
      headers("Accept-Ranges") = "bytes"
      response(chunk, 206, headers)
    } else {
      headers("content-type") = s"text/$kind;charset=${if sjis then "Shift_JIS" else "UTF-8"}"
      headers("Content-Length") = size.toString
      if kind == "plain" then {
        headers("Last-Modified") = modified.toUTCString()
        headers("Accept-Ranges") = "bytes"
      }
      response(if sjis then bytes else text, 200, headers)
    }
    extra.map(_._1.toLowerCase).distinct.foreach(result.headers.delete)
    extra.foreach((name, value) => result.headers.append(name, value))
    result
  }
}
