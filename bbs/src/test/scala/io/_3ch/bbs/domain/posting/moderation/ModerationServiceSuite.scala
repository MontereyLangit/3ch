package io._3ch.bbs.domain.posting.moderation

import io._3ch.bbs.domain.posting.ModerationDenial
import io._3ch.common.domain.BbsSettings
import io._3ch.common.domain.posting.{PostForm, UserInfo}
import munit.FunSuite

import scala.collection.immutable.ListMap

class ModerationServiceSuite extends FunSuite {
  private val form = PostForm(name = "名前", message = "本文", bbs = "sample")
  private val input = ModerationInput(form, UserInfo("", "192.0.2.1", "", "JP", "", "", "", ""), false, "JP", 2, BbsSettings(ListMap.empty))

  test("免除を Tor より先に判定し、通常の投稿はルールの評価へ進める") {
    assertEquals(ModerationService.beforeRules(input), None)
    assertEquals(ModerationService.beforeRules(input.copy(country = "T1")), Some(ModerationDecision.Deny(ModerationDenial.Tor, form)))
    assertEquals(ModerationService.beforeRules(input.copy(cap = true, country = "T1")), Some(ModerationDecision.Pass(form)))
    val saku = form.copy(bbs = "saku")
    assertEquals(ModerationService.beforeRules(input.copy(form = saku, country = "T1")), Some(ModerationDecision.Pass(saku)))
  }

  test("ルールが無いときは現在のフォームを保ち、壊れたときも加工済みフォームを添えて拒否する") {
    val marked = form.copy(message = "加工済み")
    assertEquals(ModerationService.evaluate(input, marked, ModerationRuleSource.Missing), ModerationDecision.Pass(marked))
    assertEquals(ModerationService.evaluate(input, marked, ModerationRuleSource.Broken), ModerationDecision.Deny(ModerationDenial.BrokenRules, marked))
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
