# 管理画面 改修計画（アカウント認証・新 UI・scalajs-react SPA）

2026-11-02。認証・登録機能（[認証・PHOEBE・連投規制 設計](auth-phoebe-rate-limit-design.md)、以下「設計書」）の実装に合わせて、
`/admin/*` を次の 3 点で作り直す計画。**状態: 計画（未着手）。**

1. **認証**: HTTP Basic 認証をやめ、原案の管理者アカウント（ID/Password 方式または Google 連携）によるログインにする。
2. **UI**: 利用者提示のモック（旧 delightly の板設定画面 `kanri.html` と `a.css`）の見た目に合わせる。
3. **実装**: scalajs-react の SPA（クライアント描画）で作り直し、サーバは JSON API にする。

構成の下敷きは `feature/spa_admin_page` の `docs/管理画面SPAメモ.md`（2026-10-09。scalajs-react 版の `adminClient` 案）。

## 0. 前提・不変条件

| 項目 | 扱い |
| --- | --- |
| 公開側 | dat・subject.txt・bbs.cgi・`3chiko.html`・Shift_JIS 応答は変えない |
| 保存先 | R2 の `{bbs}/SETTING.TXT`・`{bbs}/head.txt`・`deny.cgi`・`ngwords.cgi`・`bbsmenu.html`、Thread / Board DO の既存 RPC をそのまま使う。形式は変えない。新規は設計書 §9 の DO と本書の監査ログだけ |
| 管理操作の意味 | 現行のスレ停止・再開・削除・移動・レス編集・レス削除・規制ルール検証・スレ立て規制フォームの ETag 条件付き保存は、挙動を変えずに API 化する（`docs/admin.md` の記述が引き続き正） |
| 防御 | AGENTS.md 3.6（Basic 認証＋Origin 検証＋no-store＋CSP）は、Basic 認証を「再認証付きの管理者セッション」に置き換える。**Origin 検証は復活させる**（Cookie 認証では必須。現行は 2026-10-05 の利用者確認で意図的に外している）。no-store と CSP は維持し、CSP は SPA 用に改める（§5.4）。3.6 の文言更新は段 A2 で利用者の承認を得て行う |
| 起点 | DDD 化計画（`docs/bbs-cgi-ddd-refactoring-plan.md`）の段 0b で `develop_v6s` と `feature/board_top` を統合した後のブランチ。`board_top` 側の `AdminHandler` にはスレ立て規制フォームが無いので、統合で `develop_v6s` 側を採る |

## 1. 現状

- `bbs/.../handler/AdminHandler.scala`（約 200 行）が Basic 認証・入力検証・R2 / DO 操作・HTML 文字列の組み立てを 1 つの関数で行う。`AdminText` が共通レイアウトと文言を持つ。
- 画面: `/admin/`（板名入力）、`/admin/board`（`head.txt`・`SETTING.TXT` の直接編集とスレ立て規制フォーム）、`/admin/threads`、`/admin/thread`（停止・再開・削除・移動・レス編集・レス削除・ログ閲覧）、`/admin/access`・`/admin/ngwords`（`deny.cgi`・`ngwords.cgi`）、`/admin/menu`（`bbsmenu.html`）。
- 更新は `application/x-www-form-urlencoded` の POST → 303。応答は `no-store`・`nosniff`・`no-referrer`・`script-src` なしの CSP。入力 256KB 上限。
- テスト: `AdminHandlerSuite`（6 件。認証、スレ立て規制フォーム、ETag 競合、CR/LF 拒否）、`tests/admin.integration.test.js`（実 workerd）。
- 板の一覧を持つ仕組みは無い（板名を手で入力する）。

## 2. 方針の要点

- **管理画面は 1 つ**: 設計書 §6.1 の権限者向け `/mod/*` は作らず、`/admin/*` に統合して役割（`admin` / `delete` / `regulate`）で画面と操作を出し分ける（設計書を本書に合わせて訂正）。
- **ログインは公開側と同じアカウント**: `/auth/` でログインし、管理画面に入るときにもう一度パスワード（または Google）で確認して、短命の管理者セッションを別 Cookie で発行する（sudo 方式）。
- **SPA はサーバ描画しない**: 管理画面に SSR・hydration・HonoX islands は使わない。HTML は空の起動ページ 1 枚で、React は `createRoot` で起動する。
- **見た目はモックに合わせ、項目は v6 が解釈するキーに絞る**: モックは旧 v4 の画面で、v6 に無い機能の項目が多い。レイアウト・配色・部品・文言の体裁はモックに合わせ、入力項目は v6 が実際に読む設定キーと設計書の新キーにする（§4.3）。書かれていないキーは保持する。

