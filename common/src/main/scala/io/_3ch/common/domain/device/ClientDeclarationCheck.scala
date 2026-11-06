package io._3ch.common.domain.device

/** 受信UAと、認証画面が申告したUAを照合する */
object ClientDeclarationCheck {
  /** 前後空白を除いて大小文字も一致するかを検査する。どちらかが欠損なら矛盾としない */
  def userAgentMismatched(headerUserAgent: String, fingerprint: Option[ClientFingerprint]): Boolean = {
    val header = Option(headerUserAgent).getOrElse("").trim
    val declared = fingerprint.fold("")(_.os.trim)
    header.nonEmpty && declared.nonEmpty && header != declared
  }
}
