package io._3ch.bbs.repository

import io._3ch.bbs.application.posting.ports.UserAgentTableStore
import io._3ch.bbs.domain.posting.{UserAgentRule, UserAgentTable}
import io._3ch.bbs.external.cloudflare.WorkerEnv

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.util.Try
import scala.util.control.NonFatal

/** R2 の UA 解析表 ua-profiles.json。{"os": [{"match", "flags"?, "label"}…], "browser": […], "device": […]} の形 */
object UserAgentTableRepository {
  val path: String = "ua-profiles.json"

  /** JSON を読む。書式が不正なら空の表、正規表現として読めない行は飛ばす */
  def parse(text: String): UserAgentTable = Try(js.JSON.parse(text)).toOption.filter(isObject).fold(UserAgentTable.empty) { root =>
    def rules(name: String): Seq[UserAgentRule] = {
      val list = root.selectDynamic(name)
      if (!js.Array.isArray(list)) Nil
      else list.asInstanceOf[js.Array[js.Dynamic]].toSeq.filter(isObject).flatMap { row =>
        for {
          pattern <- string(row.selectDynamic("match"))
          label <- string(row.selectDynamic("label"))
          flags <- if (js.isUndefined(row.selectDynamic("flags"))) Some("") else string(row.selectDynamic("flags"))
          rule <- UserAgentRule.create(pattern, flags, label)
        } yield rule
      }
    }
    UserAgentTable(rules("os"), rules("browser"), rules("device"))
  }

  /** 表を読む。無い・読めないときは空の表にして投稿を止めない */
  def load(e: WorkerEnv): Future[UserAgentTable] =
    Future.unit.flatMap(_ => e.ch3www.get(path).toFuture).flatMap { obj =>
      if (obj == null) Future.successful(UserAgentTable.empty) else obj.text().toFuture.map(parse)
    }.recover { case NonFatal(_) =>
      js.Dynamic.global.console.warn("ua-profiles.json could not be read")
      UserAgentTable.empty
    }

  /** 投稿処理のポート */
  def store(e: WorkerEnv): UserAgentTableStore = () => load(e)

  private def isObject(value: js.Any): Boolean = value != null && js.typeOf(value) == "object" && !js.Array.isArray(value)
  private def string(value: js.Dynamic): Option[String] = if (js.typeOf(value) == "string") Some(value.asInstanceOf[String]) else None
}
