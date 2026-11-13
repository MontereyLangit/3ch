package io._3ch.bbs.application.posting.ports

import scala.scalajs.js

/** 0 以上 1 未満の乱数 */
trait Random {
  def next(): Double
}

object Random {
  /** 実行環境の Math.random を使う */
  val system: Random = () => js.Math.random()
}
