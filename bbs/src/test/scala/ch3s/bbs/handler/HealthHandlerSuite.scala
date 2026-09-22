package ch3s.bbs.handler

import munit.FunSuite

class HealthHandlerSuite extends FunSuite:

  test("Body は固定文字列を返す") {
    assertEquals(HealthHandler.Body, "ch3s v6s ok")
  }
