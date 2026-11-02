# island props の型付け計画（Codex 指摘 #4）

2026-11-02 作成。対象は板top の islands（`bbs-client/src/main/scala/ch3s/bbsclient/islands/`）と、それを呼ぶサーバー側ラッパー
（`bbs/src/main/scala/ch3s/bbs/components/`）。指摘の記録は `docs/board-top-client-review.md` の「Codex CLI レビュー（islands 版）」。

## 1. 現状と問題

サーバーとクライアントが、同じ props の形をそれぞれ文字列キーで書いている。

| island | サーバー（組み立て） | クライアント（読み戻し） |
| --- | --- | --- |
| `SiteHeaderJs` | `HeaderComponent`: `js.Dynamic.literal()` | props なし（`_ => ()`） |
| `PostingFormJs` | `PostingFormComponent.islandProps`: `js.Dynamic.literal(bbs, time, imgurClientId, commands, threadKey)` | `PostingFormIsland.Props.fromJs`: `selectDynamic("bbs")` … |
| `BoardDetailsJs` | `BoardIndexComponent`: posting form の props に `updateDynamic("headText")` / `("settingsText")` を足す | `BoardDetailsIsland.Props.fromJs`: 同じ生オブジェクトを `PostingFormIsland.Props.fromJs(raw)` にも渡す |
| `BoardListJs` | `BoardIndexComponent`: `js.Dynamic.literal(bbs, type, subjectText, query, sort, now)` | `BoardListIsland.Props.fromJs` |

問題点:

- **キー名の食い違いがコンパイルで検出されない。** 片側だけ `threadKey` を改名しても通り、返信フォームが新規スレッド用として描画される。
- **必須値の欠落が見えない。** `fromJs` は欠けた値を `""` / `0.0` に置き換えるので、配線ミスが「空の画面」として現れる。
- **生成 API が型を持たない。** `IslandComponents.XxxJs(props: js.Object)` なので、どの island にどの形を渡すべきか呼び出し側から分からない。
- `BoardDetailsJs` は posting form の props と自分の props を1つの平らなオブジェクトに混ぜており、境界が暗黙的。
- `js.Dynamic` の多用は AGENTS.md 3.5（未型付け構造型を避ける）にも反する。

## 2. 方針

**props の形を、サーバーとクライアントの両方が参照する Scala.js の非ネイティブ JS trait として1か所に定義し、codegen が生成する SSR API の引数型にもそれを使う。**

### 2.1 置き場所: `common` の JS 専用ソース

- `common` は `CrossType.Pure`（JVM / JS 共有）で、`js.Object` を継承する型は JVM 側でコンパイルできない。
  JS 専用の `Compile` ソースディレクトリ `common/src/main/scala-js` を `jsSettings` に追加する（テスト用 `scala-js` の前例と同じやり方）。
- `bbs` と `bbsClient` はどちらも `commonJS` に依存しているので、両方から参照できる。
- `bbs` → `bbsClient` の依存は作らない（クライアントのコードがサーバーのバンドルに入る。KB-27 の分離方針にも反する）。

### 2.2 props の定義（例）

```scala
package ch3s.common.islands

import scala.scalajs.js

/** PostingFormJs の props。HonoX が JSON にシリアライズするので JSON 互換の型だけを使う。 */
trait PostingFormProps extends js.Object {
  val bbs: String
  val time: String
  val threadKey: js.UndefOr[String]
  val imgurClientId: String
  val commands: js.Array[ThreadCommandProps]
}

trait ThreadCommandProps extends js.Object {
  val text: String
  val label: String
  val field: String
  val description: String
}

trait BoardDetailsProps extends js.Object {
  val bbs: String
  val headText: String
  val settingsText: String
  val postingForm: PostingFormProps   // 平らに混ぜず、入れ子のフィールドにする
}

trait BoardListProps extends js.Object {
  val bbs: String
  val `type`: String
  val subjectText: String
  val query: String
  val sort: String
  val now: Double
}
```

- サーバー: `new PostingFormProps { val bbs = props.bbs; ... }` で組み立てる。フィールドの過不足・型違いはコンパイルエラーになる。
- クライアント: `raw.asInstanceOf[PostingFormProps]` のあと、型付きのフィールドで読む。`Props.fromJs` の `selectDynamic` は無くす。
- 使ってよい型: `String`、`Double`、`Int`、`Boolean`、`js.UndefOr[...]`、`js.Array[...]`、他の props trait。
  `Option`、Scala コレクション、関数は使わない（HonoX のシリアライズで失われる。`docs/islands-ssr.md`「普通のデータ props は JSON で受け渡せる値にする」）。
- `SiteHeaderJs` のように props が無い island は `js.Object` のままでよい。

### 2.3 実行時の検証（必須値の欠落を見えるようにする）

trait では、シリアライズ後に値が欠けていても検出できない。クライアントの入口で必須フィールドを確かめる。

