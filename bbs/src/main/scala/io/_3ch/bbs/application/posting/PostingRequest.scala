package io._3ch.bbs.application.posting

import io._3ch.bbs.domain.posting.PostingError
import io._3ch.bbs.external.web.Request

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

/** bbs.cgi へのリクエストのうち投稿処理が読むヘッダと cf の値。無いヘッダは空文字列。本文は body を呼んだときに読む */
final case class PostingRequest(
  method: String,
  contentType: String,
  referer: String,
  userAgent: String,
  secChUa: String,
  secChUaFullVersionList: Option[String],
  accept: String,
  acceptLanguage: String,
  acceptEncoding: String,
  deviceInformation: String,
  clientIp: String,
  connectingIp: Option[String],
  forwardedFor: String,
  forwarded: String,
  via: String,
  cookie: String,
  asn: Option[String],
  country: Option[String],
  region: Option[String],
  city: Option[String],
  body: () => Future[Uint8Array] = () => Future.successful(new Uint8Array(0))
) {

  /** UA に Client Hints（完全版があればそちら）を連結したもの */
  def browserUserAgent: String = userAgent + secChUaFullVersionList.getOrElse(secChUa)

  /** Accept・Accept-Language・Accept-Encoding の連結 */
  def browserInfo: String = "Accept:" + accept + "Accept-Language:" + acceptLanguage + "Accept-Encoding:" + acceptEncoding

  /** Client Hints を連結した UA が Monazilla/1.00 で始まるか */
  def isMonazilla: Boolean = PostingRequest.Monazilla.test(browserUserAgent)

  /** 表示用のホスト。接続元 IP（無ければ null）に、それと異なる最初の転送ヘッダを <…> で付ける */
  def host: String = {
    val ip = connectingIp.getOrElse("null")
    Seq(forwardedFor, forwarded, via).find(v => v.nonEmpty && !connectingIp.contains(v)).fold(ip)(v => s"$ip<$v>")
  }

  /** ヘッダの検査。複数あてはまるときは従来の判定順で最初のものを返す */
  def validate: Option[PostingError] = {
    if (method != "POST") Some(PostingError.MethodNotPost)
    else if (!contentType.contains("application/x-www-form-urlencoded")) Some(PostingError.BrokenBody(contentType))
    else if (!PostingRequest.Referer.test(referer) && !PostingRequest.LocalReferer.test(referer)) Some(PostingError.InvalidReferer(referer))
    else if (userAgent.length < 7 || userAgent.length > 384 || PostingRequest.NonAscii.test(userAgent)) Some(PostingError.InvalidUserAgent(userAgent))
    else if (clientIp.nonEmpty) Some(PostingError.ProxyHeader(clientIp))
    else None
  }
}

object PostingRequest {
  private val Monazilla = new js.RegExp("^Monazilla/1.00")
  private val Referer = new js.RegExp("^https?://www\\.(3ch\\.io|3chbbs\\.com)(:[0-9]+)?([/?#]|$)")
  private val LocalReferer = new js.RegExp("^http://127\\.0\\.0\\.1:878[78]([/?#]|$)")
  private val NonAscii = new js.RegExp("[^ -~]")

  /** Request から読む。cf が無いか値が null・undefined なら None */
  def from(request: Request): PostingRequest = {
    def header(name: String): String = Option(request.headers.get(name)).getOrElse("")
    val cf = request.cf.toOption
    def metadata(name: String): Option[String] = cf.flatMap { c =>
      val value = c.selectDynamic(name)
      if (value == null || js.isUndefined(value)) None else Some(js.Dynamic.global.String(value).asInstanceOf[String])
    }
    PostingRequest(
      method = request.method,
      contentType = header("content-type"),
      referer = header("referer"),
      userAgent = header("user-agent"),
      secChUa = header("sec-ch-ua"),
      secChUaFullVersionList = Option(request.headers.get("sec-ch-ua-full-version-list")),
      accept = header("accept"),
      acceptLanguage = header("accept-language"),
      acceptEncoding = header("accept-encoding"),
      deviceInformation = header("x-ch3-device-information"),
      clientIp = header("client-ip"),
      connectingIp = Option(request.headers.get("CF-Connecting-IP")),
      forwardedFor = header("x-forwarded-for"),
      forwarded = header("forwarded"),
      via = header("via"),
      cookie = header("cookie"),
      asn = metadata("asn"),
      country = metadata("country"),
      region = metadata("region"),
      city = metadata("city"),
      body = () => request.arrayBuffer().toFuture.map(new Uint8Array(_))
    )
  }
}
