# 管理画面 改修計画のレビュー記録

2026-11-02。対象は [admin-spa-auth-plan.md](admin-spa-auth-plan.md)（コミット `e8e4f2a` 時点）。
利用者の指示により、AGENTS.md 3.2 のセルフレビューを Opus 5（Claude Code のサブエージェント、読み取り専用）に依頼した。
秘匿値は渡していない。Codex CLI / Gemini CLI レビューは未実施。

指摘のうち現行コードに関わるもの（レス編集のエスケープ、`BBS_MAX_MENU_THREAD` の既定値、`BBS_ID_SHIKIBETSU` の分岐、
現行の直接保存が無条件であること）は、コードを読んで事実を確かめてから採否を決めた。

| # | 重要度 | 指摘（要約） | 判断・対応 |
| --- | --- | --- | --- |
| 1 | High | 「役割付与時に 16 文字以上を検査」は、保存値がハッシュなので実装できない | 採用。平文が手に入る再認証・bootstrap・パスワード変更の時点で検査し、満たさなければ管理者セッションを出さない（§3.3） |
| 2 | High | 起動ページを役割ありに限ると、最初の管理者が bootstrap 画面を読み込めない | 採用。`/admin/bootstrap` の起動ページ・資産・API だけ役割の検査から外す（§3.1・§3.4・§5.3） |
| 3 | High | 「admin が 0 件」の判定と役割の付与が原子的でなく、同時 bootstrap で 2 人が admin になりうる。非常用フラグの外し忘れ | 採用。`AccountIndex("role:admin").claimFirstAdmin` で判定と確保を 1 RPC にし、`active` の件数だけ数える。非常用は期限付きの `ADMIN_BREAK_GLASS_UNTIL`。トークンの長さと定数時間比較を明記（§3.4、設計書 §9.1） |
| 4 | High | Basic 認証を消す段に役割付与の手段が無く、管理者が 1 人になる | 採用。段 M2 に最小限の役割付与 API と画面を含める（§7・§6.1） |
| 5 | High | 署名 Cookie だけでは、ログアウトしても盗まれた管理者 Cookie が最長 8 時間有効 | 採用。Account DO に `adminGen` を持ち、ログアウト・役割変更・凍結で進める。時刻の単位と鍵の更新手順を明記（§3.2、設計書 §9.1） |
| 6 | High | Google の再認証が `prompt=login` / `auth_time` 頼みで未検証。別アカウントでの再認証を防ぐ照合が無い | 採用。`ch3s_oauth` に `purpose=admin` と `aid` を入れて callback で照合し、管理者 Cookie だけを発行。`auth_time` は M2 前に実機で確認し、取れなければ Google の管理者は TOTP 必須（§3.1.1・§10-10） |
| 7 | High | メールによるパスワード再設定から管理者を乗っ取れる | 採用。役割ありのアカウントはメール再設定を使えない。別の admin か非常用経路で対応（§3.3、設計書 §3.9） |
| 8 | High | フォームが既定値を明示値として書き出し、コード既定値の変更（`BBS_RENTOU_MODE` の `on` 化）が届かなくなる | 採用。未設定と明示値を区別し、触っていない未設定項目は書き出さない（§4.3） |
| 9 | Medium | `BBS_FORCE_NONAME` がモックにあるのに「モックに無い」、`BBS_LIVE` の補足が段 A 以降の挙動 | 採用。対応表を訂正（§4.3） |
| 10 | Medium | 保持数・`BBS_ID_SHIKIBETSU=d`・`BBS_PHOEBE` の文言が v6 の挙動と食い違う | 採用（コードで確認: `Board.scala` の既定 700、`makeId` の `case _ => ""`）。補足と選択肢を訂正 |
| 11 | Medium | SETTING.TXT の読み方の基準、行順、`=` の無い行、重複の扱いが未定義 | 採用。統合後の `BbsSettings.parse` を唯一の基準にし、規則を明記（§4.3） |
| 12 | Medium | `head.txt` を元に戻す方式は競合で他人の保存を消す。`head.txt` に条件が無い | 採用。両方に ETag を使い、`head.txt` だけの競合は部分成功として返す。元に戻す処理はしない（§4.3・§6.1） |
| 13 | Medium | `common` の `BoardSettingsForm` が `bbs` の `ThreadCreationSettings` に依存できない | 採用。`common` は項目定義と相互変換だけ、検証はサーバ側（§4.3・§5.1） |
| 14 | Medium | レス編集の変換がクライアントに無い。名前欄のタグが編集で壊れる現行の不具合 | 採用。`ResEditText` を `common` に移す。名前欄の件はコードで確認し KB-40 として記録（§5.1・§6.1） |
| 15 | Medium | `Thread.moderate` は日本語の例外文言を返すのでコード化できない。`inspect` の前呼び | 採用。当面は `admin:thread-state` に文言をそのまま入れる。`inspect` を先に呼ぶことを明記（§6.1・§6.2） |
| 16 | Medium | R2 列挙で SETTING.TXT の有無を見ると既定値で動く板が漏れる。ページングと facade | 採用。接頭辞と `bbsmenu.html` のリンクの和集合、手入力も残す、facade に `list` を足す（§6.1） |
| 17 | Medium | `cap` だけの人、キャップ画面の重複、レス編集の権限、役割の逆転、投稿者ログ閲覧の記録 | 採用。「役割あり」を 3 役割に限定、自分のキャップは `/auth/account`、上位の役割への操作を禁止、投稿者ログ閲覧とレス編集の前後を監査ログに残す（§2・§3.6・§4.1） |
| 18 | Medium | Origin 検証の復活と CSP 変更は M1 で行うのに承認が M2。既存テストは他オリジンを受け付ける期待値 | 採用。M1 の前に利用者の承認を得る、M1 の完了条件にテストの書き換えを入れる（§0・§7・§10-9） |
| 19 | Medium | 計画の段名と設計書の段 A〜E が紛らわしい | 採用。計画の段を M0〜M4 に改名し、設計書側の参照も直した |
| 20 | Medium | Vite の `base`・`publicDir`・`root`・出力先、ビルド順が未定 | 採用。§5.2 に明記 |
| 21 | Medium | jsdom の構成、React 19 でのテストユーティリティ、Playwright の依存 | 採用。M1 の最初に試作で確認、必須の判定は node:test ＋実 workerd の API テスト、Playwright は任意（§8） |
| 22 | Medium | 盗まれた通常セッションから IP を分散して再認証を総当たりできる | 採用。アカウント単位で 1 日 20 回失敗で 24 時間停止、全役割に 16 文字以上（§3.2・§3.3） |
| 23 | Medium | 公開ページと同一オリジンなので、公開側の XSS から管理 API を操作されうる | 一部採用。残るリスクとして明記し、別ホスト案を §10-11 で確認事項にした。本計画では同一ホストのまま |
| 24 | Medium | 監査ログの失敗時の扱い、記録時点、migration が未定 | 採用。結果を記録、失敗しても操作は成功させて Workers Logs に残す、migration v3（§3.6） |
| 25 | Low | 設計書との食い違い（RPC・secret・旧パス・Origin の範囲・索引キー） | 採用。設計書 §3.3・§3.9・§9.1・§9.3・§11・§12 を訂正。`docs/admin-review.md` がこのブランチに無い点は、`develop_v6s` には存在しないことを確認したうえで今回は扱わない（`feature/spa_admin_page` 側にある） |
| 26 | Low | エラーコードの不足、`bbsmenu.html` が条件付きでない | 採用。コードを追加し、menu も ETag 条件付きに（§6.1・§6.2） |
| 27 | Low | CSP の理由の記述が不正確、フォント、資産ヘッダの上書き方法 | 採用。§4.2・§5.4 を訂正 |
| 28 | Low | モックで実際に使われているクラス、`mt5` の欠落、モックに無い画面の見た目 | 採用。`mt5` を補い、共通レイアウトを定め、モックからの変更を §10-12 で確認事項にした |
| 29 | Low | `extra` 4.0.0 の配布が未確認、Router に離脱確認が無い | 採用。M1 の試作で確認、離脱確認は自前で実装（§4.2・§5.1） |
| 30 | Low | `ADMIN_PASSWORD`・`ADMIN_USER` の廃止と開発時の手順が文書の更新対象に無い | 採用。§3.4・§9 に追加 |

