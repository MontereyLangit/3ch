package io._3ch.bbs.repository

import io._3ch.bbs.application.posting.ports.UserAgentTableStore
import io._3ch.bbs.domain.posting.{UserAgentRule, UserAgentTable}
import io._3ch.bbs.external.cloudflare.WorkerEnv

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.util.Try

/** R2 の UA 解析表 ua-profiles.json。{"os": [{"match", "flags"?, "label"}…], "browser": […], "device": […]} の形 */
object UserAgentTableRepository {
  val path: String = "ua-profiles.json"

  /** 同じ isolate で表を使い回す間隔（ミリ秒） */
  val cacheMillis: Double = 60000

  private val cache = new UserAgentTableCache(cacheMillis, () => js.Date.now())

  /** JSON を読む。JSON の根がオブジェクトでなければ None。正規表現として読めない行と上限を超えた行は飛ばす */
  def read(text: String): Option[UserAgentTable] = Try(js.JSON.parse(text)).toOption.filter(isObject).map { root =>
    def rules(name: String): Seq[UserAgentRule] = {
      val list = root.selectDynamic(name)
      if (!js.Array.isArray(list)) Nil
      else list.asInstanceOf[js.Array[js.Dynamic]].iterator.filter(isObject).flatMap { row =>
        for {
          pattern <- string(row.selectDynamic("match"))
          label <- string(row.selectDynamic("label"))
          flags <- if (js.isUndefined(row.selectDynamic("flags"))) Some("") else string(row.selectDynamic("flags"))
          rule <- UserAgentRule.create(pattern, flags, label)
        } yield rule
      }.take(UserAgentTable.MaxRulesPerList).toSeq
    }
    UserAgentTable(rules("os"), rules("browser"), rules("device"))
  }

  /** JSON を読む。書式が不正なら空の表 */
  def parse(text: String): UserAgentTable = read(text).getOrElse(UserAgentTable.empty)

  /** R2 から表を読む。無ければ空の表。R2 の失敗と書式の不正は Future の失敗 */
  def fetch(e: WorkerEnv): Future[UserAgentTable] =
    Future.unit.flatMap(_ => e.ch3www.get(path).toFuture).flatMap { obj =>
      if (obj == null) Future.successful(UserAgentTable.empty)
      else obj.text().toFuture.map(text => read(text).getOrElse(throw new IllegalArgumentException(s"$path is not a JSON object")))
    }

  /** 投稿処理のポート。表は isolate ごとに [[cacheMillis]] だけ使い回す */
  def store(e: WorkerEnv): UserAgentTableStore = () => cache.get(() => fetch(e))

  private def isObject(value: js.Any): Boolean = value != null && js.typeOf(value) == "object" && !js.Array.isArray(value)
  private def string(value: js.Dynamic): Option[String] = if (js.typeOf(value) == "string") Some(value.asInstanceOf[String]) else None
}
