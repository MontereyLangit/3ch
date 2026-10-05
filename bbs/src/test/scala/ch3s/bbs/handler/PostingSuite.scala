package ch3s.bbs.handler

import munit.FunSuite
import ch3s.bbs.durable.DurableTestKit
import ch3s.bbs.durable.DurableTestKit.Env
import ch3s.bbs.external.web.{Request, TextEncoder}
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/**
 * 投稿パイプライン（`Posting.entry`）の特性テスト (characterization tests)。
 * 「現在の実際の動作」を凍結するものであり、正しさを検証するものではない。既知の不具合
 * （docs/現行システム仕様書.md 11章、docs/既知不具合台帳.md）もそのまま凍結し、修正しない。
 */
class PostingSuite extends FunSuite {
  import HandlerTestKit.*

  private final case class Posted(res: js.Dynamic, text: String)

  private def sjisPercentEncode(str: String): String =
    sjisBytes(str).map(b => "%" + f"$b%02X").mkString

  private def buildBody(fields: Seq[(String, String)], sjisMode: Boolean = false): String =
    fields.map { (k, v) => s"$k=${if sjisMode then sjisPercentEncode(v) else js.URIUtils.encodeURIComponent(v)}" }.mkString("&")

  /** JS の `{...base, ...over}` と同じ並び（既存キーは位置を保って上書き、新規キーは末尾）。 */
  private def merge(base: Seq[(String, String)], over: Seq[(String, String)]): Seq[(String, String)] = {
    val overrides = over.toMap
    val keys = base.map(_._1).toSet
    base.map { (k, v) => k -> overrides.getOrElse(k, v) } ++ over.filterNot((k, _) => keys.contains(k))
  }

  private def makeRequest(method: String = "POST", headers: Seq[(String, String)] = Nil, cf: Seq[(String, String)] = Nil, body: String = ""): Request = {
    val lowered = headers.map((k, v) => k.toLowerCase -> v).toMap
    js.Dynamic.literal(
      method = method,
      cf = js.Dictionary(cf*),
      headers = js.Dynamic.literal(get = ((name: String) => lowered.getOrElse(name.toLowerCase, null)): js.Function1[String, String]),
      arrayBuffer = (() => js.Promise.resolve[js.Any](new TextEncoder().encode(body).buffer)): js.Function0[js.Promise[js.Any]]
    ).asInstanceOf[Request]
  }

  private val DefaultHeaders: Seq[(String, String)] = Seq(
    "content-type" -> "application/x-www-form-urlencoded",
    "referer" -> "https://www.3chbbs.com/test/read.cgi/sample/1/",
    "user-agent" -> "Mozilla/5.0 (TestBrowser)",
    "CF-Connecting-IP" -> "192.0.2.1"
  )
  private val DefaultCf: Seq[(String, String)] = Seq("country" -> "JP", "asn" -> "2516", "region" -> "Tokyo", "city" -> "Tokyo")
  private val DefaultFields: Seq[(String, String)] = Seq(
    "FROM" -> "", "mail" -> "", "MESSAGE" -> "テスト本文", "subject" -> "テストスレ", "bbs" -> "sample", "key" -> "", "time" -> "9999999999"
  )

  private def entry(env: Env, req: Request): Future[js.Dynamic] = fut[js.Dynamic](Posting.entry(req, env.worker))

  /** フィールドを与えて POST リクエストを投稿エントリへ渡す。submit は既定でWebブラウザ相当(UTF-8)。 */
  private def post(env: Env, fields: Seq[(String, String)] = Nil, headers: Seq[(String, String)] = Nil, cf: Seq[(String, String)] = Nil,
                   sjisMode: Boolean = false, submitText: String = "書き込む"): Future[Posted] = {
    val submit = if sjisMode then sjisPercentEncode(submitText) else js.URIUtils.encodeURIComponent(submitText)
    val body = buildBody(merge(DefaultFields, fields), sjisMode) + s"&submit=$submit"
    val req = makeRequest(headers = merge(DefaultHeaders, headers), cf = merge(DefaultCf, cf), body = body)
    for {
      res <- entry(env, req)
      body <- text(res)
    } yield Posted(res, body)
  }

  private def errorMessage(body: String): Option[String] = {
    val open = "<b>ERROR: "
    val start = body.indexOf(open)
    if start < 0 then None
    else {
      val from = start + open.length
      val end = body.indexOf("</b></font>", from)
      if end < 0 then None else Some(body.substring(from, end))
    }
  }

  test("React confirmation keeps the cookie marker, Shift_JIS and preescaped form fragments") {
    val g = js.Dynamic.literal(form = js.Dynamic.literal(
      subject = "題名 &lt;test&gt;", FROM = "名前 &amp;", mail = "a&amp;b", MESSAGE = "本文 &lt;script&gt;<br>次行"
    ))
    val res = RendererShell.legacyReact(PostingPages.respondConfirmationPhase(g, "Shift_JIS")).asInstanceOf[js.Dynamic]
    sjisText(res).map { body =>
      assertEquals(status(res), 200)
      assertEquals(header(res, "content-type"), Some("text/html;charset=Shift_JIS"))
      assert(body.contains("<!-- 2ch_X:cookie -->"))
      assert(body.contains("題名 &lt;test&gt;"))
      assert(body.contains("本文 &lt;script&gt;<br>次行"))
      assert(!body.contains("&amp;lt;"))
      assert(body.contains("投稿確認"))
      assert(body.contains("各掲示板の規定が優先されることを承諾します。"))
      assert(body.contains("<script type=\"module\" src=\"/static/client.js\"></script>"))
    }
  }

  private def threadKey(p: Posted): String = header(p.res, "x-threadkey").get
  private def dat(env: Env, bbs: String, key: String): Future[String] = env.thread(s"$bbs/$key").getDat().toFuture
  private def fieldsOf(dat: String): Array[String] = dat.split("\n", -1)(0).split("<>", -1)
  private def idOf(field: String): String = field.substring(field.indexOf("ID:"))

  /** スレ立て（既定フィールド＋`fields`）を投稿し、`sample` 板の先頭行の `<>` 区切りフィールドを返す。 */
  private def firstRow(env: Env, fields: (String, String)*): Future[Array[String]] =
    for {
      p <- post(env, fields)
      d <- dat(env, "sample", threadKey(p))
    } yield fieldsOf(d)

  // ==================== 1. checkRequest ====================

