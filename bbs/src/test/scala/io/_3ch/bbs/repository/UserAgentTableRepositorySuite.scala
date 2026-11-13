package io._3ch.bbs.repository

import io._3ch.bbs.domain.posting.{UserAgentRule, UserAgentTable}
import io._3ch.bbs.external.cloudflare.WorkerEnv
import io._3ch.common.domain.identity.UserAgentProfile
import munit.FunSuite

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

class UserAgentTableRepositorySuite extends FunSuite {
  private def env(get: String => js.Promise[js.Any]): WorkerEnv =
    js.Dynamic.literal(ch3www = js.Dynamic.literal(get = get: js.Function1[String, js.Promise[js.Any]])).asInstanceOf[WorkerEnv]

  private def stored(text: String): js.Promise[js.Any] =
    js.Promise.resolve[js.Any](js.Dynamic.literal(text = (() => js.Promise.resolve[js.Any](text)): js.Function0[js.Promise[js.Any]]))

  test("表の行を順に読み、読めない行と形の違う値は飛ばす") {
    val table = UserAgentTableRepository.parse(
      """{"os": [{"match": "(", "label": "bad"}, {"match": "Windows NT 10", "label": "Windows"}, "row", {"match": 1, "label": "x"}],
        | "browser": [{"match": "firefox/(\\d+)", "flags": "i", "label": "Firefox $1"}, {"match": "a", "flags": "g", "label": "g"}],
        | "device": {"match": "x"}}""".stripMargin)
    assertEquals(table.profile("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0"), UserAgentProfile("Windows", "Firefox 132", ""))
  }

  test("JSON でない本文・配列の根は空の表") {
    Seq("", "{", "[]", "null").foreach(text => assertEquals(UserAgentTableRepository.parse(text).profile("Mozilla"), UserAgentProfile(), text))
  }

  test("ua-profiles.json が無ければ空の表、読めない・JSON の根が不正なら失敗にする") {
    val requested = js.Array[String]()
    for {
      missing <- UserAgentTableRepository.fetch(env { key => requested.push(key); js.Promise.resolve[js.Any](null) })
      broken <- UserAgentTableRepository.fetch(env(_ => js.Promise.reject(new js.Error("r2")))).failed
      invalid <- UserAgentTableRepository.fetch(env(_ => stored("{"))).failed
      loaded <- UserAgentTableRepository.fetch(env(_ => stored("""{"device": [{"match": "Mozilla", "label": "PC"}]}""")))
    } yield {
      assertEquals(requested.toList, List("ua-profiles.json"))
      assertEquals(missing.profile("Mozilla"), UserAgentProfile())
      assert(broken.isInstanceOf[js.JavaScriptException])
      assert(invalid.isInstanceOf[IllegalArgumentException])
      assertEquals(loaded.profile("Mozilla"), UserAgentProfile(device = "PC"))
    }
  }

  test("行数は表ごとに上限まで、長すぎる pattern の行は飛ばす") {
    val rows = (1 to UserAgentTable.MaxRulesPerList + 20).map(n => s"""{"match": "^a$n$$", "label": "L$n"}""").mkString(",")
    val long = "a" * (UserAgentRule.MaxPatternLength + 1)
    val table = UserAgentTableRepository.parse(
      s"""{"os": [$rows], "browser": [{"match": "$long", "label": "long"}, {"match": "b", "label": "short"}]}""")
    assertEquals(table.profile(s"a${UserAgentTable.MaxRulesPerList}").os, s"L${UserAgentTable.MaxRulesPerList}")
    assertEquals(table.profile(s"a${UserAgentTable.MaxRulesPerList + 1}").os, "")
    assertEquals(table.profile(long).browser, "")
    assertEquals(table.profile("b").browser, "short")
  }
}
