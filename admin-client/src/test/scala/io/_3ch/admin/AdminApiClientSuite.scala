package io._3ch.admin

import io._3ch.admin.api.AdminApiClient
import io._3ch.common.admin.{AdminCodec, AdminError, AdminErrorCode}
import munit.FunSuite

class AdminApiClientSuite extends FunSuite {
  test("API の応答は成功なら値、失敗ならエラー、読めなければ状態コードを添える") {
    assertEquals(AdminApiClient.read(true, 200, """{"boards":["a"]}""", AdminCodec.boardList).map(_.boards), Right(List("a")))
    assertEquals(AdminApiClient.read(false, 409, """{"code":"admin:conflict","message":"競合"}""", AdminCodec.boardList),
      Left(AdminError(AdminErrorCode.Conflict, "競合")))
    assertEquals(AdminApiClient.read(false, 502, "<html>", AdminCodec.boardList).left.map(_.message), Left("応答を読めませんでした（HTTP 502）。"))
    assertEquals(AdminApiClient.read(true, 200, """{"other":1}""", AdminCodec.boardList).left.map(_.code), Left(AdminErrorCode.Internal))
  }
}