  test("checkRequest: POSTでない場合は最初にエラーになる") {
    val env = DurableTestKit.newEnv()
    val req = makeRequest(method = "GET", headers = DefaultHeaders, cf = DefaultCf, body = "a=b")
    for {
      res <- entry(env, req)
      body <- text(res)
    } yield {
      assertEquals(status(res), 200)
      assertEquals(errorMessage(body), Some("POST メソッドを使ってください。<br>\nRecieve GET METHOD"))
    }
  }

  test("checkRequest: Content-Typeが不正な場合のエラー") {
    val env = DurableTestKit.newEnv()
    post(env, headers = Seq("content-type" -> "text/plain")).map { p =>
      assertEquals(status(p.res), 200)
      assertEquals(errorMessage(p.text), Some("送信された内容が破損しています。->text/plain<br>\nBroken request body. "))
    }
  }

  test("KB-24: checkRequest: Refererが不正な場合のエラー（メッセージ自体にERROR:が含まれ二重表示になる既知の癖）") {
    val env = DurableTestKit.newEnv()
    post(env, headers = Seq("referer" -> "https://evil.example.com/")).map { p =>
      assertEquals(status(p.res), 200)
      // dispError に渡す文字列そのものが "ERROR: " から始まっているため、respondError が付ける
      // "ERROR: " と重複して "ERROR: ERROR: ..." になる。これは仕様書には明記されていない実装上の癖。
      assertEquals(errorMessage(p.text), Some("ERROR: あなた騙されてますよ？->https://evil.example.com/<br>\nReferer is invalid."))
    }
  }

  test("KB-24: checkRequest: UserAgentが7バイト未満の場合のエラー（同様に二重ERROR:）") {
    val env = DurableTestKit.newEnv()
    post(env, headers = Seq("user-agent" -> "abc")).map { p =>
      assertEquals(errorMessage(p.text), Some("ERROR: ブラウザ変ですよん。(ua)<br>UserAgent: abc<br>\nUserAgent is invalid."))
    }
  }

  test("checkRequest: UserAgentが384バイトを超える場合のエラー") {
    val env = DurableTestKit.newEnv()
    val ua = "a" * 385
    post(env, headers = Seq("user-agent" -> ua)).map { p =>
      assertEquals(errorMessage(p.text), Some(s"ERROR: ブラウザ変ですよん。(ua)<br>UserAgent: $ua<br>\nUserAgent is invalid."))
    }
  }

  test("checkRequest: UserAgentに非ASCII文字が含まれる場合のエラー") {
    val env = DurableTestKit.newEnv()
    val ua = "Mozilla/5.0 日本語UA"
    post(env, headers = Seq("user-agent" -> ua)).map { p =>
      assertEquals(errorMessage(p.text), Some(s"ERROR: ブラウザ変ですよん。(ua)<br>UserAgent: $ua<br>\nUserAgent is invalid."))
    }
  }

  test("checkRequest: client-ipヘッダがあると簡易Proxyチェックで拒否される") {
    val env = DurableTestKit.newEnv()
    post(env, headers = Seq("client-ip" -> "1.2.3.4")).map { p =>
      assertEquals(errorMessage(p.text), Some("変な情報が送られて来ました。<br>Client_IP: 1.2.3.4"))
    }
  }

  test("checkRequest: 複数の不正が同時にあってもソースコード上の記述順で最初のチェックが勝つ") {
    val env = DurableTestKit.newEnv()
    // POST以外 + Content-Type不正 + Referer不正 + UA不正 + client-ip 同時に混在させ、method判定が最優先されることを確認
    val req = makeRequest(
      method = "GET",
      headers = Seq("content-type" -> "text/plain", "referer" -> "https://evil.example.com/", "user-agent" -> "x", "client-ip" -> "9.9.9.9"),
      cf = DefaultCf,
      body = buildBody(Seq("FROM" -> "", "mail" -> "", "MESSAGE" -> "x", "subject" -> "x", "bbs" -> "sample", "key" -> "", "time" -> "1", "submit" -> "書き込む"))
    )
    for {
      res <- entry(env, req)
      body <- text(res)
    } yield assertEquals(errorMessage(body), Some("POST メソッドを使ってください。<br>\nRecieve GET METHOD"))
  }

  // ==================== 2. エラー時のレスポンス形式 ====================

  test("エラーレスポンスはHTTP 200で返り、<!-- 2ch_X:error --> マーカーとERROR:表記を含む（専ブラ互換のための仕様）") {
    val env = DurableTestKit.newEnv()
    post(env, Seq("MESSAGE" -> "")).map { p =>
      assertEquals(status(p.res), 200)
      assertEquals(header(p.res, "content-type"), Some("text/html;charset=UTF-8"))
      assert(p.text.contains("<!-- 2ch_X:error -->"))
      assertEquals(errorMessage(p.text), Some("本文がありません！"))
    }
  }

  // ==================== 3. 成功時のレスポンス形式 ====================

