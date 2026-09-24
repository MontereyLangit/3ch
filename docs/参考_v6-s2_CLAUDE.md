# 3ch v6-s2（TSベース + Scala は bg/common のみ）— CLAUDE.md

> **【2026-09-14 最重要】調査は「最終2案の二者択一」に収束した → `docs/tasks/TS-160.md` を必ず読むこと。**
> **案1 = Scalaフルスタック**（bbs: HonoX × Scala.js(scalajs-react) / bg: Scala JVM × GraalVM / common: Scala）
> **案2 = TS+Kotlin**（bbs: HonoX × TypeScript / bg: Kotlin Native or JVM×GraalVM / common: KMP →`.d.ts`）
> **両案共通: bbs も admin も HonoX（SSR + island）**。したがって**本ファイル中の「画面SSR = Next.js」
> 「Hono は API 専用」「front は Next.js(App Router)」「admin は SPA」という記述はすべて失効**している
> （下の 構成／最優先の調査項目／技術判断ログ の該当箇所。履歴として残すが、**現行の正は TS-160**）。
> 案2の実装調査は別リポジトリ **`v6-k`**（未作成）で行う。

**本プロジェクトの位置づけ**: 2026-09-11 のユーザー指示による**方針転換版**。
`C:\project\3ch\v6-s`（全部 Scala.js で載せる案）の調査は**一旦中断**し、
**v5のTS版 `C:\project\3ch\3ch_workers` をベースに改変し、bg と共有ロジック・型のみを Scala で書く**
構成で技術調査をやり直す。

- **v6-s は変更しない**（Scala.js全部載せ案の調査記録として凍結。実証済みの事実は参照する価値がある）
- **v6（Kotlin/KMP版）も従来どおり変更禁止・参照のみ**
- **v5 (`3ch_workers`) は実装が足りていないので、必要に応じて改変してよい**（2026-09-11 ユーザー指示）。
  ただし v5 リポジトリ自体は触らず、**本リポジトリへコピーした `ts/api/` を改変する**
- 最終的に **bg+common を Kotlin で書いた版（別リポジトリ `v6-k`）と比較する**（2026-09-11 ユーザー指示。
  Kotlin側も必要に応じて実装してよい）

## 構成（2026-09-11 確定 → **2026-09-14 に bbs/admin 部分は失効。TS-160 参照**）

```
ts/
  api/       TypeScript — Hono on Cloudflare Workers。**APIのみ**
             （subject.txt / dat 等の専ブラ向けエンドポイント、bbs.cgi、認証API）
             v5 `3ch_workers` のコピーが出発点
             ※ 2026-09-14: **bbs は HonoX（画面+API 一体）**になったため、この分離自体が再検討対象
  web/       TypeScript — ~~**Next.js**~~ **失効（2026-09-14）**。画面は **HonoX** が担当する
common/      Scala 3 cross project (JVM + Scala.js) — 共有ドメインロジック・共有型
bg/          Scala 3 JVM — 管理画面連動の重量処理。**APIのみになる可能性がある**（→ Next.jsと型共有）
batch/       Scala 3 JVM — Spring Batch
spikes/      調査用の最小プロジェクト（bg-mvc / bg-flux など）
docs/        設計資料・タスク管理
```

**役割分担（2026-09-11 ユーザー指示）**:
- **Hono(Workers) = API専用**。専ブラが叩く `subject.txt` / `dat` / `bbs.cgi` などは従来どおりここ
- **画面SSR = Next.js**。v6-s で決めた「Next.js は入れない」は**撤回**（前提が変わったため）
- **bg も API のみになる可能性がある** → その場合 **bg(JVM/Scala) と Next.js(TS) の型共有**が必要

## 最優先の調査項目（2026-09-11 ユーザー指示。他は全て後回し）

1. **v5_ts (`3ch_workers`) との相互運用**: Scala(common) のコンパイル成果物を
   TS側（Hono API / Next.js）から実際に呼べるか
