package ch3s.common.domain

class SubjectTextSuite extends munit.FunSuite {

  test("文字参照は一度だけ戻し、数値文字参照もテキストとして安全に扱う") {
    assertEquals(SubjectText.decode("&amp;lt; &#128512; &#x3c; &#999999999999;"), "&lt; 😀 < &#999999999999;")
  }
}