  test("成功レスポンスはHTTP 200でx-threadkey/x-resnumヘッダとmeta refreshを含む") {
    val env = DurableTestKit.newEnv()
    post(env, Seq("FROM" -> "nm", "mail" -> "ml", "MESSAGE" -> "hello", "subject" -> "sb")).map { p =>
      assertEquals(status(p.res), 200)
      assertEquals(header(p.res, "content-type"), Some("text/html;charset=UTF-8"))
      val key = threadKey(p)
      assert(key.nonEmpty && key.forall(_.isDigit), key)
      assertEquals(header(p.res, "x-resnum"), Some("1"))
      // Preserve the refresh target and delay across React attribute formatting.
      assert(p.text.contains(s"""content="5;URL=/test/read.cgi/sample/$key/l50""""))
      assert(p.text.contains("http-equiv=\"refresh\""))
    }
  }

  // ==================== 4. dat行フォーマット ====================

  test("dat行フォーマット: スレ立て(>>1)は<>5フィールド、本文前後に半角スペース、末尾フィールドにスレタイ+ID") {
    // ID/識別IDは現在時刻に依存するため時刻を固定し、文字クラス・長さだけでなく実測した
    // 具体的な文字列そのもの(ハッシュ値込み)を検証する。文字クラスチェックだけでは、ハッシュの
    // 入力順序・ソルト・時刻窓が変わる移植ミスを検知できない。
    // また、mailに""(空文字)を渡すとKB-10(メール欄は常に空)の検証にならない
    // (「メールを保存する実装」でも空入力なら偶然一致してしまう)ため、あえて非空の'sage'を渡す。
    // さらに行数だけを数えるとdat全体の末尾改行の有無や余分な空行の混入を検知できないため、
    // dat文字列全体を(末尾改行込みで)厳密に比較する。
    withFixedTime(1700000000d * 1000) {
      val env = DurableTestKit.newEnv()
      for {
        p <- post(env, Seq("FROM" -> "名前太郎", "mail" -> "sage", "MESSAGE" -> "テスト本文", "subject" -> "たておき"))
        key = threadKey(p)
        _ = assertEquals(key, "1700000000")
        d <- dat(env, "sample", key)
      } yield {
        // 実測値(salt='test-salt-12345', IP=192.0.2.1, asn=2516, country=JP, region=Tokyo, city=Tokyo,
        // unixtime=1700000000固定)。末尾の改行1つも含めdat全体を厳密一致させる。
        assertEquals(
          d,
          "名前太郎 </b>(JP.SA.h3 Kx-042e35264c7d19b9)<b><><>2023/11/15(水) 07:13:20 ID:zyIjCtRI<> テスト本文 <>たておき [ID:zyIjCtRI★]\n"
        )
        val fields = d.stripSuffix("\n").split("<>", -1)
        assertEquals(fields.length, 5)
        assertEquals(fields(1), "") // メールは常に空(KB-10)。mail='sage'という非空入力でも保存されない。
      }
    }
  }

  test("dat行フォーマット: 返信(>>2)はメールと5番目フィールド(タイトル)が空") {
    // ここでもmailに非空の'sage'を渡し、KB-10(メール欄は常に空)がたまたま空入力で一致している
    // だけではないことを検証する。dat全体を末尾改行込みで厳密一致させ、余分な空行や
    // 改行の欠落も検知できるようにする。
    withFixedTime(1700000000d * 1000) {
      val env = DurableTestKit.newEnv()
      for {
        p1 <- post(env, Seq("subject" -> "たておき2"))
        key = threadKey(p1)
        p2 <- post(env, Seq("key" -> key, "subject" -> "", "mail" -> "sage", "MESSAGE" -> "2番目の返信"))
        _ = assertEquals(header(p2.res, "x-resnum"), Some("2"))
        d <- dat(env, "sample", key)
      } yield {
        // 実測値。1行目・2行目とも末尾に改行が1つずつ入り、2行目のメール欄(fields[1])とタイトル欄
        // (fields[4])はどちらも空文字になる。
        assertEquals(
          d,
          "名無しさん＠３ちゃんねる </b>(JP.SA.h3 Kx-042e35264c7d19b9)<b><><>2023/11/15(水) 07:13:20 ID:zyIjCtRI<> テスト本文 <>たておき2 [ID:zyIjCtRI★]\n" +
          "名無しさん＠３ちゃんねる </b>(JP.SA.h3 Kx-042e35264c7d19b9)<b><><>2023/11/15(水) 07:13:20 ID:zyIjCtRI<> 2番目の返信 <>\n"
        )
        val lines = d.stripSuffix("\n").split("\n", -1)
        assertEquals(lines.length, 2)
        val fields = lines(1).split("<>", -1)
        assertEquals(fields.length, 5)
        assertEquals(fields(1), "") // メール欄は常に空(KB-10)。mail='sage'という非空入力でも保存されない。
        assertEquals(fields(3), " 2番目の返信 ")
        assertEquals(fields(4), "") // 2レス目以降はタイトルなし
      }
    }
  }

  // ==================== 5. readForm: 文字コード判定・サニタイズ ====================

  test("readForm: submit=書のUTF-8バイト列が無いと専ブラ(Monazilla)以外はSJIS扱いにされ拒否される") {
    val env = DurableTestKit.newEnv()
    val expected = "書き込みボタンを押忍べし<br>2ch専用ブラウザからの書き込みでこのエラーが出た場合、お使いの専用ブラウザはMonazilla/1.00で始まるUserAgentを使用していません。<br>認証時の問題などもありますので、2ch専用ブラウザではMonazilla/1.00で始まるUserAgentを使用してください。<br>(Monazilla/1.00以外のUserAgentでsubmit=%E6%9B%B8…でない場合にこのエラーが出ます。)<br>\n&apos;Submit&apos; must require &apos;書&apos; with Web browser&apos;s post."
    // このエラー画面自体はSJISでエンコードされるため、生バイトから復号して比較する。
    val req = makeRequest(
      headers = merge(DefaultHeaders, Seq("user-agent" -> "Mozilla/5.0 NotMona")),
      cf = DefaultCf,
      body = buildBody(Seq("FROM" -> "", "mail" -> "", "MESSAGE" -> "x", "subject" -> "x", "bbs" -> "sample", "key" -> "", "time" -> "9999999999"), sjisMode = true) + "&submit=" + sjisPercentEncode("書き込む")
    )
    for {
      res <- entry(env, req)
      decoded <- sjisText(res)
    } yield {
      assert(decoded.contains("ＥＲＲＯＲ！"))
      assert(decoded.contains(expected))
    }
  }

  test("readForm: Monazilla + Shift_JISエンコードされたPOSTは正しく復号される") {
    val env = DurableTestKit.newEnv()
    for {
      p <- post(env, Seq("FROM" -> "名無し", "MESSAGE" -> "日本語の本文です", "subject" -> "SJISテスト"), headers = Seq("user-agent" -> "Monazilla/1.00"), sjisMode = true, submitText = "書き込む")
      _ = assertEquals(header(p.res, "x-resnum"), Some("1"))
      d <- dat(env, "sample", threadKey(p))
    } yield assert(d.contains(" 日本語の本文です "))
  }

  test("readForm: HTML特殊文字のエスケープ (& \" < > ')") {
    val env = DurableTestKit.newEnv()
    firstRow(env, "FROM" -> "a&b\"c<d>e'f", "MESSAGE" -> "msg&<>\"'", "subject" -> "esc").map { fields =>
      assertEquals(fields(0).split(" </b>", -1)(0), "a&amp;b&quot;c&lt;d&gt;e&apos;f")
      assertEquals(fields(3), " msg&amp;&lt;&gt;&quot;&apos; ")
    }
  }

  test("readForm: タブは半角スペースに、制御文字は除去される") {
    val env = DurableTestKit.newEnv()
    firstRow(env, "MESSAGE" -> "a\tb\u0001\u0002c", "subject" -> "tabtest").map { fields =>
      assertEquals(fields(3), " a bc ") // タブ->半角スペース、\x01\x02は除去
    }
  }

  test("readForm: ★→☆, ◆→◇, ＃→#の偽キャップ・偽トリップ変換") {
    val env = DurableTestKit.newEnv()
    firstRow(env, "FROM" -> "★fake◆trip＃hash", "MESSAGE" -> "x", "subject" -> "sub2").map { fields =>
      // FROM先頭が "☆fake◇trip" になり、"＃hash" 以降はトリップキーとして分離されトリップ生成に使われる
      assert(new js.RegExp("^☆fake◇trip </b>◆[./0-9A-Za-z]{10}</b> ").test(fields(0)), fields(0))
    }
  }

  // ==================== 6. <br>整形の既知バグ ====================

  test("KB-03: 既知の不具合: MESSAGE.replace(\"<br>\",\" <br> \")は最初の1箇所のみ整形される（複数行本文で凍結）") {
    val env = DurableTestKit.newEnv()
    firstRow(env, "FROM" -> "名前太郎", "MESSAGE" -> "1行目\n2行目\n3行目", "subject" -> "brbug").map { fields =>
      // 本来なら全ての<br>前後にスペースが入るべきだが、String.prototype.replaceが第1引数に文字列(非グローバル正規表現)を
      // 渡されているため最初の1箇所しか置換されない。2つ目以降の<br>は前後スペース無しのまま凍結する。
      assertEquals(fields(3), " 1行目 <br> 2行目<br>3行目 ")
    }
  }

  // ==================== 7. checkFieldSize ====================

  test("checkFieldSize: subject/name/mailの文字数上限エラー") {
    val env = DurableTestKit.newEnv()
    for {
      subject <- post(env, Seq("subject" -> ("あ" * 65)))
      _ = assertEquals(errorMessage(subject.text), Some("サブジェクトが長すぎます！(65/64)"))
      from <- post(env, Seq("FROM" -> ("あ" * 65), "subject" -> "x"))
      _ = assertEquals(errorMessage(from.text), Some("名前が長すぎます！(65/64)"))
      mail <- post(env, Seq("mail" -> ("a" * 65), "subject" -> "x"))
      _ = assertEquals(errorMessage(mail.text), Some("メールアドレスが長すぎます！(65/64)"))
    } yield ()
  }

  test("checkFieldSize: >>2以降の本文は4096文字まで、超えるとエラー") {
    val env = DurableTestKit.newEnv()
    for {
      p <- post(env, Seq("subject" -> "msgcap"))
      over <- post(env, Seq("key" -> threadKey(p), "subject" -> "", "MESSAGE" -> ("a" * 4097)))
    } yield assertEquals(errorMessage(over.text), Some("本文が長すぎます！(4097/4096)"))
  }

  test("checkFieldSize: >>1の本文は65536文字まで拡張される（4097文字以上でも>>1なら通る）") {
    for {
      t1 <- post(DurableTestKit.newEnv(), Seq("bbs" -> "boardone", "subject" -> "bigmsg1", "MESSAGE" -> ("a" * 65536)))
      _ = assert(!t1.text.contains("ERROR"))
      t2 <- post(DurableTestKit.newEnv(), Seq("bbs" -> "boardtwo", "subject" -> "bigmsg2", "MESSAGE" -> ("a" * 65537)))
    } yield assertEquals(errorMessage(t2.text), Some("本文が長すぎます！(65537/65536)"))
  }

  test("checkFieldSize: 行数チェックは>>2以降のみ適用される (BBS_LINE_NUMBER=30のとき<br>分割数60まで許可)") {
    val env = DurableTestKit.newEnv()
    val ok = (0 until 60).map(i => s"l$i").mkString("\n") // 59個の<br> -> split長60
    val bad = (0 until 61).map(i => s"l$i").mkString("\n") // 60個の<br> -> split長61
    for {
      p <- post(env, Seq("subject" -> "linecap"))
      key = threadKey(p)
      tOk <- post(env, Seq("key" -> key, "subject" -> "", "MESSAGE" -> ok))
      _ = assert(!tOk.text.contains("ERROR"))
      tBad <- post(env, Seq("key" -> key, "subject" -> "", "MESSAGE" -> bad))
      _ = assertEquals(errorMessage(tBad.text), Some("改行が多すぎます！(61/60)"))
      // >>1(スレ立て)では行数チェック自体が適用されない
      tNewThreadManyLines <- post(env, Seq("bbs" -> "linecapboard", "subject" -> "linecapnew", "MESSAGE" -> bad))
    } yield assert(!tNewThreadManyLines.text.contains("ERROR"))
  }

  test("checkFieldSize: 名無し補完は板設定BBS_NONAME_NAME、無ければ既定値") {
    val env = DurableTestKit.newEnv()
    for {
      r1 <- post(env, Seq("FROM" -> "", "subject" -> "defaultnoname"))
      dat1 <- dat(env, "sample", threadKey(r1))
      _ = assert(fieldsOf(dat1)(0).startsWith("名無しさん＠３ちゃんねる "))
      _ = env.putR2("sample2/SETTING.TXT", "BBS_NONAME_NAME=カスタム名無し\n")
      r2 <- post(env, Seq("bbs" -> "sample2", "FROM" -> "", "subject" -> "customnoname"))
      dat2 <- dat(env, "sample2", threadKey(r2))
    } yield assert(fieldsOf(dat2)(0).startsWith("カスタム名無し "))
  }

  // ==================== 8. processTrip ====================

  test("processTrip: 12バイト以上のトリップキーはSHA-1先頭12文字、+は.に置換される") {
    firstRow(DurableTestKit.newEnv(), "FROM" -> "名前#abcdefghijklmnop", "subject" -> "triptest1").map { fields =>
      assert(fields(0).startsWith("名前 </b>◆FPOZUois0Ynm</b> "), fields(0))
    }
  }

  test("processTrip: 12バイト未満のトリップキーはDES crypt末尾10文字") {
    firstRow(DurableTestKit.newEnv(), "FROM" -> "名前#abc", "subject" -> "triptest2").map { fields =>
      assert(fields(0).startsWith("名前 </b>◆L8nEQyID76</b> "), fields(0))
    }
  }

  test("processTrip: #+16桁16進数の特殊トリップ（DES, ソルト省略時は\"..\"）") {
    firstRow(DurableTestKit.newEnv(), "FROM" -> "名前##0123456789abcdef", "subject" -> "triptest3").map { fields =>
      assert(fields(0).startsWith("名前 </b>◆ClNHFHdYIw</b> "), fields(0))
    }
  }

  test("processTrip: #+16桁16進数+ソルト2文字指定") {
    firstRow(DurableTestKit.newEnv(), "FROM" -> "名前##0123456789abcdefXY", "subject" -> "triptest3b").map { fields =>
      assert(fields(0).startsWith("名前 </b>◆MIe14Gpa2k</b> "), fields(0))
    }
  }

  test("processTrip: 先頭が$の場合は16進フォーマットの正規表現に一致せず???になる（$は正規表現内で考慮されていない既知の癖）") {
    firstRow(DurableTestKit.newEnv(), "FROM" -> "名前#$0123456789abcdef", "subject" -> "triptest4").map { fields =>
      assert(fields(0).startsWith("名前 </b>◆???</b> "), fields(0))
    }
  }

  test("processTrip: トリップキーが1023バイトを超えるとエラー") {
    post(DurableTestKit.newEnv(), Seq("FROM" -> ("名前#" + "a" * 1024), "subject" -> "triptest5")).map { p =>
      assertEquals(errorMessage(p.text), Some("トリップキー長杉！<br>\nYour tripkey must be less than 1023 bytes."))
    }
  }

  // これまでのトリップの例はいずれもASCIIキーのみだった。processTripの分岐はSJISエンコードした
  // 「バイト数」(gb.tripKey自体の文字数ではない)が12以上かどうかで決まるため、日本語キーで
  // 11/12バイトの境界をまたぐケースが無いと、KB-14(SJISバイト列をUTF-8として再エンコードして
  // SHA-1を取る3ch独自の癖)を素直なバイトハッシュで実装した移植ミスを検知できない
  // (ASCIIキーはSJISでも1バイト/文字なのでUTF-8再エンコードの影響が出にくい)。
  test("processTrip: 日本語トリップキー、SJISで11バイト(DES branch)と12バイト(SHA-1 branch)の境界") {
    for {
      // 'ぁ'(SJIS 2バイト)×5 + 'x'(1バイト) = 11バイト -> DES branch
      fields11 <- firstRow(DurableTestKit.newEnv(), "FROM" -> "名前#ぁぁぁぁぁx", "subject" -> "trip-des-jp")
      _ = assert(fields11(0).startsWith("名前 </b>◆f6w4Q5QyaY</b> "), fields11(0))
      // 'ぁ'×6 = 12バイト -> ちょうど境界でSHA-1 branchに入る
      fields12 <- firstRow(DurableTestKit.newEnv(), "FROM" -> "名前#ぁぁぁぁぁぁ", "subject" -> "trip-sha1-jp")
    } yield assert(fields12(0).startsWith("名前 </b>◆5CrvAuK4nFvz</b> "), fields12(0))
  }

  test("processTrip: SHA-1 branchの出力に+が含まれる場合は.に置換される（日本語トリップキーで実際に検証）") {
    // 元のASCIIキーの例(processTrip: 12バイト以上...)でも+の置換自体はコメントで説明されていたが、
    // 実際に+を含む出力になるASCIIキーの具体例が示されていなかった。ここでは実際にSHA-1のBase64
    // 先頭12文字に+を含む日本語トリップキーを実測して固定する。
    // 'げ'(SJIS 2バイト)×6 = 12バイト。sha1Base64(...).slice(0,12) === '4Wz7VgvHRoe+' であることを実測済み。
    firstRow(DurableTestKit.newEnv(), "FROM" -> "名前#げげげげげげ", "subject" -> "trip-plus-jp").map { fields =>
      assert(fields(0).startsWith("名前 </b>◆4Wz7VgvHRoe.</b> "), fields(0)) // 末尾の+が.に置換されている
    }
  }

  // ==================== 9. processCap ====================

  test("processCap: 旧仕様(!接頭辞)の弱キャップは \"ハンドル ★\" になり、ID:???/識別ID:★になる") {
    val env = DurableTestKit.newEnv()
    env.putCap("weakpass", "ワイズハンドル")
    firstRow(env, "mail" -> "#!weakpass", "FROM" -> "", "subject" -> "captest1").map { fields =>
      assertEquals(fields(0), "ワイズハンドル ★ </b>(★)<b>")
      assert(fields(2).endsWith("ID:???"), fields(2))
    }
  }

  test("processCap: 名前を入れていると \"名前＠ハンドル ★\" になる") {
    val env = DurableTestKit.newEnv()
    env.putCap("weakpass2", "ハンドル２")
    firstRow(env, "mail" -> "#!weakpass2", "FROM" -> "名前です", "subject" -> "captest2").map { fields =>
      assertEquals(fields(0), "名前です＠ハンドル２ ★ </b>(★)<b>")
    }
  }

  test("processCap: 末尾★の強キャップはcheckFieldSizeの文字数制限をバイパスする") {
    val env = DurableTestKit.newEnv()
    env.putCap("strongpass", "つよいてすと★")
    val longName = "a" * 100 // 既定のBBS_NAME_COUNT(64)を超える
    for {
      p <- post(env, Seq("mail" -> "#!strongpass", "FROM" -> longName, "subject" -> "captest3"))
      _ = assert(!p.text.contains("ERROR"))
      d <- dat(env, "sample", threadKey(p))
    } yield assertEquals(fieldsOf(d)(0), s"$longName＠つよいてすと ★ </b>(★)<b>")
  }

  test("processCap: mail欄の＃(全角)も#に変換されキャップとして認識される") {
    val env = DurableTestKit.newEnv()
    env.putCap("weakpass3", "ゼンカクハンドル")
    firstRow(env, "mail" -> "＃!weakpass3", "FROM" -> "", "subject" -> "captest4").map { fields =>
      assert(fields(0).startsWith("ゼンカクハンドル ★ "), fields(0))
    }
  }

  // これまでのキャップの例はすべて旧仕様(!接頭辞)の弱キャップだった。!が無い通常パス
  // (SHA-1(SHA-256(MD5(pass)+salt))を27文字に切り詰めてKVキーにする)は一度も通っておらず、
  // このロジックを移植で丸ごと落とした/間違えた場合でもテストが検知できなかった。
  test("processCap: !接頭辞が無い通常パスは SHA-1(SHA-256(MD5(pass)+salt)) の先頭27文字がKVキーになる") {
    val env = DurableTestKit.newEnv()
    val rawPass = "mynormalpass"
    // 実測値: salt='test-salt-12345' の下で
    // sha1Base64(sha256hex(md5(rawPass) + md5(salt))) の先頭27文字(=sha1Base64の全体)。
    val capPass = "tSRvRHdM/NG0AWoskdPh+cSK1zY"
    assertEquals(capPass.length, 27)
    env.putCap(capPass, "通常キャップハンドル")
    val env2 = DurableTestKit.newEnv()
    env2.putCap(capPass, "通常キャップハンドル")
    for {
      fields <- firstRow(env, "mail" -> ("#" + rawPass), "FROM" -> "", "subject" -> "capnormal")
      _ = assertEquals(fields(0), "通常キャップハンドル ★ </b>(★)<b>")
      _ = assert(fields(2).endsWith("ID:???"), fields(2))
      // rawPassが1文字でも違えばKVキーも変わり、キャップとして認識されないことも合わせて確認する
      // (通常パスが本当にハッシュ計算を行っていることの反証)。
      fields2 <- firstRow(env2, "mail" -> ("#" + rawPass + "x"), "FROM" -> "", "subject" -> "capnormal2")
    } yield assert(!fields2(0).contains("通常キャップハンドル"), fields2(0))
  }

  // ==================== 10. ID・識別ID生成 ====================

  test("makeIdAndShikibetuId: BBS_ID=off の場合、ID表示は日付のみでスレタイにID付与も無い") {
    val env = DurableTestKit.newEnv()
    env.putR2("sample/SETTING.TXT", "BBS_ID=off\n")
    firstRow(env, "subject" -> "idofftest").map { fields =>
      assert(!fields(2).contains("ID:")) // 日付のみ、IDは付かない
      assertEquals(fields(4), "idofftest") // スレタイにも[ID:...★]が付かない
    }
  }

  test("makeIdAndShikibetuId: BBS_ID_SHIKIBETSU=d では識別IDは空文字だが、名前欄には空の\" </b>()<b>\"が残る（既知の癖）") {
    val env = DurableTestKit.newEnv()
    env.putR2("sample/SETTING.TXT", "BBS_ID_SHIKIBETSU=d\n")
    firstRow(env, "subject" -> "iddtest").map { fields =>
      assertEquals(fields(0), "名無しさん＠３ちゃんねる </b>()<b>")
    }
  }

  test("makeIdAndShikibetuId: 同じ入力(IP・AS・地域・UA・時刻窓)なら同じIDと識別IDが再現される（決定性）、かつ実測した具体的な値と一致する") {
    // 単純な「2回実行して一致するか」だけの比較では、ハッシュの入力順序やソルト、時刻窓の取り方が
    // 違う実装(同じ入力に対して決定論的でありさえすれば良い実装)でも通ってしまい、移植の検証に
    // ならない。時刻を固定し、実測した具体的な文字列と一致することまで確認する。
    // また、時刻を固定しない元のテストは、2回のpostがBBS_ID_RESETの日付境界を跨ぐと
    // (滅多に起きないが)IDが変わってしまい、テストがフレーキーになる問題があった。
    withFixedTime(1700000000d * 1000) {
      val headers = Seq("CF-Connecting-IP" -> "203.0.113.9")
      val cf = Seq("country" -> "US", "asn" -> "999", "region" -> "CA", "city" -> "LA")
      for {
        f1 <- firstRowWith(DurableTestKit.newEnv(), Seq("subject" -> "dettest1", "FROM" -> "samename"), headers, cf)
        f2 <- firstRowWith(DurableTestKit.newEnv(), Seq("subject" -> "dettest2", "FROM" -> "samename"), headers, cf)
      } yield {
        assertEquals(f1(0), f2(0)) // 名前欄(識別ID込み)が一致
        assertEquals(idOf(f1(2)), idOf(f2(2))) // IDが一致
        // 実測値(salt='test-salt-12345', IP=203.0.113.9, asn=999, country=US, region=CA, city=LA,
        // unixtime=1700000000固定)。
        assertEquals(f1(0), "samename </b>(US.3T.fb No-042e35264c7d19b9)<b>")
        assertEquals(idOf(f1(2)), "ID:tAkuU2RS")
      }
    }
  }

  private def firstRowWith(env: Env, fields: Seq[(String, String)], headers: Seq[(String, String)], cf: Seq[(String, String)], bbs: String = "sample"): Future[Array[String]] =
    for {
      p <- post(env, fields, headers, cf)
      d <- dat(env, bbs, threadKey(p))
    } yield fieldsOf(d)

  test("makeIdAndShikibetuId: BBS_ID_RESETの日付境界(JST)を1秒跨ぐとID・識別IDが変わる") {
    // BBS_ID_RESET(既定86400秒)による時刻窓は Math.floor((unixtime+32400)/BBS_ID_RESET) で計算され、
    // 32400(=9時間, JSTオフセット)が加算されるためJST日付境界で切り替わる。
    // unixtime ≡ 54000 (mod 86400) が窓の切り替わり瞬間で、その1秒前(53999)と(54000)を比較する。
    def at(t: Int): Future[Array[String]] =
      withFixedTime(t.toDouble * 1000) {
        val bbs = "boardboundary" + t
        firstRowWith(DurableTestKit.newEnv(), Seq("FROM" -> "boundary", "subject" -> "boundarytest", "bbs" -> bbs), Nil, Nil, bbs)
      }
    for {
      before <- at(53999)
      after <- at(54000)
    } yield {
      // 実測値。窓が変わるため、識別ID(地域/AS由来ハッシュ)・ID(IP由来ハッシュ)ともに変化する。
      assertEquals(before(0), "boundary </b>(JP.yb.YR 67-ff3061e72f92f8bb)<b>")
      assertEquals(idOf(before(2)), "ID:QVHSvTOt")
      assertEquals(after(0), "boundary </b>(JP.8T.pU V0-4f59499349177794)<b>")
      assertEquals(idOf(after(2)), "ID:Qzj1oBxa")
      assertNotEquals(before(0), after(0))
      assertNotEquals(before(2), after(2))
    }
  }

  test("makeIdAndShikibetuId: IPv6アドレスはコロン区切りの先頭4ブロックのみがID計算の種になる") {
    // checkRequestのgb.idnotane生成 (`${octet[0]}:${octet[1]}:${octet[2]}:${octet[3]}`) により、
    // IPv6アドレスは先頭4ブロックだけが使われ、残りのブロックが変わってもIDは変化しないはず。
    def idFor(name: String, ip: String): Future[String] =
      firstRowWith(DurableTestKit.newEnv(), Seq("FROM" -> name, "subject" -> name), Seq("CF-Connecting-IP" -> ip), Nil).map(f => idOf(f(2)))
    withFixedTime(1700000000d * 1000) {
      for {
        idA <- idFor("ipv6a", "2001:0db8:85a3:0000:0000:8a2e:0370:7334")
        // 先頭4ブロックが同じで、5ブロック目以降だけ異なるアドレス -> IDは同じになるはず
        idB <- idFor("ipv6b", "2001:0db8:85a3:0000:ffff:ffff:ffff:ffff")
        // 4番目のブロックが異なるアドレス -> IDは変わるはず
        idC <- idFor("ipv6c", "2001:0db8:85a3:9999:0000:8a2e:0370:7334")
      } yield {
        assertEquals(idA, idB) // 5ブロック目以降の違いはIDに影響しない
        assertNotEquals(idA, idC) // 4ブロック目までの違いはIDに影響する
        // 実測値。
        assertEquals(idA, "ID:rdEW987b")
        assertEquals(idC, "ID:HWZr8V4Y")
      }
    }
  }

  test("makeIdAndShikibetuId: BBS_ID_GROUP/BBS_ID_RESETを明示指定した場合の実測値") {
    // BBS_ID_GROUPが空の場合はbbs名がグループとして使われるが、明示指定すると板名の代わりに使われる。
    // BBS_ID_RESETも既定の86400(1日)から変更できる。両方を明示指定した場合の実際の出力値を固定する。
    withFixedTime(1700000000d * 1000) {
      val env = DurableTestKit.newEnv()
      env.putR2("idsettingsboard/SETTING.TXT", "BBS_ID_GROUP=customgroup\nBBS_ID_RESET=3600\n")
      firstRowWith(env, Seq("FROM" -> "settingstest", "subject" -> "settingstest", "bbs" -> "idsettingsboard"), Nil, Nil, "idsettingsboard").map { fields =>
        // 実測値(BBS_ID_GROUP=customgroup, BBS_ID_RESET=3600, unixtime=1700000000固定)。
        assertEquals(fields(0), "settingstest </b>(JP.7S.93 ju-ed4575071d4e9f87)<b>")
        assertEquals(idOf(fields(2)), "ID:ZnlBkMof")
      }
    }
  }

  // ==================== 11. 特殊ルーティング ====================

  test("特殊ルーティング: !newthread コマンドで9始まりkeyからスレ立てに変換される") {
    val env = DurableTestKit.newEnv()
    for {
      p <- post(env, Seq("bbs" -> "sample", "key" -> "9", "subject" -> "", "MESSAGE" -> "!newthread議題ですね\n本文本文"))
      _ = assertEquals(header(p.res, "x-resnum"), Some("1"))
      d <- dat(env, "sample", threadKey(p))
    } yield {
      val fields = fieldsOf(d)
      assertEquals(fields(3), "  <br> 本文本文 ") // subjectとして切り出された残りの本文（先頭<br>のぶん前後スペース重複）
      assert(fields(4).startsWith("議題ですね [ID:") && fields(4).endsWith("★]"), fields(4))
    }
  }

  test("特殊ルーティング: keyが924または900始まりだと板名が強制的に\"924\"へ書き換えられる") {
    val env = DurableTestKit.newEnv()
    post(env, Seq("bbs" -> "othername", "key" -> "9240000005", "subject" -> "")).map { p =>
      // 投稿されたbbs="othername"は無視され、"924"板のスレッドとして扱われるため該当スレ無しエラーのパスに"924"が現れる
      assertEquals(errorMessage(p.text), Some("該当するスレッドがありません。924/thread/9240/9240000005.cgi"))
    }
  }

  test("特殊ルーティング: 禁止された板名は拒否される") {
    val env = DurableTestKit.newEnv()
    List("", "test", "js", "css", "html", "images").foldLeft(Future.unit) { (acc, bbs) =>
      acc.flatMap { _ =>
        post(env, Seq("bbs" -> bbs, "key" -> "", "subject" -> "x")).map { p =>
          assertEquals(errorMessage(p.text), Some("板の指定が不正です。<br>\nThe board directory is incorrect..."), s"""bbs="$bbs"""")
        }
      }
    }
  }

  // ==================== 12. スレッド上限 ====================

  test("既知の不具合: dat行数が1000以上で拒否されるため実質999レスで書き込み不能になる") {
    val env = DurableTestKit.newEnv()
    def loop(key: String, i: Int, last: Option[String]): Future[(Option[String], Option[String])] =
      if i > 1001 then Future.successful((last, None))
      else post(env, Seq("key" -> key, "subject" -> "", "MESSAGE" -> s"res$i")).flatMap { p =>
        header(p.res, "x-resnum").filter(_.nonEmpty) match {
          case Some(n) => loop(key, i + 1, Some(n))
          case None => Future.successful((last, Some(p.text)))
        }
      }
    for {
      p <- post(env, Seq("subject" -> "capthread"))
      (lastResnum, failureText) <- loop(threadKey(p), 2, None)
    } yield {
      assertEquals(lastResnum, Some("999")) // 1000レス目で拒否されるため実際は999レスで打ち止め
      assertEquals(failureText.flatMap(errorMessage), Some("このスレッドは1000を超えたのでもう書けません。"))
    }
  }

  // ==================== 13. R2条件付きput(updateSubback)の実際のAPI形状 ====================

  test("KB-25: updateSubback: onlyIf.doesNotExistは実際のR2Conditionalに存在せず無視されるため、初回作成時の重複防止ガードが効かない") {
    val env = DurableTestKit.newEnv()
    // これから立てるスレのキー(=現在unixtime)のprefixを先読みし、そこにすでに"他者の書き込み"が
    // あったことにする。updateSubback は subbackObj===null(=このprefixでの初回作成)の時、
    // onlyIf.doesNotExist を使って「まだ無ければ作る」つもりで put しているが、実際のR2はこの
    // プロパティを認識せず無条件putになるため、既存の内容があっても構わず上書きしてしまう。
    val nowPrefix = math.floor(js.Date.now() / 1000).toLong.toString.take(4)
    val subbackFile = s"sample/thread/$nowPrefix/subject.txt"
    val kakologFile = "sample/thread/subject.txt"
    env.putR2Raw(subbackFile, "foreign-writer-was-here\n", "etag-foreign")
    env.putR2Raw(kakologFile, "foreign-kakolog\n", "etag-foreign-kakolog")
    post(env, Seq("subject" -> "firstpost")).map { p =>
      // 実際にonlyIf.doesNotExistが機能していれば「既にファイルがある」と判定されエラーになるはずだが、
      // 現行仕様はエラーにならず、そのまま上書きに成功してしまう。
      assertEquals(status(p.res), 200)
      assertEquals(errorMessage(p.text), None)
      assertEquals(header(p.res, "x-resnum"), Some("1"))
      // 上書きされ、"foreign-writer-was-here" は跡形もなく消えている。
      assert(!env.r2Text(subbackFile).get.contains("foreign-writer-was-here"))
      assert(env.r2Text(subbackFile).get.contains("firstpost"))
    }
  }

  test("updateSubback: onlyIf.etagMatchesの競合注入時は実際にエラーになる（コンディショナルputが機能していることの検証）") {
    val env = DurableTestKit.newEnv()
    for {
      // 1本目のスレ立て。同一4桁key帯のsubbackFileが作成される。
      p1 <- post(env, Seq("subject" -> "conflict1"))
      key1 = threadKey(p1)
      _ = assertEquals(header(p1.res, "x-resnum"), Some("1"))
      subbackFile = s"sample/thread/${key1.take(4)}/subject.txt"
      _ = assert(env.r2Text(subbackFile).isDefined)
      // 秒境界を跨いで2本目のスレのkeyをkey1と異ならせる（同一keyだと後続の投稿がスレッド重複エラーに
      // なってしまい、updateSubbackのetagMatches分岐(=subbackObjが既に存在する場合)まで到達しない）。
      _ <- sleep(1100)
      // 2本目の投稿がsubbackFileを読んだ直後に、別プロセスが同じファイルを書き換えたことを模す。
      // (readとputの間で実際にetagがズレる状況を注入する)
      _ = {
        var hijacked = false
        env.onR2Get { key =>
          if key == subbackFile && !hijacked then {
            hijacked = true
            env.replaceR2Etag(key, "etag-conflict-injected")
          }
        }
      }
      p2 <- post(env, Seq("subject" -> "conflict2"))
    } yield {
      // respondErrorはx-threadkeyヘッダを付けないため、実際に使われたkey(=2本目のgb.unixtime)は
      // エラー文言中から取り出す。事前にDate.now()から予測せず、実際の出力から確認する。
      val errMsg = errorMessage(p2.text)
      val at = errMsg.map(_.indexOf("key:")).getOrElse(-1)
      val key2 = if at < 0 then "" else errMsg.get.substring(at + 4, at + 14)
      assert(key2.length == 10 && key2.forall(_.isDigit), s"key2が取得できなかった: $errMsg")
      assertNotEquals(key2, key1) // 秒境界を跨いでkeyが変わっていること
      // onlyIf.etagMatchesが実際に機能していれば、読んだ後にetagが変わっているためputが失敗しエラーになる。
      // このアサーションを外して条件付きロジックを取り除くと（常に無条件putにすると）この投稿は
      // 成功してしまう。つまりここでの失敗は、コンディショナルputが確かに機能している証拠になる。
      assertEquals(status(p2.res), 200)
      assertEquals(header(p2.res, "x-resnum"), None)
      assertEquals(errMsg, Some(s"updateSubback error. 板飛びそうなので、またの機会にどうぞ。。。(unixtime:undefined bbs:sample key:$key2 number:1 errmsg:updateSubback->SubbackFIle_update_conflicted!)"))
    }
  }

  // ==================== 14. BBS_LIVE (実況板の強制sage) ====================

  // preparePosting: `BBS_LIVE=checked` の板では `Math.random() * 10 >= 1` (=Math.random() >= 0.1)
  // のときに強制sageされる(10分の9の確率)。乱数を差し替えていないと、テストがどちらの分岐を
  // 通ったか制御できず、実質この分岐は未検証のまま(実行するたびランダムに通ったり通らなかったり)
  // になってしまう。ここではMath.randomを両側の値に固定し、sage時は板の並び順が変わらず
  // headlineも更新されないこと、age時は先頭に移動しheadlineが更新されることを実測して固定する。
  test("preparePosting: BBS_LIVEのランダムsage分岐をMath.randomを固定して両側とも検証する") {
    withFixedTime(1700000000d * 1000) {
      val env = DurableTestKit.newEnv()
      env.putR2("livetest/SETTING.TXT", "BBS_LIVE=checked\n")
      def subject(): Future[String] = env.board("livetest").getSubject().toFuture
      def reply(ms: Double, random: Double, key: String, message: String): Future[Posted] =
        withFixedTime(ms * 1000) {
          withRandom(random) {
            post(env, Seq("bbs" -> "livetest", "key" -> key, "MESSAGE" -> message))
          }
        }
      for {
        pa <- post(env, Seq("bbs" -> "livetest", "subject" -> "ThreadA", "MESSAGE" -> "first"))
        keyA = threadKey(pa)
        keyB <- withFixedTime(1700000001d * 1000) {
          post(env, Seq("bbs" -> "livetest", "subject" -> "ThreadB", "MESSAGE" -> "first")).map(threadKey)
        }
        // 実測値: スレ立て直後は新しい方(ThreadB)が先頭に来る。
        s1 <- subject()
        _ = assertEquals(s1, "1700000001.dat<>ThreadB [ID:zxQ0miZR★] (1)\n1700000000.dat<>ThreadA [ID:zxQ0miZR★] (1)\n")
        _ = assertEquals(env.r2Text("headline.txt"), Some("1700000001\nlivetest<>undefined<>1700000001<>1700000001<>first\n"))

        // 閾値以上(0.5 >= 0.1) -> 強制sage。板の並び順は変わらず(indexそのまま更新)、headlineも更新されない。
        high <- reply(1700000002d, 0.5, keyA, "reply-high")
        _ = assertEquals(header(high.res, "x-resnum"), Some("2"))
        s2 <- subject()
        _ = assertEquals(s2, "1700000001.dat<>ThreadB [ID:zxQ0miZR★] (1)\n1700000000.dat<>ThreadA [ID:zxQ0miZR★] (2)\n")
        _ = assertEquals(env.r2Text("headline.txt"), Some("1700000001\nlivetest<>undefined<>1700000001<>1700000001<>first\n"), "sage時はheadlineが更新されない")

        // 閾値未満(0.05 < 0.1) -> 通常age。ThreadAが先頭に移動し、headlineも更新される。
        low <- reply(1700000003d, 0.05, keyA, "reply-low")
        _ = assertEquals(header(low.res, "x-resnum"), Some("3"))
        s3 <- subject()
        _ = assertEquals(s3, "1700000000.dat<>ThreadA [ID:zxQ0miZR★] (3)\n1700000001.dat<>ThreadB [ID:zxQ0miZR★] (1)\n")
        _ = assertEquals(env.r2Text("headline.txt"), Some("1700000003\nlivetest<>undefined<>1700000003<>1700000000<>reply-low\n"), "age時はheadlineが更新される")

        // ちょうど閾値(Math.random()===0.1)は `0.1*10=1 >= 1` が真になるため、sage側(閾値含む)に入る。
        boundary <- reply(1700000004d, 0.1, keyB, "reply-boundary")
        _ = assertEquals(header(boundary.res, "x-resnum"), Some("2"))
        s4 <- subject()
      } yield assertEquals(
        s4,
        "1700000000.dat<>ThreadA [ID:zxQ0miZR★] (3)\n1700000001.dat<>ThreadB [ID:zxQ0miZR★] (2)\n",
        "Math.random()===0.1はsage側(閾値ぴったりでも強制sage)"
      )
    }
  }
}
