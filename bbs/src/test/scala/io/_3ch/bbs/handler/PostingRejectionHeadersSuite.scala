package io._3ch.bbs.handler

import io._3ch.bbs.domain.account.AuthenticationFailure
import io._3ch.bbs.domain.posting.PostingError
import io._3ch.common.domain.account.AccountRejection

class PostingRejectionHeadersSuite extends munit.FunSuite {
  test("未ログインと同意の版の更新は認証モーダルを開くヘッダにする") {
    Seq(AuthenticationFailure.LoginRequired, AuthenticationFailure.Account(AccountRejection.Agreement)).foreach { reason =>
      val headers = Posting.rejectionHeaders(PostingError.Authentication(reason), "sample").toMap
      assertEquals(headers.get("x-auth-required"), Some("login"))
      assertEquals(headers.get("x-auth-url"), Some("/auth/?return=/sample/"))
      assertEquals(headers.get("x-post-error"), Some(reason.code))
    }
  }
  test("Lv 不足は必要 Lv を返し、ほかの拒否はコードだけにする") {
    assertEquals(Posting.rejectionHeaders(PostingError.AuthenticationLevel(3), "sample").toMap.get("x-phoebe-required"), Some("3"))
    assertEquals(Posting.rejectionHeaders(PostingError.Authentication(AuthenticationFailure.Account(AccountRejection.Frozen)), "sample"),
      Seq("x-post-error" -> "auth:frozen"))
  }
}
