# 管理画面 改修計画（アカウント認証・新 UI・scalajs-react SPA）

2026-11-02。認証・登録機能（[認証・PHOEBE・連投規制 設計](auth-phoebe-rate-limit-design.md)、以下「設計書」）の実装に合わせて、
`/admin/*` を次の 3 点で作り直す計画。**状態: 段 M0（API 化）は完了（2026-11-07）。M1 の前提だった Origin 検証の復活と CSP の変更は承認済み（2026-11-07 利用者。§10-9）。** M0 の実装範囲と計画との差分は [レビュー記録](admin-spa-auth-review.md) の「段 M0 の実装記録」。

1. **認証**: HTTP Basic 認証をやめ、原案の管理者アカウント（ID/Password 方式または Google 連携）によるログインにする。
2. **UI**: 利用者提示のモック（旧 delightly の板設定画面 `kanri.html` と `a.css`）の構成を採り、見た目は現行の板トップに揃える（2026-11-05 利用者指示）。モックは `docs/ui/admin-*.html`。
3. **実装**: scalajs-react の SPA（クライアント描画）で作り直し、サーバは JSON API にする。

構成の下敷きは `feature/spa_admin_page` の `docs/管理画面SPAメモ.md`（2026-10-09。scalajs-react 版の `adminClient` 案）。
2026-11-02 に Opus 5 のセルフレビューを行い、採用した指摘を反映した（[admin-spa-auth-review.md](admin-spa-auth-review.md)）。
段の名前は設計書の段 A〜E と区別するため M0〜M4 とする。

## 0. 前提・不変条件

| 項目 | 扱い |
| --- | --- |
| 公開側 | dat・subject.txt・bbs.cgi・`3chiko.html`・Shift_JIS 応答は変えない |
| 保存先 | R2 の `{bbs}/SETTING.TXT`・`{bbs}/head.txt`・`deny.cgi`・`ngwords.cgi`・`bbsmenu.html`、Thread / Board DO の既存 RPC をそのまま使う。形式は変えない。新規は設計書 §9 の DO と本書の監査ログだけ |
| 管理操作の意味 | 現行のスレ停止・再開・削除・移動・レス編集・レス削除・規制ルール検証・スレ立て規制フォームの ETag 条件付き保存は、挙動を変えずに API 化する（`docs/admin.md` の記述が引き続き正） |
| 防御 | AGENTS.md 3.6（Basic 認証＋Origin 検証＋no-store＋CSP）は、Basic 認証を「再認証付きの管理者セッション」に置き換える。**Origin 検証は復活させる**（Cookie 認証では必須。現行は 2026-10-05 の利用者確認で意図的に外しており、外した理由は記録に無い）。no-store と CSP は維持し、CSP は SPA 用に改める（§5.4）。Origin 検証の復活と CSP の変更（`script-src 'self'`）は段 M1 で行う（**承認済み（2026-11-07 利用者）**。§10-9）。3.6 の文言更新は段 M2 で行う |
| 起点 | 現在の `develop`（板トップ統合後。`AdminHandler` にはスレ立て規制フォームがある）。DDD 化計画（`docs/bbs-cgi-ddd-refactoring-plan.md`）の段 0b と並行してよい |

## 1. 現状

- `bbs/.../handler/AdminHandler.scala`（約 200 行）が Basic 認証・入力検証・R2 / DO 操作・HTML 文字列の組み立てを 1 つの関数で行う。`AdminText` が共通レイアウトと文言を持つ。
- 画面: `/admin/`（板名入力）、`/admin/board`（`head.txt`・`SETTING.TXT` の直接編集とスレ立て規制フォーム）、`/admin/threads`、`/admin/thread`（停止・再開・削除・移動・レス編集・レス削除・ログ閲覧）、`/admin/access`・`/admin/ngwords`（`deny.cgi`・`ngwords.cgi`）、`/admin/menu`（`bbsmenu.html`）。
- 更新は `application/x-www-form-urlencoded` の POST → 303。応答は `no-store`・`nosniff`・`no-referrer`・`script-src` なしの CSP。入力 256KB 上限。
- テスト: `AdminHandlerSuite`（6 件。認証、スレ立て規制フォーム、ETag 競合、CR/LF 拒否）、`tests/admin.integration.test.js`（実 workerd）。
- 板の一覧を持つ仕組みは無い（板名を手で入力する）。

## 2. 方針の要点

- **管理画面は 1 つ**: 設計書 §6.1 の権限者向け `/mod/*` は作らず、`/admin/*` に統合して役割（`admin` / `delete` / `regulate`）で画面と操作を出し分ける（設計書を本書に合わせて訂正）。本書で「役割あり」は、この 3 つのいずれかを持つことを指す。`cap` だけの人は管理画面に入れず、自分のキャップは `/auth/account` で扱う。
- **ログインは公開側と同じアカウント**: `/auth/` でログインし、管理画面に入るときにもう一度パスワード（または Google）で確認して、短命の管理者セッションを別 Cookie で発行する（sudo 方式）。
- **SPA はサーバ描画しない**: 管理画面に SSR・hydration・HonoX islands は使わない。HTML は空の起動ページ 1 枚で、React は `createRoot` で起動する。
- **見た目はモックに合わせ、項目は v6 が解釈するキーに絞る**: モックは旧 v4 の画面で、v6 に無い機能の項目が多い。レイアウト・配色・部品・文言の体裁はモックに合わせ、入力項目は v6 が実際に読む設定キーと設計書の新キーにする（§4.3）。書かれていないキーは保持する。

## 3. 認証

### 3.1 流れ

```
/admin/ へアクセス
  ├ 通常セッション（__Host-ch3s_sid）が無い → /auth/?mode=login&return=/admin/ へ 302
  ├ 通常セッションはあるが役割なし → 403 画面（「管理権限がありません」）。ただし `/admin/bootstrap` は例外で起動ページを返す（§3.4）
  └ 役割あり → SPA の起動ページを返す
SPA が GET /admin/api/me
  ├ 管理者セッション（__Host-ch3s_admin）が有効 → 画面を表示
  └ 無効・期限切れ → 再認証画面
      ├ ID/Password: パスワード入力 → POST /admin/api/elevate
      └ Google: POST /auth/google/start（purpose=admin）→ callback（§3.1.1）で管理者セッションを発行 → /admin/ へ
```

#### 3.1.1 Google による再認証

- `start` は `purpose=admin` のとき、法的投稿確認と端末申告を求めない。署名付き Cookie `ch3s_oauth` に `purpose=admin` と、現在の通常セッションの `aid` を入れる。Google へは `max_age=0` を付け、`prompt=select_account` で送る。
- `callback` は `purpose=admin` のとき、`subHash` から引いた `aid` が `ch3s_oauth` の `aid` と一致することを確かめる。一致しなければ拒否する（別の Google アカウントでの再認証を防ぐ）。通常セッションは発行し直さず、管理者 Cookie だけを発行する。
- `id_token` の `auth_time` が 10 分以内であることを確かめる。**Google が `max_age` に応じて `auth_time` を返すかは未確認**で、段 M2 の前に実機で確かめる。返らない場合、Google のアカウントは TOTP（§3.3）を登録しない限り管理者セッションを得られないことにする（§10-10）。
- 再認証の前に未保存の入力があれば、離脱前の確認（§4.2）で止める。

