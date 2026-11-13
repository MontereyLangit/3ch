package io._3ch.bbs.hash

import io._3ch.bbs.application.posting.ports.Hasher

import scala.concurrent.Future

/** Web Crypto による投稿処理のハッシュ */
object WebHasher extends Hasher {
  def sha1Base64(value: String): Future[String] = WebHash.sha1Base64(value).toFuture
  def sha256Hex(value: String): Future[String] = WebHash.hash("SHA-256", value).toFuture
}
