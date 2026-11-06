package io._3ch.common.domain.account

import munit.FunSuite

class AccountCapSuite extends FunSuite {
  private val now = 2000000000d
  private val aid = "abcdefghijklmnop"
  private val admin = "qrstuvwxyz012345"
  private val profile = AccountProfile(aid, "user1234", AccountKind.Google, now, None, now, "v1", googleSubHash = Some("a" * 64))
  private def owner(roles: AccountRole*): AccountState =
    AccountState.create(profile, now).flatMap(_.activate(now)).flatMap(_.setRoles(Roles(roles.toSet))).toOption.get
  private def cap(id: String, hash: String, issuedBy: String = aid, strong: Boolean = false): AccountCap = AccountCap(id, "管理人", hash, strong, issuedBy, now)

  test("キャップ名は前後の空白を除いて 1〜16 文字、HTML とキャップ・トリップの印と制御文字を拒否する") {
    assertEquals(CapName.parse(" 削除人 "), Right("削除人"))
    assertEquals(CapName.parse("a" * 16), Right("a" * 16))
    assertEquals(CapName.parse("a" * 17), Left(CapNameRejection.Length))
    assertEquals(CapName.parse("   "), Left(CapNameRejection.Length))
    Seq("<b>", "a&b", "x★", "y◆", "#pass", "名＠前", "a\nb", "a​b").foreach(name => assertEquals(CapName.parse(name), Left(CapNameRejection.Character), name))
    assertEquals(cap("c", "h", strong = true).storedName, "管理人★")
    assertEquals(cap("c", "h").storedName, "管理人")
  }

  test("クライアント ID と不正な名前のキャップは登録しない") {
    val client = AccountState.create(profile.copy(kind = AccountKind.Client, googleSubHash = None), now).toOption.get
    assertEquals(client.bindCap(cap("c1", "h1"), issuerIsAdmin = true, now), Left(AccountRejection.RolesForbidden))
    assertEquals(owner(AccountRole.Cap).bindCap(cap("c1", "h1").copy(name = "<b>"), issuerIsAdmin = false, now), Left(AccountRejection.Invalid))
  }

  test("自分で発行したキャップは役割を全て外すと使えず、admin が発行したものは使える") {
    val own = owner(AccountRole.Delete).bindCap(cap("c1", "h1"), issuerIsAdmin = false, now).toOption.get
    assert(own.describeForCap(aid, "h1", now).isRight)
    assertEquals(own.setRoles(Roles()).toOption.get.describeForCap(aid, "h1", now), Left(AccountRejection.CapRevoked))
    val fromAdmin = owner().bindCap(cap("c2", "h2", issuedBy = admin), issuerIsAdmin = true, now).toOption.get
    assert(fromAdmin.describeForCap(aid, "h2", now).isRight)
  }

  test("再発行は古いキャップを失効させ、同じ名前と強さで照合値だけ新しくする。一個の上限も守る") {
    val bound = owner(AccountRole.Regulate).bindCap(cap("c1", "h1"), issuerIsAdmin = false, now).toOption.get
    val (next, old, fresh) = bound.reissueCap("c1", "c2", "h2", aid, issuerIsAdmin = false, now + 5).toOption.get
    assertEquals((old.hash, old.revokedAt), ("h1", Some(now + 5)))
    assertEquals((fresh.name, fresh.strong, fresh.hash, fresh.issuedAt), ("管理人", false, "h2", now + 5))
    assertEquals(next.describeForCap(aid, "h1", now + 5), Left(AccountRejection.CapRevoked))
    assert(next.describeForCap(aid, "h2", now + 5).isRight)
    assertEquals(next.reissueCap("c1", "c3", "h3", aid, issuerIsAdmin = false, now + 6), Left(AccountRejection.CapRevoked))
    val strong = owner(AccountRole.Regulate).bindCap(cap("s1", "hs", issuedBy = admin, strong = true), issuerIsAdmin = true, now).toOption.get
    assertEquals(strong.reissueCap("s1", "s2", "hs2", aid, issuerIsAdmin = false, now), Left(AccountRejection.RolesForbidden), "強キャップの再発行は admin だけ")
  }

  test("失効の記録は新しい 20 件まで残し、キャップごとの書き込みを新しい順に返す") {
    val many = (0 until 25).foldLeft(owner(AccountRole.Admin)) { (state, i) =>
      val bound = state.bindCap(cap(s"c$i", s"h$i"), issuerIsAdmin = true, now).toOption.get
      bound.revokeCap(s"c$i", now + i).toOption.get._1
    }
    assertEquals(many.caps.size, AccountState.revokedCapLimit)
    assertEquals(many.caps.map(_.capId).toSet, (5 until 25).map(i => s"c$i").toSet)
    val bound = owner(AccountRole.Cap).bindCap(cap("c1", "h1"), issuerIsAdmin = false, now).toOption.get
    val log = (1 to 7).toVector.map(i => AccountPost(now + i, AccountVia.Cap, capId = Some("c1"), resNum = i)) :+
      AccountPost(now + 8, AccountVia.Web, resNum = 99) :+ AccountPost(now + 9, AccountVia.Cap, capId = Some("gone"), resNum = 100)
    assertEquals(bound.capPosts(log).view.mapValues(_.map(_.resNum)).toMap, Map("c1" -> Vector(7, 6, 5, 4, 3)))
  }
}
