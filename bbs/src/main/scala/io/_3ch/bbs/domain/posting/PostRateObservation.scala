package io._3ch.bbs.domain.posting

import io._3ch.bbs.domain.posting.moderation.PostRateFinding

/** 連投規制の試行モードで拒否しなかった判定。ip・shiki はソルト付きハッシュ */
final case class PostRateObservation(bbs: String, key: String, number: Int, findings: Seq[PostRateFinding], ip: String, shiki: String)
