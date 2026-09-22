package ch3s.common.crypt

/**
 * `src/common.js` の Unix crypt(3) DESベース実装（Tim Joseph Dumol 氏によるJS実装。
 * Seventh Edition Unix の crypt.c を元にした著作物。ライセンス条文は `src/common.js`
 * 末尾のコメントを参照）の Scala 3 移植。
 *
 * ポート方針: ビット演算・添字計算をJSソースと1:1で対応させる。JS実装はモジュール
 * レベルの共有可変配列（`C`,`D`,`KS`,`E`,`L`,`R`,`tempL`,`f`,`preS`）を使うクロージャだが、
 * それらは `crypt()` の呼び出し毎に必要な範囲を全て上書きしてから読むため、
 * 呼び出し間で状態が漏れることはない（同期的なJS実行を前提にした最適化に過ぎない）。
 * Scala版ではこれをメソッドローカルな配列に置き換えている（出力は同一、かつスレッド安全）。
 *
 * `pw`/`salt` の文字は JS の `charCodeAt` と同じ扱い（UTF-16コード単位の値をそのまま使う。
 * DESの鍵設定では下位7bitしか読まないため、8bit目以降は元のJSと同様に無視される）。
 */
object UnixCrypt {

  // 初期転置(Initial permutation)
  private val IP = Array(
    58, 50, 42, 34, 26, 18, 10, 2,
    60, 52, 44, 36, 28, 20, 12, 4,
    62, 54, 46, 38, 30, 22, 14, 6,
    64, 56, 48, 40, 32, 24, 16, 8,
    57, 49, 41, 33, 25, 17, 9, 1,
    59, 51, 43, 35, 27, 19, 11, 3,
    61, 53, 45, 37, 29, 21, 13, 5,
    63, 55, 47, 39, 31, 23, 15, 7
  )

  // 最終転置(Final permutation), FP = IP^(-1)
  private val FP = Array(
    40, 8, 48, 16, 56, 24, 64, 32,
    39, 7, 47, 15, 55, 23, 63, 31,
    38, 6, 46, 14, 54, 22, 62, 30,
    37, 5, 45, 13, 53, 21, 61, 29,
    36, 4, 44, 12, 52, 20, 60, 28,
    35, 3, 43, 11, 51, 19, 59, 27,
    34, 2, 42, 10, 50, 18, 58, 26,
    33, 1, 41, 9, 49, 17, 57, 25
  )

  private val PC1_C = Array(
    57, 49, 41, 33, 25, 17, 9,
    1, 58, 50, 42, 34, 26, 18,
    10, 2, 59, 51, 43, 35, 27,
    19, 11, 3, 60, 52, 44, 36
  )

  private val PC1_D = Array(
    63, 55, 47, 39, 31, 23, 15,
    7, 62, 54, 46, 38, 30, 22,
    14, 6, 61, 53, 45, 37, 29,
    21, 13, 5, 28, 20, 12, 4
  )

  private val Shifts = Array(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)

  private val PC2_C = Array(
    14, 17, 11, 24, 1, 5,
    3, 28, 15, 6, 21, 10,
    23, 19, 12, 4, 26, 8,
    16, 7, 27, 20, 13, 2
  )

  private val PC2_D = Array(
    41, 52, 31, 37, 47, 55,
    30, 40, 51, 45, 33, 48,
    44, 49, 39, 56, 34, 53,
    46, 42, 50, 36, 29, 32
  )

  // Eビット選択表のベース値（塩によって6bit単位で左右が入れ替わる。cryptOne内でコピーして使う）
  private val eBase = Array(
    32, 1, 2, 3, 4, 5,
    4, 5, 6, 7, 8, 9,
    8, 9, 10, 11, 12, 13,
    12, 13, 14, 15, 16, 17,
    16, 17, 18, 19, 20, 21,
    20, 21, 22, 23, 24, 25,
    24, 25, 26, 27, 28, 29,
    28, 29, 30, 31, 32, 1
  )

  // 8つの選択関数(S-box)。JSコメントの通り0-origin。
  private val S: Array[Array[Int]] = Array(
    Array(14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7,
      0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
      4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0,
      15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13),
    Array(15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10,
      3, 13, 4, 7, 15, 2, 8, 14, 12, 0, 1, 10, 6, 9, 11, 5,
      0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15,
      13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9),
    Array(10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8,
      13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
      13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7,
      1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12),
    Array(7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15,
      13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
      10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4,
      3, 15, 0, 6, 10, 1, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14),
    Array(2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9,
      14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
      4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14,
      11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3),
    Array(12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11,
      10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
      9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6,
      4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13),
    Array(4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1,
      13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
      1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2,
      6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12),
    Array(13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7,
      1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
      7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8,
      2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11)
  )