## 3. 認証

### 3.1 流れ

```
/admin/ へアクセス
  ├ 通常セッション（__Host-ch3s_sid）が無い → /auth/?mode=login&return=/admin/ へ 302
  ├ 通常セッションはあるが役割なし → 403 画面（「管理権限がありません」）
  └ 役割あり → SPA の起動ページを返す
SPA が GET /admin/api/me
  ├ 管理者セッション（__Host-ch3s_admin）が有効 → 画面を表示
  └ 無効・期限切れ → 再認証画面
      ├ ID/Password: パスワード入力 → POST /admin/api/elevate
      └ Google: POST /auth/google/start（purpose=admin, prompt=login, max_age=0）→ callback で auth_time を確認して管理者セッションを発行 → /admin/ へ
```

### 3.2 管理者セッション

| 項目 | 値 |
| --- | --- |
| Cookie | `__Host-ch3s_admin`。`Path=/; Secure; HttpOnly; SameSite=Strict`。`Max-Age` を付けない（ブラウザ終了で消える） |
| 値 | `1.{aid}.{gen}.{iat}.{last}.{sig}`。`iat` は発行時刻、`last` は最終操作時刻、`sig` は secret `ADMIN_SESSION_SECRET`（`AUTH_SECRET` とは別鍵）の HMAC-SHA256 |
| 有効期限 | 無操作 30 分、発行から最長 8 時間。API 応答のたびに `last` を更新して発行し直す |
| 検証 | 毎リクエスト、署名・期限を見たうえで `Account.describeForAdmin(aid, gen)` を 1 回呼び、状態（`active` 以外は拒否）と現在の役割を得る。役割の剥奪・凍結・ログアウト（`gen` 進行）は次のリクエストから効く |
| 発行条件 | 通常セッションの `aid` と一致し、役割を 1 つ以上持ち、再認証が 10 分以内 |
| 終了 | `POST /admin/api/logout` で管理者 Cookie を消す。通常セッションは残す |

再認証の試行は設計書 §3.9 のロック（（アカウント, keyIp）単位）と `AuthRate` DO の計数に含める。

### 3.3 管理者アカウントの要件

- 種別は ID/Password または Google（設計書 §6.1。クライアント ID には役割を付けられない）。
- ID/Password で `admin` 役割を持つアカウントは、パスワード 16 文字以上を必須にする（役割付与時と変更時に検査）。Basic 認証の長い共有パスワードより弱くしないため。
- 二要素認証（TOTP）は段 A4 で追加を検討する（§10-3）。Google 連携は Google 側の 2 段階認証に委ねる。

### 3.4 初回の管理者と非常用経路

アカウント方式には「最初の `admin` をどう作るか」と「全管理者がログインできなくなったとき」の経路が要る。

- secret `ADMIN_BOOTSTRAP_TOKEN`（`wrangler secret put`。現行の `ADMIN_PASSWORD` はこれに置き換えて廃止）を使う。
- `/admin/bootstrap`: 通常セッションでログインしたアカウント（ID/Password または Google）が、トークンを入力すると `admin` 役割を得る。
- 使えるのは、**`admin` を持つアカウントが 0 件のとき**、または vars `ADMIN_BREAK_GLASS="1"` を付けてデプロイしたときだけ。後者は使い終わったら外して再デプロイする運用にする。
- 試行は `keyIp` ごとに 5 回/時（`AuthRate`）。成功・失敗とも監査ログに残す。
- `admin` 役割の件数は `AccountIndex("role:admin")`（設計書 §9.1 の索引に種別を追加）で数える。

### 3.5 CSRF・オリジン

- 変更系（`GET` 以外）の API は、`Origin` が自オリジンと一致し、`Content-Type: application/json` で、ヘッダ `X-Ch3s-Admin: 1` を持つ場合だけ受け付ける。フォーム送信や単純リクエストでは満たせず、他オリジンからはプリフライトで止まる。
- 管理者 Cookie は `SameSite=Strict`。
- 入力上限 256KB は維持する。

### 3.6 監査ログ

