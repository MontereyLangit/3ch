package io._3ch.common.domain.device

import java.util.Locale
import munit.FunSuite

class DeviceLocaleSuite extends FunSuite {
  test("トルコ語の既定ロケールでも端末ハッシュとヒント照合は変わらない") {
    val previous = Locale.getDefault
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"))
      val fp = ClientFingerprint(os = "WINDOWS", screen = "1920x1080", arch = "WIN32")
      val hints = ClientHints(platform = "WINDOWS", model = "PIXEL 9")
      assertEquals(hints.contradictionRule(Some(fp)), None)
      assertEquals(DeviceGranularity.detect(Some(fp), hints), DeviceGranularity.Personal)
      assertEquals(DeviceFingerprintHasher.hash(fp, "PIXEL 9"), DeviceFingerprintHasher.hash(fp, "pixel 9"))
      assertEquals(DeviceText.lowercase("İ Σ ΟΣ"), "i\u0307 σ ος")
    } finally {
      Locale.setDefault(previous)
    }
  }
}
