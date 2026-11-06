package io._3ch.common.domain.account

import munit.FunSuite

class OAuthStateSuite extends FunSuite {
  private val random = "A" * 43
  private val state = OAuthState(random, "B" * 43, "C" * 64, "p" + "0" * 18, "/sample/?q=1", popup = true, Some("taro_2026"), agreed = true, 2000000600L)
  private val signature = "A" * 22

  test("書式を確かめて往復させ、署名の対象は署名の手前までにする") {
    val encoded = OAuthStateCodec.encode(state, signature).get
    val parsed = OAuthStateCodec.parse(encoded).get
    assertEquals(parsed.state, state)
    assertEquals(parsed.payload, OAuthStateCodec.payload(state).get)
    assertEquals(OAuthStateCodec.fromHeader(s"a=b; ${OAuthStateCodec.Name}=$encoded").map(_.state), Some(state))
    assertEquals(OAuthStateCodec.fromHeader(s"${OAuthStateCodec.Name}=$encoded; ${OAuthStateCodec.Name}=$encoded"), None)
    val login = state.copy(uid = None, popup = false, agreed = false, device = "")
    assertEquals(OAuthStateCodec.parse(OAuthStateCodec.encode(login, signature).get).map(_.state), Some(login))
  }

  test("期限・乱数・戻り先・ユーザー ID の書式に合わない値は扱わない") {
    assert(state.isCurrent(2000000599))
    assert(!state.isCurrent(2000000600))
    Seq(state.copy(state = "short"), state.copy(returnPath = "//evil.example/"), state.copy(returnPath = "/a|b"),
      state.copy(uid = Some("UPPER")), state.copy(device = "x"), state.copy(nonce = "B" * 42 + "|")).foreach { value =>
      assertEquals(OAuthStateCodec.payload(value), None, value.toString)
    }
    val encoded = OAuthStateCodec.encode(state, signature).get
    assertEquals(OAuthStateCodec.parse(encoded.replace("|1|taro", "|2|taro")), None)
    assertEquals(OAuthStateCodec.parse(encoded + "|extra"), None)
    assertEquals(OAuthStateCodec.encode(state, "bad"), None)
  }
}
