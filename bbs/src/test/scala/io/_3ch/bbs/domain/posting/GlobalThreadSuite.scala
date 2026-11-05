package io._3ch.bbs.domain.posting

import munit.FunSuite
import io._3ch.bbs.domain.posting.GlobalThread.Kind

class GlobalThreadSuite extends FunSuite {
  test("kindOf: 先頭3桁で 924 / 900 を判定し、それ以外は None") {
    assertEquals(GlobalThread.kindOf("9241700000"), Some(Kind.CapOnly))
    assertEquals(GlobalThread.kindOf("9001700000"), Some(Kind.Open))
    assertEquals(GlobalThread.kindOf("1700000000"), None)
    assertEquals(GlobalThread.kindOf(""), None)
  }

  test("command: 本文先頭の !924 / !900 を読み、直後の空白と<br>を除く") {
    assertEquals(GlobalThread.command("!924 本文"), Some((Kind.CapOnly, "本文")))
    assertEquals(GlobalThread.command("!900<br> <br>本文<br>次"), Some((Kind.Open, "本文<br>次")))
    assertEquals(GlobalThread.command("!924"), Some((Kind.CapOnly, "")))
    assertEquals(GlobalThread.command("!9240 本文"), None)
    assertEquals(GlobalThread.command(" !924 本文"), None)
    assertEquals(GlobalThread.command("本文"), None)
  }

  test("key: unixtime の先頭3桁を接頭辞に置き換える") {
    assertEquals(GlobalThread.key(Kind.CapOnly, "1700000000"), "9240000000")
    assertEquals(GlobalThread.key(Kind.Open, "1761990123"), "9001990123")
  }

  test("canPost: 924 は★のみ、900 は誰でも") {
    assertEquals(GlobalThread.canPost(Kind.CapOnly, cap = true), true)
    assertEquals(GlobalThread.canPost(Kind.CapOnly, cap = false), false)
    assertEquals(GlobalThread.canPost(Kind.Open, cap = false), true)
  }

  test("mergeSubject: 924 → 900 → 板固有の順で、各群の並びは保つ") {
    val global = Seq("9000000003.dat<>c (1)", "9240000002.dat<>b (5)", "9000000001.dat<>a (2)", "9240000000.dat<>z (1)")
    val own = Seq("1800000010.dat<>own1 (3)", "9240000002.dat<>dup (1)", "1700000000.dat<>own2 (1)")
    assertEquals(GlobalThread.mergeSubject(global, own), Seq(
      "9240000002.dat<>b (5)", "9240000000.dat<>z (1)",
      "9000000003.dat<>c (1)", "9000000001.dat<>a (2)",
      "1800000010.dat<>own1 (3)", "1700000000.dat<>own2 (1)"
    ))
    assertEquals(GlobalThread.mergeSubject(Nil, own), Seq("1800000010.dat<>own1 (3)", "1700000000.dat<>own2 (1)"))
    assertEquals(GlobalThread.mergeSubject(global, Nil).size, 4)
  }
}
