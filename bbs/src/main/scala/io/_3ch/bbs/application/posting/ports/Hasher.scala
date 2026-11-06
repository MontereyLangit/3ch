package io._3ch.bbs.application.posting.ports

import scala.concurrent.Future

/** 投稿処理で使うハッシュ。SHA は Web Crypto で非同期 */
trait Hasher {
  /** MD5 の 16 進 */
  def md5(value: String): String

  /** SHA-1 の Base64 の先頭 27 文字 */
  def sha1Base64(value: String): Future[String]

  /** SHA-256 の 16 進 */
  def sha256Hex(value: String): Future[String]
}
