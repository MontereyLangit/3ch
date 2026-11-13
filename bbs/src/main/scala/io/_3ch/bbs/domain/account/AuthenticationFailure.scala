package io._3ch.bbs.domain.account

import io._3ch.common.domain.account.AccountRejection

/** 投稿認証の拒否理由。画面の文言は HTTP 層で組み立てる */
enum AuthenticationFailure {
  case Account(reason: AccountRejection)
  case LoginRequired
  case TokenNotDedicated
  case TokenInsecure
  case Unavailable
  /** エッジ防御でデータセンターからの投稿と判定した */
  case Datacenter

  def code: String = this match {
    case Account(reason) => reason.code
    case LoginRequired => "auth:required:login"
    case TokenNotDedicated => "auth:token:not-dedicated"
    case TokenInsecure => "auth:token:insecure"
    case Unavailable => "auth:unavailable"
    case Datacenter => "auth:edge:datacenter"
  }
}
