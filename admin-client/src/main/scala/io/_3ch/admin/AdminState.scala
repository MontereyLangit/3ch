package io._3ch.admin

import io._3ch.common.admin.{AdminError, AdminErrorCode}
import japgolly.scalajs.react.*

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** 読み込みの状態 */
enum LoadState[+A] {
  case Loading
  case Ready(value: A)
  case Failed(error: AdminError)
}

/** 保存・操作の状態。fields は入力欄ごとの誤り */
enum SaveState {
  case Idle
  case Saving
  case Saved(message: String)
  case Conflict(message: String)
  case Invalid(message: String, fields: Map[String, String])
  case Failed(message: String)

  /** 入力欄の誤り */
  def field(name: String): Option[String] = this match {
    case Invalid(_, fields) => fields.get(name)
    case _ => None
  }

  def busy: Boolean = this == Saving
}

object SaveState {
  /** API のエラーを状態にする */
  def from(error: AdminError): SaveState = error.code match {
    case AdminErrorCode.Conflict => Conflict(error.message)
    case AdminErrorCode.Invalid => Invalid(error.message, error.fields)
    case _ => Failed(error.message)
  }
}

/** 読み込んだ値と、読み直し・差し替えの Callback */
final case class Loaded[A](state: LoadState[A], reload: Callback, replace: A => Callback)

object Loaded {
  /** key が変わるか reload で読み直す。古い要求の応答は捨てる */
  def useLoad[A](key: String)(load: () => Future[Either[AdminError, A]]): HookResult[Loaded[A]] = for {
    state <- useState[LoadState[A]](LoadState.Loading)
    version <- useState(0)
    latest <- useRef(0)
    _ <- useEffectWithDeps((key, version.value)) { _ =>
      Callback {
        latest.value += 1
        val request = latest.value
        state.setState(LoadState.Loading).runNow()
        load().foreach { result =>
          if (request == latest.value) state.setState(result.fold(LoadState.Failed(_), LoadState.Ready(_))).runNow()
        }
      }
    }
  } yield Loaded(state.value, version.modState(_ + 1), value => state.setState(LoadState.Ready(value)))
}