2. **bg に最適なフレームワークの選定**: **Spring Boot の WebMVC / WebFlux を実測比較**する。
   Play Framework は机上調査のみ（2026-09-11 ユーザー選択）。
   **batch が Spring Batch である以上、Spring Boot が望ましい**という前提を置く
3. **common の `.d.ts` 生成**: 以下を**それぞれ**試して比較する
   - **scala-ts** を使う
   - Scala.js が生成した JS から **tsc の推論**（`--allowJs --declaration`）で `.d.ts` を作る
   - **dts-gen** を使う（2026-09-11 追加指示）
4. **bg(JVM) と Next.js の型共有**（2026-09-11 追加指示）: bgがAPI専用になった場合に
   リクエスト/レスポンスの型をどう共有するか（scala-ts / OpenAPI経由 / 手書き の比較）

5. **テスト関連と開発DX**（2026-09-11 ユーザー追加指示。上の1〜4すべてに横断する評価軸）:
   - **テスト**: Scala側(munit)とTS側(vitest等)でテストが二重管理にならないか。
     `@cloudflare/vitest-pool-workers` から Scala.js 成果物を読めるか。
     Spring のテスト（`@SpringBootTest` / `MockMvc` / `WebTestClient`）が Scala 3 で書けるか
   - **開発者の嗜好（2026-09-11 ユーザー備考）**: **関数型プログラミングを好み、型安全性を重視する**。
     したがって「`any` だらけの自動生成 `.d.ts`」「実行時まで壊れが分からない境界」「可変Bean前提の枠組み」は
     **低く評価する**。逆に、コンパイラが守ってくれる範囲が広い選択肢を高く評価する
   - **開発DX**: **wrangler 親和性**（`sbt ~commonJS/fastLinkJS` と `wrangler dev` のウォッチが
     噛み合うか、変更→反映までの秒数）、ビルド時間、起動時間、エラーメッセージの読みやすさ、
     `.d.ts` が無いときのTS側の型補完の効き方

調査が済んだら **bg+common を Kotlin で書いた場合と比較**する（`v6-k`。Kotlin/JS の `@JsExport` は
**`.d.ts` を公式に自動生成できる**ため、ここが最大の対照点になる見込み）。

## v6-s から引き継ぐ前提（再調査しない）

`C:\project\3ch\v6-s\CLAUDE.md` の技術判断ログに実測記録がある。本構成でも有効な主なもの:
- **Scala.js は Cloudflare Workers 上で動く**（v6-s TS-004）
- **Scala.js から Durable Object を直接 `extends` できる**（v6-s TS-005）。ただし本構成では
  DOはTS側に置くため出番は無い見込み
- **ScalablyTyped は hono を変換できるが型が劣化し、`@cloudflare/workers-types` は変換不能**（v6-s TS-002）
  → **本構成では ScalablyTyped 自体が不要**（npmライブラリを呼ぶのはTS側の責務になったため）。
  これは新構成の大きな利点
- **scalajs-react は重い**（1コンポーネントで +400KB。v6-s TS-006 / PN-10）
  → **本構成ではReactはTS側にあるので無関係**
- **Scala.js の `Long` は opaque。`js.JSON.stringify` に直接渡すと壊れる**（v6-s TS-012/TS-013c）
  → **境界設計で必ず踏む。`Double`/`String` へ落とす**
- **Kotlinとの言語差で挙動がズレる箇所**（`split` の末尾空要素、`trimEnd` 等）は v6-s CLAUDE.md
  「移植時の原則」を参照

## 外から見える挙動（厳守事項。v6-s から不変）

2chプロトコル互換・URL・dat/subject.txt の形式・Shift_JIS配信・Rangeリクエスト・
忍法帖認証/規制の機能要件は**クライアント（専ブラ含む）から観測できる挙動**なので一致させる。
内部設計は単純でよい（v5が単純さの基準）。