  private val P = Array(
    16, 7, 20, 21,
    29, 12, 28, 17,
    1, 15, 23, 26,
    5, 18, 31, 10,
    2, 8, 24, 14,
    32, 27, 3, 9,
    19, 13, 30, 6,
    22, 11, 4, 25
  )

  /** 鍵(64bit、`block`にビット単位で格納済み)からKey Schedule(16 x 48bit)を生成する。 */
  private def setkey(key: Array[Int]): Array[Array[Int]] = {
    val C = new Array[Int](28)
    val D = new Array[Int](28)
    val KS = Array.ofDim[Int](16, 48)

    var i = 0
    while (i < 28) {
      C(i) = key(PC1_C(i) - 1)
      D(i) = key(PC1_D(i) - 1)
      i += 1
    }

    i = 0
    while (i < 16) {
      var k = 0
      while (k < Shifts(i)) {
        val tC = C(0)
        var j = 0
        while (j < 28 - 1) {
          C(j) = C(j + 1)
          j += 1
        }
        C(27) = tC

        val tD = D(0)
        j = 0
        while (j < 28 - 1) {
          D(j) = D(j + 1)
          j += 1
        }
        D(27) = tD

        k += 1
      }

      var j = 0
      while (j < 24) {
        KS(i)(j) = C(PC2_C(j) - 1)
        KS(i)(j + 24) = D(PC2_D(j) - 28 - 1)
        j += 1
      }
      i += 1
    }

    KS
  }

  /** 1ブロック(64bit、`block`にビット単位で格納済み)を暗号化する(`edflag`は常に0=暗号化方向)。 */
  private def encrypt(block: Array[Int], KS: Array[Array[Int]], E: Array[Int]): Unit = {
    val L = new Array[Int](32)
    val R = new Array[Int](32)
    val tempL = new Array[Int](32)
    val f = new Array[Int](32)
    val preS = new Array[Int](48)

    val perm = new Array[Int](64)
    var j = 0
    while (j < 64) {
      perm(j) = block(IP(j) - 1)
      j += 1
    }
    j = 0
    while (j < 32) {
      L(j) = perm(j)
      R(j) = perm(j + 32)
      j += 1
    }

    var ii = 0
    while (ii < 16) {
      val i = ii // edflag=0固定なので常に順方向

      j = 0
      while (j < 32) {
        tempL(j) = R(j)
        j += 1
      }

      j = 0
      while (j < 48) {
        preS(j) = R(E(j) - 1) ^ KS(i)(j)
        j += 1
      }

      j = 0
      while (j < 8) {
        val t0 = 6 * j
        val k =
          S(j)(
            (preS(t0 + 0) << 5) +
              (preS(t0 + 1) << 3) +
              (preS(t0 + 2) << 2) +
              (preS(t0 + 3) << 1) +
              (preS(t0 + 4) << 0) +
              (preS(t0 + 5) << 4)
          )
        val t1 = 4 * j
        f(t1 + 0) = (k >> 3) & 1
        f(t1 + 1) = (k >> 2) & 1
        f(t1 + 2) = (k >> 1) & 1
        f(t1 + 3) = (k >> 0) & 1
        j += 1
      }

      j = 0
      while (j < 32) {
        R(j) = L(j) ^ f(P(j) - 1)
        j += 1
      }

      j = 0
      while (j < 32) {
        L(j) = tempL(j)
        j += 1
      }

      ii += 1
    }

    j = 0
    while (j < 32) {
      val t = L(j)
      L(j) = R(j)
      R(j) = t
      j += 1
    }

    j = 0
    while (j < 32) {
      perm(j) = L(j)
      perm(j + 32) = R(j)
      j += 1
    }
    j = 0
    while (j < 64) {
      block(j) = perm(FP(j) - 1)
      j += 1
    }
  }

  /**
   * `pw`/`salt` の各文字の UTF-16 コード単位値をそのまま「バイト」として扱う
   * （JSの `charCodeAt` と同じ。DESの鍵設定は下位7bitしか読まないため、多バイト文字が
   * 混じっていても上位ビットは単に無視される＝元のJS実装と同じ挙動）。
   */
  private def charCodes(s: String): Array[Int] = {
    val arr = new Array[Int](s.length)
    var i = 0
    while (i < s.length) {
      arr(i) = s.charAt(i).toInt
      i += 1
    }
    arr
  }

