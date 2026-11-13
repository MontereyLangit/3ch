package io._3ch.bbs.durable.codec

import io._3ch.common.domain.account.*
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

/** パスワード資格情報の保存形式。ソルトとハッシュは小文字の16進数で保存する */
object PasswordCredentialCodec {
  private def invalid(): Nothing = throw js.JavaScriptException(new js.Error("invalid-password-credential-data"))
  private def record(value: js.Any): js.Dynamic = {
    if (value == null || js.typeOf(value) != "object" || js.Array.isArray(value)) invalid()
    val prototype = js.Dynamic.global.Object.getPrototypeOf(value).asInstanceOf[js.Any]
    if (prototype != null && prototype != js.Dynamic.global.Object.prototype.asInstanceOf[js.Any]) invalid()
    value.asInstanceOf[js.Dynamic]
  }
  private def field(value: js.Any, name: String): js.Any = record(value).selectDynamic(name).asInstanceOf[js.Any]
  private def text(value: js.Any): String = if (js.typeOf(value) == "string") value.asInstanceOf[String] else invalid()
  private def time(value: js.Any): Double = {
    if (js.typeOf(value) != "number" || !AccountProfile.validTime(value.asInstanceOf[Double])) invalid()
    value.asInstanceOf[Double]
  }
  private def bytes(value: js.Any, size: Int): Vector[Byte] = {
    val encoded = text(value)
    if (!encoded.matches(s"[0-9a-f]{${size * 2}}")) invalid()
    encoded.grouped(2).map(Integer.parseInt(_, 16).toByte).toVector
  }
  private def hex(value: Vector[Byte]): String = value.iterator.map(byte => Integer.toHexString((byte.toInt & 255) | 256).substring(1)).mkString

  private def integer(value: js.Any): Int = {
    val number = time(value)
    if (number != math.floor(number) || number > Int.MaxValue) invalid()
    number.toInt
  }
  private def optionalTime(value: js.Any): Option[Double] = if (js.isUndefined(value)) None else Some(time(value))

  private def decodeThrottle(value: js.Any): LoginThrottle = {
    val sources = record(field(value, "sources")).asInstanceOf[js.Dictionary[js.Any]].iterator.map { case (key, source) =>
      key -> LoginSource(integer(field(source, "count")), time(field(source, "since")), optionalTime(field(source, "lockedUntil")))
    }.toMap
    val account = field(value, "account")
    val state = LoginThrottle(sources, LoginAccountFailures(integer(field(account, "count")), time(field(account, "since")), optionalTime(field(account, "lockedUntil"))))
    if (!state.valid) invalid()
    state
  }

  private def encodeThrottle(value: LoginThrottle): js.Dynamic = {
    def source(entry: LoginSource): js.Dynamic = {
      val result = js.Dynamic.literal(count = entry.count, since = entry.since)
      entry.lockedUntil.foreach(until => result.lockedUntil = until)
      result
    }
    val account = js.Dynamic.literal(count = value.account.count, since = value.account.since)
    value.account.lockedUntil.foreach(until => account.lockedUntil = until)
    js.Dynamic.literal(sources = js.Dictionary(value.sources.toSeq.map { case (key, entry) => key -> source(entry).asInstanceOf[js.Any] }*), account = account)
  }

  /** 必須項目の型・長さ・計数状態を検証する。欠損値を初期設定で補わない */
  def decode(value: js.Any): PasswordCredential = {
    val iterations = time(field(value, "iterations"))
    if (iterations < 1 || iterations > PasswordCredential.currentIterations) invalid()
    val credential = PasswordCredential(PasswordAlgorithm.parse(text(field(value, "algo"))).getOrElse(invalid()), iterations.toInt,
      bytes(field(value, "salt"), 16), bytes(field(value, "hash"), 32), time(field(value, "updatedAt")), decodeThrottle(field(value, "throttle")))
    if (!credential.valid) invalid()
    credential
  }

  /** 有効な資格情報だけを保存形式にする。平文パスワードは受け取らない */
  def encode(value: PasswordCredential): js.Dynamic = {
    if (value == null || !value.valid) invalid()
    js.Dynamic.literal(algo = value.algo.code, iterations = value.iterations, salt = hex(value.salt), hash = hex(value.hash), updatedAt = value.updatedAt,
      throttle = encodeThrottle(value.throttle))
  }
}
