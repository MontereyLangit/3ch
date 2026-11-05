package io._3ch.common.text

class JsNumberSuite extends munit.FunSuite {
  private def same(obtained: Double, expected: Double, clue: String): Unit =
    if (expected.isNaN) assert(obtained.isNaN, clue) else assertEquals(obtained, expected, clue)

  test("parse は JavaScript の Number と同じく読む") {
    List(
      "" -> 0d, " " -> 0d, "12" -> 12d, " 12 " -> 12d, "+1.5" -> 1.5, "-2" -> -2d, ".5" -> 0.5, "5." -> 5d, "1e3" -> 1000d,
      "0x1F" -> 31d, "0o17" -> 15d, "0b101" -> 5d, "Infinity" -> Double.PositiveInfinity, "-Infinity" -> Double.NegativeInfinity,
      "abc" -> Double.NaN, "12abc" -> Double.NaN, "-0x10" -> Double.NaN, "1,000" -> Double.NaN, "　" + "7" -> 7d, "１２" -> Double.NaN
    ).foreach((input, expected) => same(JsNumber.parse(input), expected, input))
  }

  test("parseInt は JavaScript の parseInt（基数省略）と同じく読む") {
    List(
      "12" -> 12d, " 12abc" -> 12d, "-7.9" -> -7d, "+3" -> 3d, "0x1A" -> 26d, "0x" -> Double.NaN, "abc" -> Double.NaN, "" -> Double.NaN,
      "9999999999" -> 9999999999d, "1e3" -> 1d, "１２" -> Double.NaN
    ).foreach((input, expected) => same(JsNumber.parseInt(input), expected, input))
  }
}