  /** crypt(3) の中核処理。`iobuf`(長さ13、末尾は未使用領域含む)を返す。 */
  private def cryptCore(pwBytes: Array[Int], saltBytes: Array[Int]): Array[Int] = {
    val block = new Array[Int](66)
    val iobuf = new Array[Int](13)

    var i = 0
    var k = 0
    while (i < 64 && k < pwBytes.length && pwBytes(k) != 0) {
      val c = pwBytes(k)
      var j = 0
      while (j < 7) {
        block(i) = (c >> (6 - j)) & 1
        j += 1
        i += 1
      }
      i += 1
      k += 1
    }

    val KS = setkey(block)

    java.util.Arrays.fill(block, 0)

    val E = new Array[Int](48)
    i = 0
    while (i < 48) {
      E(i) = eBase(i)
      i += 1
    }

    i = 0
    k = 0
    while (i < 2) {
      if (k < saltBytes.length) {
        var c = saltBytes(k)
        iobuf(i) = c
        if (c > 'Z') c -= 6
        if (c > '9') c -= 7
        c -= '.'
        var j = 0
        while (j < 6) {
          if (((c >> j) & 1) != 0) {
            val temp = E(6 * i + j)
            E(6 * i + j) = E(6 * i + j + 24)
            E(6 * i + j + 24) = temp
          }
          j += 1
        }
      } else {
        // JS: salt[k] が範囲外だと undefined。iobuf[i]=undefined は後で文字列化される際
        // String.fromCharCode(undefined) => NaN => ToUint16(NaN)=0 としてNULになる。
        // また c=NaN として (c>>j)&1 は常に0（NaN>>j は ToInt32(NaN)=0 になるため）で
        // Eの入れ替えは一切発生しない（この回のswap自体を丸ごとスキップする）。
        //
        // 注意: ここを単純に iobuf(i)=0 とすると、後段の `if(iobuf[1]==0) iobuf[1]=iobuf[0]`
        // 判定が「JSではundefined==0はfalseだから発動しない」のに対し「Scalaでは0==0で
        // trueになり発動してしまう」という食い違いが起きる（実測: salt="a" で確認済み。
        // JSは "a ..." を返すが、0で埋めると "aa..." になってしまう）。
        // そのため JS の undefined を表す専用の負の番兵 UndefinedSalt を使い分ける。
        iobuf(i) = UndefinedSalt
      }
      i += 1
      k += 1
    }

    i = 0
    while (i < 25) {
      encrypt(block, KS, E)
      i += 1
    }

    i = 0
    while (i < 11) {
      var c = 0
      var j = 0
      while (j < 6) {
        c <<= 1
        c |= block(6 * i + j)
        j += 1
      }
      c += '.'
      if (c > '9') c += 7
      if (c > 'Z') c += 6
      iobuf(i + 2) = c
      i += 1
    }
    // JS: `if(iobuf[1]==0) iobuf[1]=iobuf[0];`。iobuf[1]がundefined相当(UndefinedSalt)の場合は
    // `undefined==0`がfalseなので発動しない（この比較はJSの`==`の挙動に合わせ、番兵とは
    // 別物として扱う。番兵はここでは実際の0とは異なる値なので自然に一致しない）。
    if (iobuf(1) == 0) iobuf(1) = iobuf(0)

    iobuf
  }

  /** JSの `undefined` に相当する番兵。`bytesToStr`/バイト配列化の際に0(NUL)へ変換する。 */
  private val UndefinedSalt = Int.MinValue

  private def toOutputByte(v: Int): Int = if (v == UndefinedSalt) 0 else v

  /** `crypt(pw, salt)`(文字列を返す版)。JSの `returnBytes` 省略時と同じ。 */
  def crypt(pw: String, salt: String): String = {
    val iobuf = cryptCore(charCodes(pw), charCodes(salt))
    val sb = new StringBuilder(iobuf.length)
    var i = 0
    while (i < iobuf.length) {
      sb.append(toOutputByte(iobuf(i)).toChar)
      i += 1
    }
    sb.toString
  }

  /**
   * `crypt(pw, salt, true)` 相当(バイト配列を返す版)。
   * ソルトが2文字未満の場合、対応する要素はJSの`undefined`に相当する`None`になる
   * （`crypt()`文字列版のようにNULへ変換してしまうと、JSの`returnBytes=true`時に
   * 実際に`undefined`という値がそのまま配列に残ることを再現できなくなるため、
   * こちらは変換せずに区別を保持する）。
   */
  def cryptBytes(pw: String, salt: String): Array[Option[Int]] =
    cryptCore(charCodes(pw), charCodes(salt)).map { v =>
      if (v == UndefinedSalt) None else Some(v)
    }
}