## レビューで未確認とされた事項

Google の `max_age` / `auth_time` の挙動、scalajs-react `extra` 4.0.0 の配布と React 19 でのテストユーティリティ、
R2 の `etagDoesNotMatch: "*"`、`@hono/vite-build` が `dist/` を空にするか、2026-10-05 に Origin 検証を外した理由。
いずれも計画書の該当段で確かめる。

## 段 M0 の実装記録

2026-11-07。計画書 §7 の M0（API 化）。コミットは common の型と変換、`AdminService` への移設（挙動を変えない）、ETag 条件付き保存（挙動の変更）、
`/admin/api` の追加、実 workerd の統合テストの順。

### 実装したもの

- common `admin/`: `Json`、`AdminDto`・`AdminCodec`（全 DTO の往復）、`BoardSettingsForm`、`ResEditText`（と dat の行を分ける `ResView.fromDat`）。
- bbs: `application/admin/AdminService`・`AdminFailure`・`ports/AdminStores`、`repository/AdminRepository`、`durable/codec/ThreadModerationCodec`、
  `domain/admin/BoardSettingsCheck`・`BoardLinks`、`ModerationRuleSet.linesFor`、`handler/admin/AdminApi`、R2 の `list` の facade。
- HTML の画面（`AdminHandler`）は `AdminService` を使うように書き換えた。画面の HTML と文言は、条件付き保存の hidden 項目と競合の文言を除いて変えていない。
- テスト: `AdminApiSuite`（13 件）、`BoardSettingsFormSuite`・`ResEditTextSuite`・`ResViewSuite`・`JsonSuite`・`AdminCodecSuite`（JVM / JS）、
  `ResEditTextJsSuite`（移設前の JS の正規表現と同じ結果になること）、`BoardLinksSuite`、`AdminHandlerSuite` に条件付き保存の 4 件。
  `tests/admin.integration.test.js` に API の主要操作を追加。既存の `AdminHandlerSuite` と統合テストの期待値は変えずに通った。