### 3.2 管理者セッション

| 項目 | 値 |
| --- | --- |
| Cookie | `__Host-ch3s_admin`。`Path=/; Secure; HttpOnly; SameSite=Strict`。`Max-Age` を付けない（ブラウザのセッション復元で残る場合があるので、期限はサーバで判定する） |
| 値 | `1.{aid}.{adminGen}.{iat}.{last}.{sig}`。時刻は UNIX 秒。`adminGen` は Account DO の管理者セッション世代（通常セッションの `gen` とは別）。`sig` は secret `ADMIN_SESSION_SECRET`（`AUTH_SECRET` とは別鍵）の HMAC-SHA256 |
| 有効期限 | 無操作 30 分、発行から最長 8 時間。API 応答のたびに `last` を更新して発行し直す |
| 検証 | 毎リクエスト、署名・期限を見たうえで `Account.describeForAdmin(aid, gen, adminGen)` を 1 回呼び、状態（`active` 以外は拒否）・現在の役割・両方の世代の一致を確かめる。役割の剥奪・凍結・通常セッションのログアウト（`gen` 進行）・管理者のログアウト（`adminGen` 進行）は次のリクエストから効く |
| 発行条件 | 通常セッションの `aid` と一致し、役割ありで、再認証が 10 分以内。ID/Password はパスワード規則（§3.3）を満たすこと |
| 終了 | `POST /admin/api/logout` で `adminGen` を進め、管理者 Cookie を消す（盗まれた Cookie もその時点で無効になる）。通常セッションは残す |
| 鍵の更新 | `ADMIN_SESSION_SECRET` は新旧 2 本（`ADMIN_SESSION_SECRET` / `ADMIN_SESSION_SECRET_PREV`）で検証し、発行は新しい鍵だけにする。8 時間後に旧鍵を外す |

再認証の試行は設計書 §3.9 のロック（（アカウント, keyIp）単位）と `AuthRate` DO の計数に含める。加えて、**アカウント単位で 1 日 20 回失敗したら管理者セッションの発行を 24 時間止め**、監査ログに残して他の `admin` に画面上で知らせる（IP を分散した総当たりへの対策）。

### 3.3 管理者アカウントの要件

- 種別は ID/Password または Google（設計書 §6.1。クライアント ID には役割を付けられない）。
- 役割ありの ID/Password アカウントは、**パスワード 16 文字以上**を必須にする（全役割。Basic 認証の長い共有パスワードより弱くしないため）。保存値は PBKDF2 のハッシュで長さが分からないので、**平文が手に入る時点で検査する**: 再認証（elevate）と bootstrap ではパスワードの再入力を必須にしてそこで検査し、満たさなければ管理者セッションを出さずパスワード変更へ誘導する。役割ありの人のパスワード変更（`/auth/password`）にも同じ基準を当てる。
- **役割ありのアカウントはメールによるパスワード再設定（設計書 §3.9）を使えない**。メールを握った人が管理者になれる経路を作らないため。忘れた場合は別の `admin` がパスワードの再設定を許可する（役割を外したうえで再設定させ、本人確認後に役割を戻す）か、`admin` が自分しかいなければ bootstrap の非常用経路（§3.4）を使う。いずれも監査ログに残す。
- 二要素認証（TOTP）は段 M4 で追加を検討する（§10-3）。ただし Google の再認証で `auth_time` が取れない場合は、Google の管理者に TOTP が必須になる（§3.1.1）。Google 連携は Google 側の 2 段階認証にも委ねる。

### 3.4 初回の管理者と非常用経路

アカウント方式には「最初の `admin` をどう作るか」と「全管理者がログインできなくなったとき」の経路が要る。

- secret `ADMIN_BOOTSTRAP_TOKEN`（`wrangler secret put`。現行の `ADMIN_PASSWORD` はこれに置き換えて廃止）を使う。
- `/admin/bootstrap`: 通常セッションでログインしたアカウント（ID/Password または Google）が、トークン（と、ID/Password ならパスワードの再入力）を入力すると `admin` 役割を得る。この画面の起動ページ・資産と `POST /admin/api/bootstrap` だけは役割の検査から外す（§3.1・§5.3）。
- 使えるのは、**有効な `admin` が 0 件のとき**、または vars `ADMIN_BREAK_GLASS_UNTIL=<UNIX 秒>` が現在より先の時刻のときだけ。後者は期限付きなので外し忘れても閉じる（現在から 24 時間より先の値は無視する）。
- **判定と確保は 1 か所で原子的に行う**: `AccountIndex("role:admin")` の `claimFirstAdmin(aid, now, breakGlass)` が `blockConcurrencyWhile` の中で「`confirmed` かつ対象アカウントが `active` の件数が 0（または非常用期間中）」を確かめ、その `aid` を `pending` で登録する。続けて `Account.setRoles` → 索引の `confirm` の順に行い、失敗したら `pending` を取り消す。同時に 2 人が bootstrap しても `admin` になるのは 1 人だけ。凍結・退会したアカウントの索引は件数に数えない（`describe` で状態を確かめ、`active` でなければ索引から外す）。
- トークンは 32 バイト以上の乱数（base64url で 43 文字以上）とし、短ければ bootstrap 自体を無効にする。照合は両者の SHA-256 を定数時間比較する。
- 試行は `keyIp` ごとに 5 回/時（`AuthRate`）。成功・失敗とも監査ログに残す。
- 開発環境では `.dev.vars` に `ADMIN_BOOTSTRAP_TOKEN` を置き、`http://localhost:8787` でアカウントを作って bootstrap する（`__Host-` Cookie のため 127.0.0.1 ではなく localhost を使う）。Turnstile は Cloudflare のテスト用キーを使う。

### 3.5 CSRF・オリジン

- 変更系（`GET` 以外）の API は、`Origin` が自オリジンと一致し、`Content-Type: application/json` で、ヘッダ `X-Ch3s-Admin: 1` を持つ場合だけ受け付ける。フォーム送信や単純リクエストでは満たせず、他オリジンからはプリフライトで止まる。
- 管理者 Cookie は `SameSite=Strict`。
- 入力上限 256KB は維持する。
- **残るリスク**: 管理画面は公開ページ（`3chiko.html`・板 top。インラインスクリプトあり。KB-30）と同じオリジンにある。公開ページで XSS が起きると、有効な管理者セッションがある間は API を操作されうる（独自ヘッダも Origin も満たせる）。Basic 認証の現行にもある問題で、本計画では解消しない。管理画面を別ホスト（`admin.` サブドメイン。`__Host-` Cookie はホスト単位なので分離できる）に置く案を §10-11 で比較する。

