Cloudflare Workers上の2ch型スレッドフロート式掲示板「３ちゃんねる」（3ch.io）。

## 開発環境

### IntelliJ IDEA（Scala プラグイン）の注意点

Scala 3 の `object` は最初から final なので、`object` の中の `case object` に `final` は付けないのが正しい。
IntelliJ はこの書き方に「内側のケースオブジェクトを final にする」という警告を出す。
付けると今度は「オブジェクトの 'final' 修飾子は冗長です」が出て、どちらの書き方でも警告が残る。
前者は Scala 2 向けの検査なので、無効にしてください。

- 警告の箇所で Alt+Enter →「内側のケースオブジェクトを final にする」の右の ▸ →「検査の編集」/「無効にする」
- または Settings → Editor → Inspections → Scala で、該当の検査のチェックを外す