### 計画との差分

| 項目 | 計画 | 実装と理由 |
| --- | --- | --- |
| JSON（§10-4） | ライブラリを 1 つ足すか手書き | 手書き。common に依存の無い `Json` を置き、JVM / JS で同じコードをテストする。依存の追加を避けた |
| 規制ルールの条件付き保存（§10-5） | M1 で独立コミット | 指示により M0 の独立コミットで、板設定・`head.txt`・`bbsmenu.html` と合わせて行った |
| HTML の画面の条件付き保存 | 記述なし | フォームに読込時の ETag を hidden で持たせる。ETag の項目が無い送信（従来の画面・統合テストの手書きの要求）は条件なしで保存する。既存テストの期待値を変えずに済み、手書きの要求で保存できなくなる事故を避けるため |
| スレ立て規制フォーム | 現行どおり | 未作成の SETTING.TXT は「無いときだけ作る」に変えた（条件付き保存のコミットに含む）。R2 にその条件が無いという前提（KB-25 の記述）が `etagDoesNotMatch: "*"` で解消したため |
| `etagDoesNotMatch: "*"` | M0 で確かめる | ローカルの wrangler（workerd）で、無いときは書け、有るときは null になることを確かめた。本番の R2 では未確認 |
| 板設定の保存順 | SETTING.TXT → head.txt | そのとおり。HTML の画面の保存も同じ順に変えた（従来は head.txt → SETTING.TXT） |
| スレの状態の表し方 | 「停止・削除・移動の状態」 | `hidden`・`stopped`・`movedTo`・`movingTo` をそのまま返す。削除済みでも停止フラグは独立に残る（画面の停止・再開ボタンがこれを見る）ため、1 つの状態にまとめなかった |
| 規制ルール・板一覧の本文 | 未定 | どちらも `{text, etag}` |
| エラーコード | §6.2 の一覧 | Basic 認証の間だけ `admin:unauthorized`（401）と `admin:unavailable`（503）を足した。M2 で `admin:reauth` に置き換える |
| 変更系の検査（§3.5） | M1 で Origin・Content-Type・独自ヘッダ | Origin と独自ヘッダは M1 のまま。Content-Type: application/json だけは新しい API に M0 から求める（フォーム送信から JSON の API を叩けないようにするため。既存の画面には影響しない） |
| スレ操作の対象が無い | 未定 | `inspect` の後の `Thread.moderate` が「スレッドがありません。」で拒否するので 409 `admin:thread-state`。詳細の GET は 404 |
| `BoardSettingsForm` の項目定義 | 範囲・帯の色・表示条件を含む | キー・ラベル・入力の種類・既定値まで。帯の色と表示条件は画面を作る M1 で足す。値の範囲はサーバの `BoardSettingsCheck` |
| `AdminRole` | §5.1 に記載 | M2（役割の検査）で作る。M0 は Basic 認証で全操作を許す |
| 投稿者ログの閲覧の監査ログ | §6.1 | M2（監査ログ）から |

### 振る舞いの確認

- `AdminService` への移設は HTML の応答を変えない。ただし、知らない `action` をスレ操作に送った場合は Thread DO を呼ばずに同じ文言「操作が不正です。」を返す
  （従来は `inspect` の後で DO が同じ文言で拒否していた。スレが無いときの文言だけが「スレッドがありません。」から変わる）。
