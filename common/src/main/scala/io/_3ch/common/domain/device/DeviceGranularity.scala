package io._3ch.common.domain.device

/** 端末ハッシュが共有される範囲。粒度にかかわらず規制照合には使う */
enum DeviceGranularity(val code: Char) {
  case Personal extends DeviceGranularity('p')
  case Class extends DeviceGranularity('g')
  case Unknown extends DeviceGranularity('u')

  /** 同型端末で共有され得るか */
  def sharedAcrossDevices: Boolean = this != DeviceGranularity.Personal
}

object DeviceGranularity {
  /** 保存された1文字の粒度を読む */
  def fromCode(code: Char): Option[DeviceGranularity] = values.find(_.code == code)

  /** Apple GPUとタッチを先に見て、次にヒントと申告UAから共有範囲を判定する */
  def detect(fingerprint: Option[ClientFingerprint], hints: ClientHints = ClientHints()): DeviceGranularity = {
    val fp = fingerprint.getOrElse(ClientFingerprint())
    val ua = DeviceText.lowercase(fp.os.trim)
    val renderer = DeviceText.lowercase(fp.webgl.trim)
    val appleGpu = renderer.contains("apple")
    val claimsIos = List("iphone", "ipad", "ipod").exists(ua.contains) ||
      (fp.touch && List("macintosh", "mac os x").exists(ua.contains))
    if (appleGpu && fp.touch) DeviceGranularity.Class
    else if (hints.platform.nonEmpty && !hints.claimsIos) DeviceGranularity.Personal
    else if (claimsIos) {
      if (renderer.nonEmpty && !appleGpu) DeviceGranularity.Personal else DeviceGranularity.Class
    } else if (ua.isEmpty) DeviceGranularity.Unknown
    else DeviceGranularity.Personal
  }
}
