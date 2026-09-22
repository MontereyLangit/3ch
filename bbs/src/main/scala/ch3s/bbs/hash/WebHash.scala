package ch3s.bbs.hash

import ch3s.bbs.external.web.{GlobalBtoa, GlobalCrypto, TextEncoder}

import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

/**
 * `src/common.js` の `sha1Base64` / `hash` の Scala.js 移植。
 *
 * この2関数は Web Crypto (`crypto.subtle.digest`) に依存する非同期処理であり、
 * `common`（cross project, 純粋関数のみ・I/O禁止）には置けない
 * （JVM側にWeb Cryptoは存在せず、`java.security.MessageDigest`で代替すると
 * 「プラットフォーム毎に別の実装」になってしまい、移植方針_step3_ScalaJS.mdが
 * 求める「本番コードは実際に動く一本の実装」から外れる）。
 * そのため課題文の指示どおり、Web Crypto依存部分は `bbs`（Scala.js専用）側に置いた。
 *
 * KB-14: `sha1Base64` は内部で `new TextEncoder().encode(str)` を使うため、
 * 呼び出し元がすでにShift_JISバイト列を文字列化したものを渡してきた場合、それを
 * **再度UTF-8としてバイト化**した上でSHA-1を取る「二重エンコード」になる。
 * これは本家2chのトリップ値と異なる3ch独自のトリップを生む既知の挙動であり、
 * ここでは元のJSと同じく無条件に`TextEncoder`へ委譲することでその二重エンコードを保持する
 * （「正しい」バイト列変換に修正してはいけない）。
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
