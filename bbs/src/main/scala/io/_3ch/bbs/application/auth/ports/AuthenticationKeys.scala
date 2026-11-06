package io._3ch.bbs.application.auth.ports

import scala.concurrent.Future

/** 認証用の乱数と索引 HMAC。平文キーを永続化しない */
trait AuthenticationKeys {
  def aid(): String
  def uid(): String
  def token(): String
  def tokenId(): String
  def index(kind: String, value: String): Future[Option[String]]
}
