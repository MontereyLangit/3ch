package io._3ch.bbs.application.posting

import io._3ch.bbs.domain.posting.{PostingContext, PostingError}

/** 投稿処理の結果。context は最後の状態（スレキーは context.form.key、レス番号は context.number）、headers は応答に足すヘッダ */
enum PostingOutcome {
  def context: PostingContext
  def headers: Seq[(String, String)]

  case Posted(context: PostingContext, headers: Seq[(String, String)])
  case Rejected(error: PostingError, context: PostingContext, headers: Seq[(String, String)])
  /** 書けたように見せて捨てた（連投規制の吸い込み。まだ発生しない） */
  case Swallowed(context: PostingContext, headers: Seq[(String, String)])
}
