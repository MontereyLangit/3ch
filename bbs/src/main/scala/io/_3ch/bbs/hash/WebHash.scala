package io._3ch.bbs.hash

import io._3ch.bbs.external.web.{GlobalBtoa, GlobalCrypto, TextEncoder}

import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

/**
 * `src/common.js` の `sha1Base64` / `hash` の移植（Web Crypto 依存のため `bbs` 側に置く）
 * KB-14 の二重エンコードを保持する
 */
object WebHash {

  /** `sha1Base64(str)`: SHA-1のBase64エンコードを先頭27文字にスライスして返す。 */
  def sha1Base64(str: String): js.Promise[String] = {
    val data = new TextEncoder().encode(str)
    GlobalCrypto.subtle.digest("SHA-1", data).`then` { buffer =>
      val bytes = new Uint8Array(buffer)
      val chars = new StringBuilder(bytes.length)
      var i = 0
      while (i < bytes.length) {
        chars.append((bytes(i).toInt & 0xff).toChar)
        i += 1
      }
      val b64 = GlobalBtoa(chars.toString)
      b64.substring(0, math.min(27, b64.length))
    }
  }

  /** `hash(algo, str)`: 指定アルゴリズム(例: "SHA-256")のダイジェストを16進(小文字)文字列で返す。 */
  def hash(algo: String, str: String): js.Promise[String] = {
    val data = new TextEncoder().encode(str)
    GlobalCrypto.subtle.digest(algo, data).`then` { buffer =>
      val bytes = new Uint8Array(buffer)
      val sb = new StringBuilder(bytes.length * 2)
      var i = 0
      while (i < bytes.length) {
        val h = Integer.toHexString(bytes(i).toInt & 0xff)
        if (h.length < 2) sb.append('0')
        sb.append(h)
        i += 1
      }
      sb.toString
    }
  }
}
