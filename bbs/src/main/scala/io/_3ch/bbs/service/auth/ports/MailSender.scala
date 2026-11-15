package io._3ch.bbs.service.auth.ports

import io._3ch.bbs.domain.account.OutgoingMail
import scala.concurrent.Future

/** メールの送信口。提供元ごとの実装はリポジトリ層に置き、送れなければ Future の失敗で返す */
trait MailSender {
  def send(mail: OutgoingMail): Future[Unit]
}
