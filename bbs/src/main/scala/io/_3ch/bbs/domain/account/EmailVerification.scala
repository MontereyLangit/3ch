package io._3ch.bbs.domain.account

import io._3ch.common.domain.account.AccountDescription

/** 期限内の保留トークンの持ち主と、メール認証で確定させるアドレス */
final case class PendingCheck(uid: String, email: Option[String])

/** メール認証の結果と、置き換えた前のアドレス */
final case class EmailVerified(account: AccountDescription, previous: Option[String])
