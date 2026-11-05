package io._3ch.common.text

import scala.scalajs.js
import io._3ch.common.domain.identity.DateSalt

/** `JsNumber` が実行環境の `Number` / `parseInt` と一致することの確認。 */
class JsNumberRuntimeSuite extends munit.FunSuite {
  private val inputs = List(
    "", " ", "\t\n", "0", "12", " 12 ", "+1.5", "-2", ".5", "5.", "1e3", "1E-3", "1e400", "0x1F", "0X1f", "0o17", "0b101", "0x", "0b2",
    "Infinity", "+Infinity", "-Infinity", "infinity", "abc", "12abc", "-0x10", "1,000", " 7　", "﻿8", "１２", "00012",
    "99999999999999999999", "0.1", "-.5e1", "--1", "+", ".",
    "-0", "-0.0", "-0e9", "-0x0", "-0.9", "-1e-999",
    "1e2147483648", "-1e2147483648", "1e-2147483649", "-1e-2147483649",
    "0e99999999999999999999999999999", "-0e-99999999999999999999999999999",
    "5e-324", "2.4703282292062327e-324", "-2.4703282292062327e-324", "2.4703282292062328e-324",
    "2.2250738585072014e-308", "1.7976931348623157e308", "1.7976931348623159e308",
    "1.00000000000000011102230246251565404236316680908203125",
    "1.00000000000000011102230246251565404236316680908203126",
    "1e", "1e+", "1ee2", "0x1p2", "1f", "NaN", "\u00851", "\u180e1",
    "9" * 400, "-" + "9" * 400, "0x" + "f" * 300
  )

  private def same(obtained: Double, expected: Double, clue: String): Unit = {
    if (expected.isNaN) assert(obtained.isNaN, clue)
    else {
      assertEquals(obtained, expected, clue)
      if (expected == 0d) assertEquals(1d / obtained, 1d / expected, clue)
    }
  }

  test("parse と Number が一致する") {
    inputs.foreach(s => same(JsNumber.parse(s), js.Dynamic.global.Number(s).asInstanceOf[Double], s))
  }

  test("parseInt と parseInt が一致する") {
    inputs.foreach(s => same(JsNumber.parseInt(s), js.Dynamic.global.parseInt(s).asInstanceOf[Double], s))
  }

  test("識別子の日付窓は JavaScript の数値解釈と一致する") {
    for {
      time <- List("1700000000", "-32400", "bad")
      reset <- List("86400", "-0", "-0.0", "0e99999999999999999999999999999", "1e2147483648", "1e-2147483649", "bad")
    } {
      val expected = js.Math.floor((js.Dynamic.global.parseInt(time).asInstanceOf[Double] + 32400) / js.Dynamic.global.Number(reset).asInstanceOf[Double])
      same(DateSalt.window(time, reset), expected, s"time=$time reset=$reset")
    }
  }
}
