package io._3ch.common.domain.device

/** 端末の8軸と受信した機種名から、各軸2桁の18桁ハッシュを作る。OS・RAMは含めない */
object DeviceFingerprintHasher {
  final val Length: Int = 18
  private val Missing = ClientFingerprint()

  /** 申告8軸が全て欠損なら空文字。機種名だけではハッシュを作らない */
  def hash(fingerprint: Option[ClientFingerprint], model: String = ""): String = fingerprint.fold("")(hash(_, model))

  /** 欠損した軸は既定値のまま使い、機種名の前後空白と大小文字は正規化する */
  def hash(fingerprint: ClientFingerprint): String = hash(fingerprint, "")

  /** 受信した機種名を第9軸に加える */
  def hash(fingerprint: ClientFingerprint, model: String): String = {
    val fp = fingerprint
    val missing = fp.screen == Missing.screen && fp.dpr == Missing.dpr && fp.cores == Missing.cores &&
      fp.touch == Missing.touch && fp.colorDepth == Missing.colorDepth && fp.arch == Missing.arch &&
      fp.fonts == Missing.fonts && fp.webgl == Missing.webgl
    if (missing) ""
    else List(fp.screen, dprToken(fp.dpr), fp.cores.toString, fp.touch.toString, fp.colorDepth.toString,
      fp.arch, fp.fonts, fp.webgl, DeviceText.lowercase(model.trim)).map(axis).mkString
  }

  private def dprToken(dpr: Double): String = {
    if (dpr.isNaN || dpr.isInfinity) "0"
    else {
      val rounded = math.floor(dpr * 100d + 0.5d)
      if (rounded >= Int.MaxValue.toDouble) Int.MaxValue.toString
      else if (rounded <= Int.MinValue.toDouble) Int.MinValue.toString
      else rounded.toInt.toString
    }
  }

  private def axis(value: String): String = {
    val hash = value.foldLeft(0x811c9dc5L) { (acc, char) => ((acc ^ char.toLong) * 0x01000193L) & 0xffffffffL }
    ("00000000" + java.lang.Long.toHexString(hash)).takeRight(8).take(2)
  }
}
