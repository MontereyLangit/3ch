package io._3ch.common.domain.account

/** PHOEBE（Lv）の昇格条件と、書き込みに必要な Lv を読む処理 */
object Phoebe {
  val MaxLevel: Int = 10

  /** lv から次の Lv へ上がるのに必要な時間数。投稿数も同じ数だけ必要になる。Google の場合と上限の Lv では None を返す */
  def hours(kind: AccountKind, lv: Int): Option[Int] =
    if (lv < 0 || lv >= MaxLevel) None
    else kind match {
      case AccountKind.Client => Some(23 << (math.max(lv, 1) - 1))
      case AccountKind.Password => Some(23 * math.max(1, lv - 2))
      case AccountKind.Google => None
    }

  /** 次の Lv までの経過秒数 T(lv) */
  def seconds(kind: AccountKind, lv: Int): Option[Double] = hours(kind, lv).map(_ * 3600d)

  /** 次の Lv までの成功投稿数 N(lv) */
  def posts(kind: AccountKind, lv: Int): Option[Int] = hours(kind, lv)

  /** 方式ごとの開始 Lv。メール認証済みの ID・パスワードは 3 から始まる */
  def initial(kind: AccountKind, emailVerified: Boolean): Int = kind match {
    case AccountKind.Google => MaxLevel
    case AccountKind.Password if emailVerified => 3
    case AccountKind.Client | AccountKind.Password => 1
  }

  /** BBS_PHOEBE の値。0〜10 の整数だけを読み、無いか不正なら 0 */
  def required(value: Option[String]): Int =
    value.map(_.trim).filter(v => v.nonEmpty && v.length <= 2 && v.forall(c => c >= '0' && c <= '9')).map(_.toInt).filter(_ <= MaxLevel).getOrElse(0)

  /** 書き込める Lv か。exempt は Lv を問わない投稿者 */
  def admits(lv: Int, required: Int, exempt: Boolean): Boolean = exempt || lv >= required
}
