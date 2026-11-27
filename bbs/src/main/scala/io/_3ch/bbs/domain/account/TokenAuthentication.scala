package io._3ch.bbs.domain.account

import io._3ch.common.domain.account.AccountDescription

/** 専ブラキーの索引から得た参照 */
final case class TokenReference(aid: String, tokenId: String)

/** Account DO が照合する専ブラキーと、全長の UA ハッシュ */
final case class TokenProof(tokenId: String, uaHash: String)

/** 認証したキーと現在のアカウント */
final case class TokenAuthentication(account: AccountDescription, tokenId: String)
final case class CapAuthentication(account: AccountDescription, capId: String)
