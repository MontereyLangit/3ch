package io._3ch.bbs.repository

import io._3ch.bbs.external.cloudflare.WorkerEnv
import io._3ch.common.domain.posting.CapHandle

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

object CapRepository {
  /** 照合キーに対応するキャップを読む。未登録と空の表示名はキャップにしない。 */
  def get(key: String, env: WorkerEnv): Future[Option[CapHandle]] = env.CAP.get(key).toFuture.map { value =>
    if (value == null || js.isUndefined(value)) None else CapHandle.fromStored(value.asInstanceOf[String])
  }
}
