package io._3ch.common.domain.device

/** 既知の画面とDPRを持つiOS端末が動作する最小メジャー版 */
final case class IosDeviceProfile(screen: String, dpr: Double, minIosMajor: Int)

/** 端末申告の論理破綻と、受信ヘッダとの矛盾を照合する */
object DeviceFingerprintCheck {
  private val IosVersion = "(?i)(?:CPU (?:iPhone )?OS|iPhone OS) ([0-9]+)(?:[_ .]|$)".r

  /** 材料や既知プロファイルがないときは、その照合だけを飛ばす */
  def validate(fingerprint: Option[ClientFingerprint], hints: ClientHints, headerUserAgent: String,
    profiles: Vector[IosDeviceProfile] = Vector.empty): Either[DeviceRejection, Unit] = {
    val logicalMismatch = fingerprint.exists { fp =>
      DeviceInfo(fp.os, fp.os, fp.webgl).isLogicalMismatched || iosProfileMismatched(fp, profiles)
    }
    if (logicalMismatch) Left(DeviceRejection.Fingerprint)
    else if (hints.contradicts(fingerprint, headerUserAgent) || ClientDeclarationCheck.userAgentMismatched(headerUserAgent, fingerprint)) {
      Left(DeviceRejection.DeclarationMismatch)
    } else Right(())
  }

  /** 一致する既知画面の全候補より古いiOSを名乗っているか */
  def iosProfileMismatched(fingerprint: ClientFingerprint, profiles: Vector[IosDeviceProfile]): Boolean = {
    val matching = profiles.filter(p => p.screen == fingerprint.screen && p.dpr == fingerprint.dpr)
    IosVersion.findFirstMatchIn(fingerprint.os).flatMap(_.group(1).toIntOption).exists { major =>
      matching.nonEmpty && matching.forall(_.minIosMajor > major)
    }
  }
}
