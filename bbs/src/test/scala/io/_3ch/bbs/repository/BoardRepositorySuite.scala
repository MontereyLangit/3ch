package io._3ch.bbs.repository

import io._3ch.bbs.durable.DurableTestKit
import io._3ch.common.domain.{BbsSettings, BoardId}
import munit.FunSuite

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class BoardRepositorySuite extends FunSuite {
  private val board = BoardId.create("sample").toOption.get

  test("SETTING.TXT が無い板では既定設定の板名を置き換える") {
    val env = DurableTestKit.newEnv()
    BoardRepository.getBoardSettings(board, env.worker).map { settings =>
      assertEquals(settings, BbsSettings.create(BbsSettings.defaultText, board))
      assertEquals(settings.get("BBS_TITLE"), Some("sample＠3ch掲示板"))
      assertEquals(settings.get("BBS_THREAD_TATESUGI"), Some("1"))
    }
  }

  test("保存された設定は空でも既定値と混ぜずに読む") {
    val env = DurableTestKit.newEnv()
    env.putR2Raw("sample/SETTING.TXT", "", "etag-empty")
    BoardRepository.getBoardSettings(board, env.worker).map(settings => assert(settings.value.isEmpty))
  }
}
