package io._3ch.common.domain.device

import io._3ch.common.golden.{GoldenFile, Json}
import munit.FunSuite

class ClientHintsSuite extends FunSuite {
  test("原本のR4からR7と材料ごとのfail-openを固定表と照合する") {
    Json.parse(GoldenFile.readText("client-hints-reference.json")).field("cases").asArr.foreach { row =>
      def text(key: String): String = row.field(key).asString
      val hints = ClientHints.fromHeaders(text("platform"), "", "", "", text("mobile"))
      val fp = Some(ClientFingerprint(os = text("ua"), arch = text("arch")))
      assertEquals(hints.contradictionRule(fp, text("headerUa")).fold("")(_.toString), text("rule"), text("name"))
    }
  }

  test("ヘッダの引用符を取り除きCPUヒントとnavigator.platformを混同しない") {
    val hints = ClientHints.fromHeaders(" \"Windows\" ", "\"15.0.0\"", "\" Pixel 9 \"", "\"arm\"", "?0")
    assertEquals(hints, ClientHints("Windows", "15.0.0", "Pixel 9", "arm", false, true))
    assertEquals(hints.contradictionRule(Some(ClientFingerprint(os = "Windows", arch = "Win32"))), None)
    Seq("", "0", "1", "true", "?2").foreach { raw =>
      assert(!ClientHints.fromHeaders("Android", "", "", "", raw).mobileHeaderPresent)
    }
  }

  test("UA申告はtrim後に大小文字も完全一致させる") {
    val ua = "Mozilla/5.0 Android Mobile"
    assert(!ClientDeclarationCheck.userAgentMismatched(s" $ua ", Some(ClientFingerprint(os = s"\t$ua\n"))))
    assert(ClientDeclarationCheck.userAgentMismatched(ua, Some(ClientFingerprint(os = ua.toUpperCase))))
    assert(!ClientDeclarationCheck.userAgentMismatched(ua, None))
    assert(!ClientDeclarationCheck.userAgentMismatched("", Some(ClientFingerprint(os = ua))))
    assert(!ClientDeclarationCheck.userAgentMismatched(ua, Some(ClientFingerprint(os = " "))))
  }
}
