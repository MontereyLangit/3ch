package io._3ch.bbs.domain.account

import io._3ch.common.domain.account.AccountRejection

/** アカウント DO の応答が拒否または不正だったことを Future の失敗として運ぶ */
final case class AccountStoreException(reason: AccountRejection) extends RuntimeException(reason.code)
