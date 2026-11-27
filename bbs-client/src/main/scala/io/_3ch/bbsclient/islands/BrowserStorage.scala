package io._3ch.bbsclient.islands

import org.scalajs.dom

import scala.util.Try

/**
 * localStorage を読み書きする。
 * ブラウザ設定で無効にされていても例外にしない
 */
private[islands] object BrowserStorage {
  def read(key: String): Option[String] = Try(Option(dom.window.localStorage.getItem(key))).toOption.flatten

  def write(key: String, value: String): Unit = {
    Try(dom.window.localStorage.setItem(key, value))
    ()
  }

  def remove(key: String): Unit = {
    Try(dom.window.localStorage.removeItem(key))
    ()
  }
}