- 新 DO `AdminAudit`（`idFromName("global")`）に、操作者の `aid` と `uid`、役割、操作名、対象（板・key・ファイル名・アカウント）、結果、時刻、`keyIp` のハッシュを追記する。64 件ごとのページで、10,000 件を超えたら最古のページを消す。
- 記録するのは変更系の全操作、ログイン（再認証）の成否、bootstrap。閲覧は記録しない。
- 画面 `/admin/audit`（`admin` のみ）で新しい順に見る。

## 4. 画面

### 4.1 画面と役割

| 画面 | URL | 必要な役割 | 内容 |
| --- | --- | --- | --- |
| トップ | `/admin/` | いずれか | 板の選択（R2 の `*/SETTING.TXT` を列挙）、役割に応じたメニュー |
| 板設定 | `/admin/boards/:bbs` | `admin` | モックの画面。`SETTING.TXT` と `head.txt`。§4.3 |
| スレ一覧 | `/admin/boards/:bbs/threads` | `delete` | 公開中・削除/移動済み・key 直接指定（現行と同じ） |
| スレ詳細 | `/admin/boards/:bbs/threads/:key` | `delete` | 停止・再開・削除・移動・レス編集・レス削除・dat と投稿者ログの閲覧 |
| 規制ルール | `/admin/rules/access`・`/admin/rules/ngwords` | `regulate` | `deny.cgi`・`ngwords.cgi`。書式の説明はモックの「書き方」枠の体裁で現行の書式を書く |
| 板一覧 | `/admin/menu` | `admin` | `bbsmenu.html` |
| アカウント | `/admin/accounts`・`/admin/accounts/:uid` | `admin`（検索と BAN は `regulate` も可） | 設計書 §11 の項目（役割・Lv・状態・端末・キャップ・専ブラキー・利用ログ） |
| キャップ | `/admin/caps` | 役割あり（自分の 1 個）、`admin`（全件） | 設計書 §6.2。旧キャップ（KV `CAP`）の紐づけ |
| PHOEBE・エッジ防御 | `/admin/phoebe` | `regulate` | `phoebe.cgi`・`edge-rules.json`・乱造の要確認一覧 |
| 監査ログ | `/admin/audit` | `admin` | §3.6 |
| 初回設定 | `/admin/bootstrap` | 通常セッションのみ | §3.4 |

表示できない画面へのリンクはメニューに出さない。API 側でも必ず役割を検査する（画面の出し分けは利便のためで、防御ではない）。

### 4.2 見た目（モックの取り込み）

モックの構造を次のように写す。CSS は `a.css` を元にした `admin-web/admin.css` 1 本にし、**インラインの `style` 属性と `bgcolor` は使わない**（CSP の `style-src 'self'` で弾かれるため。帯の色はクラスにする）。

| モックの要素 | 管理画面での形 |
| --- | --- |
| 見出し「掲示板管理画面」 | 画面上部のバー（`section-subtitle` の黒帯）。右端にログイン中の `@uid`・役割・ログアウト |
| 左列（幅 300）: 操作ボタン → 設定項目の縦並び | 左列: 「スレッド一覧を直接編集」（スレ一覧へ）、「適用」ボタン、設定項目。項目は「ラベル（太字）＋補足（`notice`）＋入力欄（`form-control`）」の行 |
| 右列（幅 400）: 見出し付き textarea と「書き方」枠 | 右列: ヘッダー（`head.txt`）、規制ルールの該当行、スレ立て規制、その他の設定（直接編集）。見出しは淡い赤（`#FFE0E0`）、説明は白地の枠（`outset`） |
| 項目ごとの背景色の帯 | クラス `band-green`（`#E0FFE0` / `#CCFFCC`）`band-gray`（`#CCCCCC`）`band-orange`（`#FFEDDD`）`band-blue`（`#E0E5FF`）`band-dark`（`#999999`）。用途はモックに合わせ、基本設定は白、規制系は灰、スイッチ類は濃灰 |
| `btn btn-primary btn-lg btn-block` | 同じクラス名で再現。`a.css` には `btn-primary` の通常時の色が無いので、Bootstrap 3 の値（背景 `#337ab7`・枠 `#2e6da4`・文字 `#fff`）を補う |
| 「適用」が上下 2 か所 | 上下に置き、どちらも同じ保存処理。未保存の変更があるときは画面離脱前に確認する |
| 固定幅（650 / 300 / 400） | 960px 以上は 2 列、未満は 1 列（左列 → 右列の順）。横スクロールを出さない |