## 開発ポリシー（v6-s から継承）

- **実装はSonnetサブエージェントへ委譲**: メインループは調査・設計・タスク分割・統合確認・
  進捗文書の更新に徹する。数行程度の軽微な修正はメインループで直接行ってよい。
  指示書には「これを満たしたら終了」という終了条件を3つ以内で明記する
- **サブエージェントに `CLAUDE.md` / `docs/tasks.md` / `docs/tasks/**` を編集させない**。
  文書更新はメインループに一本化する（並行実行時の競合防止）
- **セルフレビューは Gemini CLI**（`gemini -m gemini-3.1-pro-preview -p ...`）。
  大きな差分はリポジトリ内の一時ファイルへ書き出して相対パスで渡す（Windowsのコマンドライン長制限）
- **単体テストを必ず書く**。テストメソッド名は日本語でふるまいを書く（munit の `test("...")` 形式）
- **FQCN直書き禁止**。`import` で解決し単純名で参照する
- **Optional Braces（インデント構文）は原則使わない**。基本は波括弧 `{}`
- **同一ビルドで sbt を同時に走らせない**（sbtサーバの排他。並行委譲する場合は
  「sbt実行が衝突したら待って再試行」を指示書に明記する）
- **wrangler は 4.129.1 以降を使う**（4.28.1 は DO内の `ctx.id.name` がローカルで `undefined`）
- **進捗管理は `docs/tasks.md`（索引）＋ `docs/tasks/TS-1xx.md`（詳細）**。
  タスクIDは **TS-100番台から**（v6-s の TS-0xx と混ざらないようにするため）

## 環境

JDK 25（`java -version` で確認済み）、Node.js 22.18.0、npm 10.9.3、sbt 1.13.0
（`C:\Users\kouki\tools\sbt\bin\sbt`）。Spring Boot は **4.0.8**（v6 の 4.0.6 と同系列の最新パッチ）。

## 技術判断ログ

- **[判断済] 画面SSRは Next.js、Hono は API 専用**（2026-09-11 ユーザー指示）:
  v6-s で「Next.js は入れない」としたのは**フロントもScala.jsで書く前提だったため**であり、
  TSベースへ方針転換した本構成ではその前提が消えた
- **[判断済] Spring Boot 前提で WebMVC / WebFlux のみ実測、Play は机上**（2026-09-11 ユーザー選択）:
  batch が Spring Batch であるため、bg も Spring Boot だと DI・設定・ビルドを共有できる
- **[実証済] TS製Workersアプリから Scala.js(common) を呼べる**（2026-09-11 TS-101。**調査項目①に回答**）:
  `wrangler dev` 上で TS のハンドラが Scala 由来の関数を呼び、`@cloudflare/vitest-pool-workers`
  （＝実 workerd）上のテストまで通った。**バンドル増分は本番相当(minify)で +217.53 KiB / gzip +57.84 KiB**
  （`docs/performance-notes.md` PS-01）。増分の大半は Scala.js ランタイム自体なので、共有関数を
  増やしても増え方は緩やか。**サイズは採否の決定打にならない**
- **[判断済] 境界に出す型は JS ネイティブに限る**（TS-101 実測。詳細は `docs/design-notes.md` DS-01）:
  `String`/`Double`/`Boolean`/`js.Array`/`js.Object` のみ。**`Long` は10進文字列**（`JSON.stringify` で壊れる）、
  `Option`/`List`/case class はマングルされた内部表現が露出、**Scala 3 `enum` は `getClass.getSimpleName` が
  JVM では "All"、JS では "anon$1" になる**ためパターンマッチで判別する。変換は
  `ch3s.common.interop.Interop` の1箇所に集約する
