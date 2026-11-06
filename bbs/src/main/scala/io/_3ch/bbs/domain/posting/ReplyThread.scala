package io._3ch.bbs.domain.posting

/** 返信先スレの題名と、この書き込みのレス番号 */
final case class ReplyThread(title: String, number: Int)

object ReplyThread {
  /** 旧形式のログのパス。スレが無いときの文言に出す */
  def logFile(bbs: String, key: String): String = s"$bbs/thread/${key.take(4)}/$key.cgi"

  /** dat から読む。dat が無い・1 行目が空・題名が無い・満杯なら拒否。レス番号は改行で区切った数（末尾の空要素を含む） */
  def open(dat: String, bbs: String, key: String): Either[PostingError, ReplyThread] = {
    val lines = dat.split("\n", -1)
    if (dat.isEmpty) Left(PostingError.ThreadMissing(logFile(bbs, key)))
    else if (lines(0).isEmpty) Left(PostingError.BrokenLog(BrokenLogKind.Empty))
    else {
      val title = lines(0).split("<>", -1).last
      if (title.isEmpty) Left(PostingError.BrokenLog(BrokenLogKind.NoTitle))
      else if (lines.length >= 1000) Left(PostingError.ThreadFull)
      else Right(ReplyThread(title, lines.length))
    }
  }
}
