package io._3ch.common.domain.posting

class ThreadWriteGuardSuite extends munit.FunSuite {
  import ThreadWriteGuard.check
  private val row = "名前<><>日付<> 本文 <>題名\n"

  test("判定は 停止・移動・削除 → BBS_HEISA → 同じ key → スレ不在 → 1000 件 の順") {
    val full = row * 999
    assertEquals(check(unavailable = true, full, "BBS_HEISA=checked", ThreadWriteKind.Create), Left(ThreadWriteRejection.Unavailable))
    assertEquals(check(unavailable = false, full, "BBS_HEISA=checked", ThreadWriteKind.Create), Left(ThreadWriteRejection.Closed))
    assertEquals(check(unavailable = false, full, "", ThreadWriteKind.Create), Left(ThreadWriteRejection.DuplicateKey))
    assertEquals(check(unavailable = false, "", "", ThreadWriteKind.Reply), Left(ThreadWriteRejection.Missing))
    assertEquals(check(unavailable = false, full, "", ThreadWriteKind.Reply), Left(ThreadWriteRejection.Full))
  }

  test("次のレス番号は末尾の空白を除いた空でない行の数に 1 を足したもの") {
    assertEquals(check(unavailable = false, "", "", ThreadWriteKind.Create), Right(1))
    assertEquals(check(unavailable = false, row + "\n" + row + " \n　", "", ThreadWriteKind.Reply), Right(3))
    assertEquals(check(unavailable = false, row * 998, "", ThreadWriteKind.Raw), Right(999))
    assertEquals(check(unavailable = false, row * 998 + "x", "", ThreadWriteKind.Reply), Left(ThreadWriteRejection.Full))
  }

  test("付帯情報の無い追記はスレの有無を問わない") {
    assertEquals(check(unavailable = false, "", "", ThreadWriteKind.Raw), Right(1))
    assertEquals(check(unavailable = false, row, "", ThreadWriteKind.Raw), Right(2))
  }

  test("BBS_HEISA はちょうど BBS_HEISA=checked の項目があれば停止。後で上書きしても停止のまま") {
    val closed = Seq("BBS_HEISA=checked", "A=1<>BBS_HEISA=checked<>", "BBS_HEISA=checked<>BBS_HEISA=<>", "X=<<>BBS_HEISA=checked<>>Y=<>")
    val open = Seq("", "BBS_HEISA=", "BBS_HEISA= checked<>", " BBS_HEISA=checked<>", "BBS_HEISA =checked<>", "BBS_HEISA=CHECKED", "BBS_HEISA=checked\n", "xBBS_HEISA=checked", "BBS_HEISA=checked<x")
    closed.foreach(s => assert(ThreadWriteGuard.closed(s), s))
    open.foreach(s => assert(!ThreadWriteGuard.closed(s), s))
  }

  test("コードから読み戻せる") {
    ThreadWriteRejection.values.foreach(r => assertEquals(ThreadWriteRejection.fromCode(r.code), Some(r)))
    assertEquals(ThreadWriteRejection.fromCode("thread-creation:capacity"), None)
  }
}