### 3.6 監査ログ

- 新 DO `AdminAudit`（`idFromName("global")`）に、操作者の `aid` と `uid`、役割、操作名、対象（板・key・ファイル名・アカウント）、結果、時刻、`keyIp` のハッシュを追記する。64 件ごとのページで、10,000 件を超えたら最古のページを消す。
- 記録するのは変更系の全操作、再認証の成否、bootstrap、**投稿者ログ（IP を含む）とアカウント詳細の閲覧**。それ以外の閲覧は記録しない。
- 記録するのは操作の結果（成功・失敗とその理由）。監査ログの書き込みに失敗しても操作は失敗させず、Workers Logs に `console.error` で残す（監査 DO の障害で管理操作が止まらないようにする）。
- DO の追加は `wrangler.jsonc` の migrations に `{tag: "v3", new_sqlite_classes: ["AdminAudit"]}` として足す（設計書の v2 の後）。
- 画面 `/admin/audit`（`admin` のみ）で新しい順に見る。

## 4. 画面

### 4.1 画面と役割

| 画面 | URL | 必要な役割 | 内容 |
| --- | --- | --- | --- |
| トップ | `/admin/` | いずれか | 板の選択（R2 の `*/SETTING.TXT` を列挙）、役割に応じたメニュー |
| 板設定 | `/admin/boards/:bbs` | `admin` | モックの画面。`SETTING.TXT` と `head.txt`。§4.3 |
| スレ一覧 | `/admin/boards/:bbs/threads` | `delete` | 公開中・削除/移動済み・key 直接指定（現行と同じ） |
| スレ詳細 | `/admin/boards/:bbs/threads/:key` | `delete` | 停止・再開・削除・移動・レス編集・レス削除・dat と投稿者ログの閲覧（投稿者ログの閲覧は監査ログに残す） |
| 規制ルール | `/admin/rules/access`・`/admin/rules/ngwords` | `regulate` | `deny.cgi`・`ngwords.cgi`。書式の説明はモックの「書き方」枠の体裁で現行の書式を書く |
| 板一覧 | `/admin/menu` | `admin` | `bbsmenu.html` |
| ユーザー一覧 | `/admin/accounts` | `admin`・`regulate` | 直近書き込み順（ほかに登録順・Lv 順・24 時間の書き込み数順）。各行に最終書き込みの日時・書き込み先・本文の先頭・接続元、24 時間の書き込み数、UA・端末・キーの数。種別・状態・役割で絞り込み、ユーザー ID（`uid`。ID/Password はログイン ID と同じ値）・メール・AS・UA・端末・識別子で検索。乱造検知の要確認を色で示す。モック `docs/ui/admin-users.html` |
| ユーザー詳細 | `/admin/accounts/:uid` | `admin`・`regulate`（役割ありのアカウントの操作は `admin` だけ） | アカウントの概要と操作（凍結・BAN・Lv 固定・役割・全キー無効化・メモ）、使われた AS・地域、UA（生の文字列も）、端末情報、日ごとの識別子、認証キーの一覧（キーごとの書き込み数と無効化）、書き込み一覧（すべて・Web・キーごとのタブ）。閲覧は監査ログに残す。役割の付与・剥奪は段 M2 から（§7）。モック `docs/ui/admin-user.html` |
| キャップ | `/admin/caps` | `admin` | 全件の一覧・他アカウント宛の発行・失効・旧キャップ（KV `CAP`）の紐づけ。自分のキャップ（1 個まで）は `/auth/account` で扱う（設計書 §6.2・§10） |
| PHOEBE・エッジ防御 | `/admin/phoebe` | `regulate` | `phoebe.cgi`・`edge-rules.json`・乱造の要確認一覧 |
| 監査ログ | `/admin/audit` | `admin` | §3.6 |
| 初回設定 | `/admin/bootstrap` | 通常セッションのみ | §3.4 |

表示できない画面へのリンクはメニューに出さない。API 側でも必ず役割を検査する（画面の出し分けは利便のためで、防御ではない）。
役割と操作の対応は `AdminRole` の表 1 つで決め、自分より上位の役割を持つアカウントへの操作（`regulate` による `admin` の凍結など）は拒否する。
レス編集は本文の改変なので `delete` に含めるが、監査ログに編集前後の本文を残す。

### 4.2 見た目（モックの取り込み）

**2026-11-05 改訂**: 見た目は現行の板トップ（ヘッダー・配色・入力欄・タブ・サイドバー・フッター）に揃える。`kanri.html` からは 2 列構成・項目の並び・「書き方」枠・上下の「適用」を引き継ぎ、色の帯は各グループ左端の色線に置き換える。
モックは `docs/ui/admin-board.html`（板設定）・`admin-thread.html`（スレ管理）・`admin-reauth.html`（再認証）で、共通 CSS は `docs/ui/auth-admin.css`。
実装では `a.css` ではなく板トップと同じ Tailwind のクラスで書き、下の表の `a.css` 由来の記述は構成の対応として読む。

**UI の方針**（設計書 §3.8 と同じ）: 角丸を使わない（`border-radius` を付けず四角にする）。部品と文言は既存画面（板トップ・スレ画面）に合わせ、
文言は全角と半角英数字の間に空白を入れない（例「残り27分」「Lvを固定」）。説明は短くする。

モックの構造を次のように写す。CSS は `a.css` を元にした `admin-web/admin.css` 1 本にし、色や余白はすべてクラスで指定する。
HTML の `style` 属性は CSP（`style-src 'self'`）で弾かれ、`bgcolor` 属性は HTML5 で廃止されているため使わない（React の `style` プロパティは CSSOM 経由で CSP の対象外だが、見た目の定義を CSS に集めるために使わない）。
モックで使われているクラスは `btn` 系と `notice mt5` だけで、`mt5`（上余白 5px）は `a.css` に無いので補う。モックの `@font-face`（Saitamaar。AA 用）は管理画面では使わない。

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

**モックからの変更**（§10-12 で確認する）: 上部のバー、960px 未満での 1 列化、モックに無い画面（トップ・スレ一覧・スレ詳細・規制ルール・板一覧・アカウント・キャップ・PHOEBE・監査・再認証・bootstrap）。これらは共通レイアウト（上部バー＋左のメニュー列＋本文）に載せ、本文の部品（見出しの黒帯 `section-subtitle`、ラベル行、`form-control`、`btn-primary`、白枠の「書き方」）はモックと同じものを使う。

**未保存の確認**: scalajs-react の Router には画面離脱を止める機能が無いので、`beforeunload` と、内部リンク・メニューのクリック時の確認ダイアログを自前で実装する。Google の再認証（全画面の遷移）の前も同じ確認を出す。