保存結果（成功・ETag 競合・検証エラー）は画面上部に帯で出し、エラーの項目は入力欄の下に `notice` の赤字で出す。

### 4.3 板設定の項目対応

モックの項目を v6 の設定キーに対応づける。**対応なし**の項目は表示しない（v6 で効かない設定を見せない）。
表に無いキーや下記で「その他」に回すキーは「その他の設定（直接編集）」欄に `KEY=VALUE` の行で出し、保存時もそのまま残す。

| モックの項目 | v6 のキー | 入力 | 扱い |
| --- | --- | --- | --- |
| 掲示板タイトル | `BBS_TITLE` | テキスト | 対応 |
| 名前未入力時の名前 | `BBS_NONAME_NAME` | テキスト | 対応 |
| 現行スレッド保持数 | `BBS_MAX_MENU_THREAD` | 数値 | 対応 |
| 強制sageまでの秒数 | `BBS_FORCE_SAGE` | 数値 | 対応 |
| スレッドタイトル・名前・メール・本文の文字数制限 | `BBS_SUBJECT_COUNT`・`BBS_NAME_COUNT`・`BBS_MAIL_COUNT`・`BBS_MESSAGE_COUNT` | 数値 | 対応 |
| 投稿行数制限 | `BBS_LINE_NUMBER` | 数値 | 対応。補足は v6 の規則（「>>2 以降、この値の 2 倍の `<br>` 区切り数まで」）に書き換える |
| IDの方式・発信元表示 | `BBS_ID` | ラジオ（`checked` / `ip` / `siberia` / `off`） | 対応。選択肢の文言は v6 の意味にする |
| 県名・回線表示 | `BBS_ID_SHIKIBETSU` | ラジオ（`checked` / `raw` / `ken` / `city` / `as` / `d`、段 C 以降は `phoebe`） | 対応 |
| （モックに無い） | `BBS_ID_GROUP`・`BBS_ID_RESET`・`BBS_ID_LV` | テキスト・数値・チェック | 同じ体裁で追加 |
| 個別規制 | `BBS_NO_MADAKANA` | ラジオ | 対応（`checked` で規制の代わりに注意表示） |
| （モックに無い） | `BBS_NGWORD_PASS` | チェック | 同じ体裁で追加 |
| 連投規制の緩和（実況モード） | `BBS_LIVE` | ラジオ | 対応 |
| 一般からの投稿を停止 | `BBS_HEISA` | チェック | 対応。補足は v6 の挙動（「新規スレ作成・返信とも拒否。キャップも拒否」）に書き換える |
| 同一IP群からのスレッド作成規制数・広域スレッド作成規制数 | `BBS_THREAD_TATESUGI(_RESET)`・`BBS_THREAD_JUNBAN(_RESET)`・`BBS_THREAD_JUNBAN_AS_ONLY` | 数値・テキスト | 対応（現行のスレ立て規制フォームの検証を流用） |
| 掲示板全体の連続投稿規制（`timecount` 回中 `timeclose` 回） | `timecount`・`timeclose` | 数値 2 つ | 設計書 §8.2。段 A（連投規制）以降に表示 |
| 短時間投稿規制秒数 | `BBS_SAMBA24` | 数値 | 設計書 §8.3。段 A 以降 |
| 各スレッド内での連続投稿規制 | `BBS_THREAD_RENTOU`・`BBS_THREAD_RENTOU_RESET` | 数値 2 つ | 設計書 §8.4。段 A 以降 |
| 連投規制類（レベル） | `BBS_RENTOU_MODE`・`BBS_RENTOU_KEYS`・`BBS_RENTOU_PASS` | ラジオ・チェック群・選択 | 設計書 §8.1・§7。段 A 以降 |
| 投稿時のログインを必須にする | `BBS_PHOEBE` | 数値（0〜10。「書き込みに必要な最低 Lv」） | 設計書 §4.4。段 C 以降 |
| スレッド作成主機能 | `BBS_DISABLE_NUSI` | ラジオ | 設計書 §4.4 で使う。段 C 以降 |
| （モックに無い） | `BBS_THREAD_PASS` | 選択 | 設計書 §7。段 D 以降 |
| ヘッダー（textarea） | `head.txt` | textarea | 対応 |
| 投稿規制設定・NGワード（textarea） | `deny.cgi`・`ngwords.cgi` | 読み取り表示 | 両ファイルは全板共通なので板設定画面では編集しない。板の正規表現がこの板に一致する行だけを表示し、規制ルール画面（`regulate`）へのリンクを置く |
| スレッド作成規制設定（textarea） | `deny.cgi` の `TateDame` 行 | 読み取り表示 | 同上 |
| キャップ（textarea） | — | — | パスワードを平文で並べる方式は採らない。キャップ画面（§4.1）へのリンクにする |
| スレッド一覧を直接編集（ボタン） | — | — | スレ一覧画面へ |
| 直近のエラーログを閲覧（ボタン） | — | — | 対応なし（§10-6） |
| 連絡先Email・パスワード | — | — | 対応なし。板ごとの管理パスワードは役割に置き換わる |
| タイトル画像・背景画像・ホームページURL・各種の色・UIスタイル・1ページ中のスレッド数・掲載投稿数 | — | — | 対応なし（v6 の板 top は使わない。新 UI のテーマ設定は別途） |
| 削除された投稿に表示される内容・レスオーバー時のメッセージ・告知欄 | — | — | 対応なし（あぼーん文言と 1000 件の文言は固定） |
| UNICODE・サードパーティー製アプリ・海外ホスト・Proxy 規制・逆引き・名前の無効化・名前入力必須・日本語必須・画像/リンク禁止・アイコン・AA・検索掲載・記者制度・ID の割り当て単位（スレごと・1 分ごと・常時表示/非表示）・`BBS_SOKO`・過去ログ化（秒数・投稿数・継続・有効秒数）・同一タイトル規制・リセット時間・レス数上限・スレッド作成間隔・各スレッド内のレス間隔・設定変更コマンド類・ログイン機能・BAN・偽造検出・識別認証用鍵・範囲チェック・なりすまし防止・設定強制 | — | — | 対応なし |
| （モックに無い。既定の SETTING.TXT にはあるが v6 が読まない） | `BBS_ADD_THREAD`・`BBS_SHIKIBETSU`・`BBS_CREATETHREAD_INTERVAL`・`BBS_COPIPE`・`timecover`・`BBS_COOK82`・`BBS_COOK82_POST_LIMIT`・`BBS_KISEIOFF`・`BBS_THREAD_SETTINGS`・`BBS_FORCE_NONAME`・`BBS_ARR` | — | 「その他の設定（直接編集）」に出す |

