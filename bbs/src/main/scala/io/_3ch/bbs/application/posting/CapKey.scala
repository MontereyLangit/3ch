package io._3ch.bbs.application.posting

import io._3ch.bbs.application.posting.ports.Hasher
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** キャップのパスワードから KV の CAP の照合キーを作る。! で始まるパスワードは照合キーそのもの */
object CapKey {
  /** saltHash は投稿者の照合値に使うソルトの SHA-256（16 進） */
  def of(hasher: Hasher, password: String, saltHash: String): Future[String] =
    if (password.startsWith("!")) Future.successful(password.drop(1)) else hasher.sha256Hex(password + saltHash).flatMap(hasher.sha1Base64)
}
