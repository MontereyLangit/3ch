package io._3ch.common.routes

import munit.FunSuite

class RouteTableSuite extends FunSuite {

  test("publicPatterns は v5 の評価順（archive が archives より先）で、代表パスにマッチする") {
    assertEquals(
      RouteTable.publicPatterns.map(_._1),
      List("dat", "subject", "archive", "archives", "head", "settings")
    )
    def firstKind(path: String): Option[String] =
      RouteTable.publicPatterns.collectFirst {
        case (kind, re) if path.matches(s"(?s)(?:$re).*") && re.r.findPrefixOf(path).isDefined => kind
      }
    assertEquals(firstKind("/news/dat/1234567890.dat"), Option("dat"))
    assertEquals(firstKind("/news/subject.txt"), Option("subject"))
    assertEquals(firstKind("/news/kako/2020/subject.txt"), Option("archive"))
    assertEquals(firstKind("/news/kako/subject.txt"), Option("archives"))
    assertEquals(firstKind("/news/SETTING.TXT"), Option("settings"))
    assertEquals(firstKind("/news/"), None)
    assertEquals(firstKind("/news/unknown"), None)
  }

}