**保存**: フォームの値と「その他」欄を合わせて `SETTING.TXT` を組み立て、読込時の ETag で条件付き保存する（現行のスレ立て規制フォームと同じ。未作成なら条件なし）。
検証はサーバ側で行い（`ThreadCreationSettings` と設計書の各設定の検証）、`KEY=VALUE` の値に改行を入れさせない。`head.txt` は同じ要求で保存し、どちらかが失敗したら両方とも書かない
（`SETTING.TXT` の検証を先に済ませ、`head.txt` → `SETTING.TXT` の順で書く現行の順序を保つ。R2 にトランザクションは無いので、`SETTING.TXT` の条件付き保存が競合で失敗した場合は `head.txt` を元に戻す）。

## 5. SPA の構成

### 5.1 プロジェクト

```
common/src/main/scala/ch3s/common/admin/
  AdminDto.scala          API の要求・応答の型（板設定、スレ、ルール、アカウント、監査など）
  AdminCodec.scala        JSON の読み書き（§10-4）
  AdminRole.scala         役割と画面・操作の対応表（§4.1）。サーバとクライアントで共有
  BoardSettingsForm.scala 板設定の項目定義（キー・入力種別・範囲・帯の色・表示条件）と SETTING.TXT との相互変換・検証
admin-client/src/main/scala/ch3s/admin/
  Main.scala              createRoot で起動
  AdminApp.scala          ルータとレイアウト
  AdminRoutes.scala       scalajs-react extra の Router 定義（URL ↔ ページ）
  api/AdminApiClient.scala fetch ラッパ（X-Ch3s-Admin、JSON、401 で再認証画面へ）
  pages/                  Top, BoardSettings, Threads, Thread, Rules, Menu, Accounts, Account, Caps, Phoebe, Audit, Bootstrap, Elevate
  components/             Layout, Band, FieldRow, HelpBox, SaveBar, ErrorBanner, ConfirmDialog
admin-web/
  index.html              マウント先と CSS・JS の参照だけ
  main.js                 import "./admin.css"; import "scalajs:main.js";
  admin.css               a.css を元にしたスタイル
vite.admin.config.ts      管理画面専用ビルド（HonoX・Tailwind なし）
bbs/src/main/scala/ch3s/bbs/
  handler/admin/AdminAuth.scala     管理者セッション・再認証・役割検査・Origin 検査
  handler/admin/AdminApi.scala      JSON の入出力とルーティング
  handler/admin/AdminAssets.scala   起動ページと資産の配信
  application/admin/AdminService.scala  板設定・スレ操作・ルール・メニュー・アカウント操作（リポジトリ経由）
  durable/AdminAudit.scala、domain/admin/AuditLog.scala   監査ログ
```

