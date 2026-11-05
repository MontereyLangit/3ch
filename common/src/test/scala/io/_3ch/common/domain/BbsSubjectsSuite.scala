package io._3ch.common.domain

class BbsSubjectsSuite extends munit.FunSuite {
  test("KB-37: subject.txt を順序とタイトルを保持して読み書きする") {
    val text = "1791039040.dat<>雑談 (続き) &amp; テスト (6)\n1792588031.dat<>質問スレ (5)\n"
    val subjects = BbsSubjects.create(text)
    assertEquals(subjects.value.map(_.key.value), List(1791039040L, 1792588031L))
    assertEquals(subjects.value.head.title.value, "雑談 (続き) &amp; テスト")
    assertEquals(subjects.value.map(_.resNum), List(6, 5))
    assertEquals(subjects.toString, text)
  }

  test("KB-37: 空行、不正行、数値の範囲外を無視する") {
    val text = "\ninvalid\n1.dat<>bad (2)\n1791039040.dat<>zero (0)\n999999999999999999999.dat<>overflow (2)\n1791039040.dat<>overflow (999999999999999999)\n1792588031.dat<>valid (1)\r\n"
    assertEquals(BbsSubjects.create(text).value.map(_.title.value), List("valid"))
    assertEquals(BbsSubjects.create("").value, Nil)
  }
}