- HTML のレス番号は JavaScript の Number で整数として読めなければ 0 にして DO に渡し、DO が従来と同じ順と文言で拒否する。
- `Thread.escape` は `ResEditText.toField` を呼ぶようにした（同じ変換）。
- 板設定の API の検証は、数値の項目に 0 以上の整数か空、候補のある項目に候補の値、チェックに checked か空、`BBS_FR_LEVEL` に off・log・整数・空、
  `BBS_PHOEBE` に 0〜10 か空を求める。既存の SETTING.TXT に候補外の値（古い値など）があると、その項目を直すまで API では保存できない（HTML の直接編集は従来どおり）。

### セルフレビュー（AGENTS.md 3.5）

- DDD: R2・DO の操作は repository に、`js.Dynamic` はコーデックとリポジトリで止めた。板設定の検証は `domain/admin`、相互変換は common。DO は `escape` の差し替えだけ。
- コメント内の `/admin/api/*` が入れ子のコメントになってコンパイルが止まった（`/*` を含む Scaladoc の罠）。表記を「/admin/api 以下」に改めた。
- 可変状態は JSON の読み取り器の位置だけ（`Json.Parser`）。種別はすべて enum（`AdminErrorCode`・`ThreadAction`・`RuleName`・`WriteCondition`・`AdminFailure`・`SettingProblem`）。

### 未解決・利用者の確認が要る点

- 本番の R2 で `etagDoesNotMatch: "*"` が効くか（効かなければ未作成時は条件なしと同じになる。データは壊れない）。
- HTML の画面で ETag の項目が無い送信を条件なしで保存する扱いでよいか。→ 2026-11-07 利用者: その扱いでよい。
- M1（SPA 化）の前に、Origin 検証の復活と CSP の変更の承認（§10-9）。→ 2026-11-07 利用者: 承認。
- KB-40 は未修正（指示により現状維持）。

## 利用者の決定（2026-11-07）

- 利用者: Origin 検証の復活と CSP の `script-src 'self'` への変更（計画書 §10-9）、M0 の ETag の項目が無い HTML の送信を条件なしで保存する扱い（§10-5）を承認。計画書と設計書の該当箇所を決定に改めた。

## 段 M1 の実装記録

2026-11-07。計画書 §7 の M1（SPA 化）。コミットはビルドの配線と画面の土台、板設定の画面、スレ・規制ルール・板一覧の画面と jsdom のテスト、
API の Origin 検査、配信と旧画面の削除、文書の順。

### 実装したもの

- `admin-client`（sbt の `adminClient`。`commonJS` だけに依存）: `AdminApp`（URL と画面の切り替え、未保存の確認）、`AdminPage`、`AdminApiClient`、
  `LoadState`・`SaveState`・`Loaded.useLoad`、`BoardSettingsDraft`・`BoardSettingsLayout`、画面はトップ・板設定・スレ一覧・スレ管理・規制ルール（アクセス・NGワード）・板一覧。
  すべて関数コンポーネント（`ScalaFnComponent`）で、コンポーネントは `final val`。見た目は板トップと同じ Tailwind のクラスで、値はモック（`docs/ui/auth-admin.css`）から写した。角丸なし。
- `admin-web/`（`index.html`・`main.js`・`admin.css`）と `vite.admin.config.ts`。`npm run v6:build` の最後に `v6:build:admin` を足し、`dist/admin/index.html` の有無を検査する。
- 配信: `wrangler.jsonc` の `run_worker_first: ["/admin", "/admin/*"]`、`AdminAssets`（Basic 認証の後に、画面の URL は起動ページ、`/admin/assets/` はそのファイル）。
  起動ページは `no-store`・`nosniff`・`no-referrer` と CSP `script-src 'self'`（§5.4 のとおり）、資産は `private, max-age=31536000, immutable`。
- API の変更系に Origin と `X-Ch3s-Admin: 1` の検査（`AdminOrigin`。満たさなければ 403 `admin:origin`）。
- 旧 HTML の画面（`AdminHandler` の描画と POST、`AdminText` の画面部分）を削除。使われなくなった `AdminService.saveCreationLimits`・`AdminFailure.CreationLineBreak`・
  `WriteCondition.Always` を消し、`boardTexts`・`saveBoardTexts` を非公開にした。板トップの「管理画面へ」のリンクを `/admin/boards/板名/` に替えた。
