package ch3s.common.domain

/**
 * スレッド一覧
 * @param value BbsSubjectのリスト。
 */
case class BbsSubjects (
  value: List[BbsSubject]
) {

  /**
   * subject.txt形式の文字列にする
   *
   * @return `"1791039040.dat<>洗濯とエアコン掃除と4か月振りの風呂掃除した (6)\n1792588031.dat<>PHPというのが大変らしい (5)\n..."` …
   */
  override def toString: String = value.map{_.toString}.mkString("", "\n", "\n")
}

object BbsSubjects {

  /**
   * subject.txt形式の文字列をパースしてスレッド一覧を作成
   *
   * @param text `"1791039040.dat<>洗濯とエアコン掃除と4か月振りの風呂掃除した (6)\n1792588031.dat<>PHPというのが大変らしい (5)\n..."` …
   * @return スレッド一覧
   */
  def create(text: String): BbsSubjects = {
    val pattern = """(\d+),\s*"([^"]+)",\s*(\d+)""".r
    val bbsSubjects = text
      .split("\n")
      .map(_.trim())
      .filter(_.nonEmpty)
      .map({
        case pattern(key, title, resNum) =>
          ThreadTitle.create(title).flatMap({
            BbsSubject.create(key.toLong, _, resNum.toInt)
          })
      })
      .flatMap(_.toOption)

    BbsSubjects(bbsSubjects.toList)
  }
}

/**
 * スレッド一覧の1行分
 * @param key スレッドキー
 * @param title スレッドタイトル
 * @param resNum レス数
 */
case class BbsSubject private (
  key: Long,
  title: ThreadTitle,
  resNum: Int
) {

  /**
   * subject.txt1行分の文字列にする
   *
   * @return `"1792588031.dat<>PHPというのが大変らしい (5)"` …
   */
  override def toString: String = s"$key.dat<>${title.toString} ($resNum)"
}

object BbsSubject {
  def create(key: Long, title: ThreadTitle, resNum: Int): Either[String, BbsSubject] = {
    val isValid = 99999999L < key && key < 10000000000L && title != null && resNum > 0
    Either.cond(isValid, BbsSubject(key, title, resNum), "不正な値")
  }
}
