package io._3ch.common.domain.device

import io._3ch.common.golden.{GoldenFile, Json, JsonValue}
import munit.FunSuite

class DeviceFingerprintSuite extends FunSuite {
  private def decode(value: JsonValue): Option[ClientFingerprint] = {
    if (value.isNull) None
    else {
      val fields = value.asObj
      def text(key: String): String = fields.get(key).fold("")(_.asString)
      def integer(key: String, default: Int = 0): Int = fields.get(key).fold(default)(_.asInt)
      val dpr = fields.get("dpr").fold(1d) {
        case JsonValue.Num(number) => number
        case JsonValue.Str(number) => number.toDouble
        case other => fail(s"invalid DPR: $other")
      }
      val touch = fields.get("touch").contains(JsonValue.Bool(true))
      Some(ClientFingerprint(text("os"), integer("cores"), integer("ram"), text("screen"), dpr, integer("colorDepth", 24),
        text("webgl"), text("fonts"), touch, text("arch")))
    }
  }

  test("原本の9軸・UTF-16・DPR丸めを固定表と照合する") {
    Json.parse(GoldenFile.readText("device-fingerprint-reference.json")).field("cases").asArr.foreach { row =>
      assertEquals(DeviceFingerprintHasher.hash(decode(row.field("fingerprint")), row.field("model").asString),
        row.field("expected").asString, row.field("name").asString)
    }
  }

  test("申告8軸が全欠損なら機種名だけでハッシュを作らない") {
    assertEquals(DeviceFingerprintHasher.hash(None, "Pixel 9"), "")
    assertEquals(DeviceFingerprintHasher.hash(ClientFingerprint(ram = 8, os = "iPhone"), "Pixel 9"), "")
    val values = Vector(ClientFingerprint(screen = "393x852"), ClientFingerprint(dpr = 3), ClientFingerprint(cores = 6),
      ClientFingerprint(touch = true), ClientFingerprint(colorDepth = 30), ClientFingerprint(arch = "iPhone"),
      ClientFingerprint(fonts = "101"), ClientFingerprint(webgl = "Apple GPU"))
    values.foreach(fp => assertEquals(DeviceFingerprintHasher.hash(fp).length, 18))
  }

  test("Apple GPUとタッチはUAやヒントより先に粒度を決める") {
    val ios = ClientFingerprint(os = "iPhone", touch = true, webgl = "Apple GPU")
    assertEquals(DeviceGranularity.detect(Some(ios), ClientHints(platform = "Windows")), DeviceGranularity.Class)
    assertEquals(DeviceGranularity.detect(Some(ios.copy(os = "custom"))), DeviceGranularity.Class)
    assertEquals(DeviceGranularity.detect(Some(ios.copy(webgl = "Adreno"))), DeviceGranularity.Personal)
    assertEquals(DeviceGranularity.detect(Some(ios.copy(webgl = "")), ClientHints(platform = "iPadOS")), DeviceGranularity.Class)
    assertEquals(DeviceGranularity.detect(Some(ios.copy(webgl = "")), ClientHints(platform = "Android")), DeviceGranularity.Personal)
    assertEquals(DeviceGranularity.detect(Some(ClientFingerprint(webgl = "Adreno"))), DeviceGranularity.Unknown)
    assertEquals(DeviceGranularity.detect(Some(ClientFingerprint(os = "Macintosh", webgl = "Apple M2"))), DeviceGranularity.Personal)
    assertEquals(DeviceGranularity.detect(Some(ClientFingerprint(os = "Macintosh", touch = true))), DeviceGranularity.Class)
  }

  test("新旧の端末保存値は粒度を問わず照合に使える") {
    val keyHash = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
    for (identifier <- Seq("0123456789abcdef", "0123456789abcdef01"); grain <- DeviceGranularity.values) {
      val ref = DeviceHashRef(grain, identifier, keyHash)
      assertEquals(DeviceHashRef.parse(ref.format), Some(ref))
      assertEquals(ref.individualIdentifier, identifier)
      assertEquals(DeviceHashRef.normalizeIdentifier(s"${grain.code}$identifier"), identifier)
      assertEquals(DeviceHashRef.parse(s"$identifier:$keyHash"), Some(ref.copy(granularity = DeviceGranularity.Unknown)))
      assertEquals(DeviceHashRef.parse(s"${grain.code}$identifier"), Some(DeviceHashRef(grain, identifier)))
      assertEquals(DeviceHashRef(grain, identifier).format, s"${grain.code}$identifier")
    }
    Seq("", "p0123456789abcde", s"p0123456789ABCDEF01:$keyHash", s"p0123456789abcdef01:$keyHash:extra",
      s"x0123456789abcdef01:$keyHash", "p0123456789abcdef01:short").foreach(raw => assertEquals(DeviceHashRef.parse(raw), None))
    assertEquals(DeviceHashRef.parse(null), None)
  }

  test("論理破綻とiOS既知プロファイルの最小版を照合する") {
    val ios = ClientFingerprint(os = "iPhone; CPU iPhone OS 15_0 like Mac OS X", screen = "393x852", dpr = 3)
    val profiles = Vector(IosDeviceProfile("393x852", 3, 16), IosDeviceProfile("393x852", 3, 17))
    assertEquals(DeviceFingerprintCheck.validate(Some(ios), ClientHints(), ios.os, profiles), Left(DeviceRejection.Fingerprint))
    assert(!DeviceFingerprintCheck.iosProfileMismatched(ios.copy(os = "iPhone; CPU iPhone OS 16_0 like Mac OS X"), profiles))
    assert(!DeviceFingerprintCheck.iosProfileMismatched(ios, profiles :+ IosDeviceProfile("393x852", 3, 15)))
    assert(!DeviceFingerprintCheck.iosProfileMismatched(ios.copy(screen = "400x900"), profiles))
    assert(!DeviceFingerprintCheck.iosProfileMismatched(ios.copy(os = "Macintosh"), profiles))
    assert(!DeviceFingerprintCheck.iosProfileMismatched(ios, Vector.empty))
    assert(DeviceInfo("Macintosh", "", "GeForce RTX 3080").isLogicalMismatched)
    assert(DeviceInfo("Android", "Android mobile", "Radeon RX 6800").isLogicalMismatched)
    assert(!DeviceInfo("Windows", "desktop", "GeForce RTX 3080").isLogicalMismatched)
  }
}
