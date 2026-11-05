package io._3ch.bbs.domain.posting

import io._3ch.common.domain.{BoardId, ThreadKey}

/**
  * bbs.cgi書き込み処理のコンテキスト
  *
  * 投稿者の識別情報[[ClientIdentity]]や投稿先板[[BoardId]], 投稿先スレ[[ThreadKey]], スレ立てフラグなどの投稿時の情報。
  * TODO: 当面の間、今後追加する機能（認証関連と規制関連）でのみ使い、bbs.cgi処理でのgb変数から生成する（将来は直接生成）。
  */
final case class PostingContext(
  board: BoardId,
  threadKey: ThreadKey,
  creatingThread: Boolean,
  title: String,
  identity: ClientIdentity
)
