package io._3ch.common.domain

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
    val pattern = """(\d+)\.dat<>(.*) \((\d+)\)""".r
    val bbsSubjects = text
      .split("\n")
      .map(_.trim())
      .filter(_.nonEmpty)
      .flatMap({
        case pattern(key, title, resNum) =>
          for {
            parsedKey <- key.toLongOption.flatMap(ThreadKey.create(_).toOption)
            parsedCount <- resNum.toIntOption
            parsedTitle <- ThreadTitle.create(title).toOption
            subject <- BbsSubject.create(parsedKey, parsedTitle, parsedCount).toOption
          } yield subject
        case _ => None
      })

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
  key: ThreadKey,
  title: ThreadTitle,
  resNum: Int
) {

  /**
   * subject.txt1行分の文字列にする
   *
   * @return `"1792588031.dat<>PHPというのが大変らしい (5)"` …
   */
  override def toString: String = s"${key.value}.dat<>${title.value} ($resNum)"
}

object BbsSubject {
  def create(key: ThreadKey, title: ThreadTitle, resNum: Int): Either[String, BbsSubject] = {
    val isValid = title != null && resNum > 0
    Either.cond(isValid, BbsSubject(key, title, resNum), "不正な値")
  }
}
