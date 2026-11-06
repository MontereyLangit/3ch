package io._3ch.common.domain.account

/** メールアドレスを受け付けない理由 */
enum EmailRejection {
  case Invalid
  case Disposable
}

/** 正規化したメールアドレス。address は送信先、canonical は一意性の照合に使う値 */
final case class EmailAddress private (address: String, canonical: String) {
  def domain: String = address.substring(address.lastIndexOf('@') + 1)
}

object EmailAddress {
  val MaxLength: Int = 254
  private val Local = "[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*"
  private val Label = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
  private val Gmail = Set("gmail.com", "googlemail.com")

  /**
    * 前後の空白を除いて小文字にし、ドメインを punycode にする。
    * gmail.com と googlemail.com はローカル部のドットと + 以降を除き gmail.com として照合する
    */
  def parse(raw: String): Either[EmailRejection, EmailAddress] = {
    val trimmed = Option(raw).getOrElse("").trim
    val at = trimmed.lastIndexOf('@')
    if (at <= 0 || at > 64 || at == trimmed.length - 1 || trimmed.length > MaxLength) Left(EmailRejection.Invalid)
    else {
      val local = LoginId.lowercase(trimmed.substring(0, at))
      val rawDomain = trimmed.substring(at + 1)
      val domain = if (rawDomain.exists(c => c <= ' ' || "/\\?#@:[]%".contains(c))) None else AccountText.asciiDomain(rawDomain)
      domain.filter(d => d.length <= 253 && d.matches(s"(?:$Label\\.)+$Label") && local.matches(Local)) match {
        case None => Left(EmailRejection.Invalid)
        case Some(d) =>
          val address = local + "@" + d
          val canonical = if (Gmail.contains(d)) local.takeWhile(_ != '+').replace(".", "") + "@gmail.com" else address
          if (address.length > MaxLength) Left(EmailRejection.Invalid) else Right(new EmailAddress(address, canonical))
      }
    }
  }
}

/** 使い捨てメールのドメイン一覧。一覧のドメインとそのサブドメインを拒否する */
final case class DisposableDomains(domains: Set[String]) {
  def blocks(email: EmailAddress): Boolean = {
    val labels = email.domain.split('.')
    labels.indices.exists(i => domains.contains(labels.drop(i).mkString(".")))
  }
}

object DisposableDomains {
  val empty: DisposableDomains = DisposableDomains(Set.empty)

  /** 1 行に 1 ドメイン。空行と # で始まる行は読み飛ばす */
  def parse(text: String): DisposableDomains = DisposableDomains(Option(text).getOrElse("").split("\\r?\\n").iterator
    .map(line => LoginId.lowercase(line.trim)).filter(line => line.nonEmpty && !line.startsWith("#")).toSet)
}

/** 保留トークンの種別。メール認証は 24 時間、パスワード再設定は 1 時間で失効する */
enum PendingKind(val code: String, val lifetime: Double) {
  case Email extends PendingKind("email", 86400)
  case Reset extends PendingKind("reset", 3600)
}

object PendingKind {
  def parse(code: String): Option[PendingKind] = PendingKind.values.find(_.code == code)
}

/** 保留中のトークン。hash はトークンの HMAC、email はメール認証で確定させるアドレス */
final case class PendingToken(hash: String, expiresAt: Double, email: Option[String] = None) {
  def valid: Boolean = DedicatedBrowserTokens.validHash(hash) && AccountProfile.validTime(expiresAt) && email.forall(_.nonEmpty)

  /** ハッシュが一致し、期限内か */
  def accepts(candidate: String, now: Double): Boolean =
    candidate.length == hash.length && candidate.indices.foldLeft(0)((d, i) => d | (candidate.charAt(i) ^ hash.charAt(i))) == 0 && now < expiresAt
}

/** メールのリンクに載せる保留トークン。aid で Account を引き、secret の HMAC を保存値と照合する */
final case class PendingLink(aid: String, secret: String) {
  def text: String = aid + "." + secret
}

object PendingLink {
  def parse(text: String): Option[PendingLink] = Option(text).filter(_.matches("[a-z0-9]{16}\\.[A-Za-z0-9_-]{43}")).map { value =>
    PendingLink(value.take(16), value.drop(17))
  }
}
