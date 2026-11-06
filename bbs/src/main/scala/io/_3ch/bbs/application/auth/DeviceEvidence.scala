package io._3ch.bbs.application.auth

import io._3ch.common.domain.device.*
import scala.scalajs.js
import scala.util.Try

/** 検証済みの端末申告と、サーバーが受信したヒント */
final case class DeviceEvidence(fingerprint: Option[ClientFingerprint], hints: ClientHints) {
  /** 規制照合用の18桁。全欠損なら空文字 */
  def deviceIdentifier: String = DeviceFingerprintHasher.hash(fingerprint, hints.model)

  /** Cookie・アカウントへ保存する粒度1字と18桁 */
  def deviceHash: String = DeviceHashRef.of(fingerprint, hints = hints).fold("")(_.format)
}

/** JSON境界で型・長さ・数値範囲を確認してから端末情報を照合する */
object DeviceEvidence {
  private def missing(value: js.Any): Boolean = value == null || js.isUndefined(value)
  private def invalid(): Nothing = throw new IllegalArgumentException("invalid-fingerprint")
  private def record(value: js.Any): js.Dictionary[js.Any] = {
    if (missing(value) || js.typeOf(value) != "object" || js.Array.isArray(value)) invalid()
    else value.asInstanceOf[js.Dictionary[js.Any]]
  }
  private def field(value: js.Dictionary[js.Any], name: String): js.Any = value.getOrElse(name, js.undefined)
  private def text(value: js.Dictionary[js.Any], name: String, maxLength: Int): String = {
    val raw = field(value, name)
    if (js.isUndefined(raw)) ""
    else if (js.typeOf(raw) == "string" && raw.asInstanceOf[String].length <= maxLength) raw.asInstanceOf[String]
    else invalid()
  }
  private def decimal(value: js.Dictionary[js.Any], name: String, default: Double, maximum: Double): Double = {
    val raw = field(value, name)
    if (js.isUndefined(raw)) default
    else if (js.typeOf(raw) != "number") invalid()
    else {
      val number = raw.asInstanceOf[Double]
      if (number.isFinite && number >= 0 && number <= maximum) number else invalid()
    }
  }
  private def integer(value: js.Dictionary[js.Any], name: String, default: Int, maximum: Int): Int = {
    val number = decimal(value, name, default.toDouble, maximum.toDouble)
    if (number == math.floor(number)) number.toInt else invalid()
  }
  private def boolean(value: js.Dictionary[js.Any], name: String): Boolean = {
    val raw = field(value, name)
    if (js.isUndefined(raw)) false else if (js.typeOf(raw) == "boolean") raw.asInstanceOf[Boolean] else invalid()
  }
  private def fingerprint(raw: js.Any): Option[ClientFingerprint] = {
    if (missing(raw)) None
    else {
      val value = record(raw)
      val screen = text(value, "screen", 32)
      if (screen.nonEmpty && !screen.matches("[1-9][0-9]{0,5}x[1-9][0-9]{0,5}")) invalid()
      Some(ClientFingerprint(text(value, "os", 4096), integer(value, "cores", 0, 65535), integer(value, "ram", 0, 65535),
        screen, decimal(value, "dpr", 1d, 100d), integer(value, "colorDepth", 24, 128),
        text(value, "webgl", 4096), text(value, "fonts", 4096), boolean(value, "touch"), text(value, "arch", 256)))
    }
  }

  /** 受信ヘッダのヒントだけを使い、本文に含まれるヒントは採用しない */
  def decode(raw: js.Any, hints: ClientHints, headerUserAgent: String,
    profiles: Vector[IosDeviceProfile] = Vector.empty): Either[DeviceRejection, DeviceEvidence] = {
    Try(fingerprint(raw)).toEither.left.map(_ => DeviceRejection.Fingerprint).flatMap { fp =>
      DeviceFingerprintCheck.validate(fp, hints, headerUserAgent, profiles).map(_ => DeviceEvidence(fp, hints))
    }
  }

  /** フォーム中のJSON文字列から端末情報を読む */
  def fromJson(raw: String, hints: ClientHints, headerUserAgent: String,
    profiles: Vector[IosDeviceProfile] = Vector.empty): Either[DeviceRejection, DeviceEvidence] = {
    if (raw.isEmpty) decode(js.undefined, hints, headerUserAgent, profiles)
    else if (raw.length > 20000) Left(DeviceRejection.Fingerprint)
    else Try(js.JSON.parse(raw)).toEither.left.map(_ => DeviceRejection.Fingerprint)
      .flatMap(value => decode(value, hints, headerUserAgent, profiles))
  }

  /** 拒否時のログに使う最初の照合規則名。申告値は返さない */
  def declarationRuleFromJson(raw: String, hints: ClientHints, headerUserAgent: String): Option[String] = {
    if (raw.length > 20000) None
    else Try {
      val fp = fingerprint(if (raw.isEmpty) js.undefined else js.JSON.parse(raw))
      hints.contradictionRule(fp, headerUserAgent).map(_.toString)
        .orElse(Option.when(ClientDeclarationCheck.userAgentMismatched(headerUserAgent, fp))("UserAgent"))
    }.toOption.flatten
  }

  /** R2の既知プロファイル配列を読む。不正な表は照合に使わない */
  def profilesFromJson(raw: String): Vector[IosDeviceProfile] = {
    Try {
      val parsed = js.JSON.parse(raw)
      if (!js.Array.isArray(parsed)) invalid()
      parsed.asInstanceOf[js.Array[js.Any]].iterator.map { item =>
        val value = record(item)
        val screen = text(value, "screen", 32)
        val dpr = decimal(value, "dpr", 0, 100)
        val major = integer(value, "minIosMajor", 0, 1000)
        if (!screen.matches("[1-9][0-9]{0,5}x[1-9][0-9]{0,5}") || dpr <= 0 || major <= 0) invalid()
        IosDeviceProfile(screen, dpr, major)
      }.toVector
    }.getOrElse(Vector.empty)
  }
}
