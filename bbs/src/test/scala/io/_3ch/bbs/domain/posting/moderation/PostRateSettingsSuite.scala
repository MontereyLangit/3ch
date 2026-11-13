package io._3ch.bbs.domain.posting.moderation

import io._3ch.bbs.domain.posting.moderation.PostRateLimit.{BoardSettings, ThreadSettings}
import io._3ch.common.domain.BbsSettings
import munit.FunSuite

class PostRateSettingsSuite extends FunSuite {
  private def parse(text: String, separator: String = "\n"): BbsSettings = BbsSettings.parse(text, separator)
  private val none = parse("")

  test("設定が無ければ既定値で、段階は規制、広域連投規制は 1") {
    val settings = PostRateSettings.from(none, none)
    assertEquals(settings, PostRateSettings(PostRateLevel.Enforce(Some(1)), BypassLevel.Privileged, live = false,
      BoardSettings(18, 10, 10, 1, PostRateKey.defaults), ThreadSettings(40, 3600, PostRateKey.defaults)))
  }

  test("shiki の照合値は識別子が checked でキャップでないときだけ使う") {
    import io._3ch.common.domain.identity.ShikibetsuMode
    assertEquals(PostRateSettings.shikiSource(ShikibetsuMode.Checked, cap = false, "ID-1"), "ID-1")
    assertEquals(PostRateSettings.shikiSource(ShikibetsuMode.Checked, cap = true, "★"), "")
    Seq(ShikibetsuMode.Raw, ShikibetsuMode.Ken, ShikibetsuMode.City, ShikibetsuMode.As, ShikibetsuMode.Hidden).foreach { mode =>
      assertEquals(PostRateSettings.shikiSource(mode, cap = false, "JP.13"), "", mode.toString)
    }
  }

  test("板の履歴の件数・秒数は板設定だけで決まり、広域連投規制を判定しない段階でも fr を保持する") {
    val board = parse("BBS_FR_SECOND=3600\n")
    val merged = parse("BBS_FR_SECOND=1\nBBS_FR_LEVEL=0\ntimecount=1\n")
    val settings = PostRateSettings.from(board, merged)
    val check = PostRateCheck(settings.level, PostRateIdentity(), settings.board, settings.thread)
    assertEquals(settings.level, PostRateLevel.Enforce(None))
    assertEquals((check.boardSettings.fr, check.boardSettings.timecount, check.boardSettings.checksFr), (3600, 18, false))
    assertEquals(check.boardSettings.windowSeconds, 3600)
  }

  test("不正な値は既定値で動き、上限を超えた値も既定値にする") {
    val board = parse("timecount=-1\ntimeclose=abc\nBBS_SAMBA24=86401\nBBS_FR_SECOND=-1\nBBS_THREAD_RENTOU=1.5\nBBS_THREAD_RENTOU_RESET=\nBBS_FR_LEVEL=yes\nBBS_RENTOU_PASS=all\nBBS_RENTOU_KEYS=id\n")
    assertEquals(PostRateSettings.from(board, board), PostRateSettings.from(none, none))
    val maximum = parse("timecount=1000\nBBS_SAMBA24=86400\nBBS_THREAD_RENTOU_RESET=315360000\n")
    val parsed = PostRateSettings.from(maximum, maximum)
    assertEquals((parsed.board.timecount, parsed.board.samba24, parsed.thread.resetSeconds), (1000, 86400, 315360000d))
  }

  test("BBS_FR_SECOND は既定 1 秒、0 で無効、上限 86400 で、不正な値は既定") {
    def fr(text: String): Int = {
      val settings = parse(text)
      PostRateSettings.from(settings, settings).board.fr
    }
    val cases = Seq("" -> 1, "BBS_FR_SECOND=0\n" -> 0, "BBS_FR_SECOND=30\n" -> 30, "BBS_FR_SECOND=86400\n" -> 86400,
      "BBS_FR_SECOND=86401\n" -> 1, "BBS_FR_SECOND=-5\n" -> 1, "BBS_FR_SECOND=1.5\n" -> 1, "BBS_FR_SECOND=abc\n" -> 1,
      "BBS_FR_SECOND=\n" -> 1, " BBS_FR_SECOND = 5 \n" -> 5)
    cases.foreach { case (text, expected) => assertEquals(fr(text), expected, text) }
  }

  test("板単位の件数と秒数は板設定だけ、スレ単位と段階はスレ設定を優先する") {
    val board = parse("timecount=5\nBBS_SAMBA24=20\nBBS_FR_SECOND=3\nBBS_THREAD_RENTOU=3\nBBS_FR_LEVEL=\n")
    val merged = board.merged(parse("timecount=1<>BBS_SAMBA24=0<>BBS_FR_SECOND=0<>BBS_THREAD_RENTOU=7<>BBS_FR_LEVEL=off<>", "<>"))
    val settings = PostRateSettings.from(board, merged)
    assertEquals((settings.board.timecount, settings.board.samba24, settings.board.fr), (5, 20, 3))
    assertEquals((settings.thread.count, settings.level), (7, PostRateLevel.Off))
  }

