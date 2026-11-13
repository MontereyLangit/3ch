package io._3ch.bbs.domain.account

import io._3ch.bbs.repository.EdgeRuleRepository
import scala.scalajs.js

class EdgeDefenseSuite extends munit.FunSuite {
  private val now = 2000000000d
  private val rules = EdgeRules(EdgeMode.On, Set("16509"), 400, Vector(TlsRule(new js.RegExp("Chrome/"), 500, 2000)))
  private val home = EdgeSignal("2516", "JP", Some(30), Some(1800), "Mozilla/5.0 Chrome/130.0")

  test("データセンターの AS は拒否し、TLS の矛盾はブラウザ向けのエンドポイントでだけ見る") {
    assertEquals(EdgeDefense.check(rules, home, browser = true, exempted = false), EdgeDecision(Vector.empty, reject = false, strict = false))
    assertEquals(EdgeDefense.check(rules, home.copy(asn = "16509"), browser = false, exempted = false).rejection, Some(EdgeFinding.Datacenter))
    val mismatch = home.copy(tlsHelloLength = Some(300))
    assertEquals(EdgeDefense.check(rules, mismatch, browser = true, exempted = false).rejection, Some(EdgeFinding.DeclarationMismatch))
    assertEquals(EdgeDefense.check(rules, mismatch, browser = false, exempted = false).findings, Vector.empty)
    assertEquals(EdgeDefense.check(rules, mismatch.copy(userAgent = "Firefox/130"), browser = true, exempted = false).findings, Vector.empty)
    assertEquals(EdgeDefense.check(rules, home.copy(tlsHelloLength = None), browser = true, exempted = false).findings, Vector.empty)
  }

  test("試行モードは見つけても拒否せず、免除された投稿者は見ない") {
    val logged = EdgeDefense.check(rules.copy(mode = EdgeMode.Log), home.copy(asn = "16509"), browser = true, exempted = false)
    assertEquals((logged.findings, logged.reject, logged.rejection), (Vector(EdgeFinding.Datacenter), false, None))
    assertEquals(EdgeDefense.check(rules, home.copy(asn = "16509"), browser = true, exempted = true).findings, Vector.empty)
  }

  test("国内で RTT が閾値を超える認証は拒否せず乱造検知を厳しくする") {
    assert(EdgeDefense.check(rules, home.copy(rttMs = Some(401)), browser = true, exempted = false).strict)
    assert(!EdgeDefense.check(rules, home.copy(rttMs = Some(400)), browser = true, exempted = false).strict)
    assert(!EdgeDefense.check(rules, home.copy(rttMs = Some(900), country = "US"), browser = true, exempted = false).strict)
    assert(!EdgeDefense.check(rules, home.copy(rttMs = Some(900)), browser = false, exempted = false).strict)
  }

  test("試行モードと規則なしでは RTT が閾値を超えても乱造検知を厳しくしない") {
    val slow = home.copy(rttMs = Some(900))
    assertEquals(EdgeDefense.check(rules.copy(mode = EdgeMode.Log), slow, browser = true, exempted = false), EdgeDecision(Vector.empty, reject = false, strict = false))
    assertEquals(EdgeDefense.check(EdgeRules.empty, slow, browser = true, exempted = false), EdgeDecision(Vector.empty, reject = false, strict = false))
    assertEquals(EdgeDefense.check(rules.copy(mode = EdgeMode.Log), slow.copy(asn = "16509", tlsHelloLength = Some(300)), browser = true, exempted = false).strict, false)
  }

  test("免除は Lv3 以上で作成から7日以上") {
    assert(EdgeDefense.exempt(3, now - 7 * 86400, now))
    assert(!EdgeDefense.exempt(3, now - 7 * 86400 + 1, now))
    assert(!EdgeDefense.exempt(2, now - 30 * 86400, now))
    assert(!EdgeDefense.exempt(10, 0, now))
  }

  test("edge-rules.json を読み、知らない値は既定にし、壊れていれば規則なしにする") {
    val parsed = EdgeRuleRepository.parse("""{"mode":"on","datacenterAsns":[16509,"14618","x"],"rttMs":300,"tls":[{"ua":"Chrome/","min":500,"max":2000}]}""")
    assertEquals((parsed.mode, parsed.datacenterAsns, parsed.rttMs, parsed.tls.size), (EdgeMode.On, Set("16509", "14618"), 300d, 1))
    assertEquals(EdgeRuleRepository.parse("""{"mode":"block"}""").mode, EdgeMode.Log)
    assertEquals(EdgeRuleRepository.parse("{broken"), EdgeRules.empty)
    assertEquals(EdgeRuleRepository.parse("""{"tls":[{"ua":"(","min":1,"max":2}]}"""), EdgeRules.empty)
  }
}
