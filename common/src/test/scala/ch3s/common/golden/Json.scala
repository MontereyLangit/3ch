package ch3s.common.golden

/**
 * `tests/golden` 配下の JSON ファイルを読むためだけの、依存ライブラリ無しの最小 JSON パーサ。
 *
 * `common` は cross project(JVM/JS) なので、テスト側でも外部JSON libを追加しない方針とした
 * （src本体の「変換ライブラリを使わない」方針に合わせ、テスト基盤も自己完結させる）。
 * 対応するのは object / array / string / number / true / false / null のみで十分
 * （golden データの schema はこれだけで表現できる）。
 */
sealed trait JsonValue {
  def asObj: Map[String, JsonValue] = this match {
    case JsonValue.Obj(m) => m
    case other            => throw new IllegalArgumentException(s"not an object: $other")
  }
  def asArr: List[JsonValue] = this match {
    case JsonValue.Arr(l) => l
    case other            => throw new IllegalArgumentException(s"not an array: $other")
  }
  def asString: String = this match {
    case JsonValue.Str(s) => s
    case other            => throw new IllegalArgumentException(s"not a string: $other")
  }
  def asInt: Int = this match {
    case JsonValue.Num(n) => n.toInt
    case other            => throw new IllegalArgumentException(s"not a number: $other")
  }
  def isNull: Boolean = this == JsonValue.Null
  /** golden生成スクリプトが JS の `undefined` を表すために使うタグ付きオブジェクト `{"$type":"undefined"}` か判定する。 */
  def isUndefinedMarker: Boolean = this match {
    case JsonValue.Obj(m) => m.get("$type").exists(_ == JsonValue.Str("undefined"))
    case _                => false
  }
  def field(name: String): JsonValue = asObj(name)
}

object JsonValue {
  case object Null extends JsonValue
  final case class Bool(v: Boolean) extends JsonValue
  final case class Num(v: Double) extends JsonValue
  final case class Str(v: String) extends JsonValue
  final case class Arr(v: List[JsonValue]) extends JsonValue
  final case class Obj(v: Map[String, JsonValue]) extends JsonValue
}

object Json {

  def parse(input: String): JsonValue = {
    val p = new Parser(input)
    p.skipWs()
    val v = p.parseValue()
    p.skipWs()
    v
  }

  private class Parser(s: String) {
    private var i = 0

    def skipWs(): Unit = {
      while (i < s.length && (s.charAt(i) == ' ' || s.charAt(i) == '\n' || s.charAt(i) == '\r' || s.charAt(i) == '\t')) i += 1
    }

    private def peek: Char = s.charAt(i)

    private def expect(c: Char): Unit = {
      if (i >= s.length || s.charAt(i) != c) throw new IllegalArgumentException(s"expected '$c' at $i in ${context()}")
      i += 1
    }

    private def context(): String = s.substring(math.max(0, i - 20), math.min(s.length, i + 20))

    def parseValue(): JsonValue = {
      skipWs()
      if (i >= s.length) throw new IllegalArgumentException("unexpected end of input")
      peek match {
        case '{' => parseObject()
        case '[' => parseArray()
        case '"' => JsonValue.Str(parseString())
        case 't' => parseLiteral("true"); JsonValue.Bool(true)
        case 'f' => parseLiteral("false"); JsonValue.Bool(false)
        case 'n' => parseLiteral("null"); JsonValue.Null
        case _   => parseNumber()
      }
    }

    private def parseLiteral(lit: String): Unit = {
      if (!s.regionMatches(i, lit, 0, lit.length)) throw new IllegalArgumentException(s"expected $lit at $i")
      i += lit.length
    }

    private def parseObject(): JsonValue.Obj = {
      expect('{')
      skipWs()
      var m = Map.empty[String, JsonValue]
      if (i < s.length && peek == '}') {
        i += 1
        return JsonValue.Obj(m)
      }
      var continue = true
      while (continue) {
        skipWs()
        val key = parseString()
        skipWs()
        expect(':')
        val value = parseValue()
        m = m + (key -> value)
        skipWs()
        if (i < s.length && peek == ',') { i += 1 } else { continue = false }
      }
      skipWs()
      expect('}')
      JsonValue.Obj(m)
    }

    private def parseArray(): JsonValue.Arr = {
      expect('[')
      skipWs()
      var items = List.empty[JsonValue]
      if (i < s.length && peek == ']') {
        i += 1
        return JsonValue.Arr(items)
      }
      val buf = scala.collection.mutable.ListBuffer.empty[JsonValue]
      var continue = true
      while (continue) {
        val v = parseValue()
        buf += v
        skipWs()
        if (i < s.length && peek == ',') { i += 1; skipWs() } else { continue = false }
      }
      skipWs()
      expect(']')
      JsonValue.Arr(buf.toList)
    }

    private def parseString(): String = {
      expect('"')
      val sb = new StringBuilder
      var continue = true
      while (continue) {
        if (i >= s.length) throw new IllegalArgumentException("unterminated string")
        val c = s.charAt(i)
        if (c == '"') { i += 1; continue = false } else if (c == '\\') {
          i += 1
          val esc = s.charAt(i)
          esc match {
            case '"'  => sb.append('"'); i += 1
            case '\\' => sb.append('\\'); i += 1
            case '/'  => sb.append('/'); i += 1
            case 'b'  => sb.append('\b'); i += 1
            case 'f'  => sb.append('\f'); i += 1
            case 'n'  => sb.append('\n'); i += 1
            case 'r'  => sb.append('\r'); i += 1
            case 't'  => sb.append('\t'); i += 1
            case 'u' =>
              val hex = s.substring(i + 1, i + 5)
              sb.append(Integer.parseInt(hex, 16).toChar)
              i += 5
            case other => throw new IllegalArgumentException(s"bad escape \\$other")
          }
        } else {
          sb.append(c)
          i += 1
        }
      }
      sb.toString
    }

    private def parseNumber(): JsonValue.Num = {
      val start = i
      if (i < s.length && (s.charAt(i) == '-' || s.charAt(i) == '+')) i += 1
      while (i < s.length && (s.charAt(i).isDigit || s.charAt(i) == '.' || s.charAt(i) == 'e' || s.charAt(i) == 'E' || s.charAt(i) == '+' || s.charAt(i) == '-')) i += 1
      val numStr = s.substring(start, i)
      JsonValue.Num(numStr.toDouble)
    }
  }
}
