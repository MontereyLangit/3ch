package io._3ch.bbs.domain.posting

import munit.FunSuite
import io._3ch.bbs.external.web.TextEncoder
import io._3ch.bbs.util.SjisEncoding
import io._3ch.common.domain.posting.{PostForm, PostKind}

import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

/** `FormSanitizer` / `PostRouting` の現仕様固定。 */
class FormSanitizerSuite extends FunSuite {
  private def utf8(fields: (String, String)*): Uint8Array =
    new TextEncoder().encode((fields :+ ("submit" -> "書き込む")).map((k, v) => s"$k=${js.URIUtils.encodeURIComponent(v)}").mkString("&"))

  private def sjis(fields: (String, String)*): Uint8Array = {
    def encode(v: String): String = {
      val bytes = SjisEncoding.encode(v)
      (0 until bytes.length).map(i => f"%%${bytes(i).toInt & 0xff}%02X").mkString
    }
    new TextEncoder().encode((fields :+ ("submit" -> "書き込む")).map((k, v) => s"$k=${encode(v)}").mkString("&"))
  }

  private def decoded(body: Uint8Array, isMonazilla: Boolean = false): PostForm =
    FormSanitizer.decode(body, isMonazilla).fold(left => fail(s"rejected: $left"), identity)

  test("HTML 特殊文字をエスケープし、既存の文字参照の & は残す") {
    val form = decoded(utf8("FROM" -> "a&b\"c<d>e'f", "MESSAGE" -> "&#65;&amp;x"))
    assertEquals(form.name, "a&amp;b&quot;c&lt;d&gt;e&apos;f")
    assertEquals(form.message, "&#65;&amp;x")
  }

  test("タブは空白、改行と改行の文字参照は <br>、その他の制御文字は除く") {
    val form = decoded(utf8("MESSAGE" -> "a\tb\u0001c\r\nd\re\nf&#10;g&#x0D;h&#013;i"))
    assertEquals(form.message, "a bc<br>d<br>e<br>f<br>g<br>h<br>i")
  }

  test("KB-03 の前段: 改行はすべて空白なしの <br> になる（空白を足すのは dat 整形で、最初の 1 か所だけ）") {
    assertEquals(decoded(utf8("MESSAGE" -> "1\n2\n3")).message, "1<br>2<br>3")
  }

  test("名前欄の ★◆＃ は ☆◇# に、メール欄の ＃ は # にする。トリップキーは名前から切り出す") {
    val form = decoded(utf8("FROM" -> "★名◆前＃key＃2", "mail" -> "sage＃pass"))
    assertEquals(form.name, "☆名◇前")
    assertEquals(form.tripKey, "key#2")
    assertEquals(form.mail, "sage#pass")
  }

  test("文字参照直後の # はトリップの区切りにしない") {
    val form = decoded(utf8("FROM" -> "&#65;#key"))
    assertEquals(form.name, "&#65;")
    assertEquals(form.tripKey, "key")
    assertEquals(decoded(utf8("FROM" -> "&#x41#key")).tripKey, "key")
  }

  test("同じ名前の項目は後勝ち、値の 2 個目以降の = は捨てる") {
    val body = new TextEncoder().encode("MESSAGE=first&MESSAGE=a=b&submit=%E6%9B%B8")
    assertEquals(decoded(body).message, "a")
  }

  test("submit が無ければ NoSubmit、Web からの Shift_JIS は SjisFromWeb") {
    assertEquals(FormSanitizer.decode(new TextEncoder().encode("MESSAGE=x"), isMonazilla = false), Left(PostingError.NoSubmit -> PostForm()))
    assertEquals(FormSanitizer.decode(sjis("MESSAGE" -> "x"), isMonazilla = false), Left(PostingError.SjisFromWeb -> PostForm(isSJIS = true)))
  }

  test("Monazilla の Shift_JIS は復号する") {
    val form = decoded(sjis("MESSAGE" -> "日本語", "FROM" -> "名前"), isMonazilla = true)
    assertEquals(form.message, "日本語")
    assertEquals(form.name, "名前")
    assert(form.isSJIS)
  }

  test("トリップキーが 1023 文字を超えると、切り出し後のフォームを添えて拒否する") {
    val result = FormSanitizer.decode(utf8("FROM" -> ("n#" + "k" * 1024)), isMonazilla = false)
    assertEquals(result.left.map((error, form) => (error, form.name)), Left((PostingError.TripKeyTooLong, "n")))
  }

  private val base = PostForm(message = "本文", subject = "題名", bbs = "sample", time = "1")

  test("スレ立ては key を unixtime に、返信は題名を空にする") {
    assertEquals(PostRouting.route(base, "1700000000").map(f => (f.key, f.kind)), Right(("1700000000", PostKind.NewThread)))
    assertEquals(PostRouting.route(base.copy(key = "123"), "1700000000").map(f => (f.key, f.subject, f.kind)), Right(("123", "", PostKind.Reply)))
  }

  test("!newthread は 9 始まりの key でだけスレ立てにする") {
    val routed = PostRouting.route(base.copy(subject = "", key = "9123", message = "!newthread題名<br>本文"), "1700000000")
    assertEquals(routed.map(f => (f.subject, f.message, f.key)), Right(("題名", "<br>本文", "1700000000")))
    assertEquals(PostRouting.route(base.copy(subject = "", key = "8123", message = "!newthread題名"), "1").map(_.key), Right("8123"))
  }

  test("板名・key・time の不正な文字を除き、924 / 900 始まりの key は 924 板にする") {
    val routed = PostRouting.route(base.copy(bbs = "sa!mple", key = "90a1", time = "1x2"), "1")
    assertEquals(routed.map(f => (f.bbs, f.key, f.time)), Right(("924", "901", "12")))
  }

  test("題名・名前・メール・submit の <br> は前後の空白ごと除く") {
    val routed = PostRouting.route(base.copy(subject = "a <br> b", name = "c<br>d", mail = "e <br>f", submit = "g<br> h"), "1")
    assertEquals(routed.map(f => (f.subject, f.name, f.mail, f.submit)), Right(("ab", "cd", "ef", "gh")))
  }

  test("必須項目の検査は 禁止板 → 本文 → 題名と key → time の順") {
    def error(form: PostForm): Option[PostingError] = PostRouting.route(form, "1").left.toOption.map(_._1)
    assertEquals(error(base.copy(bbs = "test", message = "")), Some(PostingError.ForbiddenBoard))
    assertEquals(error(base.copy(message = "", subject = "")), Some(PostingError.EmptyMessage))
    assertEquals(error(base.copy(subject = "", key = "")), Some(PostingError.MissingSubject("")))
    assertEquals(error(base.copy(time = "")), Some(PostingError.InvalidTime("")))
  }
}