- **[判断済] ビルド連携は `scalaJSLinkerOutputDirectory` で TS 側へ直接出力**（TS-101）:
  `ts/api/src/scala-out/{fast,full}` へ出す（コピー手順なし）。**wrangler dev の esbuild ウォッチャが
  sbt の出力更新を自動検知してホットリロードする**（実測）。開発中は `sbt "~commonJS/fastLinkJS"` の
  継続ビルドを回すこと（`-batch` 単発は毎回JVM起動で5〜10秒、継続ビルドなら内部1秒。PS-02）
- **[未解決・重要] 手書き型注釈では Scala 側の変更を検知できない**（TS-101。`docs/design-notes.md` DS-03）:
  `.d.ts` が無いと `tsc` は `TS7016` で落ち、`allowJs` を足すと実質 `any` になる。採用した
  「import 行だけ `@ts-ignore` + 手書き型注釈」は `as` キャストなので、**Scala 側の戻り値の実形と
  食い違っても tsc は検出しない**（実験で確認）。**型安全性を重視する方針上ここが最大の弱点**であり、
  TS-103/104/105（`.d.ts` 生成）の結果が構成の採否を左右する
- **[実証済・意外な良い知らせ] Scala 3 は `MethodParameters` を出力する**（2026-09-11 メインループが検証）:
  そのため **Jackson は引数名ベースで Scala クラスをデシリアライズできる**（`javap -v` で属性の存在を確認し、
  WebClient → JSON → Scala クラスの往復が実動作することを `curl` で実測）。
  **`@BeanProperty` が必要なのはシリアライズ（getter）側**であって、デシリアライズには不要だった
- **[実証済] MockMvc は仮想スレッドを経由しない**（TS-110 検証時に発見）: `spring.threads.virtual.enabled=true`
  でも MockMvc 経由の応答は `virtualThread: false`（サーブレットコンテナを通さないため）。
  実サーバ + curl では `true`。**「テストが緑＝設定が効いている」とは限らない**という DX 上の注意点
- **[暫定] bg は WebMVC(+仮想スレッド) が優勢**（TS-110/111。確定は TS-115 と比較後の TS-150）:
  cats-effect との統合が境界の `unsafeRunSync()` 1行で済む一方、WebFlux は **cats-effect ↔ Reactor の
  公式 interop が無く `IO ↔ Mono` 変換を自作**する必要がある。v6 が WebFlux を選んだ理由（Kotlin coroutine
  との親和性）は Scala には当てはまらない。詳細と摩擦一覧は `docs/design-notes.md` DS-04/DS-05
- **[運用注意・新規] `sbt <proj>/run` は実行中ずっとビルド全体の sbt ロックを保持する**（2026-09-11 TS-101/110 実測）:
  Spring Boot をフォアグラウンド起動している間、**他の sbt 起動は `compile` すら一切できない**
  （`-Dsbt.server.autostart=false` や `--no-server` でも回避できない別機構のロック）。
  → **サーバを `run` で常駐させるタスクは単独で走らせる**。並行委譲する場合は「起動→確認→即停止」を指示する
- **[運用注意・新規] `wrangler dev` は放置すると積み上がってメモリを食い潰す**（2026-09-14 実測。TS-187 の計測中に発生）:
  サブエージェントが検証のたびに `wrangler dev` を起動し、**終了時に止めていなかった**ため、
  **node(wrangler) 24プロセス + workerd 16プロセス（約1.2GB）が残留**し、
  バックグラウンドコマンドが「システムのメモリ不足」で強制終了された。
  - **`workerd` だけ `Stop-Process` しても親の wrangler が即座に再起動するので消えない**。
    **必ず親（`node.exe` でコマンドラインに `wrangler` を含むもの）から止める**:
    `Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'node.exe' -and $_.CommandLine -match 'wrangler' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }`
  - **サブエージェントへの指示書に「検証が終わったら `wrangler dev` を必ず停止する」を入れること**

