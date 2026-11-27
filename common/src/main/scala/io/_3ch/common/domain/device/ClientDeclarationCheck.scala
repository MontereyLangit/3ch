package io._3ch.common.domain.device

/** 受信UAと、認証画面が申告したUAを照合する */
object ClientDeclarationCheck {
  /** 前後の空白を除いて、大小文字も含めて一致するかを検査する。どちらかが欠けていれば矛盾とみなさない */
  def userAgentMismatched(headerUserAgent: String, fingerprint: Option[ClientFingerprint]): Boolean = {
    val header = DeviceStrings.trim(Option(headerUserAgent).getOrElse(""))
    val declared = fingerprint.fold("")(fp => DeviceStrings.trim(fp.os))
    header.nonEmpty && declared.nonEmpty && header != declared
  }
}
