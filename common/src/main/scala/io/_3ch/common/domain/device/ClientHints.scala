package io._3ch.common.domain.device

/** サーバーが受信した UA Client Hints。mobileHeaderPresent は有効な ?0・?1 が届いたか */
final case class ClientHints(
  platform: String = "",
  platformVersion: String = "",
  model: String = "",
  cpuArch: String = "",
  mobile: Boolean = false,
  mobileHeaderPresent: Boolean = false
) {
  /** platform・版・機種・CPUのヒントが全て空か */
  def isEmpty: Boolean = platform.isEmpty && platformVersion.isEmpty && model.isEmpty && cpuArch.isEmpty

  /** ヒントを1つでも受け取っているか */
  def isNotEmpty: Boolean = !isEmpty

  /** iOS・iPadOSを名乗っているか */
  def claimsIos: Boolean = Set("ios", "ipados").contains(DeviceText.lowercase(platform.trim))

  /** 申告と受信ヘッダに矛盾があるか */
  def contradicts(fingerprint: Option[ClientFingerprint], headerUserAgent: String = ""): Boolean =
    contradictionRule(fingerprint, headerUserAgent).nonEmpty

  /** 申告と矛盾する最初の規則。材料が無ければ判定しない */
  def contradictionRule(fingerprint: Option[ClientFingerprint], headerUserAgent: String = ""): Option[ClientHints.Rule] = {
    import ClientHints.Rule
    val platformLower = DeviceText.lowercase(platform.trim)
    val navigatorPlatform = fingerprint.fold("")(fp => DeviceText.lowercase(fp.arch.trim))
    val declared = fingerprint.fold("")(_.os.trim)
    val userAgent = DeviceText.lowercase(if (declared.nonEmpty) declared else headerUserAgent.trim)
    val expectedPrefix = Map("windows" -> "win", "macos" -> "mac").get(platformLower)
    val markers = Map("android" -> List("android"), "windows" -> List("windows"),
      "macos" -> List("mac os", "macintosh"), "linux" -> List("linux", "x11")).get(platformLower)
    if (platformLower.isEmpty) None
    else if (navigatorPlatform.nonEmpty && !claimsIos && List("iphone", "ipad").exists(navigatorPlatform.contains)) Some(Rule.R4)
    else if (navigatorPlatform.nonEmpty && expectedPrefix.exists(prefix => !navigatorPlatform.startsWith(prefix))) Some(Rule.R5)
    else if (userAgent.nonEmpty && markers.exists(values => !values.exists(userAgent.contains))) Some(Rule.R6)
    else if (userAgent.nonEmpty && mobileHeaderPresent && mobile != userAgent.contains("mobile")) Some(Rule.R7)
    else None
  }
}

object ClientHints {
  /** 申告値と受信ヘッダが矛盾した規則 */
  enum Rule { case R4, R5, R6, R7 }

  /** ヘッダの引用符と前後空白を除き、mobile は ?0・?1 だけを有効とする */
  def fromHeaders(platform: String, platformVersion: String, model: String, cpuArch: String, mobile: String): ClientHints = {
    def unquote(raw: String): String = {
      val value = Option(raw).getOrElse("").trim
      if (value.length >= 2 && value.head == '"' && value.last == '"') value.substring(1, value.length - 1).trim else value
    }
    val mobileValue = unquote(mobile)
    ClientHints(unquote(platform), unquote(platformVersion), unquote(model), unquote(cpuArch),
      mobileValue == "?1", mobileValue == "?1" || mobileValue == "?0")
  }
}
