package io._3ch.admin.api

import io._3ch.common.admin.{AdminCodec, AdminError, AdminErrorCode, Json}
import org.scalajs.dom

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** 管理 API の呼び出し。変更は JSON と X-Ch3s-Admin を付けて送る */
object AdminApiClient {
  val NetworkError = "通信できませんでした。時間をおいてやり直してください。"
  private val Prefix = "/admin/api"

  /** 取得する。path は /admin/api より後で、末尾は / */
  def get[A](path: String)(decode: Json => AdminCodec.Decoded[A]): Future[Either[AdminError, A]] =
    call(dom.HttpMethod.GET, path, None)(decode)

  def put[A](path: String, body: Json)(decode: Json => AdminCodec.Decoded[A]): Future[Either[AdminError, A]] =
    call(dom.HttpMethod.PUT, path, Some(body))(decode)

  def post[A](path: String, body: Json)(decode: Json => AdminCodec.Decoded[A]): Future[Either[AdminError, A]] =
    call(dom.HttpMethod.POST, path, Some(body))(decode)

  private def call[A](method: dom.HttpMethod, path: String, body: Option[Json])(decode: Json => AdminCodec.Decoded[A]): Future[Either[AdminError, A]] = {
    val headers = new dom.Headers()
    headers.set("accept", "application/json")
    val init = new dom.RequestInit {}
    init.method = method
    init.credentials = dom.RequestCredentials.`same-origin`
    init.redirect = dom.RequestRedirect.error
    body.foreach { value =>
      headers.set("content-type", "application/json")
      headers.set("x-ch3s-admin", "1")
      init.body = Json.render(value)
    }
    init.headers = headers
    dom.fetch(Prefix + path, init).toFuture.flatMap(response => response.text().toFuture.map(text => read(response.ok, response.status, text, decode)))
      .recover { case _ => Left(AdminError(AdminErrorCode.Internal, NetworkError)) }
  }

  /** 応答の本文を読む。読めない応答は状態コードを添えた文言にする */
  def read[A](ok: Boolean, status: Int, text: String, decode: Json => AdminCodec.Decoded[A]): Either[AdminError, A] = {
    def unreadable: AdminError = AdminError(AdminErrorCode.Internal, s"応答を読めませんでした（HTTP $status）。")
    Json.parse(text) match {
      case Left(_) => Left(unreadable)
      case Right(json) if ok => decode(json).left.map(_ => unreadable)
      case Right(json) => Left(AdminCodec.error(json).getOrElse(unreadable))
    }
  }

}
