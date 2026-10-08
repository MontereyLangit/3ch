本PJなら、管理画面を独立した React SPA としてビルドし、既存 Worker に静的ファイルと管理用 JSON API を配信させる構
  成が自然です。Worker・DO・R2・KV は共用できます。

  「管理画面の React は HonoX を使わない」「既存サーバーの入口には HonoX が残る」という分離になります。

  全体構成

  ブラウザ
    │
    ├─ 掲示板 ───────────── 現行の公開ルート・3chiko.html
    │
    └─ 管理画面 /admin/
         React SPA
         ・ブラウザで画面生成
         ・画面遷移、入力状態、保存結果を管理
         │
         └─ fetch /admin/api/*
                    │
  Cloudflare Worker │
    ├─ 既存の HonoX / Scala.js ルーティング
    ├─ 管理画面 HTML・JS・CSS の配信
    └─ 管理用 API（Scala.js）
         ・Basic 認証
         ・入力検証、Origin 検証
         ・データ取得、更新
                    │
              既存 DO / R2 / KV

  React は createRoot() で起動します。SSR・hydration・HonoX islands を管理画面に使う必要はありません。React 公式

  現行コードからの分割

  現在の bbs/src/main/scala/ch3s/bbs/handler/AdminHandler.scala は、認証・データ取得・更新・HTML 生成をまとめて扱っています。
  これを以下のように分けます。

   担当                                      CSR 化後
  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
   画面の HTML、フォーム、ナビゲーション     React に移す
  ────────────────────────────────────────  ──────────────────────────────────
   画面の入力状態、保存中表示、エラー表示    React で管理
  ────────────────────────────────────────  ──────────────────────────────────
   Basic 認証、サーバー側入力検証            Scala.js に残す
  ────────────────────────────────────────  ──────────────────────────────────
   R2 の設定編集、DO の管理操作              Scala.js に残す
  ────────────────────────────────────────  ──────────────────────────────────
   取得結果                                  HTML から JSON へ
  ────────────────────────────────────────  ──────────────────────────────────
   保存結果                                  303 リダイレクトから JSON 応答へ

  既存の管理処理を共通サービスとして切り出しておくと、移行中も従来画面と新 API で共有できます。

  ディレクトリ案は次のとおりです。ここでは管理画面を TypeScript + React で書く想定です。

  admin-ui/
    index.html
    src/
      main.tsx
      App.tsx
      api.ts
      types.ts
      pages/
        HomePage.tsx
        BoardPage.tsx
        ThreadsPage.tsx
        ThreadPage.tsx
        RulesPage.tsx
        MenuPage.tsx

  vite.admin.config.ts       管理画面専用。HonoX プラグインなし

  bbs/src/main/scala/ch3s/bbs/handler/
    AdminAuth.scala          共通認証・セキュリティ処理
    AdminApi.scala           JSON の入出力
    AdminService.scala       データ取得・更新
    AdminAssets.scala        認証後の SPA 配信

  app/                       既存のサーバー構成
  public/3chiko.html          既存フロント

  bbs-client/ の hydration 用ビルドとも切り離せます。管理画面を Scala.js + React で書くことも可能ですが、CSR 化そのものとは別
  の言語選択です。

  URL と API の例

  既存の画面 URL を維持するなら、以下の対応にできます。

   React の画面 URL                対応する API の例
  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
   /admin/                         必要に応じて初期情報取得
  ──────────────────────────────  ──────────────────────────────────────
   /admin/board?bbs=test           GET /admin/api/board?bbs=test
  ──────────────────────────────  ──────────────────────────────────────
   /admin/threads?bbs=test         GET /admin/api/threads?bbs=test
  ──────────────────────────────  ──────────────────────────────────────
   /admin/thread?bbs=test&key=…    GET /admin/api/thread?bbs=test&key=…
  ──────────────────────────────  ──────────────────────────────────────
   /admin/access                   GET /admin/api/access
  ──────────────────────────────  ──────────────────────────────────────
   /admin/ngwords                  GET /admin/api/ngwords
  ──────────────────────────────  ──────────────────────────────────────
   /admin/menu                     GET /admin/api/menu

  更新は対応する API への POST とし、スレ操作なら { "action": "stop" } などを送ります。現行の操作名を引き継げば、バックエンド
  の変更を抑えられます。

  画面 URL を直接開いた場合も、認証後に同じ SPA の HTML を返します。React 側は URL を見て表示ページを選びます。API の不存在や
  失敗は JSON で返し、SPA の HTML へフォールバックさせない設計にします。

  配信と認証で押さえる点

  同一オリジンの /admin/ 配下に置く構成なら、別ドメインや CORS 設定は不要です。初回アクセスで Basic 認証し、API 側でも毎回認
  証します。

  Cloudflare Static Assets は通常、該当する静的ファイルを Worker より先に返します。そのため管理画面を認証下に置くには、/
  admin・/admin/* を run_worker_first の対象にし、認証後に env.ASSETS.fetch() で配信する構成にします。Cloudflare 公式

  また、現在の管理画面の CSP は JavaScript を許可していないため、CSR 用に以下を明示的に許可します。

  - script-src 'self'：ビルド済み React の実行
  - connect-src 'self'：管理 API への通信
  - style-src 'self'：外部 CSS の読み込み

  frame-ancestors 'none'、base-uri 'none'、HTML・API の no-store、更新時の Origin 検証は維持します。

  ビルド・デプロイ

  管理画面専用の Vite ビルドを既存手順に追加し、最終的に次を同じデプロイへ含めます。

  dist/
    index.js                  既存 Worker
    3chiko.html
    static/                   既存クライアント資材
    admin/
      index.html              SPA の起動 HTML
      assets/
        index-<hash>.js
        index-<hash>.css

  ビルド間で dist/ を消し合わないよう、出力先とクリーン処理は調整が必要です。

  この案で主に必要になるのは、管理画面の React 実装と、現行管理処理の JSON API 化です。管理画面を CSR にするために、Worker を
  分離したり、ストレージスキーマを変更したりする必要はありません。


-----

管理画面専用の Scala.js サブプロジェクトを追加し、scalajs-react で React SPA を書く構成になります。前案の TypeScript + React 部分を Scala.js に置き換え、
管理 API と配信構成はそのまま使えます。

現行の build.sbt では、既にサーバー側の bbs に scalajs-react 4.0.0 が入っています。同ライブラリは React 19 に対応しているので、管理画面側にも採用するのが
自然です。scalajs-react 公式

プロジェクトの分け方

既存の bbsClient は islands 用の骨格なので、管理画面は独立した adminClient にすると役割が明確です。

common/ 純粋ロジック・ルート定義
.../admin/
AdminModels.scala API の要求・応答型
AdminValidation.scala 共有できる入力検証
AdminJson.scala JSON codec（追加する場合）

bbs/ Worker 側
.../handler/
AdminAuth.scala 認証
AdminApi.scala 管理用 JSON API
AdminService.scala DO / R2 等への管理操作
AdminAssets.scala SPA の配信

admin-client/ ブラウザ側 Scala.js
src/main/scala/ch3s/admin/
Main.scala React の起動
AdminApp.scala アプリ全体
AdminRouter.scala 画面 URL とページの対応
api/AdminApiClient.scala
pages/
BoardPage.scala
ThreadsPage.scala
ThreadPage.scala
RulesPage.scala
MenuPage.scala
components/
Layout.scala
SaveButton.scala
ErrorMessage.scala

admin-web/
index.html マウント先の HTML
main.js Scala.js と CSS の import
style.css

vite.admin.config.ts 管理画面専用ビルド

依存関係はこうなります。

flowchart BT
B["bbs：Worker"] --> C["commonJS：型・純粋ロジック"]
A["adminClient：React CSR"] --> C
A -. "HTTP / JSON" .-> B

adminClient から bbs へ sbt の依存を張らないのがポイントです。ブラウザ用と Worker 用を別々にリンクし、通信は HTTP API を通します。

画面も状態管理も Scala で書ける

scalajs-react では JSX の代わりに Scala の VDOM DSL を使います。画面定義のイメージは次のとおりです。

import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.html_<^.\_

object BoardPage {
final case class Props(board: String)

    val Component = ScalaFnComponent[Props] { props =>
      <.section(
        <.h1(s"板管理: ${props.board}"),
        <.p("SETTING.TXT と head.txt を編集します。")
      )
    }

}

フォーム値、読込中、保存中、エラーなども Scala の型で表せます。

enum LoadState[+A] {
case Loading
case Ready(value: A)
case Failed(message: String)
}

final case class BoardSettings(
bbs: String,
settings: String,
head: String
)

React の state / hooks でこれらを保持し、AdminApiClient がブラウザの fetch を呼びます。通常の React と同様に、非同期処理の完了に応じて状態を更新して再描
画します。

Scala 同士で共有できる範囲

この構成の利点は、API のデータ型や純粋な検証を共有できることです。

共有するもの 配置
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
板設定・スレ情報の DTO common
───────────────────────────── ────────────────────────────────
管理操作を表す enum common
───────────────────────────── ────────────────────────────────
API エラーの型 common
───────────────────────────── ────────────────────────────────
板名などの純粋な入力検証 common
───────────────────────────── ────────────────────────────────
JSON のエンコード・デコード 選んだライブラリに応じて共通化
───────────────────────────── ────────────────────────────────
認証、DO・R2 操作 bbs のみ
───────────────────────────── ────────────────────────────────
DOM、画面状態、ブラウザ通信 adminClient のみ

ただし、同じ Scala 型を使うだけで通信が自動的に型安全になるわけではありません。HTTP 境界では JSON codec と受信データの検証が必要です。クライアントで検証
していても、サーバー側の検証は必須です。

現行コードにある js.Dynamic のデータをそのまま画面へ渡すより、管理 API 用の DTO を定義する方が、この構成の利点を生かせます。

起動とビルド

adminClient は、概ね次の sbt 設定になります。

lazy val adminClient = project
.in(file("admin-client"))
.enablePlugins(ScalaJSPlugin)
.dependsOn(commonJS)
.settings(commonSettings)
.settings(
scalaJSUseMainModuleInitializer := true,
scalaJSLinkerConfig ~= {
\_.withModuleKind(ModuleKind.ESModule)
},
libraryDependencies +=
"com.github.japgolly.scalajs-react" %%% "core" % "4.0.0"
)

Scala 側の Main.main で React の root を作成し、AdminApp をマウントします。JS 側は起動用 import だけにできます。

// admin-web/main.js
import "./style.css";
import "scalajs:main.js";

Vite の Scala.js プラグインに projectID: "adminClient" を指定して、管理画面だけをビルドします。この起動方式は Scala.js 公式の Vite 構成に沿っています。
Scala.js 公式

管理画面の Scala ソース
→ Scala.js コンパイル・リンク
→ Vite が React / CSS とまとめる
→ dist/admin/ に出力
→ 既存 Worker と一緒にデプロイ

管理画面の Vite 設定には HonoX プラグインは不要です。ブラウザ側に渡すのは React SPA で、既存 HonoX は Worker の入口として残ります。

本PJでは adminClient を独立させ、common に API 型を置き、bbs に管理処理を残す構成を勧めます。Scala に実装言語を揃えられる一方、React 向けの外部 UI ライブ
ラリを使う場合は Scala.js facade の用意が必要になる点と、画面変更にも Scala.js のコンパイルが入る点は、TypeScript 案との実務上の違いです。


参考

- scalajs-react付属のRouter: https://github.com/japgolly/scalajs-react/blob/main/doc/ROUTER.md
- VDOM: https://github.com/japgolly/scalajs-react/blob/main/doc/VDOM.md
- その他: https://github.com/japgolly/scalajs-react/tree/main/doc
