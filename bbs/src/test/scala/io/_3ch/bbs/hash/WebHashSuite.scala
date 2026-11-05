package io._3ch.bbs.hash

import io._3ch.common.golden.{GoldenFile, Json}
import munit.FunSuite

import scala.concurrent.Future
import scala.scalajs.js.Thenable.Implicits._
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** [[WebHash]] を `sha1base64.json` / `hash.json` の JS 実測値と照合する（KB-14 を含む）。 */
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
