package io._3ch.common.domain.account

import munit.FunSuite

class EmailAddressSuite extends FunSuite {
  private def parse(raw: String) = EmailAddress.parse(raw).toOption.get

  test("確認画面用の伏せ字はローカル部の先頭 1 文字とドメインだけを出す") {
    assertEquals(EmailAddress.mask("taro.yamada@example.com"), "t***@example.com")
    assertEquals(EmailAddress.mask("a@b.jp"), "a***@b.jp")
    assertEquals(EmailAddress.mask("no-at-sign"), "")
  }

  test("前後の空白を除いて小文字にし、送信先と照合値を分ける") {
    val email = parse("  Taro.Yamada+bbs@Example.COM ")
    assertEquals((email.address, email.canonical, email.domain), ("taro.yamada+bbs@example.com", "taro.yamada+bbs@example.com", "example.com"))
  }

  test("gmail.com と googlemail.com はローカル部のドットと + 以降を除いて gmail.com として照合する") {
    assertEquals(parse("T.a.r.o+x@googlemail.com").canonical, "taro@gmail.com")
    assertEquals(parse("taro@gmail.com").canonical, "taro@gmail.com")
    assertEquals(parse("T.a.r.o+x@googlemail.com").address, "t.a.r.o+x@googlemail.com")
  }

  test("国際化ドメイン名は punycode にする") {
    assertEquals(parse("info@日本.jp").address, "info@xn--wgv71a.jp")
  }

  test("書式に合わないアドレスは拒否する") {
    Seq("", "no-at", "@example.com", "taro@", "taro@localhost", "ta ro@example.com", "taro..x@example.com", ".taro@example.com",
      "taro@exa mple.com", "taro@-example.com", "taro@example.com/evil", "a" * 65 + "@example.com", "taro@" + "a" * 250 + ".com", null).foreach { raw =>
      assertEquals(EmailAddress.parse(raw), Left(EmailRejection.Invalid), String.valueOf(raw))
    }
  }

  test("使い捨てのドメインとそのサブドメインを拒否し、空行と # の行は読み飛ばす") {
    val domains = DisposableDomains.parse("# list\n\nMailinator.com\r\nyopmail.com\n")
    assert(domains.blocks(parse("x@mailinator.com")))
    assert(domains.blocks(parse("x@sub.mailinator.com")))
    assert(!domains.blocks(parse("x@notmailinator.com")))
    assert(!DisposableDomains.empty.blocks(parse("x@mailinator.com")))
  }

  test("メールのリンクのトークンは aid と 43 文字の乱数だけを受け付ける") {
    val secret = "A" * 42 + "_"
    assertEquals(PendingLink.parse("abcdefghijklmnop." + secret), Some(PendingLink("abcdefghijklmnop", secret)))
    assertEquals(PendingLink("abcdefghijklmnop", secret).text, "abcdefghijklmnop." + secret)
    Seq("abcdefghijklmnop." + secret + "A", "ABCDEFGHIJKLMNOP." + secret, "abcdefghijklmnop" + secret, null).foreach { raw =>
      assertEquals(PendingLink.parse(raw), None)
    }
  }
}
