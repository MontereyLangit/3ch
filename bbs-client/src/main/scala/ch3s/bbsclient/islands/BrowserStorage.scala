package ch3s.bbsclient.islands

import org.scalajs.dom

import scala.util.Try

/** localStorage の読み書き。ブラウザ設定で無効化されていても例外にしない。 */
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
