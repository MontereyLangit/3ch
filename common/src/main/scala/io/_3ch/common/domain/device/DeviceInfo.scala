package io._3ch.common.domain.device

/** 申告されたOS・端末区分・GPUの組合せを照合する */
final case class DeviceInfo(os: String = "", device: String = "", webgl: String = "") {
  /** Mac系とGeForce、携帯端末とデスクトップGPUの組合せを不整合とする */
  def isLogicalMismatched: Boolean = {
    val osLower = DeviceText.lowercase(os)
    val renderer = DeviceText.lowercase(webgl)
    val deviceLower = DeviceText.lowercase(device)
    val appleOs = List("mac", "ios", "iphone", "ipad").exists(osLower.contains)
    val geforce = List("rtx", "gtx", "geforce").exists(renderer.contains)
    val mobile = List("mobile", "phone", "android", "iphone", "ipad").exists(deviceLower.contains)
    val desktopGpu = List("rtx", "gtx", "radeon rx", "intel(r) iris").exists(renderer.contains)
    (appleOs && geforce) || (mobile && desktopGpu)
  }
}
