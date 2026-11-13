package io._3ch.common.domain.device

/** 受信UAと、認証画面が申告したUAを照合する */
object ClientDeclarationCheck {
  /** 前後空白を除いて大小文字も一致するかを検査する。どちらかが欠損なら矛盾としない */
  def userAgentMismatched(headerUserAgent: String, fingerprint: Option[ClientFingerprint]): Boolean = {
    val header = DeviceStrings.trim(Option(headerUserAgent).getOrElse(""))
    val declared = fingerprint.fold("")(fp => DeviceStrings.trim(fp.os))
    header.nonEmpty && declared.nonEmpty && header != declared
  }
}
