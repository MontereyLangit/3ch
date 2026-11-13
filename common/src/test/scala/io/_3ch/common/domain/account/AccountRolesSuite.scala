package io._3ch.common.domain.account

import munit.FunSuite

class AccountRolesSuite extends FunSuite {
  private val now = 2000000000d
  private val aid = "abcdefghijklmnop"
  private val other = "qrstuvwxyz012345"
  private val profile = AccountProfile(aid, "user1234", AccountKind.Google, now, None, now, "v1", googleSubHash = Some("a" * 64))
  private def roles(values: AccountRole*): Roles = Roles(values.toSet)
  private def google(values: AccountRole*): AccountState =
    AccountState.create(profile, now).flatMap(_.activate(now)).flatMap(_.setRoles(roles(values*))).toOption.get

  test("staff は cap 以外の役割を持つこと、操作できる相手は admin なら誰でも、ほかの staff は staff でない相手だけ") {
    assert(!roles().staff && !roles(AccountRole.Cap).staff)
    assert(roles(AccountRole.Delete).staff && roles(AccountRole.Regulate).staff && roles(AccountRole.Admin).staff)
    assert(roles(AccountRole.Admin).manages(roles(AccountRole.Admin)))
    assert(roles(AccountRole.Regulate).manages(roles(AccountRole.Cap)))
    assert(roles(AccountRole.Regulate).manages(roles()))
    assert(!roles(AccountRole.Regulate).manages(roles(AccountRole.Delete)))
    assert(!roles(AccountRole.Delete).manages(roles(AccountRole.Admin)))
    assert(!roles(AccountRole.Cap).manages(roles()))
    assert(!roles().manages(roles()))
  }

  test("役割の名前を読み、知らない名前があれば全体を拒否する") {
    assertEquals(Roles.parse(Seq("admin", "cap")), Some(roles(AccountRole.Admin, AccountRole.Cap)))
    assertEquals(Roles.parse(Nil), Some(roles()))
    assertEquals(Roles.parse(Seq("admin", "Admin")), None)
  }

  test("役割を変えられるのは admin だけで、クライアント ID には付けない") {
    val target = google(AccountRole.Cap)
    assertEquals(target.assignRoles(roles(AccountRole.Delete), roles(AccountRole.Admin), now).map(_.roles), Right(roles(AccountRole.Delete)))
    assertEquals(target.assignRoles(roles(AccountRole.Delete), roles(AccountRole.Regulate), now), Left(AccountRejection.Forbidden))
    assertEquals(google(AccountRole.Admin).assignRoles(roles(), roles(AccountRole.Delete), now), Left(AccountRejection.Forbidden))
    assertEquals(google(AccountRole.Admin).assignRoles(roles(), roles(AccountRole.Admin), now).map(_.roles), Right(roles()))
    val client = AccountState.create(profile.copy(kind = AccountKind.Client, googleSubHash = None), now).toOption.get
    assertEquals(client.assignRoles(roles(AccountRole.Cap), roles(AccountRole.Admin), now), Left(AccountRejection.RolesForbidden))
    assertEquals(target.assignRoles(roles(), roles(AccountRole.Admin), Double.NaN), Left(AccountRejection.Invalid))
  }

  test("初回の管理者は利用できるアカウントにだけ admin を足し、凍結中は有効な admin に数えない") {
    val granted = google(AccountRole.Delete).grantFirstAdmin(claimed = true, now).toOption.get
    assertEquals(granted.roles, roles(AccountRole.Admin, AccountRole.Delete))
    assert(granted.activeAdmin(now))
    val frozen = granted.setStatus(AccountStatus.Frozen).toOption.get
    assert(!frozen.activeAdmin(now))
    assertEquals(frozen.grantFirstAdmin(claimed = true, now), Left(AccountRejection.Frozen))
    assertEquals(google(AccountRole.Delete).grantFirstAdmin(claimed = false, now), Left(AccountRejection.Forbidden))
    assert(!google(AccountRole.Delete).activeAdmin(now))
  }

  test("非常用期間は現在より先で 24 時間以内の期限だけ") {
    assert(FirstAdminClaim.breakGlass(Some(now + 1), now))
    assert(FirstAdminClaim.breakGlass(Some(now + 86400), now))
    assert(!FirstAdminClaim.breakGlass(Some(now + 86401), now))
    assert(!FirstAdminClaim.breakGlass(Some(now), now))
    assert(!FirstAdminClaim.breakGlass(None, now))
    assert(!FirstAdminClaim.breakGlass(Some(Double.PositiveInfinity), now))
  }

  test("確定前でも役割の付与が済んだ所有者は有効な admin として数え、他に有効な admin が居るかを返す") {
    val pendingAdmin = AdminSeat("a" * 64, AccountIndexEntry(other, now - 10), activeAdmin = true)
    val expiredAdmin = AdminSeat("b" * 64, AccountIndexEntry(other, now - 600), activeAdmin = true)
    assertEquals(FirstAdminClaim.claim(Vector(pendingAdmin), aid, now, breakGlass = false), Left(AccountRejection.Forbidden))
    assertEquals(FirstAdminClaim.claim(Vector(expiredAdmin), aid, now, breakGlass = false), Left(AccountRejection.Forbidden), "期限切れの予約でも admin なら外さない")
    assertEquals(FirstAdminClaim.claim(Vector(expiredAdmin), aid, now, breakGlass = true).map(_._1), Right(Vector.empty[String]))
    val mine = AdminSeat("c" * 64, AccountIndexEntry(aid, now - 100, confirmed = true), activeAdmin = true)
    assert(!FirstAdminClaim.otherAdminExists(Vector(mine), aid))
    assert(FirstAdminClaim.otherAdminExists(Vector(mine, pendingAdmin), aid))
    assert(!FirstAdminClaim.otherAdminExists(Vector(mine, pendingAdmin.copy(activeAdmin = false)), aid))
  }

  test("有効な admin か他人の予約があれば拒否し、使えなくなった索引と期限切れの予約は外す") {
    val active = AdminSeat("a" * 64, AccountIndexEntry(other, now - 100, confirmed = true), activeAdmin = true)
    val inactive = AdminSeat("b" * 64, AccountIndexEntry(other, now - 100, confirmed = true), activeAdmin = false)
    val pending = AdminSeat("c" * 64, AccountIndexEntry(other, now - 10), activeAdmin = false)
    val expired = AdminSeat("d" * 64, AccountIndexEntry(other, now - 600), activeAdmin = false)
    val own = AdminSeat("e" * 64, AccountIndexEntry(aid, now - 10), activeAdmin = false)
    assertEquals(FirstAdminClaim.claim(Vector.empty, aid, now, breakGlass = false), Right((Vector.empty, AccountIndexEntry(aid, now))))
    assertEquals(FirstAdminClaim.claim(Vector(active), aid, now, breakGlass = false), Left(AccountRejection.Forbidden))
    assertEquals(FirstAdminClaim.claim(Vector(pending), aid, now, breakGlass = false), Left(AccountRejection.Forbidden))
    assertEquals(FirstAdminClaim.claim(Vector(inactive, expired, own), aid, now, breakGlass = false).map(_._1), Right(Vector("b" * 64, "d" * 64)))
    assertEquals(FirstAdminClaim.claim(Vector(active, pending), aid, now, breakGlass = true).map(_._2), Right(AccountIndexEntry(aid, now)))
    assertEquals(FirstAdminClaim.claim(Vector.empty, "short", now, breakGlass = false), Left(AccountRejection.Invalid))
  }
}
