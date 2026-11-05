package io._3ch.common.domain.posting

/** 名前・ユーザー ID 表示・識別子を dat の名前欄に連結する。識別子が空でも括弧は残す */
object NameField {
  /** 名前の後にユーザー ID 表示と、識別子を括弧で囲んで付ける */
  def withIdentity(name: String, userSuffix: String, shikibetsu: String): String = name + userSuffix + s" </b>($shikibetsu)<b>"
}
