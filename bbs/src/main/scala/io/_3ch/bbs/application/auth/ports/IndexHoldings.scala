package io._3ch.bbs.application.auth.ports

import scala.concurrent.Future

/** 索引の所有者が値を今も持っているかの確認と、確定前の予約の取り消し。放置された索引を解放するのに使う */
trait IndexHoldings {
  /** 退会していないアカウント aid が、索引の値（ログイン ID・Google の sub の HMAC・メールの正規形）を今も持っているか */
  def holds(aid: String, kind: String, value: String): Future[Boolean]
  /** 自分の確定前の予約だけを取り消す。確定済みの値は取り消さない */
  def releaseReservation(kind: String, hash: String, aid: String): Future[Unit]
  def remove(kind: String, hash: String, aid: String): Future[Unit]
}
