package io._3ch.bbs.domain.posting

import io._3ch.common.domain.BbsSettings
import io._3ch.common.domain.posting.PostForm

/** 入力欄の文字数と本文の行数の上限 */
object PostFormLimits {
  /**
    * 題名・名前・メール・本文の文字数と返信の行数を検査し、名前が空なら BBS_NONAME_NAME にする。強キャップは何もしない
    * 本文が !ADMIN: で始まれば本文の検査と名前の補完をしない。スレ立ての本文は 65536 文字まで許す
    */
  def check(form: PostForm, settings: BbsSettings, number: Int, strongCap: Boolean): Either[PostingError, PostForm] = {
    /** 上限値と、文言に出す上限の文字列（設定値そのもの、無ければ既定値） */
    def limit(name: String, default: Int): (Double, String) = (settings.number(name, default), settings.get(name).getOrElse(default.toString))
    val fields = List((form.subject, "BBS_SUBJECT_COUNT", PostField.Subject), (form.name, "BBS_NAME_COUNT", PostField.Name), (form.mail, "BBS_MAIL_COUNT", PostField.Mail))
    def tooLong: Option[PostingError] = fields.iterator.flatMap { case (value, setting, field) =>
      val (max, shown) = limit(setting, 64)
      Option.when(value.length > max)(PostingError.FieldTooLong(field, value.length, shown))
    }.nextOption()
    if (strongCap) Right(form)
    else tooLong match {
      case Some(error) => Left(error)
      case None if form.message.startsWith("!ADMIN:") => Right(form)
      case None => checkMessage(form, settings, number, limit("BBS_MESSAGE_COUNT", 4096))
    }
  }

  private def checkMessage(form: PostForm, settings: BbsSettings, number: Int, configured: (Double, String)): Either[PostingError, PostForm] = {
    val message = form.message
    val (messageMax, messageShown) = if (number == 1 && configured._1 < 65536) (65536d, "65536") else configured
    val lines = message.split("<br>", -1).length
    val lineLimit = settings.number("BBS_LINE_NUMBER", 30) * 2
    if (message.length > messageMax) Left(PostingError.FieldTooLong(PostField.Message, message.length, messageShown))
    else if (number >= 2 && lines > lineLimit) Left(PostingError.TooManyLines(lines, lineLimit.toString))
    else Right(if (form.name.isEmpty) form.copy(name = settings.orElse("BBS_NONAME_NAME", "名無しさん＠３ちゃんねる")) else form)
  }
}
