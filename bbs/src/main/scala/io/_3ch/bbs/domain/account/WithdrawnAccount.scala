package io._3ch.bbs.domain.account

/** 退会した Account から外す値。caps は失効させたキャップの KV の照合値 */
final case class WithdrawnAccount(loginId: Option[String], email: Option[String], googleSubHash: Option[String], caps: Vector[String])
