package ch3s.bbs.components

import japgolly.scalajs.react.ScalaFnComponent
import japgolly.scalajs.react.vdom.html_<^.*

case class FeedThread(key: String, title: String, count: Int, ago: String, speed: Double, first: ThreadPost, latest: ThreadPost)

case class ThreadFeedProps(bbs: String, time: String, query: String = "", sort: String = "new")

/** フィード取得APIの実装まで利用する画面確認用仮データ */
object DummyThreadFeed {
  val threads: Vector[FeedThread] = Vector(
    FeedThread("1793358001", "雑談スレッド Part 12", 2, "2分前", 32.4, post(1, "気軽に雑談していきましょう。"), post(2, "昼休みに覗きに来た")),
    FeedThread("1793358002", "今日の出来事を書いていくスレ", 1, "15分前", 12.8, post(1, "駅前のパン屋が新装開店してた"), post(1, "駅前のパン屋が新装開店してた")),
    FeedThread("1793358003", "掲示板の使い方・質問スレッド", 1, "1時間前", 8.2, post(1, "スレ一覧の並び順はどこで変えられますか？"), post(1, "スレ一覧の並び順はどこで変えられますか？")),
    FeedThread("1793358004", "好きな音楽を教えてください", 1, "3時間前", 5.1, post(1, "最近はジャズばかり聴いてる"), post(1, "最近はジャズばかり聴いてる")),
    FeedThread("1793358005", "掲示板からのお知らせ", 1, "1日前", 0.5, post(1, "新しい板topの試験運用を始めました。"), post(1, "新しい板topの試験運用を始めました。"))
  )
  val commonThreads: Vector[FeedThread] = Vector(
    FeedThread("9240000001", "★ ３ちゃんねるからのお知らせ", 12, "3時間前", 0, post(1, "３ちゃんねるからのお知らせを掲載します。\nこのスレッドは★（キャップ）のみ書き込めます。"), post(12, "メンテナンス予定のお知らせです。")),
    FeedThread("9240000002", "アクセス規制情報", 30, "2日前", 0, post(1, "アクセス規制の実施・解除をお知らせします。"), post(30, "一部プロバイダからの書き込み規制を解除しました。")),
    FeedThread("9000000001", "３ちゃんねる 全板共通スレ", 523, "1時間前", 0, post(1, "どの板からでも書き込める共通スレッドです。\n雑談・質問などご自由にどうぞ。"), post(523, "新しい板topいい感じだな")),
    FeedThread("9000000002", "通報スレ", 88, "14時間前", 0, post(1, "荒らし・スパム・削除依頼の通報はこちらへ。"), post(88, "対応ありがとうございます。"))
  )

  private def post(number: Int, message: String) = ThreadPost(number, "名無しさん", "2026/10/30(金) 12:58:10", message, "(ｱｳｱｳ Sa01-AbCd)")
}

object ThreadFeedComponent {
  final val Component = ScalaFnComponent[ThreadFeedProps] { props =>
    val filtered = DummyThreadFeed.threads.filter(_.title.toLowerCase.contains(props.query.toLowerCase))
    val sorted = props.sort match {
      case "res" => filtered.sortBy(t => -t.count)
      case "speed" => filtered.sortBy(t => -t.speed)
      case _ => filtered
    }
    <.div(^.className := "thread-feed", VdomAttr("data-feed-source") := "dummy",
      <.p(^.className := "dummy-notice m-2 text-xs text-[#697785]", "スレッドフィードはダミーデータで表示しています。"),
      <.details(^.className :=
        """common-threads my-2 border-t border-[#dce1e6] [&>summary]:px-4 [&>summary]:py-[9px] [&>summary>span]:ml-2.5 
          | [&>summary>span]:text-xs [&>summary>span]:text-[#4d4d4d] max-[660px]:[&>summary]:px-[9px]""".stripMargin,
        <.summary(<.b("全板共通スレ"), <.span("最終更新 1時間前")),
        DummyThreadFeed.commonThreads.filter(_.title.toLowerCase.contains(props.query.toLowerCase)).toVdomArray(card(_, props))),
      if sorted.isEmpty then <.p(^.className := "empty-state m-2 text-xs text-[#697785]", "該当するスレッドはありません。") else sorted.toVdomArray(card(_, props))
    )
  }

  private def card(value: FeedThread, props: ThreadFeedProps): VdomElement = {
    val posts = if value.latest.number != value.first.number then Seq(value.first, value.latest) else Seq(value.first)
    <.div(^.key := value.key, ^.className := "feed-thread",
      ThreadComponent.Component(ThreadProps(props.bbs, props.time, value.key, value.title, value.count, posts, Option("↑最新10レスを閲覧"), dummy = true)))
  }
}
