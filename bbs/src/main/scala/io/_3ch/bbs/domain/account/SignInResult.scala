package io._3ch.bbs.domain.account

import io._3ch.common.domain.account.{AccountDescription, AccountRejection}

/** パスワード照合と Google の照合の結果 */
enum SignInResult {
  /** 本人と確認でき、利用できるアカウント */
  case Verified(account: AccountDescription)
  /** 本人と確認できたが、登録の確定が途中で止まっている */
  case Unfinished(uid: String)
  /** 照合に失敗した。存在しない ID・不一致・ロック中を区別しない */
  case Failed
  /** 本人と確認できたが、凍結などで利用できない */
  case Refused(reason: AccountRejection)
}
