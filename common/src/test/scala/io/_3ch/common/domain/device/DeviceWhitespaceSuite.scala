package io._3ch.common.domain.device

import io._3ch.common.golden.{GoldenFile, Json, JsonValue}
import munit.FunSuite

class DeviceWhitespaceSuite extends FunSuite {
  private lazy val golden = Json.parse(GoldenFile.readText("device-whitespace-reference.json"))
  private def rows(name: String): List[JsonValue] = golden.field(name).asArr
  private def bool(value: JsonValue): Boolean = value == JsonValue.Bool(true)

  test("KotlinJS原本の空白28文字を全BMP文字の境界と照合する") {
    val whitespace = rows("whitespace").map(_.asInt).toSet
    assertEquals(whitespace.size, 28)
    (0 to 0xffff).foreach { code =>
      val value = code.toChar.toString
      assertEquals(DeviceStrings.trim(value), if (whitespace.contains(code)) "" else value, f"U+$code%04X")
    }
    rows("nonWhitespace").foreach { value =>
      val char = value.asInt.toChar.toString
      assertEquals(DeviceStrings.trim(char + "middle" + char), char + "middle" + char)
    }
    val padding = rows("whitespace").map(_.asInt.toChar).mkString
    assertEquals(DeviceStrings.trim(padding + "left middle right" + padding), "left middle right")
  }

  test("モデルのUnicode空白だけを原本通りに取り除いて9軸ハッシュを作る") {
    val base = ClientFingerprint(os = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/140.0.0.0", cores = 8, ram = 8,
      screen = "1920x1080", dpr = 1, colorDepth = 24, webgl = "ANGLE (NVIDIA GeForce RTX 3080)",
      fonts = "Arial,Courier,Georgia", touch = false, arch = "Win32")
    rows("fingerprint").foreach { row =>
      assertEquals(DeviceFingerprintHasher.hash(base, row.field("model").asString), row.field("expected").asString, row.field("name").asString)
    }
  }

  test("引用符の内外のUnicode空白とUAの代替材料を原本通りに照合する") {
    rows("hints").foreach { row =>
      def text(key: String): String = row.field(key).asString
      val hints = ClientHints.fromHeaders(text("platform"), "\u00a0\"\u300015.0.0\u3000\"\u00a0",
        "\u00a0\"\u3000Pixel 9\u3000\"\u00a0", "\u00a0\"\u3000arm\u3000\"\u00a0", text("mobile"))
      assertEquals(hints.platform, text("decodedPlatform"), text("name"))
      assertEquals(hints.platformVersion, "15.0.0")
      assertEquals(hints.model, "Pixel 9")
      assertEquals(hints.cpuArch, "arm")
      assertEquals(hints.mobile, bool(row.field("decodedMobile")), text("name"))
      assertEquals(hints.mobileHeaderPresent, bool(row.field("mobilePresent")), text("name"))
      val fingerprint = Some(ClientFingerprint(os = text("ua"), arch = text("arch")))
      assertEquals(hints.contradictionRule(fingerprint, text("headerUa")).fold("")(_.toString), text("rule"), text("name"))
    }
  }

  test("UAの完全一致と材料欠損の判定にKotlinの空白範囲を使う") {
    rows("declarations").foreach { row =>
      assertEquals(ClientDeclarationCheck.userAgentMismatched(row.field("header").asString,
        Some(ClientFingerprint(os = row.field("declared").asString))), bool(row.field("mismatched")), row.field("name").asString)
    }
  }

  test("粒度の欠損判定とiOSヒントもUnicode空白を除いて照合する") {
    rows("granularity").foreach { row =>
      val fingerprint = Some(ClientFingerprint(os = row.field("ua").asString, touch = bool(row.field("touch")), webgl = row.field("webgl").asString))
      val hints = ClientHints(platform = row.field("platform").asString)
      assertEquals(DeviceGranularity.detect(fingerprint, hints).code.toString, row.field("expected").asString, row.field("name").asString)
    }
  }

  test("粒度付き新形式と公開鍵付き旧形式の前後空白を原本通りに読む") {
    rows("refs").foreach { row =>
      val expected = if (row.field("expected").isNull) None else Some(row.field("expected").asString)
      assertEquals(DeviceHashRef.parse(row.field("stored").asString).map(_.format), expected, row.field("name").asString)
    }
  }
}
