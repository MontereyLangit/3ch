package io._3ch.bbs.domain.posting

class ReplyThreadSuite extends munit.FunSuite {
  private val row = "名前<><>日付<> 本文 <>題名\n"

  test("題名は 1 行目の最後の欄、レス番号は改行で区切った数") {
    assertEquals(ReplyThread.open(row + "a<><>d<> m <>\n", "sample", "1699990000"), Right(ReplyThread("題名", 3)))
    assertEquals(ReplyThread.open("x<>題名", "sample", "1"), Right(ReplyThread("題名", 1)))
  }

  test("dat が無ければ旧ログのパスを添えて拒否する") {
    assertEquals(ReplyThread.open("", "sample", "1699990000"), Left(PostingError.ThreadMissing("sample/thread/1699/1699990000.cgi")))
  }

  test("1 行目が空、題名が空、999 行を超えたらそれぞれ拒否し、壊れたログを満杯より先に見る") {
    assertEquals(ReplyThread.open("\n" + row * 999, "b", "1"), Left(PostingError.BrokenLog(BrokenLogKind.Empty)))
    assertEquals(ReplyThread.open("a<>b<>c<>d<>\n", "b", "1"), Left(PostingError.BrokenLog(BrokenLogKind.NoTitle)))
    assertEquals(ReplyThread.open(row * 998, "b", "1"), Right(ReplyThread("題名", 999)))
    assertEquals(ReplyThread.open(row * 999, "b", "1"), Left(PostingError.ThreadFull))
  }
}