### 4.3 板設定の項目対応

モックの項目を v6 の設定キーに対応づける。**対応なし**の項目は表示しない（v6 で効かない設定を見せない）。
表に無いキーや下記で「その他」に回すキーは「その他の設定（直接編集）」欄に `KEY=VALUE` の行で出し、保存時もそのまま残す。

| モックの項目 | v6 のキー | 入力 | 扱い |
| --- | --- | --- | --- |
| 掲示板タイトル | `BBS_TITLE` | テキスト | 対応 |
| 名前未入力時の名前 | `BBS_NONAME_NAME` | テキスト | 対応 |
| 現行スレッド保持数 | `BBS_MAX_MENU_THREAD` | 数値 | 対応。補足は v6 の挙動（「subject.txt に載せるスレ数。超えた分は一覧から外れるだけで過去ログ化はしない」）に書き換える。キーが無いときの実効値は 700（既定の SETTING.TXT は 300） |
| 強制sageまでの秒数 | `BBS_FORCE_SAGE` | 数値 | 対応 |
| スレッドタイトル・名前・メール・本文の文字数制限 | `BBS_SUBJECT_COUNT`・`BBS_NAME_COUNT`・`BBS_MAIL_COUNT`・`BBS_MESSAGE_COUNT` | 数値 | 対応 |
| 投稿行数制限 | `BBS_LINE_NUMBER` | 数値 | 対応。補足は v6 の規則（「>>2 以降、この値の 2 倍の `<br>` 区切り数まで」）に書き換える |
| IDの方式・発信元表示 | `BBS_ID` | — | 対応なし。日付欄の ID は廃止済みで v6 は読まない（[トリップ・ID廃止とスレ主表示](トリップ・ID廃止とスレ主表示.md)）。キーが残っていれば「その他」に回す |
| 県名・回線表示 | `BBS_ID_SHIKIBETSU` | ラジオ（`checked` / `raw` / `ken` / `city` / `as` / 非表示、設計書 段 C 以降は `phoebe`） | 対応。非表示は `d` を書く（コードは 5 値以外をすべて非表示として扱う） |
| （モックに無い） | `BBS_ID_GROUP`・`BBS_ID_RESET` | テキスト・数値 | 「識別子のグループ・リセット周期」として同じ体裁で追加。`BBS_ID_LV` は ID とともに廃止済みなので「その他」に回す |
| 個別規制 | `BBS_NO_MADAKANA` | ラジオ | 対応（`checked` で規制の代わりに注意表示） |
| （モックに無い） | `BBS_NGWORD_PASS` | チェック | 同じ体裁で追加 |
| 連投規制の緩和（実況モード） | `BBS_LIVE` | ラジオ | 対応。補足は「現在は 9 割の投稿を強制 sage。設計書 段 A 以降は連投規制の適用除外も加わる」 |
| 一般からの投稿を停止 | `BBS_HEISA` | チェック | 対応。補足は v6 の挙動（「新規スレ作成・返信とも拒否。キャップも拒否」）に書き換える |
| 同一IP群からのスレッド作成規制数・広域スレッド作成規制数 | `BBS_THREAD_TATESUGI(_RESET)`・`BBS_THREAD_JUNBAN(_RESET)`・`BBS_THREAD_JUNBAN_AS_ONLY` | 数値・テキスト | 対応（現行のスレ立て規制フォームの検証を流用） |
| 掲示板全体の連続投稿規制（`timecount` 回中 `timeclose` 回） | `timecount`・`timeclose` | 数値 2 つ | 設計書 §8.2。設計書 段 A（連投規制）以降に表示 |
| 短時間投稿規制秒数 | `BBS_SAMBA24` | 数値 | 設計書 §8.3。設計書 段 A 以降 |
| 広域連投規制秒数 | `BBS_FR_SECOND` | 数値 | 設計書 §8.3a。設計書 段 A 以降 |
| 各スレッド内での連続投稿規制 | `BBS_THREAD_RENTOU`・`BBS_THREAD_RENTOU_RESET` | 数値 2 つ | 設計書 §8.4。設計書 段 A 以降 |
| 連投規制類（レベル） | `BBS_FR_LEVEL`・`BBS_RENTOU_KEYS`・`BBS_RENTOU_PASS` | ラジオ・数値・チェック群・選択 | 設計書 §8.1・§7。設計書 段 A 以降 |
| 投稿時のログインを必須にする | `BBS_PHOEBE` | 数値（0〜10） | 設計書 §4.4。設計書 段 C 以降。ラベルは「書き込みに必要な最低 Lv」に変える（Web 版は常にログイン必須で、この値が効くのは専ブラの未ログインと Lv。設計書 §3.5） |
| スレッド作成主機能 | `BBS_DISABLE_NUSI` | ラジオ | 対応。ラベルは「スレ主の機能」。名前欄のスレ主表示 ` (主)`（実装済み）と、設計書 §4.4 のスレ主のコマンド（段 C 以降）を止める |
| （モックに無い） | `BBS_THREAD_PASS` | 選択 | 設計書 §7。設計書 段 D 以降 |
| ヘッダー（textarea） | `head.txt` | textarea | 対応 |
| 投稿規制設定・NGワード（textarea） | `deny.cgi`・`ngwords.cgi` | 読み取り表示 | 両ファイルは全板共通なので板設定画面では編集しない。板の正規表現がこの板に一致する行だけを表示し、規制ルール画面（`regulate`）へのリンクを置く |
| スレッド作成規制設定（textarea） | `deny.cgi` の `TateDame` 行 | 読み取り表示 | 同上 |
| キャップ（textarea） | — | — | パスワードを平文で並べる方式は採らない。キャップ画面（§4.1）へのリンクにする |
| スレッド一覧を直接編集（ボタン） | — | — | スレ一覧画面へ |
| 直近のエラーログを閲覧（ボタン） | — | — | 対応なし（§10-6） |
| 連絡先Email・パスワード | — | — | 対応なし。板ごとの管理パスワードは役割に置き換わる |
| タイトル画像・背景画像・ホームページURL・各種の色・UIスタイル・1ページ中のスレッド数・掲載投稿数 | — | — | 対応なし（v6 の板 top は使わない。新 UI のテーマ設定は別途） |
| 削除された投稿に表示される内容・レスオーバー時のメッセージ・告知欄 | — | — | 対応なし（あぼーん文言と 1000 件の文言は固定） |
| UNICODE・サードパーティー製アプリ・海外ホスト・Proxy 規制・逆引き・名前入力必須・日本語必須・画像/リンク禁止・アイコン・AA・検索掲載・記者制度・ID の割り当て単位（スレごと・1 分ごと・常時表示/非表示）・`BBS_SOKO`・過去ログ化（秒数・投稿数・継続・有効秒数）・同一タイトル規制・リセット時間・レス数上限・各スレッド内のレス間隔・設定変更コマンド類・ログイン機能・BAN・偽造検出・識別認証用鍵・範囲チェック・なりすまし防止・設定強制 | — | — | 対応なし |
| 名前を無効化 | `BBS_FORCE_NONAME` | — | キーは既定の SETTING.TXT にあるが v6 は読まない。フォームには出さず「その他」に回す |
| スレッド作成間隔制限秒数 | `BBS_CREATETHREAD_INTERVAL` | — | 同上 |
| （旧 `timecover`。モックでは「各スレッド内での連続投稿規制」の欄名） | `timecover` | — | 同上。設計書 §8.2 で「読まない」。モックの同欄は `BBS_THREAD_RENTOU` に割り当てる（上記） |
| （モックに無い。既定の SETTING.TXT にはあるが v6 が読まない） | `BBS_ADD_THREAD`・`BBS_SHIKIBETSU`・`BBS_COPIPE`・`BBS_COOK82`・`BBS_COOK82_POST_LIMIT`・`BBS_KISEIOFF`・`BBS_THREAD_SETTINGS`・`BBS_ARR` | — | 「その他の設定（直接編集）」に出す |

