package io._3ch.bbs.handler

private[handler] object DomainMigration {
  val key = "0000000000"
  val title = "３ちゃんねるは www.3ch.io へ移行中です"
  val modifiedMillis = 1793718000000d

  private def notice(board: String, threadKey: Option[String]): String = {
    val threadLine = threadKey.fold("")(key => s"スレURL: https://www.3ch.io/test/read.cgi/$board/$key/<br>")
    s"３ちゃんねるは新ドメインへの移行中です。<br>https://www.3ch.io/<br>現在使用中の3chbbs.comは当面の間維持しますが、将来的には廃止される予定です。<br>以下の移行用リンクを専ブラに登録するか・直接開いて移動をお願いします。<br>BBSMENU: https://www.3ch.io/bbsmenu.html<br>板URL: https://www.3ch.io/$board/<br>${threadLine}<br>上手く移行出来ない場合は、専ブラ名と試した手順等を全板共通スレに書き込んでご相談ください - 2026/11/04 †Monterey Langit ★ <br>3ch https://github.com/MontereyLangit/3ch"
  }

  def annotateDat(text: String, board: String, threadKey: String): String = {
    val end = text.indexOf('\n')
    val first = if end < 0 then text else text.substring(0, end)
    val fields = first.split("<>", -1)
    if fields.length != 5 then text
    else {
      val body = fields(3)
      val trailing = body.reverse.takeWhile(_ == ' ').reverse
      fields(3) = body.dropRight(trailing.length) + "<hr>" + notice(board, Some(threadKey)) + trailing
      fields.mkString("<>") + (if end < 0 then "" else text.substring(end))
    }
  }

  def subject(text: String): String = {
    if text.linesIterator.exists(_.startsWith(s"$key.dat<>")) then text
    else s"$key.dat<>$title (1)\n$text"
  }

  def dat(board: String): String =
    s"３ちゃんねる ★<><>2026/11/04(水) 00:00:00<> <hr>${notice(board, None)} <>$title\n"
}
