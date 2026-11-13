package io._3ch.common.domain.account

/** 一意な値を管理する索引の種別。uid と cap は確定後に削除しない（cap は退会・失効後も所有者を指して、古い KV の写しを旧キャップとして通さない） */
enum AccountIndexKind(val code: String) {
  case Uid extends AccountIndexKind("uid")
  case Login extends AccountIndexKind("login")
  case Email extends AccountIndexKind("email")
  case Google extends AccountIndexKind("google")
  case Cap extends AccountIndexKind("cap")
  case Token extends AccountIndexKind("token")
  case Admin extends AccountIndexKind("role:admin")
}
object AccountIndexKind {
  def parse(code: String): Option[AccountIndexKind] = AccountIndexKind.values.find(_.code == code)
}

/** 索引の予約と確定。token 索引だけはキー ID も持つ */
final case class AccountIndexEntry(aid: String, createdAt: Double, confirmed: Boolean = false, tokenId: Option[String] = None) {
  def reserved(now: Double): Boolean = confirmed || createdAt > now - AccountIndexEntry.reservationLifetime

  /** 同じ所有者だけが確定できる。期限切れでも、他の所有者に取られていなければ確定できる。確定済みの再確認も受け付ける */
  def confirm(expectedAid: String, now: Double): Either[AccountRejection, AccountIndexEntry] =
    if (!AccountProfile.validTime(now)) Left(AccountRejection.Invalid)
    else if (aid != expectedAid) Left(AccountRejection.IndexOwnership) else Right(copy(confirmed = true))

  /** 所有者を照合し、確定済みの uid と cap の削除は退会後も許可しない */
  def remove(kind: AccountIndexKind, expectedAid: String): Either[AccountRejection, Unit] =
    if (aid != expectedAid) Left(AccountRejection.IndexOwnership)
    else if ((kind == AccountIndexKind.Uid || kind == AccountIndexKind.Cap) && confirmed) Left(AccountRejection.IndexPermanent)
    else Right(())

  /** 自分の確定前の予約だけを取り消せる。確定済みの値は取り消さない */
  def release(expectedAid: String): Either[AccountRejection, Unit] =
    if (aid != expectedAid) Left(AccountRejection.IndexOwnership)
    else if (confirmed) Left(AccountRejection.IndexPermanent)
    else Right(())

  /** 期限内の予約か確定で、aid が所有者か */
  def claimedBy(expectedAid: String, now: Double): Boolean = aid == expectedAid && reserved(now)
}
object AccountIndexEntry {
  val reservationLifetime: Double = 600

  /** 未登録か予約から10分を過ぎた未確定値だけを取得する。token 索引にはキー ID を必須とする */
  def register(previous: Option[AccountIndexEntry], aid: String, now: Double, tokenId: Option[String], kind: AccountIndexKind): Either[AccountRejection, AccountIndexEntry] = {
    if (!aid.matches("[a-z0-9]{16}") || !AccountProfile.validTime(now) ||
      (kind == AccountIndexKind.Token && !tokenId.exists(DedicatedBrowserTokens.validId)) || (kind != AccountIndexKind.Token && tokenId.isDefined)) Left(AccountRejection.Invalid)
    else if (previous.exists(_.reserved(now))) Left(AccountRejection.IndexConflict)
    else Right(AccountIndexEntry(aid, now, tokenId = tokenId))
  }
}

/** role:admin 索引の一件。activeAdmin は所有者が今も利用できる admin か（確定前でも、役割の付与が済んでいれば真） */
final case class AdminSeat(hash: String, entry: AccountIndexEntry, activeAdmin: Boolean)

/** 初回の管理者の確保。有効な admin が居ない間か、非常用期間だけ予約できる */
object FirstAdminClaim {
  /** 非常用期間の上限（秒）。これより先の期限は無視する */
  val breakGlassWindow: Double = 86400

  /** ADMIN_BREAK_GLASS_UNTIL が現在より先で、24 時間以内のときだけ非常用期間とする */
  def breakGlass(until: Option[Double], now: Double): Boolean =
    AccountProfile.validTime(now) && until.exists(value => value.isFinite && value > now && value <= now + breakGlassWindow)

  /**
    * 外す索引のハッシュと、aid の予約を返す。有効な admin か他人の期限内の予約があれば、非常用期間を除いて拒否する。
    * admin でなくなった所有者の確定済みの値と、期限切れの予約は外す
    */
  def claim(seats: Vector[AdminSeat], aid: String, now: Double, breakGlass: Boolean): Either[AccountRejection, (Vector[String], AccountIndexEntry)] = {
    if (!aid.matches("[a-z0-9]{16}") || !AccountProfile.validTime(now)) Left(AccountRejection.Invalid)
    else {
      val stale = seats.filter(seat => !seat.activeAdmin && (seat.entry.confirmed || !seat.entry.reserved(now)))
      val occupied = seats.exists(seat => seat.activeAdmin || (!seat.entry.confirmed && seat.entry.reserved(now) && seat.entry.aid != aid))
      if (occupied && !breakGlass) Left(AccountRejection.Forbidden)
      else Right((stale.map(_.hash), AccountIndexEntry(aid, now)))
    }
  }

  /** aid 以外に利用できる admin が居るか。最後の admin から admin を外さないために使う */
  def otherAdminExists(seats: Vector[AdminSeat], aid: String): Boolean = seats.exists(seat => seat.activeAdmin && seat.entry.aid != aid)
}