**未設定と明示値**: 各項目は「未設定（既定値 n を表示）」と「明示値」を区別して持つ。保存時は、明示値の項目と、未設定から値を入れた項目だけを書き出し、触っていない未設定の項目は書き出さない（KB-12 によりコードの既定値がそのまま効くので、既定値の変更が保存した板にも届くようにするため。設計書 §8.1 の `BBS_FR_LEVEL` はキー無しなら `1` で広域連投規制も有効、明示した空の値なら `0` で広域連投規制だけを除外する）。明示値を未設定に戻す操作（「既定に戻す」）も置く。

**読み方の基準**: `SETTING.TXT` の解析は `develop` で統一された `BbsSettings`（`create` / `parse`）だけを使う（最初の `=` で分割、キー・値を trim、重複は後勝ち）。行順を保ち、`=` の無い行（コメントなど）は「その他」欄にそのまま残す。フォームの項目と同じキーを「その他」欄に書いたら保存を拒否する（`admin:invalid`）。

**保存の手順**: 読込時に `SETTING.TXT` と `head.txt` の両方の ETag を返し、保存では両方を ETag 条件付きで書く。先に `SETTING.TXT`、次に `head.txt` の順で書き、`SETTING.TXT` が競合したら何も書かずに 409 を返す。`head.txt` だけが競合したら部分成功（`SETTING.TXT` は保存済み）として返し、元に戻す処理はしない（間に保存された他人の `head.txt` を消さないため）。未作成のファイルは「存在しない場合だけ作る」条件（R2 の `etagDoesNotMatch: "*"`）で書く。ローカルの wrangler（workerd）で効くことを段 M0 で確かめた。本番の R2 では未確認。
検証はサーバ側（`bbs` の `domain/admin/BoardSettingsCheck`）で行い、スレ立て規制は `ThreadCreationSettings`、ほかは項目の種類（数値・候補・チェック）と `BBS_FR_LEVEL`・`BBS_PHOEBE` の値の範囲を見る。`common` の `BoardSettingsForm` は項目の定義と `SETTING.TXT` との相互変換だけを持ち、検証を持たない（`ThreadCreationSettings` は `bbs` の型に依存するため）。`KEY=VALUE` の値に改行を入れさせない。
現行の直接保存（`head.txt` と `SETTING.TXT` を無条件で書く）から条件付き保存への変更は、挙動の変更として段 M0 の独立コミットにした。HTML の画面はフォームに読込時の ETag を持たせ、ETag の項目が無い送信（従来の画面・手書きの要求）は条件なしで保存する（この扱いは**承認済み（2026-11-07 利用者）**）。保存の順も SETTING.TXT、head.txt に揃えた。

**保存時の行順**（M0 で決めた規則）: 保存時に読んだ SETTING.TXT（無ければ既定の内容）を下敷きにし、フォームの項目はキーの最初の行の位置に、「その他」の行は元の「その他」の行の位置に順に入れる。余った行と元に無い項目は末尾に足し、重複した項目の行と空行は書かない。変更しなければ元の内容と同じになる。

## 5. SPA の構成

### 5.1 プロジェクト

```
common/src/main/scala/io/_3ch/common/admin/
  Json.scala              依存ライブラリを使わない JSON の値・読み書き（§10-4）
  AdminDto.scala          API の要求・応答の型（板設定、スレ、ルール、アカウント、監査など）
  AdminCodec.scala        DTO と JSON の相互変換
  AdminRole.scala         役割と画面・操作の対応表（§4.1）。サーバとクライアントで共有
  BoardSettingsForm.scala 板設定の項目定義（キー・ラベル・入力種別・既定値。帯の色・表示条件は M1）と SETTING.TXT との相互変換（検証はサーバ側）
  ResEditText.scala       dat のフィールドと編集用テキストの相互変換（現行 AdminHandler.editable の移設）
admin-client/src/main/scala/io/_3ch/admin/
  Main.scala              createRoot で起動
  AdminApp.scala          ルータとレイアウト
  AdminRoutes.scala       scalajs-react extra の Router 定義（URL ↔ ページ）
  api/AdminApiClient.scala fetch ラッパ（X-Ch3s-Admin、JSON、401 で再認証画面へ）
  pages/                  Top, BoardSettings, Threads, Thread, Rules, Menu, Accounts, Account, Caps, Phoebe, Audit, Bootstrap, Elevate
  components/             Layout, Band, FieldRow, HelpBox, SaveBar, ErrorBanner, ConfirmDialog
admin-web/
  index.html              マウント先と CSS・JS の参照だけ
  main.js                 import "./admin.css"; import "scalajs:main.js";
  admin.css               Tailwind の読み込み（app/style.css と同じ @source）と管理画面固有の少数の指定
vite.admin.config.ts      管理画面専用ビルド（HonoX なし。Tailwind は板トップと同じ設定）
bbs/src/main/scala/io/_3ch/bbs/
  handler/admin/AdminAuth.scala     管理者セッション・再認証・役割検査・Origin 検査
  handler/admin/AdminApi.scala      JSON の入出力とルーティング（M0。認証は AdminHandler の Basic 認証を通った後）
  handler/admin/AdminAssets.scala   起動ページと資産の配信
  application/admin/AdminService.scala  板設定・スレ操作・ルール・メニュー・アカウント操作（ports/AdminStores の口経由。実装は repository/AdminRepository）
  domain/admin/BoardSettingsCheck.scala・BoardLinks.scala  板設定の検証、bbsmenu.html のリンク先の板名（M0）
  durable/codec/ThreadModerationCodec.scala  Thread.moderate の引数と inspect の結果の変換（M0）
  durable/AdminAudit.scala、domain/admin/AuditLog.scala   監査ログ
```

