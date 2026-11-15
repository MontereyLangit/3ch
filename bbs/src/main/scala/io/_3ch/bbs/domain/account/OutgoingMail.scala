package io._3ch.bbs.domain.account

/** 送るメール。本文は固定の文面とトークン付きのリンクだけで、利用者の入力を含めない */
final case class OutgoingMail(to: String, subject: String, text: String)
