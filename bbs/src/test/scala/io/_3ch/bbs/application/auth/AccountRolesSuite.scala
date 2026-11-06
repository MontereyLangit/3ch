package io._3ch.bbs.application.auth

import io._3ch.bbs.application.auth.ports.{AccountStoreException, AuthenticationKeys}
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
    val regulate = admin(first).copy(roles = Roles(Set(AccountRole.Regulate)))
    for {
      (_, repository, roles) <- setup(first, second)
      refused <- roles.assign(regulate, second, Roles(Set(AccountRole.Admin)), now).failed
      unchanged <- rolesOf(repository, second)
      unlisted <- adminIndex(repository, second)
      invalid <- repository.setRoles(second, Roles(), Roles(Set(AccountRole.Admin)), Double.NaN).failed
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
      _ <- repository.setRoles(first, Roles(), Roles(Set(AccountRole.Admin)), now)
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
}
