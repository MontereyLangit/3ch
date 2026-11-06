package io._3ch.common.domain.account

import munit.FunSuite

class DedicatedBrowserTokensSuite extends FunSuite {
  private val now = 2000000000d
  private val hash = "a" * 64
  private val ua = "b" * 64
  private def issued: Vector[DedicatedBrowserToken] = DedicatedBrowserTokens.issue(Vector.empty, "token1", hash, now).toOption.get
  private def bound: Vector[DedicatedBrowserToken] = DedicatedBrowserTokens.use(issued, "token1", ua, "専ブラ", now + 1).tokens

  test("未使用キーは一個だけで二十四時間後から使えない") {
    val renewed = DedicatedBrowserTokens.issue(issued, "token2", "c" * 64, now + 1).toOption.get
    assertEquals(renewed.head.revokeReason, Some(TokenRevokeReason.Superseded))
    assertEquals(renewed.count(_.active(now + 1)), 1)
    assertEquals(DedicatedBrowserTokens.use(issued, "token1", ua, "専ブラ", now + 86400).result, Left(AccountRejection.TokenRevoked))
    assert(DedicatedBrowserTokens.use(issued, "token1", ua, "専ブラ", now + 86399).result.isRight)
  }

  test("UA はハッシュ全体を比較し不一致でも正規のキーを止めない") {
    val different = ua.take(16) + "c" * 48
    val rejected = DedicatedBrowserTokens.use(bound, "token1", different, "別の専ブラ", now + 2)
    assertEquals(rejected.result, Left(AccountRejection.TokenUaMismatch))
    assertEquals(rejected.tokens, bound)
    assertEquals(DedicatedBrowserTokens.describe(bound, "token1", different, now + 2), Left(AccountRejection.TokenUaMismatch))
    assert(DedicatedBrowserTokens.describe(bound, "token1", ua, now + 86401).isRight)
  }

  test("五つの UA に紐づいた状態でも同じ UA のキーは置き換えられる") {
    val tokens = (0 until 5).map { i =>
      DedicatedBrowserToken(s"token$i", f"$i%064x", now, now + 86400, uaHash = Some(f"${i + 10}%064x"), boundAt = Some(now))
    }.toVector
    val issued = DedicatedBrowserTokens.issue(tokens, "newtoken", "f" * 64, now).toOption.get
    val refused = DedicatedBrowserTokens.use(issued, "newtoken", "e" * 64, "新しい専ブラ", now + 1)
    assertEquals(refused.result, Left(AccountRejection.TokenLimit))
    val replaced = DedicatedBrowserTokens.use(issued, "newtoken", tokens.head.uaHash.get, "同じ専ブラ", now + 1)
    assert(replaced.result.isRight)
    assertEquals(replaced.tokens.head.revokeReason, Some(TokenRevokeReason.Superseded))
    assertEquals(replaced.tokens.last.replacedTokenAt, Some(now + 1))
    assertEquals(replaced.tokens.count(t => t.active(now + 1) && t.uaHash.isDefined), 5)
  }

  test("無効化したキーを三十日保持し Cookie 照合も拒否する") {
    val revoked = DedicatedBrowserTokens.revoke(bound, "token1", now + 2).toOption.get
    assertEquals(DedicatedBrowserTokens.describe(revoked, "token1", ua, now + 3), Left(AccountRejection.TokenRevoked))
    assertEquals(DedicatedBrowserTokens.retained(revoked, now + 2 + DedicatedBrowserTokens.retainedLifetime - 1).size, 1)
    assertEquals(DedicatedBrowserTokens.retained(revoked, now + 2 + DedicatedBrowserTokens.retainedLifetime).size, 0)
  }

  test("投稿は有効な紐づき済みキーに直近五件だけ保持する") {
    val posts = (1 to 6).foldLeft(bound) { (tokens, i) =>
      DedicatedBrowserTokens.recordPost(tokens, "token1", TokenPost(now + i, "bbs", "key", "title", i, "<br>" + "あ" * 50, "回線"), now + i)
    }
    assertEquals(posts.head.recentPosts.map(_.resNum), Vector(6, 5, 4, 3, 2))
    assertEquals(posts.head.recentPosts.head.excerpt, " " + "あ" * 39)
    val revoked = DedicatedBrowserTokens.revoke(posts, "token1", now + 7).toOption.get
    assertEquals(DedicatedBrowserTokens.recordPost(revoked, "token1", posts.head.recentPosts.head, now + 8), revoked)
    assertEquals(DedicatedBrowserTokens.revoke(bound, "token1", Double.NaN), Left(AccountRejection.Invalid))
  }

  test("無効にしたキーの記録は新しいものから上限まで残し、有効なキーは消さない") {
    val revoked = (1 to 30).map(i => DedicatedBrowserToken(f"old$i%02d", f"$i%064x", now, now + 86400, revokedAt = Some(now + i), revokeReason = Some(TokenRevokeReason.Superseded))).toVector
    val kept = DedicatedBrowserTokens.retained(bound ++ revoked, now + 100)
    assertEquals(kept.count(_.revokedAt.isDefined), DedicatedBrowserTokens.revokedLimit)
    assert(kept.exists(_.tokenId == "old30"))
    assert(!kept.exists(_.tokenId == "old10"))
    assert(kept.exists(_.tokenId == "token1"))
  }

  test("有効だったキーが無効・削除になったときだけ照合ハッシュを索引から外す対象にする") {
    val renewed = DedicatedBrowserTokens.issue(issued, "token2", "c" * 64, now + 1).toOption.get
    assertEquals(DedicatedBrowserTokens.released(issued, renewed), Vector(hash))
    assertEquals(DedicatedBrowserTokens.released(renewed, renewed), Vector.empty)
    val revoked = DedicatedBrowserTokens.revoke(bound, "token1", now + 2).toOption.get
    assertEquals(DedicatedBrowserTokens.released(bound, revoked), Vector(hash))
    assertEquals(DedicatedBrowserTokens.released(issued, DedicatedBrowserTokens.retained(issued, now + 86400)), Vector(hash))
  }

  test("抜粋は改行タグを空白にし、文字参照の途中で切らない") {
    assertEquals(AccountPost.excerptOf("一行目<br>二行目"), "一行目 二行目")
    val long = "あ" * 38 + "&gt;" + "い"
    assertEquals(AccountPost.excerptOf(long), "あ" * 38)
    assertEquals(AccountPost.excerptOf("あ" * 36 + "&gt;" + "い"), "あ" * 36 + "&gt;")
    assertEquals(AccountPost.excerptOf("あ" * 39 + "& い"), "あ" * 39 + "&")
    assertEquals(AccountPost.excerptOf("あ" * 50).length, 40)
  }

  test("拒否の記録は新しい順に上限まで残す") {
    val entries = (1 to 25).foldLeft(Vector.empty[AccountPost])((log, i) => AccountRejectionLog.append(log, AccountPost(now + i, result = "auth:token:ua-mismatch")))
    assertEquals(entries.size, AccountRejectionLog.limit)
    assertEquals(entries.head.t, now + 25)
  }
}