- `adminClient` は `commonJS` にだけ依存し、`bbs` には依存しない（サーバ実装をブラウザ用バンドルに混ぜない。KB-27 の教訓）。
- 依存: scalajs-react `core` と `extra`（Router）`4.0.0`（`bbs` と同じ版）。`extra` 4.0.0 の配布と React 19 での動作は、段 M1 の最初に小さな試作で確かめる（未確認）。
- 状態は画面ごとの `useState` 系と、`LoadState`（`Loading` / `Ready` / `Failed`）と `SaveState`（`Idle` / `Saving` / `Saved` / `Conflict` / `Invalid(errors)`）の enum で持つ。全体で共有するのはログイン中の利用者（`me`）だけ。
- 外部の React 部品ライブラリは使わない（facade が要るため）。

### 5.2 ビルド

- `npm run v6:build` に `vite build --config vite.admin.config.ts` を足し、`dist/admin/`（`index.html`・`assets/*.js`・`assets/*.css`）へ出す。設定は `root: "admin-web"`、`base: "/admin/"`（無いと資産を `/assets/*` で参照して 404 になる）、`publicDir: false`（無いと `public/3chiko.html` が複製される）、`build.outDir: "../dist/admin"`、`emptyOutDir: true`（`dist/admin/` だけを空にする）。
- 実行順は client → workers → admin。workers ビルド（`@hono/vite-build`）が `dist/` を空にするかは未確認なので、admin を最後にして、`dist/admin/index.html` の存在をビルド後に検査する。`vite.config.ts` にある Scala.js プロジェクトの直列化ラッパーを流用する。
- sbt は `adminClient/fastLinkJS`・`fullLinkJS` を既存ビルドと直列に実行する（`docs/client-build-review.md` の方針どおり sbt を同時起動しない）。

### 5.3 配信

- `wrangler.jsonc` の `assets` に `run_worker_first: ["/admin", "/admin/*"]` を足し、`/admin` 配下は必ず Worker を通す（Static Assets が先に返すと認証を飛ばせるため）。
- `AdminAssets` は §3.1 の条件（通常セッション＋役割あり。`/admin/bootstrap` と資産は通常セッションだけ）を満たすときだけ `env.ASSETS.fetch` で `dist/admin/` の起動ページ・資産を返す。画面 URL（`/admin/boards/test` 等）はすべて `new Request(new URL("/admin/index.html", request.url), request)` で起動ページを取得して返し（`html_handling: "none"` のまま。`3chiko.html` と同じ取得方法）、ルーティングはクライアントで行う。
- `/admin/api/*` は JSON だけを返し、起動ページへフォールバックしない。存在しない API は 404 の JSON。
- KB-28（`dist` の Worker バンドルが資産として取れる）の修正とは独立だが、`dist/admin/` を Worker 経由に限ることで同じ問題を管理画面に持ち込まない。

### 5.4 応答ヘッダ

| 対象 | ヘッダ |
| --- | --- |
| 起動ページ・API | `cache-control: no-store`、`x-content-type-options: nosniff`、`referrer-policy: no-referrer` |
| 起動ページ | CSP `default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; form-action 'self' https://accounts.google.com; frame-ancestors 'none'; base-uri 'none'` |
| 資産（ハッシュ付きファイル名） | `cache-control: private, max-age=31536000, immutable`。`ASSETS.fetch` の応答はヘッダが変更できないので、`new Response(body, response)` で複製してから付け替える |

## 6. API

### 6.1 一覧

| メソッドとパス | 役割 | 内容 |
| --- | --- | --- |
| `GET /admin/api/me` | （管理者セッション） | `{uid, roles, expiresAt}`。無効なら 401 `{code:"admin:reauth"}` |
| `POST /admin/api/elevate` | 通常セッション | `{password}` を検証して管理者セッションを発行。Google は §3.1 |
| `POST /admin/api/logout` | — | 管理者 Cookie を消す |
| `POST /admin/api/bootstrap` | 通常セッション | `{token}`。§3.4 |
| `GET /admin/api/boards` | いずれか | **M0 実装済み**。`{boards: [...]}`。R2 を `delimiter: "/"` で列挙した接頭辞（ページを辿る）と、`bbsmenu.html` のリンク先（`/板名/` の形）の和集合を、板名の規則（英数字・`_`・`-` の 1〜80 文字）で絞って並べる。`SETTING.TXT` の有無は確かめない（無い板も既定値で動いているため）。画面では板名の手入力も残す。R2 の facade に `list` を足した |
| `GET /admin/api/boards/:bbs/settings` | `admin` | **M0 実装済み**。`{bbs, fields, extra, head, settingsEtag, headEtag, rules: {access, ngwords}}`。`fields` は項目ごとに `{value, explicit}`（未設定は既定値と `explicit: false`）、`extra` は「その他」の行の配列。ETag はファイルが無ければ `null`（`SETTING.TXT` が無い板は既定の内容を明示値として返す）。`rules` はこの板に一致する行（`ModerationRuleSet.linesFor`。JS RegExp で判定） |
| `PUT /admin/api/boards/:bbs/settings` | `admin` | **M0 実装済み**。`{fields, extra, head, settingsEtag, headEtag}`（ETag のキーは必須で、読込時に無ければ `null`。`fields` に無い項目は未設定）。応答は `{settingsEtag, headEtag, partial}`。競合は 409 `admin:conflict`、`head.txt` だけの競合は 200 `{partial: true}`（§4.3）。検証の誤りは 400 `admin:invalid` の `fields` に `fields.キー`・`extra` ごとの理由 |
| `GET /admin/api/boards/:bbs/threads` | `delete` | **M0 実装済み**。`{bbs, subject: [{key, line}], removed: [key]}` |
| `GET /admin/api/boards/:bbs/threads/:key` | `delete` | **M0 実装済み**。`{bbs, key, hidden, stopped, movedTo, movingTo, res, dat, log, legacyLog, settings}`。`res` は行ごとの `{number, name, mail, date, message, edit: {name, mail, message}}`（`edit` は `ResEditText` の編集用テキスト）。dat が無ければ 404。投稿者ログの閲覧の監査ログは M2 |
| `POST /admin/api/boards/:bbs/threads/:key/actions` | `delete` | **M0 実装済み**。`{action: "stop"|"resume"|"delete"|"move"|"editRes"|"deleteRes", ...}`（`move` は `targetBoard`・`targetKey`、`editRes` は `number`・`name`・`mail`・`message`、`deleteRes` は `number`）。応答は `{ok: true}`。現行どおり `inspect` を先に呼んで旧データを取り込んでから `Thread.moderate` に渡す。レス番号 1 未満・名前とメールの改行・本文の単独の CR・移動先の書式は 400、DO の拒否は 409 `admin:thread-state` |
| `GET`・`PUT /admin/api/rules/:name` | `regulate` | **M0 実装済み**。`name` は `access` / `ngwords`。本文は `{text, etag}`。`PUT` は `ModerationRuleSet.parse` で検証（誤りは `fields.text` に行番号付き）し、ETag 条件付きで保存。応答は `{etag}` |
| `GET`・`PUT /admin/api/menu` | `admin` | **M0 実装済み**。`bbsmenu.html`。本文は `{text, etag}`、ETag 条件付きで保存 |
| `PUT /admin/api/accounts/:uid/roles` | `admin` | 役割の付与・剥奪（段 M2 から。自分の `admin` は外せない。最後の 1 人の `admin` も外せない） |
| `GET /admin/api/audit?before=` | `admin` | 監査ログのページ |
| `GET /admin/api/accounts?sort=&kind=&status=&role=&q=&cursor=` | `admin`・`regulate` | ユーザー一覧（`AccountDirectory.list`）。`sort` は `recent`（既定）/ `created` / `lv` / `posts24h`。`q` は種類を判定して `AccountIndex` か `AccountDirectory.search` で引く |
| `GET /admin/api/accounts/:uid` | `admin`・`regulate` | ユーザー詳細（`Account.inspect`: 概要・`seen`・`tokens`）。閲覧を監査ログに残す |
| `GET /admin/api/accounts/:uid/posts?via=&tokenId=&before=` | `admin`・`regulate` | 書き込み記録のページ（`log`） |
| `POST /admin/api/accounts/:uid/actions` | `admin`・`regulate` | `{action: "freeze"|"unfreeze"|"ban"|"setLv"|"revokeToken"|"revokeAllTokens"|"note", ...}`。役割ありのアカウントへは `admin` だけ |
| キャップ・PHOEBE | 設計書 §11 に従う | 段 M3 で定義 |

