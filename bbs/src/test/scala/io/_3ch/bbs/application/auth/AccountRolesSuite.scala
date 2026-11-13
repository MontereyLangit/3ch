package io._3ch.bbs.application.auth

import io._3ch.bbs.application.auth.ports.{AccountRoleStore, AccountStoreException, AuthenticationKeys}
import io._3ch.bbs.durable.DurableTestKit
import io._3ch.bbs.hash.AuthHmac
import io._3ch.bbs.repository.AccountRepository
import io._3ch.common.domain.account.*
import munit.FunSuite
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class AccountRolesSuite extends FunSuite {
  private val now = 2000000000d
  private val secret = "test-roles-secret-0123456789abcdef"
  private val first = "abcdefghijklmnop"
  private val second = "qrstuvwxyz012345"
  private val third = "zyxwvutsrqponmlk"

  private val keys = new AuthenticationKeys {
    def aid(): String = AuthHmac.randomAid()
    def uid(): String = AuthHmac.randomUid()
    def token(): String = AuthHmac.randomBase32(20)
    def tokenId(): String = AuthHmac.randomBase32(16)
    def linkSecret(): String = AuthHmac.randomUrlSafe(43)
    def index(kind: String, value: String): Future[Option[String]] = AuthHmac.indexKey(secret, kind, value)
  }

  private def setup(aids: String*): Future[(DurableTestKit.Env, AccountRepository, AccountRoles)] = {
    val kit = DurableTestKit.newEnv()
    val repository = new AccountRepository(kit.worker)
    val created = aids.zipWithIndex.foldLeft(Future.unit) { case (acc, (aid, i)) =>
      val profile = AccountProfile(aid, s"user_$i", AccountKind.Google, now, None, now, "v1", googleSubHash = Some(f"$i%064x"))
      acc.flatMap(_ => repository.create(profile, now)).flatMap(_ => repository.activate(aid, now)).map(_ => ())
    }
    created.map(_ => (kit, repository, new AccountRoles(repository, keys)))
  }
  private def rolesOf(repository: AccountRepository, aid: String): Future[Set[AccountRole]] = repository.inspect(aid).map(_.account.roles.values)
  private def adminIndex(repository: AccountRepository, aid: String): Future[Option[String]] =
    keys.index(AccountIndexKind.Admin.code, aid).flatMap(hash => repository.lookup(AccountIndexKind.Admin.code, hash.get))
  private def admin(aid: String): AccountDescription =
    AccountDescription(aid, "admin", AccountKind.Google, 0, None, 10, Roles(Set(AccountRole.Admin)), now, "v1")

  test("admin が役割を付け外しし、admin の付与と剥奪を role:admin 索引に反映する") {
    for {
      (_, repository, roles) <- setup(first, second)
      _ <- roles.claimFirstAdmin(first, now, breakGlass = false)
      _ <- roles.assign(admin(first), second, Roles(Set(AccountRole.Admin, AccountRole.Cap)), now)
      granted <- rolesOf(repository, second)
      listed <- adminIndex(repository, second)
      _ <- roles.assign(admin(first), second, Roles(Set(AccountRole.Admin, AccountRole.Cap)), now)
      _ <- roles.assign(admin(first), second, Roles(Set(AccountRole.Delete)), now)
      demoted <- rolesOf(repository, second)
      unlisted <- adminIndex(repository, second)
    } yield {
      assertEquals(granted, Set(AccountRole.Admin, AccountRole.Cap))
      assertEquals(listed, Some(second))
      assertEquals(demoted, Set(AccountRole.Delete))
      assertEquals(unlisted, None)
    }
  }

  test("admin 以外の役割の変更は Account DO が拒否し、索引も変えない") {
    for {
      (_, repository, roles) <- setup(first, second, third)
      _ <- roles.claimFirstAdmin(third, now, breakGlass = false)
      _ <- roles.assign(admin(third), first, Roles(Set(AccountRole.Regulate)), now)
      refused <- roles.assign(admin(first), second, Roles(Set(AccountRole.Admin)), now).failed
      unchanged <- rolesOf(repository, second)
      unlisted <- adminIndex(repository, second)
      invalid <- repository.setRoles(second, Roles(), third, Double.NaN).failed
    } yield {
      assertEquals(refused, AccountStoreException(AccountRejection.Forbidden))
      assertEquals((unchanged, unlisted), (Set.empty[AccountRole], None))
      assertEquals(invalid, AccountStoreException(AccountRejection.Invalid))
    }
  }

  test("同時に初回の管理者を確保しても admin になるのは一人だけ") {
    for {
      (_, repository, roles) <- setup(first, second, third)
      results <- Future.traverse(Seq(first, second, third))(aid => roles.claimFirstAdmin(aid, now, breakGlass = false).map(_ => aid -> true).recover { case _ => aid -> false })
      admins <- Future.traverse(Seq(first, second, third))(aid => rolesOf(repository, aid).map(r => aid -> r.contains(AccountRole.Admin)))
      later <- roles.claimFirstAdmin(third, now + 1000, breakGlass = false).failed
    } yield {
      assertEquals(results.count(_._2), 1)
      assertEquals(admins.filter(_._2).map(_._1), results.filter(_._2).map(_._1))
      assertEquals(later, AccountStoreException(AccountRejection.Forbidden))
    }
  }

  test("admin を外したアカウントは数えずに確保でき、非常用期間は有効な admin が居ても確保できる") {
    for {
      (_, repository, roles) <- setup(first, second, third)
      _ <- roles.claimFirstAdmin(first, now, breakGlass = false)
      _ <- repository.setRoles(first, Roles(), first, now)
      _ <- roles.claimFirstAdmin(second, now + 1, breakGlass = false)
      _ <- roles.claimFirstAdmin(third, now + 2, breakGlass = true)
      firstIndex <- adminIndex(repository, first)
      admins <- Future.traverse(Seq(first, second, third))(aid => rolesOf(repository, aid).map(_.contains(AccountRole.Admin)))
    } yield {
      assertEquals(firstIndex, None, "admin でなくなった所有者の索引は外す")
      assertEquals(admins, Seq(false, true, true))
    }
  }

  test("役割の付与に失敗したら予約を外し、ほかの人が確保できる") {
    for {
      (_, repository, roles) <- setup(second)
      missing <- roles.claimFirstAdmin(first, now, breakGlass = false).failed
      _ <- roles.claimFirstAdmin(second, now + 1, breakGlass = false)
      admins <- rolesOf(repository, second)
    } yield {
      assertEquals(missing, AccountStoreException(AccountRejection.Missing))
      assert(admins.contains(AccountRole.Admin))
    }
  }

  /** 実 DO を呼び、指定した操作だけ失敗や応答の喪失を起こす */
  private final class Flaky(repository: AccountRepository) extends AccountRoleStore {
    var lostGrant = false
    var failIsAdmin = false
    var failConfirms = 0
    def setRoles(aid: String, roles: Roles, actorAid: String, now: Double) = repository.setRoles(aid, roles, actorAid, now)
    def grantFirstAdmin(hash: String, aid: String, now: Double) =
      repository.grantFirstAdmin(hash, aid, now).flatMap(_ => if (lostGrant) Future.failed(new IllegalStateException("lost")) else Future.unit)
    def isAdmin(aid: String, now: Double) = if (failIsAdmin) Future.failed(new IllegalStateException("down")) else repository.isAdmin(aid, now)
    def surrender(hash: String, aid: String, now: Double) = repository.surrender(hash, aid, now)
    def claimFirstAdmin(hash: String, aid: String, now: Double, breakGlass: Boolean) = repository.claimFirstAdmin(hash, aid, now, breakGlass)
    def reserve(kind: String, hash: String, aid: String, now: Double, tokenId: Option[String]) = repository.reserve(kind, hash, aid, now, tokenId)
    def confirm(kind: String, hash: String, aid: String, now: Double) =
      if (failConfirms > 0) { failConfirms -= 1; Future.failed(new IllegalStateException("confirm lost")) } else repository.confirm(kind, hash, aid, now)
    def remove(kind: String, hash: String, aid: String) = repository.remove(kind, hash, aid)
  }

  test("最後の有効な admin は外せず、ほかに admin が居れば外せる") {
    for {
      (_, repository, roles) <- setup(first, second)
      _ <- roles.claimFirstAdmin(first, now, breakGlass = false)
      last <- roles.assign(admin(first), first, Roles(), now).failed
      stillAdmin <- rolesOf(repository, first)
      stillListed <- adminIndex(repository, first)
      _ <- roles.assign(admin(first), second, Roles(Set(AccountRole.Admin)), now)
      _ <- roles.assign(admin(second), first, Roles(Set(AccountRole.Cap)), now)
      stripped <- rolesOf(repository, first)
      firstIndex <- adminIndex(repository, first)
      lastAgain <- roles.assign(admin(second), second, Roles(), now).failed
    } yield {
      assertEquals(last, AccountStoreException(AccountRejection.Forbidden))
      assertEquals((stillAdmin, stillListed), (Set(AccountRole.Admin), Some(first)))
      assertEquals((stripped, firstIndex), (Set(AccountRole.Cap), None))
      assertEquals(lastAgain, AccountStoreException(AccountRejection.Forbidden))
    }
  }

  test("役割の変更は呼び出し元の申告でなく操作する人の Account の今の役割で判定する") {
    for {
      (_, repository, roles) <- setup(first, second, third)
      _ <- roles.claimFirstAdmin(first, now, breakGlass = false)
      _ <- roles.assign(admin(first), second, Roles(Set(AccountRole.Cap)), now)
      forged <- roles.assign(admin(second), third, Roles(Set(AccountRole.Delete)), now).failed
      unknown <- roles.assign(admin("zzzzzzzzzzzzzzzz"), third, Roles(Set(AccountRole.Delete)), now).failed
      unchanged <- rolesOf(repository, third)
    } yield {
      assertEquals(forged, AccountStoreException(AccountRejection.Forbidden))
      assertEquals(unknown, AccountStoreException(AccountRejection.Forbidden))
      assertEquals(unchanged, Set.empty[AccountRole])
    }
  }

  test("索引に確保が無いアカウントには初回の管理者として admin を足さない") {
    for {
      (_, repository, _) <- setup(first)
      hash <- keys.index(AccountIndexKind.Admin.code, first).map(_.get)
      refused <- repository.grantFirstAdmin(hash, first, now).failed
      roles <- rolesOf(repository, first)
    } yield {
      assertEquals(refused, AccountStoreException(AccountRejection.Forbidden))
      assertEquals(roles, Set.empty[AccountRole])
    }
  }

  test("確保の途中で止まったら admin の有無を読み直し、admin なら確保を確定して残す") {
    for {
      (_, repository, _) <- setup(first, second)
      flaky = new Flaky(repository)
      lost = new AccountRoles(flaky, keys)
      _ = { flaky.lostGrant = true }
      _ <- lost.claimFirstAdmin(first, now, breakGlass = false)
      admins <- rolesOf(repository, first)
      listed <- adminIndex(repository, first)
      later <- new AccountRoles(repository, keys).claimFirstAdmin(second, now + 1000, breakGlass = false).failed
    } yield {
      assert(admins.contains(AccountRole.Admin))
      assertEquals(listed, Some(first))
      assertEquals(later, AccountStoreException(AccountRejection.Forbidden))
    }
  }

  test("確定が失敗しても admin なら確定し直し、読み直せないときは確保を外さず、期限が切れても admin の確保は取られない") {
    for {
      (_, repository, _) <- setup(first, second)
      flaky = new Flaky(repository)
      _ = { flaky.failConfirms = 1 }
      _ <- new AccountRoles(flaky, keys).claimFirstAdmin(first, now, breakGlass = false)
      listed <- adminIndex(repository, first)
      (_, other, _) <- setup(first, second)
      down = new Flaky(other)
      _ = { down.lostGrant = true; down.failIsAdmin = true }
      failed <- new AccountRoles(down, keys).claimFirstAdmin(first, now, breakGlass = false).failed
      pending <- adminIndex(other, first)
      expired <- new AccountRoles(other, keys).claimFirstAdmin(second, now + 1000, breakGlass = false).failed
    } yield {
      assertEquals(listed, Some(first))
      assert(failed.isInstanceOf[IllegalStateException])
      assertEquals(pending, None, "確定前の確保は lookup に出ない")
      assertEquals(expired, AccountStoreException(AccountRejection.Forbidden), "admin が付いた所有者の確保は期限が切れても取れない")
    }
  }
}
