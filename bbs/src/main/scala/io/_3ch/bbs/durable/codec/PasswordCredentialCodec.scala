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

  private def decodeThrottle(value: js.Any): LoginThrottle = {
    val failures = field(value, "failures")
    if (!js.Array.isArray(failures)) invalid()
    val events = failures.asInstanceOf[js.Array[js.Any]].toVector.map(event =>
      LoginFailure(text(field(event, "keyIpHash")), time(field(event, "t"))))
    val locks = record(field(value, "locks")).asInstanceOf[js.Dictionary[js.Any]].iterator.map { case (key, until) => key -> time(until) }.toMap
    val state = LoginThrottle(events, locks)
    if (!state.valid) invalid()
    state
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
      throttle = js.Dynamic.literal(failures = value.throttle.failures.map(failure => js.Dynamic.literal(keyIpHash = failure.keyIpHash, t = failure.t)).toJSArray,
        locks = js.Dictionary(value.throttle.locks.toSeq.map { case (key, until) => key -> until.asInstanceOf[js.Any] }*)))
  }
}
