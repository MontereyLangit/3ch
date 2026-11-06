package io._3ch.bbs.hash

import io._3ch.bbs.external.web.{GlobalCrypto, TextEncoder}
import io._3ch.common.domain.account.{AccountProfile, Password, PasswordAlgorithm, PasswordCredential}
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.typedarray.Uint8Array
import scala.util.control.NonFatal

/** 入力・保存値の不正と、暗号 API の利用不能を区別する */
enum PasswordHashFailure {
  case Invalid
  case Unavailable
}

/** Web Crypto でパスワード資格情報を作成・照合する。平文や計算値をログへ出さない */
object PasswordHasher {
  /** 新しい16バイトのソルトで現在の反復数の資格情報を作る */
  def create(password: Password, now: Double): Future[Either[PasswordHashFailure, PasswordCredential]] = {
    if (password == null || !AccountProfile.validTime(now)) Future.successful(Left(PasswordHashFailure.Invalid))
    else available {
      val salt = vector(GlobalCrypto.getRandomValues(new Uint8Array(16)))
      derive(password, salt, PasswordCredential.currentIterations).map { hash =>
        PasswordCredential(PasswordAlgorithm.Pbkdf2Sha256, PasswordCredential.currentIterations, salt, hash, now)
      }
    }
  }

  /** 保存済みの方式・反復数・ソルトで計算し、全32バイトを比較する */
  def verify(password: Password, credential: PasswordCredential): Future[Either[PasswordHashFailure, Boolean]] = {
    val valid = credential != null && credential.valid
    if (password == null || !valid) Future.successful(Left(PasswordHashFailure.Invalid))
    else available {
      credential.algo match {
        case PasswordAlgorithm.Pbkdf2Sha256 => derive(password, credential.salt, credential.iterations).map(equal(_, credential.hash))
      }
    }
  }

  private def available[A](operation: => Future[A]): Future[Either[PasswordHashFailure, A]] =
    Future.unit.flatMap(_ => operation).map(value => Right(value): Either[PasswordHashFailure, A])
      .recover { case NonFatal(_) => Left(PasswordHashFailure.Unavailable) }

  private def derive(password: Password, salt: Vector[Byte], iterations: Int): Future[Vector[Byte]] = {
    val algorithm = js.Dynamic.literal(name = "PBKDF2", salt = bytes(salt), iterations = iterations, hash = "SHA-256")
    for {
      key <- GlobalCrypto.subtle.importKey("raw", new TextEncoder().encode(password.value), js.Dynamic.literal(name = "PBKDF2"), false, js.Array("deriveBits")).toFuture
      buffer <- GlobalCrypto.subtle.deriveBits(algorithm, key, 256).toFuture
    } yield {
      val hash = new Uint8Array(buffer)
      if (hash.length != 32) throw new IllegalStateException("invalid-password-hash-length")
      vector(hash)
    }
  }

  private def bytes(value: Vector[Byte]): Uint8Array = {
    val result = new Uint8Array(value.size)
    value.zipWithIndex.foreach { case (byte, index) => result(index) = (byte.toInt & 0xff).toShort }
    result
  }

  private def vector(value: Uint8Array): Vector[Byte] = Vector.tabulate(value.length)(index => value(index).toByte)

  private def equal(actual: Vector[Byte], expected: Vector[Byte]): Boolean = {
    if (actual.size != expected.size) false
    else actual.indices.foldLeft(0)((difference, index) => difference | ((actual(index).toInt & 0xff) ^ (expected(index).toInt & 0xff))) == 0
  }
}