- **[運用注意] 継続ビルド中に `build.sbt` を他プロセスが編集すると監視が壊れる**（TS-101 実測）:
  `build source files have changed` の警告後、以降の変更を検知しなくなる。`~` を回す作業と
  ビルド定義を編集する作業を同時に走らせないこと

- **[判断済・重要] front は Next.js(App Router) で hydration する**（2026-09-12 ユーザー確定）:
  ユーザー提示の**構成案B**（front=Scala.js on Next.js / bbs-backend=Scala.js vanilla /
  bg・batch=Scala(Spring) / common=Scala.js。詳細は `docs/tasks/TS-130.md`）における
  フロントの方針が確定した。**hydration する**ため scalajs-react を使う意味があり、
  **SSRとCSRで同一コンポーネントを共有する v6 と同じ設計**が取れる。
  - **これにより v6-s の「Next.js は入れない」も、構成案Aの「画面SSRはTSのNext.js」も上書きされる**
    （構成案Aでは `ts/web` を TS で書く想定だった。案Bでは front が Scala.js になる）
  - **App Router では SSR を Next 側が行うため、v6-s で必要だった
    `react-dom/server.edge` の自前facadeは不要になる見込み**（TS-132で確認）
  - **代わりに RSC の制約が入る**: hydration するコンポーネントは Client Component なので
    `"use client"` を書いた TS の薄いラッパから re-export する形になる見込み。
    また **props は RSC のシリアライズを通る**ため `docs/design-notes.md` DS-01 の境界規約
    （`Long`/`Option`/case class/`enum` は渡せない）がそのまま効く
  - **未確定（TS-132 が最優先）**: Turbopack が Scala.js の ESM 出力を通すか／
    React インスタンスが Next と1つに解決されるか（2コピーだと hydration が必ず壊れる）／
    クライアントバンドルの実サイズ（v6-s の実測ではクライアント側 fullLink が 469,953B）／
    `@opennextjs/cloudflare` で Cloudflare に寄せられるか
  - **参考テンプレート（gbogard/scala3-nextjs-template）はコードを転用できない**:
    2021年最終更新・Next.js 11・React 17・`pages/` ルータで、App Router/RSC/Turbopack/React 19 を含まない。
    「Scala.js を ESM で出して Next から import/re-export する」というパターンのみ有効
- **[未承認・提案] bbs-backend を Scala.js で書くなら vanilla + 手書きfacade + Scala側ADTルータ**
  （2026-09-12 メインループの推奨。`docs/tasks/TS-130.md` 論点2。ユーザー判断待ち）:
  Hono を ScalablyTyped で facade 化すると型が劣化する（v6-s TS-002 実測）ため、
  **ScalablyTyped を再導入せず**、`Request`/`Response`/`env` を100行程度の `@js.native` facade で
  型付けし、ルートは `sealed trait` のADT＋パターンマッチで書く方が**型安全側に倒れる**。
  参考記事の `js.Dynamic` 多用方式は型が付かないので採らない
- **[判断済・上書き] bg の候補に http4s + Tapir を加える**（2026-09-11 ユーザー承認。上の「Spring Boot前提」を一部上書き）:
  「FPを好む・型安全性を重視する」という前提が後から示されたため、**純粋FPかつエンドポイントが型で守られる**
  選択肢を同じ土俵で測る（TS-115）。**Tapir は OpenAPI を生成できるので TS-113（bg↔Next.jsの型共有）も同時に解ける**
  可能性がある。Spring版（TS-110/111）の実測は予定どおり続け、**3方式を同じ題材で比較**して TS-150 で決める
- **[判断済] Scala版・Kotlin版は別リポジトリ**（2026-09-11 ユーザー選択）:
  `v6-s2`（Scala arm）と `v6-k`（Kotlin arm）を並べ、比較の公平性を優先する
- **[判断済] ScalablyTyped は使わない**: npmライブラリを呼ぶのはTS側の責務になったため不要。
  Scala側は「TSから呼ばれる純粋なロジック」に閉じる
