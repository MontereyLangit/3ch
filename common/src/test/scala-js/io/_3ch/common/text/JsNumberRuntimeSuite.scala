package io._3ch.common.text

import scala.scalajs.js

/** `JsNumber` が実行環境の `Number` / `parseInt` と一致することの確認。 */
class JsNumberRuntimeSuite extends munit.FunSuite {
  private val inputs = List(
    "", " ", "\t\n", "0", "12", " 12 ", "+1.5", "-2", ".5", "5.", "1e3", "1E-3", "1e400", "0x1F", "0X1f", "0o17", "0b101", "0x", "0b2",
    "Infinity", "+Infinity", "-Infinity", "infinity", "abc", "12abc", "-0x10", "1,000", " 7　", "﻿8", "１２", "00012",
    "99999999999999999999", "0.1", "-.5e1", "--1", "+", "."
  )

  private def same(obtained: Double, expected: Double, clue: String): Unit =
    if (expected.isNaN) assert(obtained.isNaN, clue) else assertEquals(obtained, expected, clue)

  test("parse と Number が一致する") {
    inputs.foreach(s => same(JsNumber.parse(s), js.Dynamic.global.Number(s).asInstanceOf[Double], s))
  }

  test("parseInt と parseInt が一致する") {
    inputs.foreach(s => same(JsNumber.parseInt(s), js.Dynamic.global.parseInt(s).asInstanceOf[Double], s))
  }
}
