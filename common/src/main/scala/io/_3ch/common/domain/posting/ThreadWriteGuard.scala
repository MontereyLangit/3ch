package io._3ch.common.domain.posting

import io._3ch.common.text.JsText

/** スレにレスを追記できない理由。code は Thread DO が RPC で返す文字列 */
enum ThreadWriteRejection(val code: String) {
  /** 停止・移動・削除の処理中か済み */
  case Unavailable extends ThreadWriteRejection("thread-write:unavailable")
  /** スレ設定で BBS_HEISA=checked */
  case Closed extends ThreadWriteRejection("thread-write:closed")
  /** スレ立てで同じ key の dat がある */
  case DuplicateKey extends ThreadWriteRejection("thread-write:duplicate-key")
  /** 返信で dat が無い */
  case Missing extends ThreadWriteRejection("thread-write:missing")
  /** 1000 件に達した */
  case Full extends ThreadWriteRejection("thread-write:full")
}

object ThreadWriteRejection {
  /** RPC の文字列から読む。該当しなければ None */
  def fromCode(code: String): Option[ThreadWriteRejection] = values.find(_.code == code)
}

/** 追記の種類。Raw は付帯情報の無い呼び出しで、スレの有無を問わない */
enum ThreadWriteKind {
  case Create, Reply, Raw
}

/** Thread DO にレスを追記してよいかの判定 */
object ThreadWriteGuard {
  /**
    * 停止・移動・削除、BBS_HEISA、同じ key、スレ不在、1000 件の順に判定し、追記できれば確定するレス番号を返す
    * settings は保存されたスレ設定の文字列そのもの
    */
  def check(unavailable: Boolean, dat: String, settings: String, kind: ThreadWriteKind): Either[ThreadWriteRejection, Int] = {
    val number = nextNumber(dat)
    if (unavailable) Left(ThreadWriteRejection.Unavailable)
    else if (closed(settings)) Left(ThreadWriteRejection.Closed)
    else if (kind == ThreadWriteKind.Create && dat.nonEmpty) Left(ThreadWriteRejection.DuplicateKey)
    else if (kind == ThreadWriteKind.Reply && dat.isEmpty) Left(ThreadWriteRejection.Missing)
    else if (number >= 1000) Left(ThreadWriteRejection.Full)
    else Right(number)
  }

  /** スレ設定のどれかの項目がちょうど BBS_HEISA=checked か。後の項目で上書きされていても停止とみなす */
  def closed(settings: String): Boolean = settings.split("<>", -1).contains("BBS_HEISA=checked")

  /** 次のレス番号。末尾の空白を除いた dat の空でない行の数に 1 を足す */
  def nextNumber(dat: String): Int = JsText.trimEnd(dat).split("\n", -1).count(_.nonEmpty) + 1
}
