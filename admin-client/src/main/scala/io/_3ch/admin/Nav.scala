package io._3ch.admin

import japgolly.scalajs.react.Callback

/** 現在の画面と、画面の移動・未保存の印。go は未保存の変更があれば確認してから移る */
final case class Nav(page: AdminPage, go: AdminPage => Callback, setDirty: Boolean => Callback)
