package io._3ch.bbs.application.auth

import io._3ch.bbs.application.auth.ports.{AccountStore, PostingCredentials, TokenProof}
import io._3ch.bbs.domain.account.{AuthenticatedPoster, AuthenticationFailure}
import io._3ch.common.domain.account.{AccountDescription, AccountRejection, AccountVia, DedicatedBrowserLabel, SessionBinding, SessionClaims}

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.control.NonFatal

/** 投稿の認証結果。LegacyCap は所有者のない旧キャップ */
enum PostingAuthenticationResult {
  case Anonymous
  case LegacyCap
  case Accepted(poster: AuthenticatedPoster)
  case Rejected(reason: AuthenticationFailure)
}

/** 投稿認証の材料。capKey は照合済みキャップの既存 KV キー */
final case class PostingAuthenticationRequest(mail: String, cookieHeader: String, userAgent: String, isMonazilla: Boolean,
  secure: Boolean, now: Double, capKey: Option[String] = None)

/** キャップ、明示した専ブラキー、Cookie の順に投稿者を解決する */
final class PostingAuthentication(accounts: AccountStore, credentials: PostingCredentials, agreementVersion: String) {
  import PostingAuthenticationResult.*

  /** 明示したキーが拒否された場合は Cookie を参照しない。外部の障害でも認証を通さない */
  def resolve(request: PostingAuthenticationRequest): Future[PostingAuthenticationResult] =
    Future.unit.flatMap { _ =>
      request.capKey match {
        case Some(key) => cap(request, key)
        case None => explicitToken(request.mail) match {
          case Some(token) => dedicated(request, token)
          case None => session(request)
        }
      }
    }.recover { case NonFatal(_) => Rejected(AuthenticationFailure.Unavailable) }

  private def cap(request: PostingAuthenticationRequest, key: String): Future[PostingAuthenticationResult] =
    credentials.index("cap", key).flatMap {
      case None => Future.successful(Rejected(AuthenticationFailure.Unavailable))
      case Some(hash) => accounts.capOwner(hash).flatMap {
        case Left(reason) => Future.successful(Rejected(AuthenticationFailure.Account(reason)))
        case Right(None) => Future.successful(LegacyCap)
        case Right(Some(aid)) => accounts.describeCap(aid, key, request.now).map {
          case Left(reason) => Rejected(AuthenticationFailure.Account(reason))
          case Right(value) => accepted(value.account, AccountVia.Cap, None, Some(value.capId), "", None)
        }
      }
    }

  private def dedicated(request: PostingAuthenticationRequest, token: String): Future[PostingAuthenticationResult] = {
    if (!request.isMonazilla) Future.successful(Rejected(AuthenticationFailure.TokenNotDedicated))
    else if (!request.secure) Future.successful(Rejected(AuthenticationFailure.TokenInsecure))
    else if (!token.matches("[a-z2-7]{20}")) Future.successful(Rejected(AuthenticationFailure.Account(AccountRejection.TokenUnknown)))
    else for {
      hash <- credentials.index("token", token)
      ua <- credentials.ua(request.userAgent)
      result <- (hash, ua) match {
        case (Some(key), Some(uaHash)) => accounts.token(key).flatMap {
          case Left(reason) => Future.successful(Rejected(AuthenticationFailure.Account(reason)))
          case Right(None) => Future.successful(Rejected(AuthenticationFailure.Account(AccountRejection.TokenUnknown)))
          case Right(Some(reference)) => accounts.useToken(reference, uaHash, DedicatedBrowserLabel.of(request.userAgent), request.now).map {
            case Left(reason) => Rejected(AuthenticationFailure.Account(reason))
            case Right(value) =>
              val claims = claimsFor(value.account, request.now, "", Some(SessionBinding(value.tokenId, uaHash.take(16))))
              accepted(value.account, AccountVia.Token, Some(value.tokenId), None, "", Some(claims))
          }
        }
        case _ => Future.successful(Rejected(AuthenticationFailure.Unavailable))
      }
    } yield result
  }

  private def session(request: PostingAuthenticationRequest): Future[PostingAuthenticationResult] =
    credentials.session(request.cookieHeader, request.now).flatMap {
      case Some(claims) if claims.generation.isValidInt && claims.generation >= 0 && claims.isCurrent(request.now) =>
        val proof: Future[Option[Option[TokenProof]]] = claims.binding match {
          case None => Future.successful(Some(None))
          case Some(binding) => credentials.ua(request.userAgent).map(_.filter(_.startsWith(binding.uaHash)).map(hash => Some(TokenProof(binding.tokenId, hash))))
        }
        proof.flatMap {
          case None => Future.successful(Anonymous)
          case Some(token) => accounts.describe(claims.aid, claims.generation.toInt, request.now, token).map {
            case Left(reason @ (AccountRejection.Frozen | AccountRejection.Banned | AccountRejection.Deleted)) => Rejected(AuthenticationFailure.Account(reason))
            case Left(_) => Anonymous
            case Right(account) =>
              val next = claimsFor(account, request.now, claims.device, claims.binding)
              val refresh = next.lv != claims.lv || next.uid != claims.uid || math.abs(next.expiresAt.toDouble - claims.expiresAt.toDouble) >= 86400
              accepted(account, if (claims.binding.isDefined) AccountVia.Token else AccountVia.Web, claims.binding.map(_.tokenId), None,
                claims.device, Some(next), refresh)
          }
        }
      case _ => Future.successful(Anonymous)
    }

  private def accepted(account: AccountDescription, via: AccountVia, tokenId: Option[String], capId: Option[String], device: String,
    session: Option[SessionClaims], refreshSession: Boolean = true): PostingAuthenticationResult =
    if (agreementVersion.isEmpty || account.agreementVersion != agreementVersion) Rejected(AuthenticationFailure.Account(AccountRejection.Agreement))
    else Accepted(AuthenticatedPoster(account, via, tokenId, capId, device, session, refreshSession))

  private def claimsFor(account: AccountDescription, now: Double, device: String, binding: Option[SessionBinding]): SessionClaims =
    SessionClaims(account.aid, account.uid, account.generation.toLong, account.expiresAt.getOrElse(now + 365d * 86400).toLong,
      account.lv, device, binding)

  private def explicitToken(mail: String): Option[String] = {
    val index = mail.indexOf("!#")
    Option.when(index >= 0)(mail.substring(index + 2).takeWhile(c => !c.isWhitespace))
  }
}