- `adminClient` は `commonJS` にだけ依存し、`bbs` には依存しない（サーバ実装をブラウザ用バンドルに混ぜない。KB-27 の教訓）。
- 依存: scalajs-react `core` と `extra`（Router）`4.0.0`（`bbs` と同じ版）。
- 状態は画面ごとの `useState` 系と、`LoadState`（`Loading` / `Ready` / `Failed`）と `SaveState`（`Idle` / `Saving` / `Saved` / `Conflict` / `Invalid(errors)`）の enum で持つ。全体で共有するのはログイン中の利用者（`me`）だけ。
- 外部の React 部品ライブラリは使わない（facade が要るため）。

### 5.2 ビルド

- `npm run v6:build` に `vite build --config vite.admin.config.ts` を足し、`dist/admin/`（`index.html`・`assets/*.js`・`assets/*.css`）へ出す。既存の client / workers ビルドと出力先を分け、互いに消し合わないよう `emptyOutDir: false` にする。
- sbt は `adminClient/fastLinkJS`・`fullLinkJS` を既存ビルドと直列に実行する（`docs/client-build-review.md` の方針どおり sbt を同時起動しない）。

### 5.3 配信

- `wrangler.jsonc` の `assets` に `run_worker_first: ["/admin", "/admin/*"]` を足し、`/admin` 配下は必ず Worker を通す（Static Assets が先に返すと認証を飛ばせるため）。
- `AdminAssets` は §3.1 の条件（通常セッション＋役割）を満たすときだけ `env.ASSETS.fetch` で `dist/admin/` の起動ページ・資産を返す。画面 URL（`/admin/boards/test` 等）はすべて起動ページを返し、ルーティングはクライアントで行う。
- `/admin/api/*` は JSON だけを返し、起動ページへフォールバックしない。存在しない API は 404 の JSON。
- KB-28（`dist` の Worker バンドルが資産として取れる）の修正とは独立だが、`dist/admin/` を Worker 経由に限ることで同じ問題を管理画面に持ち込まない。

### 5.4 応答ヘッダ

| 対象 | ヘッダ |
| --- | --- |
| 起動ページ・API | `cache-control: no-store`、`x-content-type-options: nosniff`、`referrer-policy: no-referrer` |
| 起動ページ | CSP `default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; form-action 'self' https://accounts.google.com; frame-ancestors 'none'; base-uri 'none'` |
| 資産（ハッシュ付きファイル名） | `cache-control: private, max-age=31536000, immutable` |

## 6. API

### 6.1 一覧

| メソッドとパス | 役割 | 内容 |
| --- | --- | --- |
| `GET /admin/api/me` | （管理者セッション） | `{uid, roles, expiresAt}`。無効なら 401 `{code:"admin:reauth"}` |
| `POST /admin/api/elevate` | 通常セッション | `{password}` を検証して管理者セッションを発行。Google は §3.1 |
| `POST /admin/api/logout` | — | 管理者 Cookie を消す |
| `POST /admin/api/bootstrap` | 通常セッション | `{token}`。§3.4 |
| `GET /admin/api/boards` | いずれか | 板名の一覧（R2 を `delimiter: "/"` で列挙し `SETTING.TXT` を持つもの） |
| `GET /admin/api/boards/:bbs/settings` | `admin` | `{fields, extra, head, etag, rules: {access, ngwords}}`。`rules` はこの板に一致する行 |
| `PUT /admin/api/boards/:bbs/settings` | `admin` | `{fields, extra, head, etag}`。競合は 409 `{code:"admin:conflict"}` |
| `GET /admin/api/boards/:bbs/threads` | `delete` | 公開中（subject）と削除/移動済み |
| `GET /admin/api/boards/:bbs/threads/:key` | `delete` | `inspect` の結果を構造化（dat の各行を 5 フィールドに分けたもの、投稿者ログ、旧形式ログ、スレ設定、停止・削除・移動の状態） |
| `POST /admin/api/boards/:bbs/threads/:key/actions` | `delete` | `{action: "stop"|"resume"|"delete"|"move"|"editRes"|"deleteRes", ...}`。現行の `Thread.moderate` に渡す |
| `GET`・`PUT /admin/api/rules/:name` | `regulate` | `name` は `access` / `ngwords`。`PUT` は `Moderation.parseRules` で検証し、ETag 条件付きで保存（現行は無条件。§10-5） |
| `GET`・`PUT /admin/api/menu` | `admin` | `bbsmenu.html` |
| `GET /admin/api/audit?before=` | `admin` | 監査ログのページ |
| アカウント・キャップ・PHOEBE | 設計書 §11 に従う | 段 A3 で定義 |

