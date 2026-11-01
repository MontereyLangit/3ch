package ch3s.bbs.hash

import munit.FunSuite

class HexSuite extends FunSuite {

  test("16進16桁（大文字小文字混在）を8バイトにする") {
    assertEquals(Hex.toBytes("00ff7FAb0123CdEf").toArray.map(_.toInt & 0xff).toList, List(0, 255, 127, 171, 1, 35, 205, 239))
  }

  test("バイト列で渡した鍵の crypt は、同じバイト値を文字にした鍵と同じ値になる") {
    val bytes = Hex.toBytes("0123456789abcdef")
    val chars = bytes.toArray.map(b => (b.toInt & 0xff).toChar).mkString
    assertEquals(UnixCrypt.crypt(bytes, "ab"), UnixCrypt.crypt(chars, "ab"))
  }
}
