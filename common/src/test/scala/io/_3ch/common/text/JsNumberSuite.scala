package io._3ch.common.text

class JsNumberSuite extends munit.FunSuite {
  private def same(obtained: Double, expected: Double, clue: String): Unit = {
    if (expected.isNaN) assert(obtained.isNaN, clue)
    else {
      assertEquals(obtained, expected, clue)
      if (expected == 0d) assertEquals(1d / obtained, 1d / expected, clue)
    }
  }

  test("parse は JavaScript の Number と同じく読む") {
    List(
      "" -> 0d, " " -> 0d, "12" -> 12d, " 12 " -> 12d, "+1.5" -> 1.5, "-2" -> -2d, ".5" -> 0.5, "5." -> 5d, "1e3" -> 1000d,
      "0x1F" -> 31d, "0o17" -> 15d, "0b101" -> 5d, "Infinity" -> Double.PositiveInfinity, "-Infinity" -> Double.NegativeInfinity,
      "abc" -> Double.NaN, "12abc" -> Double.NaN, "-0x10" -> Double.NaN, "1,000" -> Double.NaN, "　" + "7" -> 7d, "１２" -> Double.NaN
    ).foreach((input, expected) => same(JsNumber.parse(input), expected, input))
  }

  test("parse はゼロの符号を保ち、指数の範囲外は無限大かゼロにする") {
    List(
      "-0" -> -0d, "-0.0" -> -0d, "-0e9" -> -0d, "-1e-999" -> -0d,
      "1e2147483648" -> Double.PositiveInfinity, "-1e2147483648" -> Double.NegativeInfinity,
      "1e-2147483649" -> 0d, "-1e-2147483649" -> -0d,
      "0e99999999999999999999999999999" -> 0d, "-0e-99999999999999999999999999999" -> -0d
    ).foreach((input, expected) => same(JsNumber.parse(input), expected, input))
  }

  test("parse は非正規化数と丸めの境界を保つ") {
    List(
      "5e-324" -> Double.MinPositiveValue,
      "2.4703282292062327e-324" -> 0d,
      "-2.4703282292062327e-324" -> -0d,
      "2.4703282292062328e-324" -> Double.MinPositiveValue,
      "1.00000000000000011102230246251565404236316680908203125" -> 1d,
      "1.00000000000000011102230246251565404236316680908203126" -> 1.0000000000000002d
    ).foreach((input, expected) => same(JsNumber.parse(input), expected, input))
  }

  test("parseInt は JavaScript の parseInt（基数省略）と同じく読む") {
    List(
      "12" -> 12d, " 12abc" -> 12d, "-7.9" -> -7d, "+3" -> 3d, "0x1A" -> 26d, "0x" -> Double.NaN, "abc" -> Double.NaN, "" -> Double.NaN,
      "9999999999" -> 9999999999d, "1e3" -> 1d, "１２" -> Double.NaN,
      "-0" -> -0d, "-0.9" -> -0d, "-0x0" -> -0d,
      ("9" * 400) -> Double.PositiveInfinity, ("-" + "9" * 400) -> Double.NegativeInfinity
    ).foreach((input, expected) => same(JsNumber.parseInt(input), expected, input))
  }
}