### 6.2 共通規則

- 要求・応答は UTF-8 の JSON。変更系は §3.5 の条件を満たさなければ 403 `{code:"admin:origin"}`。
- エラーは `{code, message, fields?}`。`code` は `admin:reauth`（401）・`admin:forbidden`（403）・`admin:origin`（403）・`admin:invalid`（400、`fields` に項目ごとの理由）・`admin:conflict`（409）・`admin:too-large`（413）・`admin:not-found`（404）・`admin:internal`（500）。`message` は日本語の利用者向け文言で、サーバの `AdminText` が組み立てる。
- 役割の検査は `AdminRole.allows(roles, operation)` だけで行い、各 API で個別に書かない。
- DO・R2 の操作は `AdminService` 経由で、DO の新しい RPC は増やさない（段 A3 のアカウント系を除く）。`Thread.moderate` の `js.Dynamic` 引数は `durable/codec/` のコーデックで作る（AGENTS.md 3.5）。

## 7. 段階

各段で `sbt test`、`npm run v6:build`、`npm run v6:typecheck`、`npm test`、`sbt 'set ThisBuild / scalacOptions += "-no-indent"' Test/compile` を通し、AGENTS.md 3.2 のレビュー連鎖を行う。

| 段 | 内容 | 前提 | 完了条件 |
| --- | --- | --- | --- |
| A0 | **API 化**: `AdminService`・`AdminApi`・`AdminDto` / `AdminCodec`・`BoardSettingsForm` を作り、`/admin/api/*` を Basic 認証のまま追加する。既存の HTML 画面はそのまま残し、内部で `AdminService` を共用する | DDD 化計画の段 0b | 既存の `AdminHandlerSuite`・統合テストが変更なしで通る。`AdminApiSuite` で全 API の正常系・検証エラー・ETag 競合・CR/LF 拒否・入力上限を確認 |
| A1 | **SPA 化**: `adminClient`・`admin-web`・`vite.admin.config.ts`、配信（`run_worker_first`・`AdminAssets`）、モックの見た目。全画面が現行と同じ操作をできたら、旧 HTML の描画（`AdminText` の画面部分）を削除する。認証は Basic 認証のまま、API に §3.5 の Origin 等の検査を加える | A0 | 現行の全操作を SPA から実行できることを実 workerd ＋ Playwright（Chromium）で確認。CSP 違反が出ない。`dist/admin/` が `/admin` 以外から取れない |
| A2 | **アカウント認証へ切替**: 管理者セッション・再認証・役割検査・bootstrap・監査ログ。Basic 認証と `ADMIN_PASSWORD` を削除。AGENTS.md 3.6・`docs/admin.md` を更新 | 設計書の段 B（`Account` / `AccountIndex` DO・セッション）と、段 E のうち ID/Password ログインと Google ログイン（§12 を参照し E-1 として前倒し） | 役割ごとの許可・拒否の表を全 API について `AdminAuthSuite` で確認。Basic 認証の資格情報では入れない。bootstrap の条件（0 件・非常用フラグ）を確認 |
| A3 | **認証機能の管理画面**: アカウント・キャップ・PHOEBE / エッジ防御・連投規制とスレ立て規制の各項目（§4.3 の「段 A / C / D 以降」） | 設計書の各段 | 各画面の API テスト。§4.3 の表示条件が各段の完了と一致する |
| A4 | **任意**: 管理者の TOTP、エラーログ閲覧（§10-3・§10-6） | — | — |

