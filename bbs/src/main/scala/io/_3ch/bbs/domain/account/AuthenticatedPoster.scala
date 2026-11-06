package io._3ch.bbs.domain.account

import io._3ch.common.domain.account.{AccountDescription, AccountVia, SessionClaims}

/** 投稿で認証したアカウントと経路。session は応答で Cookie を発行し直すときの内容で、画面には渡さない */
final case class AuthenticatedPoster(account: AccountDescription, via: AccountVia, tokenId: Option[String], capId: Option[String], device: String,
  session: Option[SessionClaims], refreshSession: Boolean = true)
