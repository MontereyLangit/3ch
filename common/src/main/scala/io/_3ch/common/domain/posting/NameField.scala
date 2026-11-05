package io._3ch.common.domain.posting

/** 名前・ユーザー ID 表示・識別子を dat の名前欄に連結する。識別子が空でも括弧は残す */
object NameField {
  def withIdentity(name: String, userSuffix: String, shikibetsu: String): String = name + userSuffix + s" </b>($shikibetsu)<b>"
}