A0 と A1 は認証基盤を待たずに進められる。A2 は設計書の認証基盤が先に要る。

## 8. テスト

- **単体（JVM/JS）**: `BoardSettingsForm`（SETTING.TXT ↔ フォーム値の往復、未知キーの保持、値の検証、改行拒否）、`AdminRole`（役割 × 操作の全組合せ）、`AdminCodec`（DTO の往復）、`AuditLog`（保持規則）。
- **サーバ（Scala.js）**: `AdminApiSuite`・`AdminAuthSuite`（`HandlerTestKit` で R2・DO・時刻を差し替え）。管理者 Cookie の署名・期限・無操作失効・`gen` 進行での失効、Origin / Content-Type / 独自ヘッダの各欠落での拒否。
- **クライアント**: scalajs-react のテストユーティリティ（`ReactTestUtils`）と jsdom で、ページごとの読込・保存・エラー表示・未保存確認。既存の `tests/islands-hydration.test.js` と同じく jsdom を使う。
- **統合**: `tests/admin.integration.test.js` を API 版に書き換え、Playwright で SPA の主要操作（ログイン → 再認証 → 板設定保存 → スレ停止 → ルール保存 → ログアウト）を実 workerd 上で通す。

## 9. 設計書・既存文書への反映

- 設計書 §6.1・§10・§11・§13-14: `/mod/*` を `/admin/*` に統合し、認証を本書 §3 に差し替える（本コミットで訂正）。
- 設計書 §12: 段 E を E-1（ID/Password ログイン・Google ログイン）と E-2（メール認証・再設定・方式移行・エッジ防御・乱造検知・Lv 上限ルール）に分け、E-1 を段 B の直後に置く（本コミットで訂正）。
- `docs/admin.md`: 段 A2 で書き換える（ログイン手順、bootstrap、役割）。
- AGENTS.md 3.6: 段 A2 で「管理画面は再認証付きの管理者セッション＋役割＋Origin 検証＋no-store＋CSP 前提」に改める（利用者の承認後）。
- `docs/auth-phoebe-review.md` 設計 #8 の判断（`/mod` 分離・`/admin` は Basic のまま）は、2026-11-02 の利用者指示で本書の方針に置き換わった旨を追記する（本コミット）。

## 10. 要確認事項

| # | 事項 | 本書の仮置き |
| --- | --- | --- |
| 1 | モックの項目のうち v6 に対応しないもの（色・画像・過去ログ化・UNICODE 等）を表示しないこと | 表示しない。必要なら新機能として別途設計 |
| 2 | 権限者用の別画面（`/mod`）を作らず `/admin` に統合すること。役割は全板共通（板ごとの管理者は作らない） | 統合する。全板共通。板ごとの委任が要るなら `roles` に板の範囲を持たせる拡張を別途検討 |
| 3 | 管理者の二要素認証 | A2 では入れない（`admin` の ID/Password は 16 文字以上を必須）。A4 で TOTP を検討 |
| 4 | JSON の読み書き | `common` に置く DTO を JVM / JS 両方で使うため、クロスビルドできる JSON ライブラリ（uPickle 等）を 1 つ追加する。追加を避けるなら手書きの codec（JS 側は `js.JSON`、JVM 側はテスト専用） |
| 5 | `deny.cgi`・`ngwords.cgi` の保存を ETag 条件付きにすること（現行は無条件。複数の管理者が同時に編集すると後勝ちで消える） | 条件付きにする（挙動の変更なので A1 で独立コミット） |
| 6 | モックの「直近のエラーログを閲覧」 | A2 までは作らない。Workers Logs の検索をボタンから開く案と、投稿エラーを DO に記録する案を A4 で比較 |
| 7 | 初回の管理者を `ADMIN_BOOTSTRAP_TOKEN` で作り、`ADMIN_PASSWORD` を廃止すること。非常用経路を `ADMIN_BREAK_GLASS` の再デプロイに限ること | そのとおり |
| 8 | 管理者セッションの無操作 30 分・最長 8 時間、再認証の猶予 10 分 | 仮置き |
| 9 | 現行で Origin 検証を外している理由（2026-10-05 の利用者確認）が、Cookie 認証化後も当てはまるか | 当てはまらないと判断し、A1 から Origin 検証を入れる |