### 6.2 共通規則

- 要求・応答は UTF-8 の JSON。変更系は §3.5 の条件を満たさなければ 403 `{code:"admin:origin"}`（M1 から。M0 は `Content-Type: application/json` だけを求め、満たさなければ 415）。
- M0（Basic 認証）の間は、資格情報が無ければ 401 `admin:unauthorized`（`WWW-Authenticate` 付き）、`ADMIN_PASSWORD` が無ければ 503 `admin:unavailable` を JSON で返す。M2 で `admin:reauth` に置き換える。
- エラーは `{code, message, fields?}`。`code` は `admin:reauth`（401。管理者セッションが無い・期限切れ）・`admin:reauth-failed`（401。再認証の失敗）・`admin:forbidden`（403）・`admin:origin`（403。Origin 不一致）・`admin:content-type`（415）・`admin:method`（405）・`admin:bootstrap-closed`（403）・`admin:rate-limited`（429）・`admin:invalid`（400、`fields` に項目ごとの理由）・`admin:conflict`（409）・`admin:thread-state`（409。スレの停止・移動中など DO が拒否した操作）・`admin:too-large`（413）・`admin:not-found`（404）・`admin:internal`（500）。`message` は日本語の利用者向け文言で、サーバの `AdminText` が組み立てる。
- `Thread.moderate` は理由を日本語の例外文言で返すので、当面は `admin:thread-state` の `message` にその文言をそのまま入れる。DO をコードで返す形に変えるのは別の独立コミットにする（AGENTS.md 3.5 の方針への移行）。
- 役割の検査は `AdminRole.allows(roles, operation)` だけで行い、各 API で個別に書かない。
- DO・R2 の操作は `AdminService` 経由で、DO の新しい RPC は増やさない（段 M3 のアカウント系を除く）。`Thread.moderate` の `js.Dynamic` 引数は `durable/codec/` のコーデックで作る（AGENTS.md 3.5）。

## 7. 段階

各段で `sbt test`、`npm run v6:build`、`npm run v6:typecheck`、`npm test`、`sbt 'set ThisBuild / scalacOptions += "-no-indent"' Test/compile` を通し、AGENTS.md 3.2 のレビュー連鎖を行う。

| 段 | 内容 | 前提 | 完了条件 |
| --- | --- | --- | --- |
| M0（完了） | **API 化**: `AdminService`・`AdminApi`・`AdminDto` / `AdminCodec`・`BoardSettingsForm`・`ResEditText` を作り、`/admin/api/*` を Basic 認証のまま追加する。既存の HTML 画面はそのまま残し、内部で `AdminService` を共用する。板設定・`head.txt`・`bbsmenu.html`・規制ルールの ETag 条件付き保存への変更は独立コミット | — | 既存の `AdminHandlerSuite`・統合テストが、条件付き保存の変更分を除いて通る。`AdminApiSuite` で全 API の正常系・検証エラー・ETag 競合・CR/LF 拒否・入力上限・未設定と明示値の往復を確認 |
| M1 | **SPA 化**: `adminClient`・`admin-web`・`vite.admin.config.ts`、配信（`run_worker_first`・`AdminAssets`）、モックの見た目。全画面が現行と同じ操作をできたら、旧 HTML の描画（`AdminText` の画面部分）を削除する。認証は Basic 認証のまま、API に §3.5 の Origin 等の検査を加える。Origin 検証の復活と CSP の変更は承認済み（2026-11-07 利用者。§10-9） | M0、利用者の承認（済み） | 現行の全操作を SPA から実行できることを実 workerd で確認（§8）。CSP 違反が出ない。`dist/admin/` が `/admin` 以外から取れない。`AdminHandlerSuite` の「Origin・Fetch Metadata によらず受け付ける」と `tests/admin.integration.test.js` の同趣旨の確認を、他オリジンからの変更を拒否する期待値に書き換える |
| M2 | **アカウント認証へ切替**: 管理者セッション・再認証・役割検査・bootstrap・監査ログ・**最小限の役割付与 API と画面**（`PUT /admin/api/accounts/:uid/roles`。Basic 認証で作業していた人に役割を配れるようにするため）。Basic 認証・`ADMIN_PASSWORD`・`ADMIN_USER` を削除。AGENTS.md 1.6・3.6、`docs/admin.md`（ログイン・bootstrap・役割・開発時の手順）を更新 | 設計書の段 B（`Account` / `AccountIndex` DO・セッション）と、段 E-1（ID/Password ログインと Google ログイン） | 役割ごとの許可・拒否の表を全 API について `AdminAuthSuite` で確認。Basic 認証の資格情報では入れない。bootstrap の条件（0 件・非常用期限・同時実行で 1 人だけ）、`adminGen` による失効、パスワード規則の検査時点、Google 再認証の `aid` 照合を確認。`auth_time` の実機確認の結果を本書に反映 |
| M3 | **認証機能の管理画面**: ユーザー一覧・ユーザー詳細（`AccountDirectory` を含む）・キャップ・PHOEBE / エッジ防御・連投規制とスレ立て規制の各項目（§4.3 の「設計書 段 A / C / D 以降」） | 設計書の各段 | 各画面の API テスト。§4.3 の表示条件が各段の完了と一致する |
| M4 | **任意**: 管理者の TOTP、エラーログ閲覧（§10-3・§10-6） | — | — |

