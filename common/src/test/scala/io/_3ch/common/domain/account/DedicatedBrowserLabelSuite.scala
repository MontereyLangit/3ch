package io._3ch.common.domain.account

class DedicatedBrowserLabelSuite extends munit.FunSuite {
  test("製品名と版、既知の OS 名だけを返す") {
    assertEquals(DedicatedBrowserLabel.of("Monazilla/1.00 (JaneStyle/4.23; Windows NT 10.0; private-value)"), "JaneStyle 4.23 · Windows")
  }
  test("不明な UA は元の UA を表示せず既定の文言を返す") {
    assertEquals(DedicatedBrowserLabel.of("Monazilla/1.00 secret-device-value"), "専用ブラウザ")
  }
  test("macOS と Linux の既知の表記を読む") {
    assertEquals(DedicatedBrowserLabel.of("Monazilla/1.00 (BathyScaphe/3.1; Macintosh)"), "BathyScaphe 3.1 · macOS")
    assertEquals(DedicatedBrowserLabel.of("Monazilla/1.00 (JD/2.8; Linux)"), "JD 2.8 · Linux")
  }
}
