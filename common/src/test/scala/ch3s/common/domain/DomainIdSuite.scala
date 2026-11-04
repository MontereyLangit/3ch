package ch3s.common.domain

import munit.FunSuite

/**
 * 板IDやスレキーなどID系ドメイン型のテスト．
 */
class DomainIdSuite extends FunSuite {

  test("BoardId: 1〜16文字の英数字のみ受け付ける") {
    List("a", "sample", "924", "A" * 16).foreach(v => assertEquals(BoardId.create(v).map(_.value), Right(v)))
    List("", "A" * 17, "foo_bar", "foo-bar", "test/", "板").foreach(v => assert(BoardId.create(v).isLeft, v))
  }

  test("ThreadKey.parse: 正規表記の文字列だけを受け付ける") {
    assertEquals(ThreadKey.parse("1800000001").map(_.value), Right(1800000001L))
    List("", "abc", "01800000001", "+1800000001", "1800000001 ", "99999999", "10000000000").foreach(v => assert(ThreadKey.parse(v).isLeft, v))
  }

  test("ThreadKey: 9〜10桁の正の整数のみ受け付ける") {
    List(100000000L, 1700000000L, 9999999999L).foreach(v => assertEquals(ThreadKey.create(v).map(_.value), Right(v)))
    List(0L, 99999999L, 10000000000L, -1700000000L).foreach(v => assert(ThreadKey.create(v).isLeft, v.toString))
  }
}
