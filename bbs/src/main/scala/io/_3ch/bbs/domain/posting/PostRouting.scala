package io._3ch.bbs.domain.posting

import io._3ch.common.domain.posting.PostForm

import scala.scalajs.js
import scala.scalajs.js.JSStringOps.*

/** 復号済みフォームの振り分け（!newthread、全板共通スレ、禁止板）と必須項目の検査 */
object PostRouting {
  private val NewThread = new js.RegExp("^!newthread([^\\r\\t\\n\\f\\v<]+)(.*)$")
  private val LineBreak = new js.RegExp(" ?<br> ?", "g")
  private val BoardChars = new js.RegExp("[^a-zA-Z0-9_-]", "g")
  private val NonDigit = new js.RegExp("\\D", "g")
  private val Digits = new js.RegExp("^[0-9]+$")
  private val Forbidden = Set("", "test", "js", "css", "html", "images", "auth", "admin")

  /**
    * スレ立てなら key を unixtime（本文先頭が !924・!900 なら共通スレのキー）にし、返信なら題名を空にする。
    * 拒否時はそこまで整えたフォームを添えて返す
    */
  def route(form: PostForm, unixtime: String): Either[(PostingError, PostForm), PostForm] = {
    val next = NewThread.exec(form.message)
    val converted = if (form.key.startsWith("9") && next != null) form.copy(subject = next(1).get, message = next(2).get, key = "") else form
    val stripped = converted.copy(
      subject = converted.subject.jsReplace(LineBreak, ""),
      name = converted.name.jsReplace(LineBreak, ""),
      mail = converted.mail.jsReplace(LineBreak, ""),
      submit = converted.submit.jsReplace(LineBreak, ""),
      bbs = converted.bbs.jsReplace(BoardChars, ""),
      key = converted.key.jsReplace(NonDigit, ""),
      time = converted.time.jsReplace(NonDigit, "")
    )
    val f = if (GlobalThread.kindOf(stripped.key).isDefined) stripped.copy(bbs = GlobalThread.board) else stripped
    if (Forbidden.contains(f.bbs)) Left(PostingError.ForbiddenBoard -> f)
    else if (f.message.isEmpty) Left(PostingError.EmptyMessage -> f)
    else if ((f.key.isEmpty || !Digits.test(f.key)) && f.subject.isEmpty) Left(PostingError.MissingSubject(f.key) -> f)
    else if (f.time.isEmpty || !Digits.test(f.time)) Left(PostingError.InvalidTime(f.time) -> f)
    else {
      val reply = if (f.key.nonEmpty) f.copy(subject = "") else f
      if (reply.subject.isEmpty) Right(reply)
      else {
        val created = reply.copy(key = unixtime)
        GlobalThread.command(created.message) match {
          case None => Right(created)
          case Some((_, rest)) if rest.isEmpty => Left(PostingError.EmptyMessage -> created)
          case Some((kind, rest)) => Right(created.copy(message = rest, key = GlobalThread.key(kind, unixtime), bbs = GlobalThread.board))
        }
      }
    }
  }
}
