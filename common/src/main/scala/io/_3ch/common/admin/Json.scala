package io._3ch.common.admin

/** 管理 API の JSON の値。オブジェクトはキーの順を保つ */
enum Json {
  case Null
  case Bool(value: Boolean)
  case Num(value: Double)
  case Str(value: String)
  case Arr(items: List[Json])
  case Obj(fields: List[(String, Json)])

  /** オブジェクトの項目。同じキーが複数あれば後の値 */
  def get(name: String): Option[Json] = this match {
    case Obj(fields) => fields.reverseIterator.collectFirst { case (`name`, value) => value }
    case _ => None
  }
}

/** 依存ライブラリを使わない JSON の読み書き（RFC 8259 の範囲） */
object Json {
  private val MaxDepth = 64

  /** 文字列にする。数は整数なら小数点を付けない */
  def render(value: Json): String = {
    val out = new StringBuilder
    write(value, out)
    out.toString
  }

  /** 読む。誤りがあれば位置を含む理由 */
  def parse(text: String): Either[String, Json] = {
    val parser = new Parser(text)
    try {
      val value = parser.value(0)
      parser.end()
      Right(value)
    } catch {
      case e: IllegalArgumentException => Left(e.getMessage)
    }
  }

  private def write(value: Json, out: StringBuilder): Unit = value match {
    case Null => out.append("null")
    case Bool(b) => out.append(if (b) "true" else "false")
    case Num(n) =>
      if (n.isNaN || n.isInfinite) out.append("null")
      else if (n == math.rint(n) && math.abs(n) < 9.007199254740992e15) out.append(n.toLong.toString)
      else out.append(n.toString)
    case Str(s) => quote(s, out)
    case Arr(items) =>
      out.append('[')
      items.zipWithIndex.foreach { (item, i) =>
        if (i > 0) out.append(',')
        write(item, out)
      }
      out.append(']')
    case Obj(fields) =>
      out.append('{')
      fields.zipWithIndex.foreach { case ((name, item), i) =>
        if (i > 0) out.append(',')
        quote(name, out)
        out.append(':')
        write(item, out)
      }
      out.append('}')
  }

  private def quote(s: String, out: StringBuilder): Unit = {
    out.append('"')
    s.foreach {
      case '"' => out.append("\\\"")
      case '\\' => out.append("\\\\")
      case '\n' => out.append("\\n")
      case '\r' => out.append("\\r")
      case '\t' => out.append("\\t")
      case c if c < ' ' || c == ' ' || c == ' ' => out.append(f"\\u${c.toInt}%04x")
      case c => out.append(c)
    }
    out.append('"')
  }

  private final class Parser(s: String) {
    private var i = 0

    private def fail(reason: String): Nothing = throw new IllegalArgumentException(s"$reason (位置 $i)")

    private def skip(): Unit = while (i < s.length && " \t\r\n".indexOf(s.charAt(i).toInt) >= 0) i += 1

    private def expect(c: Char): Unit = {
      if (i >= s.length || s.charAt(i) != c) fail(s"$c がありません")
      i += 1
    }

    def end(): Unit = {
      skip()
      if (i < s.length) fail("余分な文字があります")
    }

    def value(depth: Int): Json = {
      if (depth > MaxDepth) fail("入れ子が深すぎます")
      skip()
      if (i >= s.length) fail("値がありません")
      s.charAt(i) match {
        case '{' => obj(depth)
        case '[' => arr(depth)
        case '"' => Str(string())
        case 't' => literal("true", Bool(true))
        case 'f' => literal("false", Bool(false))
        case 'n' => literal("null", Null)
        case _ => number()
      }
    }

    private def literal(word: String, result: Json): Json = {
      if (!s.startsWith(word, i)) fail("値が不正です")
      i += word.length
      result
    }

    private def obj(depth: Int): Json = {
      expect('{')
      skip()
      val fields = List.newBuilder[(String, Json)]
      if (i < s.length && s.charAt(i) == '}') i += 1
      else {
        var more = true
        while (more) {
          skip()
          val name = string()
          skip()
          expect(':')
          fields += name -> value(depth + 1)
          skip()
          if (i < s.length && s.charAt(i) == ',') i += 1 else more = false
        }
        expect('}')
      }
      Obj(fields.result())
    }

    private def arr(depth: Int): Json = {
      expect('[')
      skip()
      val items = List.newBuilder[Json]
      if (i < s.length && s.charAt(i) == ']') i += 1
      else {
        var more = true
        while (more) {
          items += value(depth + 1)
          skip()
          if (i < s.length && s.charAt(i) == ',') i += 1 else more = false
        }
        expect(']')
      }
      Arr(items.result())
    }

    private def string(): String = {
      expect('"')
      val out = new StringBuilder
      var closed = false
      while (!closed) {
        if (i >= s.length) fail("文字列が閉じていません")
        val c = s.charAt(i)
        i += 1
        if (c == '"') closed = true
        else if (c == '\\') {
          if (i >= s.length) fail("文字列が閉じていません")
          val e = s.charAt(i)
          i += 1
          e match {
            case '"' => out.append('"')
            case '\\' => out.append('\\')
            case '/' => out.append('/')
            case 'b' => out.append('\b')
            case 'f' => out.append('\f')
            case 'n' => out.append('\n')
            case 'r' => out.append('\r')
            case 't' => out.append('\t')
            case 'u' =>
              if (i + 4 > s.length) fail("\\u の後が 4 桁ではありません")
              val hex = s.substring(i, i + 4)
              if (!hex.forall(h => Character.digit(h, 16) >= 0 && h < '\u0080')) fail("\\u の後が 16 進数ではありません")
              out.append(Integer.parseInt(hex, 16).toChar)
              i += 4
            case _ => fail("エスケープが不正です")
          }
        } else if (c < ' ') fail("文字列に制御文字があります")
        else out.append(c)
      }
      out.toString
    }

    private def number(): Json = {
      val start = i
      def digits(): Int = {
        val from = i
        while (i < s.length && s.charAt(i) >= '0' && s.charAt(i) <= '9') i += 1
        i - from
      }
      if (i < s.length && s.charAt(i) == '-') i += 1
      if (i < s.length && s.charAt(i) == '0') i += 1
      else if (digits() == 0) fail("値が不正です")
      if (i < s.length && s.charAt(i) == '.') {
        i += 1
        if (digits() == 0) fail("数が不正です")
      }
      if (i < s.length && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
        i += 1
        if (i < s.length && (s.charAt(i) == '+' || s.charAt(i) == '-')) i += 1
        if (digits() == 0) fail("数が不正です")
      }
      Num(java.lang.Double.parseDouble(s.substring(start, i)))
    }
  }
}
