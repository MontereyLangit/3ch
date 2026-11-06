package io._3ch.bbs.domain.posting.moderation

import io._3ch.bbs.domain.posting.moderation.PostRateLimit.{BoardSettings, ThreadSettings}
import io._3ch.common.domain.BbsSettings
import munit.FunSuite

class PostRateSettingsSuite extends FunSuite {
  private def parse(text: String, separator: String = "\n"): BbsSettings = BbsSettings.parse(text, separator)
  private val none = parse("")

  test("設定が無ければ既定値で、試行モードは log") {
    val settings = PostRateSettings.from(none, none)
    assertEquals(settings, PostRateSettings(PostRateMode.Log, BypassLevel.Privileged, live = false,
      BoardSettings(18, 10, 10, PostRateKey.defaults), ThreadSettings(40, 3600, PostRateKey.defaults)))
  }

  test("不正な値は既定値で動き、上限を超えた値も既定値にする") {
    val board = parse("timecount=-1\ntimeclose=abc\nBBS_SAMBA24=86401\nBBS_THREAD_RENTOU=1.5\nBBS_THREAD_RENTOU_RESET=\nBBS_RENTOU_MODE=yes\nBBS_RENTOU_PASS=all\nBBS_RENTOU_KEYS=id\n")
    assertEquals(PostRateSettings.from(board, board), PostRateSettings.from(none, none))
    val maximum = parse("timecount=1000\nBBS_SAMBA24=86400\nBBS_THREAD_RENTOU_RESET=315360000\n")
    val parsed = PostRateSettings.from(maximum, maximum)
    assertEquals((parsed.board.timecount, parsed.board.samba24, parsed.thread.resetSeconds), (1000, 86400, 315360000d))
  }

  test("板単位の件数と秒数は板設定だけ、スレ単位と試行モードはスレ設定を優先する") {
    val board = parse("timecount=5\nBBS_SAMBA24=20\nBBS_THREAD_RENTOU=3\nBBS_RENTOU_MODE=on\n")
    val merged = board.merged(parse("timecount=1<>BBS_SAMBA24=0<>BBS_THREAD_RENTOU=7<>BBS_RENTOU_MODE=off<>", "<>"))
    val settings = PostRateSettings.from(board, merged)
    assertEquals((settings.board.timecount, settings.board.samba24), (5, 20))
    assertEquals((settings.thread.count, settings.mode), (7, PostRateMode.Off))
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
    assert(!applies("BBS_RENTOU_MODE=off\n"))
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
    val settings = parse("BBS_TITLE=t\nBBS_SAMBA24=5\n BBS_RENTOU_MODE =on\ntimecount=3\nBBS_LIVE=checked\n")
    assertEquals(PostRateSettings.copiedToThread(settings).toThreadSettings, "BBS_TITLE=t<>timecount=3<>BBS_LIVE=checked<>")
  }
}
