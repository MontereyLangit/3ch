package io._3ch.bbs.handler.auth

import io._3ch.bbs.application.auth.{AuthServiceException, AuthServiceFailure, CapIssuing}
import io._3ch.bbs.application.auth.ports.{AccountStoreException, CapDirectory}
import io._3ch.bbs.application.posting.CapKey
import io._3ch.bbs.hash.WebHasher
import io._3ch.bbs.repository.{AccountRepository, CapRepository}
import io._3ch.common.domain.account.{AccountDescription, AccountKind, AccountRejection, AccountRole, Roles}
import io._3ch.common.domain.posting.CapHandle
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class AuthCapSuite extends munit.FunSuite {
  private val register = Seq("id" -> "taro_2026", "password" -> "correct horse", "password2" -> "correct horse", "agreement" -> "v1", "cf-turnstile-response" -> "accepted")
  private val adminRoles = Roles(Set(AccountRole.Admin))

  /** 登録してセッション Cookie と aid を返し、指定した役割を付ける */
  private def signedUp(t: AuthSignInTestKit, roles: Set[AccountRole]): Future[(String, String)] = for {
    registered <- t.send(t.post("/auth/register", register))
    cookie = t.cookie(registered, "__Host-ch3s_sid").get
    session <- SessionCookies.verify(cookie, AuthTestEnv.secret, js.Date.now() / 1000)
    aid = session.get.aid
    _ <- if (roles.isEmpty) Future.unit else t.admin().flatMap(actor => new AccountRepository(t.env).setRoles(aid, Roles(roles), actor, math.floor(js.Date.now() / 1000)))
  } yield (cookie, aid)

  private val salt = "test-salt-12345"
  private def kvKey(password: String): Future[String] = WebHasher.sha256Hex(salt).flatMap(CapKey.of(WebHasher, password.stripPrefix("#"), _))

  test("役割の無いアカウントは発行できず、権限を持つ人の文言を返す") {
    val t = new AuthSignInTestKit()
    t.run {
      for {
        (cookie, _) <- signedUp(t, Set.empty)
        refused <- t.send(t.post("/auth/cap/issue", Seq("name" -> "管理人"), cookie = cookie))
        body <- t.body(refused)
      } yield {
        assertEquals(refused.status, 403)
        assertEquals(body.code.asInstanceOf[String], "auth:roles:forbidden")
        assert(body.error.asInstanceOf[String].contains("権限"))
      }
    }
  }

  test("cap 権限者は自分用に一個発行でき、KV と cap 索引に載り、強キャップや二個目は拒否する") {
    val t = new AuthSignInTestKit()
    t.run {
      for {
        (cookie, aid) <- signedUp(t, Set(AccountRole.Cap))
        badName <- t.send(t.post("/auth/cap/issue", Seq("name" -> "<b>"), cookie = cookie))
        strong <- t.send(t.post("/auth/cap/issue", Seq("name" -> "管理人", "strong" -> "1"), cookie = cookie))
        issued <- t.send(t.post("/auth/cap/issue", Seq("name" -> " 管理人 "), cookie = cookie))
        body <- t.body(issued)
        password = body.password.asInstanceOf[String]
        key <- kvKey(password)
        indexHash <- t.index("cap", key)
        owner <- new AccountRepository(t.env).capOwner(indexHash)
        second <- t.send(t.post("/auth/cap/issue", Seq("name" -> "別名"), cookie = cookie))
        panel <- new AccountRepository(t.env).inspect(aid)
      } yield {
        assertEquals((badName.status, strong.status, issued.status, second.status), (400, 403, 200, 403))
        assert(password.matches("#[a-z2-7]{20}"), password)
        assertEquals(t.kit.capValue(key).flatMap(CapHandle.fromStored), Some(CapHandle("管理人", strong = false)))
        assertEquals(owner, Right(Some(aid)))
        assertEquals(panel.caps.map(c => (c.id, c.name, c.revokedAt.isEmpty)), Vector((body.id.asInstanceOf[String], "管理人", true)))
      }
    }
  }

  test("再発行は古いパスワードを KV から外し、cap 索引は残して古い写しが KV に残っても失効として拒否する") {
    val t = new AuthSignInTestKit()
    t.run {
      for {
        (cookie, aid) <- signedUp(t, Set(AccountRole.Delete))
        issued <- t.send(t.post("/auth/cap/issue", Seq("name" -> "削除人"), cookie = cookie)).flatMap(t.body)
        oldKey <- kvKey(issued.password.asInstanceOf[String])
        oldIndex <- t.index("cap", oldKey)
        reissued <- t.send(t.post("/auth/cap/reissue", Seq("id" -> issued.id.asInstanceOf[String]), cookie = cookie)).flatMap(t.body)
        newKey <- kvKey(reissued.password.asInstanceOf[String])
        oldOwner <- new AccountRepository(t.env).capOwner(oldIndex)
        stale <- new AccountRepository(t.env).describeCap(aid, oldKey, math.floor(js.Date.now() / 1000)).map(_.map(_.capId))
        afterReissue = (t.kit.capValue(oldKey), t.kit.capValue(newKey))
        revoked <- t.send(t.post("/auth/cap/revoke", Seq("id" -> reissued.id.asInstanceOf[String]), cookie = cookie))
        again <- t.send(t.post("/auth/cap/revoke", Seq("id" -> reissued.id.asInstanceOf[String]), cookie = cookie))
        panel <- new AccountRepository(t.env).inspect(aid)
      } yield {
        assertEquals(afterReissue, (None, Some("削除人")))
        assertEquals(oldOwner, Right(Some(aid)), "cap 索引は失効後も所有者を指し、旧キャップに見せない")
        assertEquals(stale, Left(AccountRejection.CapRevoked))
        assertEquals((revoked.status, again.status), (200, 200))
        assertEquals(t.kit.capValue(newKey), None)
        assertEquals(panel.caps.count(_.revokedAt.isEmpty), 0)
        assertEquals(panel.caps.size, 2)
      }
    }
  }

  test("旧キャップ（索引に無い KV の値）は発行・失効の対象にならず、そのまま残る") {
    val t = new AuthSignInTestKit()
    t.kit.putCap("legacy-key", "旧キャップ")
    t.run {
      for {
        (cookie, _) <- signedUp(t, Set(AccountRole.Cap))
        unknown <- t.send(t.post("/auth/cap/revoke", Seq("id" -> "legacykey"), cookie = cookie))
      } yield {
        assertEquals(unknown.status, 403)
        assertEquals(t.kit.capValue("legacy-key"), Some("旧キャップ"))
      }
    }
  }

  test("admin は他人に強キャップを発行でき、admin でない人の他人宛て発行と一日十一回目の発行は拒否する") {
    val t = new AuthSignInTestKit()
    val repository = new AccountRepository(t.env)
    def service = new CapIssuing(repository, CapRepository.directory(t.env), AuthHandler.keys(AuthTestEnv.secret), password => WebHasher.sha256Hex(salt).flatMap(CapKey.of(WebHasher, password, _)))
    val now = math.floor(js.Date.now() / 1000)
    t.run {
      for {
        (_, owner) <- signedUp(t, Set.empty)
        admin = AccountDescription("zyxwvutsrqponmlk", "admin_1", AccountKind.Google, 0, None, 10, adminRoles, now, "v1")
        issued <- service.issue(admin, owner, "運営", strong = true, now)
        key <- kvKey(issued.password)
        refused <- service.issue(admin.copy(roles = Roles(Set(AccountRole.Regulate))), owner, "規制", strong = false, now).failed
        _ <- Future.traverse((1 to 8).toList)(i => service.issue(admin, owner, s"運営$i", strong = false, now))
        limited <- service.issue(admin, owner, "上限", strong = false, now).failed
        foreign <- service.revoke(admin.copy(roles = Roles(Set(AccountRole.Delete))), owner, issued.capId, now).failed
      } yield {
        assertEquals(t.kit.capValue(key), Some("運営★"))
        assertEquals(refused, AccountStoreException(AccountRejection.RolesForbidden))
        assertEquals(limited, AuthServiceException(AuthServiceFailure.RateLimited))
        assertEquals(foreign, AccountStoreException(AccountRejection.Forbidden))
      }
    }
  }

  test("発行の途中で KV に書けなくても cap 索引は所有者を指したままで、キャップは失効する") {
    val t = new AuthSignInTestKit()
    val repository = new AccountRepository(t.env)
    var tried = Option.empty[String]
    val directory = new CapDirectory {
      def exists(key: String): Future[Boolean] = { tried = Some(key); Future.successful(false) }
      def put(key: String, storedName: String): Future[Unit] = Future.failed(new IllegalStateException("kv down"))
      def delete(key: String): Future[Unit] = Future.unit
    }
    val service = new CapIssuing(repository, directory, AuthHandler.keys(AuthTestEnv.secret), password => WebHasher.sha256Hex(salt).flatMap(CapKey.of(WebHasher, password, _)))
    val now = math.floor(js.Date.now() / 1000)
    t.run {
      for {
        (_, owner) <- signedUp(t, Set(AccountRole.Cap))
        issuer <- repository.inspect(owner).map(_.account)
        failed <- service.issue(issuer, owner, "運営", strong = false, now).failed
        hash <- t.index("cap", tried.get)
        indexed <- repository.capOwner(hash)
        used <- repository.describeCap(owner, tried.get, now).map(_.map(_.capId))
        panel <- repository.inspect(owner)
      } yield {
        assert(failed.isInstanceOf[IllegalStateException])
        assertEquals(indexed, Right(Some(owner)))
        assertEquals(used, Left(AccountRejection.CapRevoked))
        assertEquals(panel.caps.map(_.revokedAt.isDefined), Vector(true))
      }
    }
  }
}
