package ch3s.bbs.handler

import ch3s.bbs.external.cloudflare.WorkerEnv
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
  def check(gb: js.Dynamic, env: WorkerEnv): js.Promise[Int] = {
    def deny(message: String): Int = { gb.error = message; 0 }
    if truth(gb.cap) || gb.form.bbs.asInstanceOf[String] == "saku" then js.Promise.resolve(1)
    else if gb.country.asInstanceOf[String] == "T1" then js.Promise.resolve(deny("変なホスト規制中！！ Tor is not allowed."))
    else {
      val content = s"FROM=${str(gb.form.FROM)} mail=${str(gb.form.mail)} MESSAGE=${str(gb.form.MESSAGE)} subject=${str(gb.form.subject)}"
      val replaceNumeric: js.Function2[String, String, String] = (entity, digits) => {
        val code = if digits(0).toLower == 'x' then js.Dynamic.global.parseInt(digits.drop(1), 16).asInstanceOf[Double] else js.Dynamic.global.Number(digits).asInstanceOf[Double]
        if code > 0 && code <= 0x10ffff then js.Dynamic.global.String.fromCodePoint(code).asInstanceOf[String] else entity
      }
      val entities = Map("&amp;" -> "&", "&lt;" -> "<", "&gt;" -> ">", "&quot;" -> "\"", "&apos;" -> "'")
      val replaceNamed: js.Function1[String, String] = entity => entities(entity)
      val decoded = content.asInstanceOf[js.Dynamic].replace(new js.RegExp("&#(x[0-9a-f]+|\\d+);", "gi"), replaceNumeric)
        .replace(new js.RegExp("&(?:amp|lt|gt|quot|apos);", "g"), replaceNamed).asInstanceOf[String]
      def fileLoop(files: List[String]): Future[Int] = files match {
        case Nil => Future.successful(1)
        case file :: rest => env.ch3www.get(file).toFuture.flatMap { obj =>
          if obj == null then fileLoop(rest) else
            obj.text().toFuture.map(parseRules).map(Some(_)).recover { case _ => None }.flatMap {
              case None => Future.successful(deny("規制設定に問題があります。管理者にお問い合わせください。"))
              case Some(rules) =>
                val blocked = rules.find { rule =>
                  def matches(field: String, value: String) = rule.selectDynamic(field).asInstanceOf[js.RegExp].test(value)
                  val isWord = rule.isWord.asInstanceOf[Boolean]
                  val action = rule.action.asInstanceOf[String]
                  if !matches("board", str(gb.form.bbs)) || !matches("target", str(gb.userInfo)) || !(matches("word", content) || matches("word", decoded)) then false
                  else if isWord && gb.settings.BBS_NGWORD_PASS.asInstanceOf[js.Any] == "checked".asInstanceOf[js.Any] then false
                  else if action == "TateDame" && gb.number.asInstanceOf[Int] != 1 then false
                  else if action == "mark" || gb.settings.BBS_NO_MADAKANA.asInstanceOf[js.Any] == "checked".asInstanceOf[js.Any] then {
                    if !isWord && !str(gb.form.FROM).startsWith("</b>[´・ω・｀]<b> ") then gb.form.FROM = "</b>[´・ω・｀]<b> " + str(gb.form.FROM)
                    gb.form.MESSAGE = str(gb.form.MESSAGE) + " <small>👀 Madakana: Caution</small>"
                    false
                  } else {
                    deny(if isWord then "NGワード禁止！" else if action == "TateDame" then "このホストではスレッドを立てられません。" else "アクセス規制中です！！")
                    true
                  }
                }
                if blocked.isDefined then Future.successful(0) else fileLoop(rest)
            }
        }
      }
      fileLoop(List("deny.cgi", "ngwords.cgi")).toJSPromise
    }
  }
}
