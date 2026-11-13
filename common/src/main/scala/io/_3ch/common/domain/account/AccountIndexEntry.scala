package io._3ch.common.domain.account

/** 一意な値を管理する索引の種別。uid は確定後に削除しない */
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

  /** 所有者を照合し、確定済み UID の削除は退会後も許可しない */
  def remove(kind: AccountIndexKind, expectedAid: String): Either[AccountRejection, Unit] =
    if (aid != expectedAid) Left(AccountRejection.IndexOwnership)
    else if (kind == AccountIndexKind.Uid && confirmed) Left(AccountRejection.IndexPermanent)
    else Right(())
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