- 各 props に `validate(raw: js.Object): Either[String, XxxProps]` を `common/src/main/scala-js` に置く（必須キーの存在と `js.typeOf` を確認）。
- 失敗したら `console.error` に island 名と欠けたキーを出す。SSR の markup をそのまま残すため、例外にはしない。
  描画は「JS 無し相当」とする（例: 送信抑止や下書きなどの拡張機能を付けない）。
- サーバー側は型で保証されるので検証しない。

### 2.4 codegen: 生成 API に型を付ける

- `@islands` に props 型を指定できるようにする。

  ```scala
  final class islands[P](val filePath: String) extends StaticAnnotation   // common/.../Annotations.scala
  ```

  `common` は JVM でもコンパイルされ、そこには `js.Object` が無い。そのため境界は付けず `islands[P](filePath)` にし、
  型引数の構文だけを codegen が読む。`@islands("x.tsx")` のように型引数を省いた場合は従来どおり `js.Object` とする。
- `AnnotatedAppEntries` は scalameta の `Mod.Annot` から型引数の構文（例: `PostingFormProps`）を取り出し、`IslandEntry.propsType: Option[String]` に入れる。
  型名は完全修飾名で書かせる（例: `@islands[ch3s.common.islands.PostingFormProps]("posting-form.tsx")`）か、
  `ch3s.common.islands` 配下に限定して単純名を許す。前者の方が codegen が単純になるので推奨。
- `GenerateIslandComponents` は `def PostingFormJs(props: ch3s.common.islands.PostingFormProps): VdomElement` を出力する。
- クライアントの入口も同じ型を使う: `Component.cmapCtorProps[PostingFormProps](...)`。
  これで「生成 API の引数型 = クライアントが受け取る型」が1つの型名でつながる。
- `AnnotatedAppEntriesSuite` / `GenerateIslandComponents` のテストに、型引数あり・なしの両ケースを追加する。

## 3. 作業手順

1 件ずつ独立したコミットにする。

1. **基盤**: `build.sbt` の `common.jsSettings` に `Compile / unmanagedSourceDirectories += .../src/main/scala-js` を追加し、空のパッケージ `ch3s.common.islands` を作る。
2. **codegen**: `islands[P]` の型引数を読み、型付きの生成 API を出す。型引数が無ければ現状と同じ出力（既存テストが通ること）。
3. **`PostingFormProps`**: trait と `validate` を定義し、`PostingFormComponent.islandProps` と `PostingFormIsland` を置き換える。`ThreadCommand`（common の case class）と `ThreadCommandProps` の変換はサーバー側に置く。
4. **`BoardDetailsProps`**: posting form の props を `postingForm` フィールドに入れ子にし、`updateDynamic` を廃止する。
5. **`BoardListProps`**: 置き換える。
6. **掃除**: `Props.fromJs` と `selectDynamic` が bbs-client と board top のラッパーから無くなったことを確認する（`grep -n "selectDynamic\|js.Dynamic.literal"`）。`docs/islands-ssr.md` の宣言例を型付き版に更新する。

## 4. テスト

- `common` JS: `validate` の単体テスト（全キーあり / 必須キー欠落 / 型違い / `js.UndefOr` の省略）。
  `JSON.parse(JSON.stringify(props))` の往復後も `validate` が通ることも確かめる（HonoX のシリアライズを模す）。
- `bbs`: `BoardIndexSuite` は markup の期待値を変えずに通ること。`data-serialized-props` の中身が、入れ子化した `BoardDetailsProps` の形になっていることを1件確認する。
- `tests/board-islands.test.js` / 板top の hydration テスト（#5 で追加）: 期待値を変えずに通ること。
  加えて、必須キーを欠いた props で island を描画したときに `console.error` が出て例外にならないことを1件確認する。
- 型の食い違いがコンパイルエラーになること自体は自動テストにしない（手で確認し、レビュー記録に残す）。

## 5. 影響範囲とリスク

- 変更は板top の island と、そのサーバー側ラッパー、codegen、`common` のビルド設定に限られる。公開エンドポイント・dat・Shift_JIS 出力・ストレージ（AGENTS.md 3.4）には触れない。
- `data-serialized-props` の形が変わる（`BoardDetailsJs` の入れ子化）。ページの SSR と hydration は同じデプロイで更新されるので、互換性の問題はない。
- `islands[P]` に型引数を足すと、`common` の JVM 側でも型パラメータ付きアノテーションとしてコンパイルされる。`js.Object` を参照しないので問題ない想定だが、手順 2 で JVM ビルドを確認する。
- scalameta での型引数の取り出しは構文ベース。型エイリアスや import した別名は解決しないため、完全修飾名に限定するのが安全。

## 6. 未決事項

- 型名の書き方: 完全修飾名に限定するか、`ch3s.common.islands` の単純名を許すか（推奨は完全修飾名）。
- 検証に失敗したときの描画: 「JS 無し相当」で十分か、画面上にエラーを出すか。
- 今後 read.cgi など他画面の island を足すときも、同じく `ch3s.common.islands` に props を置くか（画面ごとにサブパッケージを切るか）。
