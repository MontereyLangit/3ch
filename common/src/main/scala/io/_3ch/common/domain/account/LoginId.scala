package io._3ch.common.domain.account

/** ユーザー ID の書式と登録時の予約語による拒否 */
enum LoginIdRejection {
  case Invalid
  case Reserved
}

/** 小文字の英数字とアンダースコアからなる公開 ID。登録後は変更しない */
final class LoginId private (val value: String) {
  override def equals(other: Any): Boolean = other match {
    case that: LoginId => value == that.value
    case _ => false
  }
  override def hashCode(): Int = value.hashCode
  override def toString: String = value
}

object LoginId {
  private val reserved = Set("admin", "root", "test", "3ch", "saku", "auth", "mod", "noname")

  private[account] def lowercase(value: String): String = value.map { c =>
    if (c >= 'A' && c <= 'Z') (c + ('a' - 'A')).toChar else c
  }

  /** ログイン入力を小文字化して検証する。前後の空白は除かず、予約語は判定しない */
  def parse(raw: String): Either[LoginIdRejection, LoginId] = {
    if (raw == null) Left(LoginIdRejection.Invalid)
    else {
      val value = lowercase(raw)
      if (value.matches("[a-z0-9_]{3,16}")) Right(new LoginId(value)) else Left(LoginIdRejection.Invalid)
    }
  }

  /** 登録用。固定の予約語に、呼び出し元の予約語と現在の板名を加えて拒否する */
  def create(raw: String, reservedIds: Set[String], boardIds: Set[String]): Either[LoginIdRejection, LoginId] =
    parse(raw).flatMap { id =>
      val additional = (reservedIds ++ boardIds).iterator.filter(_ != null).map(lowercase).toSet
      if (reserved.contains(id.value) || additional.contains(id.value)) Left(LoginIdRejection.Reserved) else Right(id)
    }
}
