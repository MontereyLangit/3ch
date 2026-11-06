package io._3ch.bbs.application.auth.ports

import scala.concurrent.Future

/** 送るメール。本文は固定の文面とトークン付きのリンクだけで、利用者の入力を含めない */
final case class OutgoingMail(to: String, subject: String, text: String)

/** メールの送信口。提供元ごとの実装はリポジトリ層に置き、送れなければ Future の失敗で返す */
trait MailSender {
  def send(mail: OutgoingMail): Future[Unit]
}
