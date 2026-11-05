package io._3ch.bbs.handler

import io._3ch.bbs.domain.posting.{ModerationDenial, PostingError}
import io._3ch.bbs.external.cloudflare.WorkerEnv
import io._3ch.common.domain.BbsSettings
import io._3ch.common.domain.posting.PostForm
import scala.collection.immutable.ListMap
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.concurrent.Future

/** JS RegExp is intentional: JVM regular expressions accept a different language. */
object Moderation {
  def parseRules(text: String): js.Array[js.Dynamic] = {
    val rules = js.Array[js.Dynamic]()
    text.split("\r?\n", -1).zipWithIndex.foreach { (line, index) =>
      val trimmed = line.asInstanceOf[js.Dynamic].trim().asInstanceOf[String]
      if trimmed.nonEmpty && !trimmed.startsWith("#") then {
        val parts = line.split("<>", -1)
        def fail(message: String): Nothing = throw js.JavaScriptException(new js.Error(s"${index + 1}行目: $message"))
        if parts.length != 4 then fail("板<>対象<>NGワード<>動作 の形式で入力してください。")
        if !Set("", "deny", "mark", "TateDame", "md5").contains(parts(3)) then fail("動作が不正です。")
        try {
          def regex(i: Int) = new js.RegExp(if parts(i).isEmpty then ".*" else parts(i))
          rules.push(js.Dynamic.literal(board = regex(0), target = regex(1), word = regex(2), action = parts(3), isWord = parts(2).nonEmpty))
        } catch case _: js.JavaScriptException => fail("正規表現が不正です。")
      }
    }
    rules
  }

  private def truth(v: js.Any): Boolean = js.Dynamic.global.Boolean(v).asInstanceOf[Boolean]
  private def str(v: js.Any): String = js.Dynamic.global.String(v).asInstanceOf[String]
  /** 規制を評価して gb.form の名前・本文を書き換える。拒否なら gb.error に文言を入れて 0、通過なら 1 */
  def check(gb: js.Dynamic, env: WorkerEnv): js.Promise[Int] = {
    val form = PostForm(name = str(gb.form.FROM), mail = str(gb.form.mail), message = str(gb.form.MESSAGE), subject = str(gb.form.subject), bbs = str(gb.form.bbs))
    val settings = BbsSettings(ListMap.from(gb.settings.asInstanceOf[js.Dictionary[js.Any]].toSeq.map((k, v) => k -> str(v))))
    decide(truth(gb.cap), str(gb.country), str(gb.userInfo), gb.number.asInstanceOf[Double], form, settings, env).map { (denial, marked) =>
      if marked != form then {
        gb.form.FROM = marked.name
        gb.form.MESSAGE = marked.message
      }
      denial.fold(1) { d => gb.error = PostingText.message(PostingError.Moderated(d)); 0 }
    }.toJSPromise
  }

  /** 規制を評価し、拒否理由と mark を付けた後のフォームを返す。最初の拒否で評価をやめる */
  def decide(cap: Boolean, country: String, userInfo: String, number: Double, form: PostForm, settings: BbsSettings, env: WorkerEnv): Future[(Option[ModerationDenial], PostForm)] = {
    if cap || form.bbs == "saku" then Future.successful((None, form))
    else if country == "T1" then Future.successful((Some(ModerationDenial.Tor), form))
    else {
      val content = s"FROM=${form.name} mail=${form.mail} MESSAGE=${form.message} subject=${form.subject}"
      val replaceNumeric: js.Function2[String, String, String] = (entity, digits) => {
        val code = if digits(0).toLower == 'x' then js.Dynamic.global.parseInt(digits.drop(1), 16).asInstanceOf[Double] else js.Dynamic.global.Number(digits).asInstanceOf[Double]
        if code > 0 && code <= 0x10ffff then js.Dynamic.global.String.fromCodePoint(code).asInstanceOf[String] else entity
      }
      val entities = Map("&amp;" -> "&", "&lt;" -> "<", "&gt;" -> ">", "&quot;" -> "\"", "&apos;" -> "'")
      val replaceNamed: js.Function1[String, String] = entity => entities(entity)
      val decoded = content.asInstanceOf[js.Dynamic].replace(new js.RegExp("&#(x[0-9a-f]+|\\d+);", "gi"), replaceNumeric)
        .replace(new js.RegExp("&(?:amp|lt|gt|quot|apos);", "g"), replaceNamed).asInstanceOf[String]
      type Outcome = (Option[ModerationDenial], PostForm)
      def evaluate(outcome: Outcome, rule: js.Dynamic): Outcome = outcome match {
        case (Some(_), _) => outcome
        case (None, marked) =>
          def matches(field: String, value: String) = rule.selectDynamic(field).asInstanceOf[js.RegExp].test(value)
          val isWord = rule.isWord.asInstanceOf[Boolean]
          val action = rule.action.asInstanceOf[String]
          if !matches("board", form.bbs) || !matches("target", userInfo) || !(matches("word", content) || matches("word", decoded)) then outcome
          else if isWord && settings.checked("BBS_NGWORD_PASS") then outcome
          else if action == "TateDame" && number != 1 then outcome
          else if action == "mark" || settings.checked("BBS_NO_MADAKANA") then {
            val name = if !isWord && !marked.name.startsWith("</b>[´・ω・｀]<b> ") then "</b>[´・ω・｀]<b> " + marked.name else marked.name
            (None, marked.copy(name = name, message = marked.message + " <small>👀 Madakana: Caution</small>"))
          } else (Some(if isWord then ModerationDenial.NgWord else if action == "TateDame" then ModerationDenial.TateDame else ModerationDenial.Host), marked)
      }
      def fileLoop(files: List[String], marked: PostForm): Future[Outcome] = files match {
        case Nil => Future.successful((None, marked))
        case file :: rest => env.ch3www.get(file).toFuture.flatMap { obj =>
          if obj == null then fileLoop(rest, marked) else
            obj.text().toFuture.map(parseRules).map(Some(_)).recover { case _ => None }.flatMap {
              case None => Future.successful((Some(ModerationDenial.BrokenRules), marked))
              case Some(rules) => rules.foldLeft[Outcome]((None, marked))(evaluate) match {
                case (None, next) => fileLoop(rest, next)
                case denied => Future.successful(denied)
              }
            }
        }
      }
      fileLoop(List("deny.cgi", "ngwords.cgi"), form)
    }
  }
}