- テスト: `adminClient` の munit（`AdminPageSuite`・`BoardSettingsDraftSuite`・`BoardSettingsLayoutSuite`・`AdminApiClientSuite`、12 件）、`tests/admin-spa.test.js`（node:test ＋ jsdom、10 件）、
  `AdminHandlerSuite` を配信と Origin の 4 件に書き換え、`AdminApiSuite` は管理画面と同じヘッダで送るようにして規制ルールの誤りの文言を足した。
  `tests/admin.integration.test.js` と `tests/post-rate.integration.test.js` を API に書き換え、起動ページ・資産・CSP・認証・他オリジンの拒否・`/admin` 以外の書き方を足した。
  `tests/client-bundle.test.js` に管理画面の JS にサーバ処理が入らないことを足した。

### 計画との差分

| 項目 | 計画 | 実装と理由 |
| --- | --- | --- |
| Router | scalajs-react `extra` の Router | `extra` 4.0.0 はミラーから取得できたが、Router は内部でクラスコンポーネントを使い、離脱を止める機能も無い。画面は 6 種類だけなので、`AdminPage`（URL ↔ 画面）と `pushState`・`popstate` を自前で書いた |
| Scala.js のテストを jsdom で | 試作で確かめる | `scalajs-env-jsdom-nodejs` 1.1.0 の jar を確かめると入力はスクリプト（`Input.Script`）だけで、ES モジュールで React を読む `adminClient` は載らない。node:test ＋ jsdom の JS テストにした |
| `admin.css` の `@source` | `app/style.css` と同じ | `admin-client` のソースだけ（公開側のクラスを管理画面の CSS に入れないため） |
| Content-Type の誤り | §6.2「§3.5 を満たさなければ 403」 | Origin と独自ヘッダを先に見て 403。満たした要求の Content-Type の誤りは M0 と同じ 415 のまま |
| 板設定の項目 | 帯の色・表示条件を `BoardSettingsForm` に足す | 画面の並び・帯の色・選択肢の表示は `adminClient` の `BoardSettingsLayout` に置いた（サーバは使わないため）。設計書の段 A・C・D は完了しているので、表示条件は設けず全項目を出す（`BBS_RENTOU_PASS`・`BBS_THREAD_PASS` を含む。旧画面では SETTING.TXT の直接編集で変えられたので、出さないと操作が減る） |
| 旧画面の URL | 記述なし | `/admin/board?bbs=` などの旧 URL は SPA の「ページがありません」になる。板トップのリンクは新しい URL に替えた |
| 「直近のエラーログ」「キャップ」「アカウント」 | §4.1 | M1 では作らない（M2〜M4） |
| 上部バーの利用者名・役割・ログアウト | §4.2 | Basic 認証の間は出さない（M2） |

### 振る舞いの確認

- 実 workerd で、`/admin/`・`/admin/index.html`・`/admin/assets/*`・画面の URL が認証なしでは 401、認証ありで起動ページと資産を返すこと、起動ページにインラインのスクリプト・スタイルが無いこと、
  `/%61dmin/index.html`・`//admin/index.html`・`/admin%2Findex.html`・`/%2Fadmin/index.html` は Static Assets が `/admin/…` へ 307 で正規化し、`/ADMIN/…`・`/.%2Fadmin/…` は 404 になること（どれも起動ページを返さない）を確かめた。
- 他オリジン・Origin なし・`Origin: null`・`X-Ch3s-Admin` なしの変更は 403 で、保存されない。GET は Origin を問わない。
- 公開側（dat・subject.txt・bbs.cgi・Shift_JIS）の応答は変えていない。板トップの「管理画面へ」のリンク先だけが変わる。
- CSP の違反は、ブラウザーを使った確認はしていない（Playwright は任意で入れていない）。起動ページにインラインのスクリプト・スタイルが無いこと、画面に `style` 属性が無いこと（jsdom）、
  資産が `/admin/assets/` から読まれることで代えた。

### セルフレビュー（AGENTS.md 3.5）

- `Callback.when(条件)` の条件が描画のたびに評価され、確認のダイアログが描画中に出ていた（jsdom のテストで検出）。`Confirm`（`CallbackTo` で条件を遅らせる）にまとめた。
- TS / JS は `admin-web/main.js` の import と Vite の設定だけで、画面の処理と API の呼び出しはすべて Scala。可変の状態は Hooks（`useState`・`useRef`）だけ。
- 文言は既存画面に合わせ、全角と英数字の間に空白を入れていない。

### 未解決・利用者の確認が要る点

- ブラウザーでの CSP 違反の確認（Playwright を入れるか、手で開いて開発者ツールで見るか）。
- 管理画面の JS は 1 本で約 680KB（gzip 約 190KB）。分割は M2 以降で必要なら検討する。
- 旧 URL（`/admin/board?bbs=` など）のブックマークは「ページがありません」になる。転送が要るか。