  test("BBS_FR_LEVEL は off と log が規制を外し、空と 0 は広域連投規制だけを外し、キー無しと不正な値は既定") {
    def level(text: String): PostRateLevel = {
      val settings = parse(text)
      PostRateSettings.from(settings, settings).level
    }
    val cases = Seq(
      "" -> PostRateLevel.Enforce(Some(1)),
      "BBS_FR_LEVEL=\n" -> PostRateLevel.Enforce(None),
      "BBS_FR_LEVEL=   \n" -> PostRateLevel.Enforce(None),
      "BBS_FR_LEVEL=0\n" -> PostRateLevel.Enforce(None),
      "BBS_FR_LEVEL=00\n" -> PostRateLevel.Enforce(None),
      "BBS_FR_LEVEL=1\n" -> PostRateLevel.Enforce(Some(1)),
      "BBS_FR_LEVEL=30\n" -> PostRateLevel.Enforce(Some(30)),
      "BBS_FR_LEVEL=off\n" -> PostRateLevel.Off,
      "BBS_FR_LEVEL=log\n" -> PostRateLevel.Log,
      "BBS_FR_LEVEL=-1\n" -> PostRateLevel.Enforce(Some(1)),
      "BBS_FR_LEVEL=OFF\n" -> PostRateLevel.Enforce(Some(1)),
      "BBS_FR_LEVEL=Log\n" -> PostRateLevel.Enforce(Some(1)),
      "BBS_FR_LEVEL=on\n" -> PostRateLevel.Enforce(Some(1)),
      "BBS_FR_LEVEL=none\n" -> PostRateLevel.Enforce(Some(1)),
      "BBS_FR_LEVEL=1.5\n" -> PostRateLevel.Enforce(Some(1)),
      "BBS_FR_LEVEL=99999999999\n" -> PostRateLevel.Enforce(Some(1)),
      " BBS_FR_LEVEL = off \n" -> PostRateLevel.Off,
      "BBS_FR_LEVEL=  log\n" -> PostRateLevel.Log,
      "BBS_FR_LEVEL= 5 \n" -> PostRateLevel.Enforce(Some(5)),
      "BBS_FR_LEVEL= 0 \n" -> PostRateLevel.Enforce(None),
      "BBS_RENTOU_MODE=off\n" -> PostRateLevel.Enforce(Some(1)))
    cases.foreach { case (text, expected) => assertEquals(level(text), expected, text) }
  }

  test("規制するのは Enforce だけ、広域連投規制を判定するのは Log と 1 以上の Enforce で、設定の値の形で往復する") {
    val levels = Seq(PostRateLevel.Off, PostRateLevel.Log, PostRateLevel.Enforce(None), PostRateLevel.Enforce(Some(3)))
    assertEquals(levels.map(_.value), Seq("off", "log", "0", "3"))
    assertEquals(levels.map(level => PostRateLevel.parse(Some(level.value))), levels)
    assertEquals(levels.map(_.checksFlyRibbon), Seq(false, true, false, true))
    val identity = PostRateIdentity("ip", "shiki", "")
    val defaults = PostRateSettings.from(none, none)
    val checks = levels.map(level => PostRateCheck(level, identity, defaults.board, defaults.thread))
    assertEquals(checks.map(_.enabled), Seq(false, true, true, true))
    assertEquals(checks.map(_.enforce), Seq(false, false, true, true))
    assertEquals(checks.map(_.boardSettings.checksFr), Seq(false, true, false, true))
    assertEquals(checks.map(_.boardSettings.fr), Seq(1, 1, 1, 1), "履歴の保持のため fr は板設定のまま")
    assertEquals(checks.map(_.boardSettings.copy(checksFr = true)), Seq.fill(levels.size)(defaults.board), "checksFr のほかは変えない")
  }

  test("設定名の前後の空白は無視する") {
    val board = parse(" BBS_SAMBA24 = 30\n")
    assertEquals(PostRateSettings.from(board, board).board.samba24, 30)
  }

  test("off・BBS_LIVE・回避レベルで免除される投稿者には適用しない") {
    def applies(text: String, cap: Boolean = false, authenticated: Boolean = false): Boolean = {
      val settings = parse(text)
      PostRateSettings.from(settings, settings).applies(cap, authenticated)
    }
    assert(applies(""))
    assert(!applies("BBS_FR_LEVEL=off\n"))
    assert(applies("BBS_FR_LEVEL=log\n"))
    assert(!applies("BBS_LIVE=checked\n"))
    assert(!applies("BBS_LIVE=1\n"))
    assert(applies("BBS_LIVE=on\n"))
    assert(!applies("", cap = true), "既定の privileged はキャップを免除する")
    assert(applies("", authenticated = true))
    assert(!applies("BBS_RENTOU_PASS=login\n", authenticated = true))
    assert(applies("BBS_RENTOU_PASS=admin\n", cap = true), "権限が無いので admin では誰も免除しない")
    assert(applies("BBS_RENTOU_PASS=staff\n", cap = true))
    assert(!applies("BBS_RENTOU_PASS=l10\n", cap = true))
  }

  test("スレ立て時の書き写しから本書の新キーだけを除く") {
    val settings = parse("BBS_TITLE=t\nBBS_SAMBA24=5\nBBS_FR_SECOND=2\n BBS_FR_LEVEL =log\ntimecount=3\nBBS_LIVE=checked\n")
    assertEquals(PostRateSettings.copiedToThread(settings).toThreadSettings, "BBS_TITLE=t<>timecount=3<>BBS_LIVE=checked<>")
  }
}
