package io._3ch.bbs.repository

import io._3ch.bbs.application.auth.ports.{MailSender, OutgoingMail}
import io._3ch.bbs.external.cloudflare.{MailBinding, WorkerEnv}
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/** メールの送信先の設定。どれも無ければ None で、メール認証と再設定を止める */
object MailSenders {
  /** MAIL_SENDER（send を持つ service binding）を使う。提供元を選んだら MAIL_API_KEY を読む実装をここに足す */
  def from(env: WorkerEnv): Option[MailSender] =
    env.MAIL_SENDER.toOption.filter(b => b != null && js.typeOf(b.asInstanceOf[js.Dynamic].send) == "function").map(binding)

  private def binding(target: MailBinding): MailSender = (mail: OutgoingMail) =>
    Future.unit.flatMap(_ => target.send(js.Dynamic.literal(to = mail.to, subject = mail.subject, text = mail.text)).toFuture).map(_ => ())
}