M0 と M1 は認証基盤を待たずに進められる。M2 は設計書の認証基盤が先に要る。

## 8. テスト

- **単体（JVM/JS）**: `BoardSettingsForm`（SETTING.TXT ↔ フォーム値の往復、未設定と明示値の区別、未知キーと `=` の無い行の保持、行順、重複キーの拒否）、`ResEditText`（dat ↔ 編集用テキストの往復）、`AdminRole`（役割 × 操作の全組合せ、上位の役割への操作の拒否）、`AdminCodec`（DTO の往復）、`AuditLog`（保持規則）。
- **サーバ（Scala.js）**: `AdminApiSuite`・`AdminAuthSuite`（`HandlerTestKit` で R2・DO・時刻を差し替え）。管理者 Cookie の署名・期限・無操作失効・`gen` と `adminGen` の進行での失効、鍵の更新、Origin / Content-Type / 独自ヘッダの各欠落での拒否、bootstrap の同時実行。
- **API の統合（必須）**: `tests/admin.integration.test.js` を API 版に書き換え、node:test ＋実 workerd で全 API の主要操作を通す。必須の判定はここで行う。
- **クライアント**: 段 M1 の最初に、Scala.js のテストを jsdom で動かす構成（`scalajs-env-jsdom-nodejs` の追加）と、React 19 での scalajs-react のテストユーティリティ（`react-dom/test-utils` は `act` 以外が廃止）が使えるかを試作で確かめる。使えなければ、`bbs-client` と同じく node:test ＋ jsdom の JS テストからリンク済みの `adminClient` を読み込んで検証する。
- **画面の通し（任意）**: Playwright（Chromium）で、ログイン → 再認証 → 板設定保存 → スレ停止 → ルール保存 → ログアウトを実 workerd 上で通す。npm の依存に無いので任意のテストとして分け、CI の必須にはしない。

## 9. 設計書・既存文書への反映

- 設計書 §6.1・§10・§11・§13-14: `/mod/*` を `/admin/*` に統合し、認証を本書 §3 に差し替える（本コミットで訂正）。
- 設計書 §12: 段 E を E-1（ID/Password ログイン・Google ログイン）と E-2（メール認証・再設定・方式移行・エッジ防御・乱造検知・Lv 上限ルール）に分け、E-1 を段 B の直後に置く（本コミットで訂正）。
- `docs/admin.md`: 段 M2 で書き換える（ログイン手順、bootstrap、役割、開発時の `localhost` と Turnstile のテスト用キー）。
- AGENTS.md 1.6: 段 M2 で `.dev.vars` の `ADMIN_PASSWORD` の記述を `ADMIN_BOOTSTRAP_TOKEN` 等に改める。
- 設計書 §9.1・§9.3: `describeForAdmin`・`adminGen`・`claimFirstAdmin`、secret `ADMIN_SESSION_SECRET`(`_PREV`)・`ADMIN_BOOTSTRAP_TOKEN`、vars `ADMIN_BREAK_GLASS_UNTIL`、migration v3 を足す（本コミット）。
- `docs/既知不具合台帳.md`: レス編集で名前欄のトリップ・識別子のタグが壊れる件を KB-40 として記録（本コミット）。
- AGENTS.md 3.6: 段 M2 で「管理画面は再認証付きの管理者セッション＋役割＋Origin 検証＋no-store＋CSP 前提」に改める（利用者の承認後）。
- `docs/auth-phoebe-review.md` 設計 #8 の判断（`/mod` 分離・`/admin` は Basic のまま）は、2026-11-02 の利用者指示で本書の方針に置き換わった旨を追記する（本コミット）。

## 10. 要確認事項

| # | 事項 | 本書の仮置き |
| --- | --- | --- |
| 1 | モックの項目のうち v6 に対応しないもの（色・画像・過去ログ化・UNICODE 等）を表示しないこと | 表示しない。必要なら新機能として別途設計 |
| 2 | 権限者用の別画面（`/mod`）を作らず `/admin` に統合すること。役割は全板共通（板ごとの管理者は作らない） | 統合する。全板共通。板ごとの委任が要るなら `roles` に板の範囲を持たせる拡張を別途検討 |
| 3 | 管理者の二要素認証 | M2 では入れない（役割ありの ID/Password は 16 文字以上を必須）。M4 で TOTP を検討。§10-10 の結果によっては Google の管理者に先に必要 |
| 4 | JSON の読み書き | **M0 で決定**: ライブラリは足さず、`common` に依存の無い小さな JSON の値と読み書き（`Json`）と手書きの codec（`AdminCodec`）を置いた。JVM / JS で同じコードが動き、両方でテストする。依存の追加（Maven の取得・バンドルの増加・Scala.js 版の追従）を避けるため |
| 5 | `deny.cgi`・`ngwords.cgi` の保存を ETag 条件付きにすること（現行は無条件。複数の管理者が同時に編集すると後勝ちで消える） | M0 で板設定・`head.txt`・`bbsmenu.html` と合わせて条件付きにした（独立コミット）。HTML の画面で ETag の項目が無い送信は条件なし（**決定（2026-11-07 利用者）**: その扱いでよい） |
| 6 | モックの「直近のエラーログを閲覧」 | M2 までは作らない。Workers Logs の検索をボタンから開く案と、投稿エラーを DO に記録する案を M4 で比較 |
| 7 | 初回の管理者を `ADMIN_BOOTSTRAP_TOKEN` で作り、`ADMIN_PASSWORD` を廃止すること。非常用経路を `ADMIN_BREAK_GLASS` の再デプロイに限ること | そのとおり |
| 8 | 管理者セッションの無操作 30 分・最長 8 時間、再認証の猶予 10 分 | 仮置き |
| 9 | Origin 検証の復活と CSP の変更（`script-src 'self'`）。2026-10-05 に Origin 検証を外した理由（記録に無い）が、Cookie 認証化後も当てはまるか | **決定（2026-11-07 利用者）**: 当てはまらないと判断し、M1 から Origin 検証を入れ、CSP を `script-src 'self'` にする |
| 10 | Google の `max_age=0` に対して `auth_time` が返らない場合の扱い | Google の管理者は TOTP の登録を必須にする |
| 11 | 管理画面を別ホスト（`admin.` サブドメイン）に置いて、公開ページの XSS から切り離すか | 当面は同一ホスト。リスクとして記載（§3.5） |
| 12 | モックからの変更（上部バー、960px 未満の 1 列化、モックに無い画面の共通レイアウト） | §4.2 のとおり |
| 13 | 役割ありのアカウントにメールによるパスワード再設定を許さないこと、全役割にパスワード 16 文字以上を求めること | そのとおり |
