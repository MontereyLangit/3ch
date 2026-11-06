package io._3ch.bbs.repository

import io._3ch.bbs.application.auth.ports.CapDirectory
import io._3ch.bbs.application.posting.ports.CapStore
import io._3ch.bbs.external.cloudflare.WorkerEnv
import io._3ch.common.domain.posting.CapHandle

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

/** KV の CAP からキャップを読む */
object CapRepository {
  /** 照合キーに対応するキャップを読む。未登録や表示名が空ならキャップにしない */
  def get(key: String, env: WorkerEnv): Future[Option[CapHandle]] = env.CAP.get(key).toFuture.map { value =>
    if (value == null || js.isUndefined(value)) None else CapHandle.fromStored(value.asInstanceOf[String])
  }

  /** 投稿処理のポート */
  def store(e: WorkerEnv): CapStore = key => get(key, e)

  /** キャップの発行・失効で KV を書き換える */
  def directory(e: WorkerEnv): CapDirectory = new CapDirectory {
    def exists(key: String): Future[Boolean] = e.CAP.get(key).toFuture.map(value => value != null && !js.isUndefined(value))
    def put(key: String, storedName: String): Future[Unit] = e.CAP.put(key, storedName).toFuture
    def delete(key: String): Future[Unit] = e.CAP.delete(key).toFuture
  }
}
