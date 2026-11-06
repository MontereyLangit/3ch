package io._3ch.bbs.domain.posting.moderation

import io._3ch.bbs.domain.posting.ModerationDenial
import io._3ch.common.domain.BbsSettings
import io._3ch.common.domain.account.{AccountRole, Roles}
import io._3ch.common.domain.posting.{PostForm, UserInfo}
import munit.FunSuite

import scala.collection.immutable.ListMap

class ModerationServiceSuite extends FunSuite {
  private val form = PostForm(name = "名前", message = "本文", bbs = "sample")
  private val input = ModerationInput(form, UserInfo("", "192.0.2.1", "", "JP", "", "", "", ""), BypassPoster(), "JP", 2, BbsSettings(ListMap.empty))

  test("saku 板と admin を Tor より先に通し、通常の投稿はルールの評価へ進める") {
    assertEquals(ModerationService.beforeRules(input), None)
    assertEquals(ModerationService.beforeRules(input.copy(country = "T1")), Some(ModerationDecision.Deny(ModerationDenial.Tor, form)))
    assertEquals(ModerationService.beforeRules(input.copy(poster = BypassPoster(cap = true), country = "T1")), None, "キャップは Tor でも拒否せず、行ごとの段階で評価する")
    assertEquals(ModerationService.beforeRules(input.copy(poster = BypassPoster(Roles(Set(AccountRole.Admin))), country = "T1")), Some(ModerationDecision.Pass(form)))
    val saku = form.copy(bbs = "saku")
    assertEquals(ModerationService.beforeRules(input.copy(form = saku, country = "T1")), Some(ModerationDecision.Pass(saku)))
  }

  test("ルールが無いときは現在のフォームを保ち、壊れたときも加工済みフォームを添えて拒否する") {
    val marked = form.copy(message = "加工済み")
    assertEquals(ModerationService.evaluate(input, marked, ModerationRuleSource.Missing), ModerationDecision.Pass(marked))
    assertEquals(ModerationService.evaluate(input, marked, ModerationRuleSource.Broken), ModerationDecision.Deny(ModerationDenial.BrokenRules, marked))
    val staff = input.copy(poster = BypassPoster(Roles(Set(AccountRole.Delete))))
    assertEquals(ModerationService.evaluate(staff, marked, ModerationRuleSource.Broken), ModerationDecision.Pass(marked))
  }

  test("行ごとの回避レベルで免除し、免除されない行だけを評価する") {
    val rules = ModerationRuleSet.parse("<><><>mark<>staff\n<><>本文<>deny\n<><><>deny<>admin").toOption.get
    val cap = input.copy(poster = BypassPoster(cap = true))
    assertEquals(ModerationService.evaluate(cap, form, rules), ModerationDecision.Deny(ModerationDenial.Host, form.copy(name = "</b>[´・ω・｀]<b> 名前", message = "本文 <small>👀 Madakana: Caution</small>")))
    val regulate = input.copy(poster = BypassPoster(Roles(Set(AccountRole.Regulate))))
    assertEquals(ModerationService.evaluate(regulate, form, rules), ModerationDecision.Deny(ModerationDenial.Host, form))
    val admin = input.copy(poster = BypassPoster(Roles(Set(AccountRole.Admin))))
    assertEquals(ModerationService.evaluate(admin, form, rules), ModerationDecision.Pass(form))
    val capRole = input.copy(poster = BypassPoster(Roles(Set(AccountRole.Cap)), authenticated = true, lv = 10))
    assertEquals(ModerationService.evaluate(capRole, form, rules), ModerationDecision.Deny(ModerationDenial.NgWord, form.copy(name = "</b>[´・ω・｀]<b> 名前", message = "本文 <small>👀 Madakana: Caution</small>")))
  }

  test("照合は元の入力で行い、拒否時はそれまでの mark を残す") {
    val marked = form.copy(name = "加工した名前", message = "加工した本文")
    val rules = ModerationRuleSet.parse("<><>本文<>deny").toOption.get
    assertEquals(ModerationService.evaluate(input, marked, ModerationRuleSource.Loaded(rules)), ModerationDecision.Deny(ModerationDenial.NgWord, marked))
    assertEquals(ModerationService.evaluate(input.copy(settings = BbsSettings(ListMap("BBS_NGWORD_PASS" -> "checked"))), marked, rules), ModerationDecision.Pass(marked))
  }

  test("文字参照の復元は数値参照を先に、範囲外はそのまま残す") {
    assertEquals(ModerationService.decodeEntities("&#65;&#X42;&#x1F440;&amp;lt;&lt;&apos;&quot;&gt;"), "AB👀&lt;<'\">")
    assertEquals(ModerationService.decodeEntities("&#0;&#x110000;&#99999999999999999999;&#xd800;&nbsp;"), "&#0;&#x110000;&#99999999999999999999;\ud800&nbsp;")
    assertEquals(ModerationService.decodeEntities("&#38;lt;"), "<")
  }
}
