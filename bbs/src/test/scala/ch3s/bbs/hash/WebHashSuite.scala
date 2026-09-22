package ch3s.bbs.hash

import ch3s.common.golden.{GoldenFile, Json}
import munit.FunSuite

import scala.concurrent.Future
import scala.scalajs.js.Thenable.Implicits._
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/**
 * `tests/golden/sha1base64.json` / `hash.json` を読み、`src/common.js` の
 * `sha1Base64()` / `hash()`（Web Crypto依存、KB-14の二重エンコードを含む）と
 * Scala.js版 [[WebHash]] の出力が一致することを確認する。
 *
 * golden JSON の読み込みには `common` プロジェクトのテスト専用ユーティリティ
 * （`ch3s.common.golden.{Json, GoldenFile}`）を `"test->test"` 依存でそのまま再利用している
 * （build.sbt の `bbs` プロジェクト設定を参照）。
 */
class WebHashSuite extends FunSuite {

  test("golden: sha1base64.json の全ケースがJS実測値と一致する(KB-14: TextEncoderによる二重エンコードを含む)") {
    val cases = Json.parse(GoldenFile.readText("sha1base64.json")).asArr
    Future.traverse(cases) { c =>
      val input = c.field("input").asString
      val expected = c.field("output").asString
      WebHash.sha1Base64(input).toFuture.map { got =>
        assertEquals(got, expected, s"input=$input")
      }
    }.map(_ => ())
  }

  test("golden: hash.json の全ケースがJS実測値と一致する") {
    val cases = Json.parse(GoldenFile.readText("hash.json")).asArr
    Future.traverse(cases) { c =>
      val algo = c.field("algo").asString
      val input = c.field("input").asString
      val expected = c.field("output").asString
      WebHash.hash(algo, input).toFuture.map { got =>
        assertEquals(got, expected, s"algo=$algo input=$input")
      }
    }.map(_ => ())
  }
}
