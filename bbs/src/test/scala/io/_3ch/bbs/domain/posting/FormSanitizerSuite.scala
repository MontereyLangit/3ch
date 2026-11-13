package io._3ch.bbs.domain.posting

import munit.FunSuite

/** フォーム値の整形と名前欄のコマンド */
class FormSanitizerSuite extends FunSuite {
  test("HTML 特殊文字をエスケープし、数値文字参照の & は残す（名前付き参照の & はエスケープする）") {
    val form = FormSanitizer.fromFields(Map("FROM" -> "a&b\"c<d>e'f", "MESSAGE" -> "&#65;&amp;x"))
    assertEquals(form.name, "a&amp;b&quot;c&lt;d&gt;e&apos;f")
    assertEquals(form.message, "&#65;&amp;amp;x")
  }

  test("タブは空白、改行と改行の文字参照は <br>、その他の制御文字は除く") {
    val form = FormSanitizer.fromFields(Map("MESSAGE" -> "a\tb\u0001c\r\nd\re\nf&#10;g&#x0D;h&#013;i"))
    assertEquals(form.message, "a bc<br>d<br>e<br>f<br>g<br>h<br>i")
  }

  test("改行はすべて空白なしの <br> になる") {
    assertEquals(FormSanitizer.fromFields(Map("MESSAGE" -> "1\n2\n3")).message, "1<br>2<br>3")
  }

  test("名前欄の ★◆＃ は ☆◇# に、メール欄の ＃ は # にする。名前の # 以降（旧トリップ）は捨てる") {
    val form = FormSanitizer.fromFields(Map("FROM" -> "★名◆前＃key＃2", "mail" -> "sage＃pass"))
    assertEquals(form.name, "☆名◇前")
    assertEquals(form.mail, "sage#pass")
    assertEquals(FormSanitizer.fromFields(Map("FROM" -> ("n#" + "k" * 2000))).name, "n")
  }

  test("名前は最初の # で切る（数値文字参照の # でも切れる）") {
    assertEquals(FormSanitizer.fromFields(Map("FROM" -> "&#65;#key")).name, "&")
    assertEquals(FormSanitizer.fromFields(Map("FROM" -> "a&#x41#key")).name, "a&amp")
  }

  test("名前欄の !id / !no と showid 欄を読み取る") {
    val form = FormSanitizer.fromFields(Map("FROM" -> " 名前!id !no "))
    assertEquals((form.name, form.showId, form.noNusi), ("名前", true, true))
    assertEquals(FormSanitizer.fromFields(Map("FROM" -> "名前", "showid" -> "1")).showId, true)
    assertEquals(FormSanitizer.fromFields(Map("FROM" -> "!idx")).showId, false)
  }
}