- **[判断済] Scala.js は最新の 1.22.0 を使う**（2026-09-11）: v6-s では ScalablyTyped(`sbt-converter`)が
  Scala.js 1.18 以降で壊れる報告があり 1.17.0 に固定していたが、**本構成では ScalablyTyped 自体が不要**に
  なったため制約が外れた。`sbt commonJVM/test` `commonJS/test` は 1.22.0 + Scala 3.3.8 で緑（230件）
- **[情報] TS側は fp-ts を導入予定**（2026-09-11 ユーザー情報。今回の調査の直接対象ではない）:
  境界設計（TS-106）に効く。Scala の `Either`/`Option` を **fp-ts の `Either`/`Option` へ写す**のか、
  境界はプレーンな値に保ってTS側で包むのかを、`.d.ts` 生成方式の評価と合わせて考えること
- **[要調査] 共有テンプレート（Scalatags）の扱い**: 当初「ロジックとテンプレートをScalaで共有」の
  想定だったが、画面SSRが Next.js になったため **テンプレートはTSX側に寄る可能性が高い**。
  専ブラ向けHTML（板TOP等のSJIS配信ページ）だけ Scalatags で共有する案は残る。TS-101で判断する

- **[判断済・最重要] 最終2案を確定し、以後の調査は二者択一の決着だけを行う**（2026-09-14 ユーザー指示。
  **詳細と原文は `docs/tasks/TS-160.md`**）:
  - **案1 Scalaフルスタック** — bbs: **HonoX × Scala.js（scalajs-react）** / bg: **Scala JVM × GraalVM native**
    （FW未定） / common: **Scala 3**。ユーザー評「尖っている方。**HonoX を Scala で書く例は前例が無く
    宣伝効果が大きい**」。ユーザーの懸念は**ただ1点「言語を統一できるか（Wrapperで TS/JS を沢山書くなら意味がない）」**
  - **案2 TS+Kotlin** — bbs: **HonoX × TypeScript** / bg: **Kotlin Native または Kotlin/JVM × GraalVM**
    （Ktor未定） / common: **KMP（TS向けに `.d.ts` 出力）**。ユーザー評「技術的に現実的な方。
    ただし TS版 HonoX の掲示板は既にあるので公開時の受けは微妙」
  - **両案共通で確定したこと**: **bbs も admin も HonoX（SSR + island）**。
    **admin はこれまでの SPA 方針から変更**（★2026-09-14）
  - **この確定により撤回されるもの**: 「画面SSR = Next.js / Hono は API 専用」（2026-09-11）、
    「front は Next.js(App Router) で hydration する」（TS-133）、「admin は scalajs-react の SPA」（v6-s）、
    **構成案A・構成案B**。TS-102/TS-130 は破棄、TS-141/142 → TS-162、TS-120 → TS-164/165 へ吸収
  - **2案の本質的な違いは「TS と JVM系言語の境界をどこに引くか」が正反対であること**:
    **案1は境界を消す**（bbs も common も Scala なので `.d.ts` が要らない。
    ただし HonoX のファイル規約により **TS は route / island / renderer / vite.config へ残る**）。
    **案2は境界を正面から受ける**（TS から KMP を呼ぶので **`.d.ts` の品質が生命線**。
    Kotlin/JS は `@JsExport` で公式に生成できるとされ、**Scala.js が4方式すべて失敗した DS-06 と正反対**）
  - **決定基準（7項目）**: 言語統一度 / 型安全性 / 開発DX / 性能 / bgの現実性 /
    **話題性・宣伝効果（ユーザーが明示した非技術基準）** / 既存資産の流用
  - **次にやるのは TS-161**（案1の「1画面あたりに書く TS の量」を削れるかの実測）。
    **現状スパイクは画面1つにつき TS 55行・2ファイル**（route 30行 + island ラッパ 25行）で、
    このままでは案1の採用動機が崩れる
