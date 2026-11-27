package io._3ch.bbs.domain.account

/** 再発行で失効させたキャップの照合値と、新しいキャップを KV に書くときの表示名 */
final case class CapReissue(previousHash: String, storedName: String)
