# 認証・PHOEBE（Lv）・連投規制 設計

2026-11-01。利用者提示の原案（§1）、[PHOEBE.md](bbscgi/PHOEBE.md)、端末フィンガープリントと認証に関する裁定メモ
（リポジトリ外の `ninja_auth_system.md` 系。要点は §3.7 に転記）、および新 UI（`develop` に統合済みの板トップ。PR #37。
`docs/ui/板top_書き込み欄と投稿後更新の方針.md`・`docs/islands-ssr.md`・`docs/handler-rendering.md`）をもとに、
v6 へ追加する認証・Lv・連投規制と、新 UI での Web 版認証の挙動（§3.8）を設計する。
**状態: 段Aは実装済み、段Bは部分実装、段E-1・段Cは実装済み（2026-11-07）。** E-1はID・パスワードの登録・ログイン・ロックとGoogleでのログイン・登録まで。Googleのクライアント登録と本番での接続確認は未実施。段CはUA解析表のデータ（§5.2）と識別子の既定値の切替（§14-1）を残す。段D・E-2は未実装。残件、設定、検証結果は [引き継ぎ](claude-handoff.md) を参照する。実装は§12の順で行い、本書を実装結果に合わせて訂正する。
2026-11-02 に Opus 5 によるセルフレビューを行い、採用した指摘を反映した（[auth-phoebe-review.md](auth-phoebe-review.md)）。
2026-11-05、トリップと日付欄の ID の廃止・`!id`・スレ主表示（[トリップ・ID廃止とスレ主表示](トリップ・ID廃止とスレ主表示.md)。`develop` で実装済み）に合わせて改めた。

既存のスレ立て規制 [thread-creation-regulation.md](thread-creation-regulation.md) の構成（ドメイン純粋関数 ＋ DO はロックと保存のみ ＋
拒否はコード文字列で返し handler が文言化）をそのまま踏襲する。AGENTS.md 3.5 の DO 規約に従う。

## 0. 位置づけと不変条件

| 項目 | 扱い |
| --- | --- |
| 対象 step | 連投規制（§8）は既存 DO へのキー追加のみで先行実装する（`docs/作業用_add_bbs_cgi_regulation_and_auth.md` の順序）。認証・Lv・権限（§3〜§7）は新 DO を伴う step 5 相当 |
| dat 構造・本文前後の空白・エラーマーカー・Range/304 | 変更しない |
| Shift_JIS 出力 | 変更しない。認証画面（`/auth/`）は UTF-8 の新規画面 |
| キャップの計算 | 変更しない。日付欄の ID（`ID:xxxx`・`BBS_ID`・`BBS_ID_LV`）とトリップは 2026-11-02 に廃止済みで、本書はそれを前提にする |
| 名前欄の識別子 | **書式を変える（§5）。** 既存の `BBS_ID_SHIKIBETSU=checked` は残し、新書式は新しい値 `phoebe` で有効化する。既定値の切替は承認を得てから行う |
| ストレージ | Board / Thread DO の既存キー・形式は変更しない。追加キーと新 DO（`Account`・`AccountIndex`）だけ |
| 管理画面の防御 | Basic 認証は再認証付きの管理者セッション＋役割＋Origin 検証に置き換え、no-store・CSP は維持する（[管理画面 改修計画](admin-spa-auth-plan.md) §3。AGENTS.md 3.6 の更新は承認後） |

## 1. 原案（利用者提示、2026-11-01）

画像で提示された案をそのまま転記する。本書の各節はこれを具体化したもので、解釈が分かれる点は §13 に挙げた。

- アカウント `!uid` で名前欄に id 出せる（→ `@${id}`）
  - ID/Password → L1〜10。作成時の認証あり。メール認証入れるなら L3〜 ※`!バルサン` が L3 未満書き込み不可なので
  - Google 連携 → L10。作成時の認証なし
  - クライアント ID（認証の同意鍵）: かんたんログインのようなもの → 1年でリセット？ 権限付与不可 → L1〜10。他方式への移行可能、他からの移行は不可
  - （未ログイン）→ L0 固定
- 権限: 管理者 / 削除 / 規制 / キャップ書き込み
- キャップ
  - 上記の権限持ちが発行可能。管理者以外は 1 個のみ発行可能（管理者: 無制限）
  - アカウントに紐づけて発行する（管理者のみ他アカウントに紐づけて発行可能）
  - 利用ログを見れる、不正利用の場合は削除や再発行できる
- 専ブラ用認証キー（E-mail に入れる）
  - キャップ同様利用ログを見れる、不正利用の場合は削除や再発行できる
  - キャップ利用時はキャップを用いてログイン扱いとするので不要
- 名無しでの権限使用（規制回避）: キャップ書き込み以外は利用可能
- 規制: 管理者のみ回避可能 / 管理者・削除・規制回避可能 / 全権限回避可能 / 全権限＋L10 回避可能 / 全権限＋ログイン済み（認証済）回避可能

> 読み替え（2026-11-05）: `!uid` と `@${id}` は、トリップ・ID 廃止の仕様変更で名前欄の `!id` と ` </b>(@ユーザーID)<b>` になった（§5.3）。
> 表示するユーザー ID は、クライアント ID は自動採番の ID、ID/Password と Google は登録したユーザー ID（§2 の `uid`）。

## 2. 用語

| 用語 | 意味 |
| --- | --- |
| アカウント | `Account` DO 1 件。公開 ID `uid` を持つ。種別は未ログイン以外の 3 つ（§3.1） |
| `aid` | 内部 ID。`Account` DO の名前（`idFromName(aid)`）。作成時に 16 文字 `[a-z0-9]` で採番し、以後変えない。Cookie・索引・ログの内部参照はこれを使う |
| `uid` | ユーザー ID。名前欄の `!id` で `(@uid)` として出す公開 ID。クライアント ID は自動採番 8 文字 `[a-z0-9]`、ID/Password はログイン ID と同じ値、Google は登録時に決める ID（規則はログイン ID と同じ。§3.9・§3.10）。`AccountIndex("uid")` で全種別を通して一意にし、退会後や移行で使わなくなった値も削除しない（他人の再利用を防ぐ） |
| Lv | PHOEBE レベル 0〜10。アカウントに 1 つ。板・スレをまたいで共通 |
| 認証済 | 投稿の主体（§3.4）が未ログイン以外であること。実効 Lv の値（§4.3 の上限で 0 になりうる）とは無関係。未ログインは L0 固定 |
| 権限 | アカウントに付く役割。`admin`（管理者）/ `delete`（削除）/ `regulate`（規制）/ `cap`（キャップ書き込み） |
| 端末ハッシュ | 認証時に取得する 9 軸 18 桁 hex の端末識別値（§3.7）。粒度文字 1 字を先頭に付けて保存 |
| 識別子 | 名前欄末尾の `(JP.AA CC-EEEFFFGGGZZZXXXD)`（§5） |
| 回避レベル | 規制を免除する属性の段階（§7） |
| `keyIp` | 計数・ロックの単位。IPv4 はそのまま、IPv6 は /64 |

## 3. アカウントと認証方式

### 3.1 種別

| 種別 | 作成時 | 初期 Lv | 上限 | 権限付与 | 有効期限 | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| 未ログイン | — | 0 固定 | 0 | 不可 | — | Web 版からは書けない（§3.5）。専ブラは `BBS_PHOEBE=0` の板・スレに書ける |
| クライアント ID | Turnstile ＋ 法的投稿確認 | 1 | 10（§4.2 の曲線で、書き込みを続ければ約 487 日で L10） | **不可** | **最後の書き込みから 365 日、書き込みが無ければ失効**（書き込みのたびに延びる。一度も書き込まなければ発行から 365 日）。失効後は再発行で L1 から | 「かんたんログイン」。Cookie だけで識別する |
| ID/Password | Turnstile ＋ 法的投稿確認 ＋ ID・パスワード登録 | 1（メール認証済なら 3） | 10 | 可 | なし | クライアント ID からの移行先になれる |
| Google 連携 | Google OAuth（Turnstile なし） | 10 固定 | 10 | 可 | なし | 1 つの Google アカウント（`sub`）につき 1 アカウント |

- **移行**: クライアント ID → ID/Password または Google へ移行できる。移行しても `aid`（DO）は変わらず、Lv・端末履歴・ログを引き継ぐ。`uid` は登録したユーザー ID に変わる（自動採番の旧 `uid` は索引に残す）。手順は §3.9・§3.10。ID/Password・Google からクライアント ID への移行、ID/Password ⇄ Google の相互移行はしない。
- **ID/Password** の詳細は §3.9、**Google 連携**の詳細は §3.10。
- **法的投稿確認**: 認証画面で利用規約・投稿の法的責任への同意チェックを必須にし、`agreedAt` と同意文書の版を保存する。版を上げたら次回投稿前に再同意させる（投稿の拒否に `x-auth-required: login` を付け、認証モーダルの verify で同意し直す。JS なしはエラー画面の認証・登録のリンクから）。

### 3.2 認証フロー

**A. Web 版の初回書き込み**（新 UI。詳細は §3.8。現行 `3chiko.html` は B の誘導のみ）
1. 投稿フォーム送信時に有効なセッションがなければ、認証・登録モーダル（`/auth/?embed=1` を iframe で開く）を出す。
2. 方式を選ぶ（既定: クライアント ID）→ 法的投稿確認 → Turnstile → `POST /auth/verify`。
3. 成功で `Set-Cookie` を返し、モーダルは `postMessage` で親へ通知して閉じる。親は投稿を自動で再送する。

**B. 認証・登録ページ**（`/auth/`）
- 板トップ・read.cgi のリンク、および bbs.cgi の認証要求エラー（§3.5）に載せる URL から開く。`return` クエリで戻り先を持つ（同一オリジンのパスのみ許可）。
- 専ブラ利用者は、ログイン後のアカウントメニュー（`/auth/account`）で専ブラ用認証キーを発行し、専ブラの初回投稿でメール欄に `!#{キー}` を入れる（§6.3）。キーは最初に使った専ブラの UA に紐づく。サーバは照合に成功したら `Set-Cookie` を返し、以後は同じ UA からの Cookie で認証済として扱う。

**現行 `3chiko.html` の扱い**: ロジックを変えない方針のため、Web 版の投稿で未認証なら bbs.cgi が `/auth/?return=...` へのリンク付きエラー（HTTP 200 ＋ `<!-- 2ch_X:error -->`）を返す。モーダルは step 4 の新 UI で実装する。

### 3.3 セッション Cookie

| 項目 | 値 |
| --- | --- |
| 名前 | `__Host-ch3s_sid`（host-only。`Domain` を付けない） |
| 値 | `1.{aid}.{uid}.{gen}.{exp}.{lv}.{dev}.{bind}.{sig}`。`bind` は専ブラ用認証キーで発行したときだけ `t:{tokenId}:{UA ハッシュ先頭 16 文字}`（§6.3）、それ以外は空。`gen` はアカウントの世代番号、`exp` は失効 UNIX 秒、`lv` は発行時点の Lv（表示用の写し）、`dev` は端末ハッシュ（粒度 1 字＋18 hex。無ければ空）、`sig` は `AUTH_SECRET` による HMAC-SHA256 の先頭 128 bit（base64url） |
| 属性 | `Path=/; Max-Age=31536000; Secure; HttpOnly; SameSite=Lax`。**HTTPS の要求にだけ発行する**（HTTP で来た専ブラのキー認証は「HTTPS で接続してください」で拒否）。開発環境は `http://localhost:8787` で確認する（主要ブラウザは localhost を安全なコンテキストとして `Secure` Cookie を受け付ける。`127.0.0.1` では付かない場合がある） |
| 検証 | bbs.cgi: 署名 → `exp` → `Account.describeForPosting(aid, gen)` で `gen` 一致・状態（active / frozen / banned）・Lv・権限を得る。DO 呼び出しは投稿 1 回につき 1 回。DO の Lv が Cookie の `lv` と違えば応答で Cookie を発行し直す。SSR 画面（板 top・read.cgi）: 署名と `exp` だけ見て `uid` と `lv` を表示に使い、DO は呼ばない（`SessionReader`、§3.8） |
| 失効 | ログアウト・パスワード変更・再設定・方式移行・退会・管理者の凍結はいずれも `gen` を進める（全端末のセッションが無効になる。端末ごとの失効は持たない）。クライアント ID の `exp` は「最後の書き込み（無ければ発行）＋365 日」。書き込みが成功するたびに Account DO の `expiresAt` を延ばし、Cookie の `exp` と 1 日以上ずれたら応答で Cookie を発行し直す（`Max-Age` も同じ日時まで）。失効は Account DO の `expiresAt` で判定し、Cookie の `exp` は SSR 画面で DO を呼ばずに表示を出し分けるための写しとする |
| 複数 Cookie | 現行の `PON` と同時に出すため、応答ヘッダは `Headers.append` で `Set-Cookie` を複数出せる形にする（現行の `js.Dictionary` では 1 本に上書きされる。計画書 §3） |

既存の `PON` 等の Cookie は現行どおり発行し、意味は変えない（`develop` では `PON` も `Domain` を付けない host-only になっている）。
`www.3chbbs.com` は専ブラ以外を `www.3ch.io` へ 301 で転送するので、Web 版の Cookie は `www.3ch.io` にだけ載る。専ブラが `www.3chbbs.com` のまま書き込んだ場合、キー認証で発行する Cookie はそのホストに載り、`www.3ch.io` の Cookie とは別になる。`/auth/*` の POST と `/admin/*` の `GET` 以外の要求は `Origin` ヘッダが自ホストと一致しなければ拒否する（`SameSite=Lax` だけに頼らない。管理画面の追加条件は改修計画 §3.5）。

### 3.4 bbs.cgi での認証主体の決定

優先順は次のとおり。最初に当たったものを採用し、残りは見ない。

1. メール欄の `#パスワード` がキャップに一致 → **キャップの所有アカウントとしてログイン扱い**。所有者のない旧キャップは「権限 `cap` だけの仮主体」で、認証済として扱い、`BBS_PHOEBE` ゲート（§4.4）と Lv 判定の対象外（現行の「キャップは全規制スルー」に準じる）。
2. メール欄の `!#{キー}` が専ブラ認証キーに一致し、UA の判定（§6.3）を通る → そのアカウント。`Set-Cookie` を返す。拒否なら書き込みも拒否し（HTTP 200 ＋ エラーマーカー）、3 以降は見ない。
3. 有効なセッション Cookie → そのアカウント。キーから発行した Cookie は UA の一致とキーの有効性も確かめる（§6.3）。
4. いずれもない → 未ログイン（L0）。

メール欄からは `!#...`（空白の手前まで）・キャップのパスワード部分（最初の `#` から次の `#` の手前まで。空白も含め、キャップの照合と同じ範囲）・`!auth` を取り除いてから sage 判定に使う（dat には保存されないので表示影響なし。読み取りと除去は `common` の `MailCommands`）。ログイン済で `!auth` を付けた場合は無視して通常投稿にする。
応答ヘッダ `Set-Cookie` は 2 と 3 で `__Host-ch3s_sid` を出しつつ、現行の `PON` も出す（§3.3）。

### 3.5 認証要求の発生条件（板設定ゲート原則）

認証要求（`/auth/` の URL 付きエラー）は次の場合**だけ**出す。

| 投稿元 | 条件 |
| --- | --- |
| Web 版（現行の `isMonazilla`＝UA が `Monazilla/1.00` で始まらない） | 常にログイン必須。未ログインなら認証要求 |
| 専ブラ（`isMonazilla`） | 板設定（スレ設定で上書き）`BBS_PHOEBE` が現在の Lv を上回るときのみ。`BBS_PHOEBE=0`（既定）なら未ログインでも書ける |
| メール欄 `!auth` | 投稿元によらず常に認証要求（広域規制回避・事前認証の導線） |

エラー文言は「この板（スレ）に書き込むには認証が必要です（必要 Lv: n）」＋ URL。どの規則で落ちたかは出さない。
**Web 版のログイン必須は利用者を認証へ導くための仕組みで、防御ではない**（UA を `Monazilla/1.00` にすれば `BBS_PHOEBE=0` の板へ未ログインで書ける）。
防御は `BBS_PHOEBE`・エッジ防御（§3.6）・連投規制（§8）が担う。
認証要求のエラー応答には HTTP 200・エラーマーカーに加えて応答ヘッダ `x-auth-required: login`（未ログイン、または同意の版が古い）または
`x-auth-required: level` と `x-phoebe-required: n`（Lv 不足）、`x-auth-url: /auth/?...` を付ける。新 UI は本文を解析せず
このヘッダでモーダルを開く（§3.8）。ヘッダの追加は専ブラ互換（`x-threadkey` / `x-resnum` と同じ扱い）に触れない。

### 3.6 エッジ防御（`request.cf`）

判定規則は R2 `edge-rules.json`（データセンター ASN 一覧、RTT 閾値、TLS 指紋の矛盾表）に置き、管理画面で編集する。
**初回は記録だけの試行モード**（`mode: "log"`。Workers Logs に出す）で始め、誤判定を見てから `"on"` にする。

| 判定 | 適用先 | 動作 |
| --- | --- | --- |
| `asn` がデータセンター一覧に含まれる | `/auth/verify` `/auth/register` `/auth/login` `/auth/google/start` と、未ログインまたは認証済 Lv 2 以下の投稿（専ブラを含む） | 拒否 `reason=datacenter` |
| `country=JP` かつ `clientTcpRtt` が閾値（既定 400ms）超 | `/auth/verify` `/auth/register` | 拒否しない。Turnstile を再実行し、乱造検知（§3.7）の閾値を半分にする |
| `tlsClientHelloLength` 等の TLS 指紋と UA 系統の矛盾（矛盾表は実測して作る。JA3 / JA4 は Enterprise 専用で使えない） | `/auth/*` のブラウザ向けエンドポイントのみ（専ブラ・bbs.cgi には適用しない） | 拒否 `reason=declaration_mismatch`（§3.7 と同じコード） |
| `country=T1`（Tor） | 現行どおり全投稿 | 拒否 |

免除: 認証済で**実効 Lv 3 以上かつアカウント作成から 7 日以上**。R2 が読めないときは規則なしとして通す（fail-open。認証系の計数は §3.7 と違い投稿経路に乗るため）。

### 3.7 端末フィンガープリント（裁定メモの要点）

材料・粒度・突合規則は裁定済みのため変えない。本書では v6 で実装する形に絞って書く。

- **材料は 9 軸 18 桁 hex**（各軸 2 桁。`common` に `DeviceFingerprintHasher` を置く）。申告 8 軸（画面論理解像度・DPR・コア数・タッチ有無・色深度・CPU アーキ・フォントハッシュ・WebGL renderer）＋サーバ受信の `Sec-CH-UA-Model`。OS 版・UA・deviceMemory は材料に含めず照合専用。申告 8 軸が全欠損なら端末ハッシュは作らない（空）。
- **保存値**は粒度 1 字（`p` 準個体 / `g` 機種クラス / `u` 不明）＋18 hex。解析は旧 16 桁も通す（`DeviceHashRef.parse`）。粒度は申告であり照合可否を決めない。
- **粒度判定**はハードウェア由来のシグナル（Apple GPU ＋タッチ ＝ iOS 実機）を先に見て、それが iOS を示さないときに限り「ヒントが届いた＝準個体」とする。`iOS` / `iPadOS` を名乗るヒントは準個体にしない。
- **突合（`reason=declaration_mismatch`）**: R4・R5（`Sec-CH-UA-Platform` × `navigator.platform`）、R6・R7（`Sec-CH-UA-Platform` / `Sec-CH-UA-Mobile` × UA 文字列。申告 UA が空なら受信 `User-Agent` で代替）、受信 `User-Agent` × `navigator.userAgent` の trim 後完全一致。材料が空なら、その材料を使う規則だけ飛ばす（fail-open）。発火規則は Workers Logs に `console.warn` で残し、応答には出さない。
- **論理破綻検知（`reason=fingerprint`）**: 実在しない組合せ（macOS × RTX、iPhone の画面値 × 旧 iOS 等）。iOS は既知プロファイル表（R2 `ios-profiles.json`、毎年 9 月更新）と照合する。
- **乱造検知**: `keyIp` ごとの計数 DO `AuthRate`（§9.1。KV は原子的な加算がなく結果整合のため使わない）で verify 回数と相異なる端末ハッシュ数を数え、閾値（既定: 1 時間に 10 回、24 時間に 5 ハッシュ）超で拒否し、`deny.cgi` へ自動追記はせず管理画面の「要確認」一覧に載せる。計数 DO が応答しないときは認証系の要求を拒否する（fail-closed。投稿経路には乗らない）。
- **`Accept-CH` は全応答に付ける**（裁定 2026-08-15。dat・subject.txt・SETTING.TXT・bbs.cgi 応答・bbsmenu・`/admin` を含む）。ブラウザが予約を保存するのはトップレベルの文書応答だけなので、効くのは板 top・read.cgi・`/auth/` などの HTML 応答で、他への付与は害も効果もない。Hono のミドルウェアで付け、`Responses.plain` の 304 経路などハンドラ側の分岐に依存しない。`Critical-CH` は付けない。
- **`/auth/` の注意事項**は Turnstile・実行ボタンの手前に置く: 推奨環境（Safari・Chrome・Edge・Firefox 最新版）、認証できないことがある例（専ブラ内蔵ブラウザ・一部アプリ内ブラウザ・PC 版サイト表示・UA 変更拡張。いずれも断定しない）。`declaration_mismatch` のエラー文言も同じ導線を示す。
- **用途**: 名前欄識別子の 15 桁（§5）、投稿ログの `Device:`、`deny.cgi` による端末軸の規制、連投規制の照合キー（識別子経由）。優先順位は「規制のオプトアウト阻止 ＞ 巻き込み削減」。

### 3.8 新 UI での Web 版の挙動

画面のモックは `docs/ui/auth.html`（`/auth/`）・`auth-modal.html`（板トップ上の認証モーダル）・`account.html`（`/auth/account`）。見た目は板トップに揃えている。

**UI の方針**: 角丸を使わない（`border-radius` を付けず四角にする）。部品（ヘッダー・タブ・入力欄・ボタン・サイドバー）と文言は既存画面（板トップ・スレ画面）に合わせ、
新しい部品を足すときも既存の配色と余白を使う。文言は全角と半角英数字の間に空白を入れない（例「Lv3から」「Googleで続ける」）。説明は短くし、手順の羅列にしない。

新 UI（板トップ以降）は scalajs-react の SSR コンポーネント（`bbs/.../components/`）と、`bbs-client` の
`@islands` で hydrate するクライアント部品で構成される。投稿欄は板 top の各スレカードに `details` 方式で置き、
`fetch` で bbs.cgi へ送って `x-threadkey` / `x-resnum` で成否を見る（上記の方針メモ 2 章）。認証はこの構成に次を足す。

**SSR 側（`SessionReader`）**

- 全画面ハンドラは `SessionReader.fromRequest(c)` で Cookie（§3.3）を署名と `exp` だけ検証し、`Option[SessionSnapshot(aid, uid, lv)]` を得る。
  DO は呼ばない。失効・改竄は未ログインとして描画する。
- `HeaderComponent` のアカウント領域（現在「準備中」の仮置き）は snapshot で出し分ける。
  - 未ログイン: 「認証・登録」→ `/auth/?return={現在のパス}`、「ログイン」→ `/auth/?mode=login&return={現在のパス}`。
  - ログイン済: `@{uid}`（ユーザー ID）と Lv バッジ（`Lv3`、10 以上は `Lv*`）、アカウントメニュー（`AccountNav` island）: アカウント設定 `/auth/account`、
    専ブラ用認証キー、キャップ、方式の移行、ログアウト（`POST /auth/logout`）。
- `PostingFormComponent` は未ログインでも描画する（下書きを書ける）。未ログイン時は送信ボタンの上に
  「書き込みには認証・登録が必要です」と `/auth/?return=...` へのリンクを SSR で出す（JS 無しでも導線がある）。
  スレ設定・板設定の `BBS_PHOEBE` が snapshot の `lv` を上回るときは「このスレはLv{n}以上」と `/auth/account?upgrade=1` への導線を出す。
  ログイン済なら、ユーザー ID を名前欄に出すチェックボックス（フォーム項目 `showid`。名前欄の `!id` と同じ扱い）を出す。
- props は JSON 化できる JS オブジェクトだけ（`docs/islands-ssr.md`）。`uid`・`lv`・`turnstileSiteKey`・`authUrl` を渡し、Cookie 値や秘密は渡さない。
- Cookie を読んで出し分けた画面は `Cache-Control: private, no-cache` と `Vary: Cookie` を付け、エッジやブラウザが他人の画面を再利用しないようにする（板 top の共有キャッシュを優先するなら、ヘッダーだけ `GET /auth/session` を island が取りに行く案 — §13-27）。

**クライアント側（islands）**

| island | 役割 |
| --- | --- |
| `AuthModal`（`auth-modal.tsx`、レイアウトに 1 つ） | `<dialog>` に `/auth/?embed=1&return=...` を iframe で開く。`event.origin` が自オリジンの `message`（`{type:"ch3s-auth", status:"ok", uid, lv}`）だけを受けて閉じ、DOM イベント `ch3s:auth-changed` を発火する。`postMessage` の `targetOrigin` は自オリジン固定。ポップアップ経由（§3.10）で `window.opener` が失われた場合に備え、`BroadcastChannel("ch3s-auth")` と `GET /auth/session` の 2 秒間隔ポーリング（最長 5 分）も受け口にする |
| `AccountNav` | ヘッダーのアカウント領域。`ch3s:auth-changed` でログイン済表示へ切り替える（リロード不要） |
| 投稿欄（方針メモの fetch 送信部） | 送信結果のヘッダで分岐する（下記） |

投稿欄の `fetch` 後の分岐:

```
200 + x-resnum                     → 成功（方針メモ 3 章のフィード更新。吸い込み §8.5 も同じ経路）
200 + x-auth-required: login       → AuthModal を開く → ok を受けたら同じ内容を 1 回だけ自動再送
200 + x-auth-required: level       → 「Lv n 以上が必要」を欄の近くに表示し、/auth/account?upgrade=1 へ誘導（再送しない）
200 + x-post-error: <code>         → コードに応じた文言（連投規制は待ち時間付き）を欄の近くに表示、下書きは残す
200 + それ以外（マーカーのみ）        → 本文の <!-- 2ch_X:error --> 以降の文言を表示（現行どおり）
```

`x-post-error` は §8.9 の拒否コードと `thread-creation:*`（既存）を載せる追加ヘッダ。`declaration_mismatch` 系の内訳は載せない（§3.7）。

**`/auth/` の埋め込み時の制約**

- `embed=1` では共通ヘッダー・フッターを出さず、完了時に `window.parent.postMessage` して自分では遷移しない。
  通常表示（B）では完了後に `return` へ遷移する。同じハンドラ・同じコンポーネントで `embed` の有無だけ変える。
- Turnstile は同一オリジンの iframe 内で動く。Google OAuth は iframe 内で始められないため、`embed=1` のときは
  ボタンが `POST /auth/google/start`（`popup=1`）をポップアップへ送り、callback 画面が `window.opener` へ `postMessage` して自分を閉じる（§3.10）。
- CSP は `frame-ancestors 'self'`、`script-src` と `frame-src` に `https://challenges.cloudflare.com`、`form-action 'self' https://accounts.google.com`（Chrome はリダイレクト先にも `form-action` を適用する）。`/auth/*` には `Referrer-Policy: no-referrer`（トークン付き URL を漏らさない）。Turnstile のスクリプトは `/auth/` だけが読む。
- `SameSite=Lax` の Cookie は同一オリジンの iframe・`fetch`・ポップアップの top-level 遷移のいずれでも送受信できる。

**JS が動かない環境**

通常の `POST` で bbs.cgi へ送られ、認証要求のエラー画面（§3.5）のリンクで `/auth/?return=/{bbs}/` に移る。
認証後は `return` に戻るが下書きは残らない（島が無いため）。`return` は `new URL(return, origin)` の `origin` が自オリジンと一致し、かつ `/` で始まり `[A-Za-z0-9/_\-.?=&%]` 以外を含まない場合だけ使う（`/\evil`、`%2F%2F`、制御文字を含むものは捨てる）。

**read.cgi（新 UI 化後）**

スレ表示の新 UI も同じ `SessionReader`・`PostingFormComponent`・`AuthModal` を使う。画面固有の差はない。
現行 `3chiko.html` の read.cgi は §3.2 のとおりエラー画面のリンクだけで誘導する。

### 3.9 ID/Password 方式

**ログイン ID とパスワードの規則**（`common/domain/account/{LoginId, Password}`）

| 項目 | 規則 |
| --- | --- |
| ログイン ID | `[a-z0-9_]{3,16}`。入力は小文字化してから検証。予約語（`admin`・`root`・`test`・`3ch`・`saku`・`auth`・`mod`・板名と同じ文字列・`noname` 等。R2 `reserved-ids.txt`）は拒否。登録後は変更不可。**ユーザー ID（`uid`）と同じ値で公開される**（名前欄の `!id`・管理画面）。画面ではどちらも「ユーザーID」と呼ぶ |
| パスワード | 8〜128 文字。NFC 正規化後に UTF-8 で数える。ログイン ID と同一・ログイン ID を含むものは拒否。文字種の強制はしない |
| 保存 | PBKDF2-HMAC-SHA256、反復 100,000、ソルト 16 バイト（`crypto.getRandomValues`）。`credential` に `{algo, iterations, salt, hash}` で持ち、`algo`・`iterations` を保存するので将来の強化（反復増・別アルゴリズム）はログイン成功時に再ハッシュして移行する |
| 照合 | `Account.verifyPassword(password, keyIpHash, now)` が DO 内で PBKDF2 を計算し定数時間比較する。失敗回数の更新は同じ RPC で直列化する（Worker 側で照合すると失敗計数が競合する）。ロックの規則そのもの（回数・期間・段階的遅延）は `common/domain/account/LoginThrottle` の純粋関数で、DO は記録と呼び出しだけ |
| ロック | （アカウント, `keyIp`）の組で直近 1 時間に 10 回失敗で 1 時間ロック（第三者が ID だけでアカウント全体を締め出せないようにする）。アカウント全体の失敗が 100 回に達すると全接続元を 1 時間ロックする。応答は接続元ごとの失敗 5 回目から 1 秒ずつ遅らせる（上限 8 秒。`AuthRate` DO の `loginFail`。ID の有無に依存しない）。`keyIp` 全体は 30 回/時（`AuthRate` DO）。ロック中も応答は失敗時と同じ文言 |
| 所要時間 | 存在しない ID でも固定の `aid="dummy"` の DO に同じ `verifyPassword` を投げ、RPC と PBKDF2 の両方を同じ回数行う。dummy の DO には何も保存しない。失敗の遅延は DO のロックの外（HTTP 層）で待つ |
| 文言 | 存在しない ID・パスワード不一致・ロック中のいずれも「ID またはパスワードが違います」。ログイン ID はユーザー ID として公開されるので、列挙はロックと所要時間の均一化（上記）で抑える |
| 反復数 | 設定は100,000回。2026-11-07の実workerd検証では100,001回も受け付け、Nodeの独立した計算値に一致した。プラットフォーム上限は未測定で、100,000回がAPIの上限という従来の想定は採用しない。今回の保存値・発行時の方針は100,000回のままとする |

**登録**（`POST /auth/register`。`/auth/?mode=register`）

1. 入力: ログイン ID・パスワード（2 回）・メール（任意）・法的投稿確認の同意・Turnstile トークン・端末申告（§3.7）。
2. Turnstile 検証 → 突合（`declaration_mismatch` / `fingerprint`）→ 乱造検知 → 入力規則。登録は `keyIp` ごとに 5 件/日。
3. `aid` を Worker 側で採番し、`uid` をログイン ID として `AccountIndex("uid").register(hash(uid), aid)` → `AccountIndex("login").register(hash(loginId), aid)`。どちらかが衝突したら「この ID は使えません」（自動採番の `uid` とも重ならないようにする）。索引は `pending` 状態で作る（§9.1）。
4. メールがあれば正規化（下記）して `AccountIndex("email").register(hash(email), aid)`。衝突ならメール欄だけエラーにし、登録を続けない（§13-19）。
5. `Account.create(profile, credential)` → `bindDevice` → 索引を `confirm`。失敗したら 3・4 の索引を `remove` して戻す。`pending` のまま 10 分過ぎた索引は次の `register` 時に掃除する（確定済みは消さない）。
6. メールがあれば認証メール送信（下記）。`Set-Cookie`。初期 Lv は 1（メール認証完了で 3）。

**実装（E-1）**: 入力規則と確認の一致は Turnstile より前に確かめる（Turnstile のトークンを無駄にしない）。メール欄は E-2 まで画面に出さず、受け取らない。
確定は「`create`（資格情報付き・登録準備中）→ `login` の `confirm` → `uid` の `confirm` → `Account.activate` → `bindDevice`」の順。
`uid` の `confirm` が拒否されたら両方の索引を `remove` し、応答だけが失われたら `uid` の `lookup` で所有者を確かめてから続ける（結果不明のまま取り消さない）。
`activate` の前に止まったアカウントは、次のログインで正しいパスワードを確かめた後に `uid` の確定と `activate` を再開する。10 分の予約期限は変えない。
使えないユーザー ID（予約語・`reserved-ids.txt`・板名・使用済み）は区別せず「このユーザーIDは使えません」にする。

**ログイン**（`POST /auth/login`。`/auth/?mode=login`）

1. 入力: ログイン ID・パスワード・Turnstile トークン・端末申告。Turnstile は毎回（§13-20）。
2. Turnstile 検証 → 突合 → `AccountIndex("login").lookup` → `Account.verifyPassword`。存在しない ID でもダミーの PBKDF2 を 1 回計算して所要時間を揃える。
3. 成功: 状態（`frozen` / `banned` / `deleted` は拒否）→ `bindDevice` → `Set-Cookie`（`gen`・`lv`・`dev` 入り）→ `return` へ遷移、`embed=1` なら `postMessage`。
4. ログアウト（`POST /auth/logout`）は `gen` を進めて Cookie を消す。全端末が同時にログアウトする（盗まれた Cookie を残さないため。端末ごとの失効は持たない — §3.3）。

**クライアント ID からの移行**（`/auth/account?upgrade=1` → `POST /auth/register` に `upgrade=1`）

- 有効なセッション（クライアント ID）がある状態で登録手順 1〜4 を行い、5 の代わりに `Account.convert("password", loginId)` で同じ `aid` の種別を変え、`uid` をログイン ID にする。
  旧 `uid` の索引は残す。Lv・`lvSince`・`lvPosts`・端末履歴・ログはそのまま。`expiresAt` を外す。`gen` を進めて Cookie を発行し直す。
- 移行後のクライアント ID としての Cookie は `gen` 不一致で無効になる（他端末を含む）。

**パスワード変更**（`POST /auth/password`。ログイン済）

現在のパスワードで `verifyPassword` → `setPassword` → `gen` を進めて現在の端末だけ Cookie を発行し直す（他端末はログアウト）。

**メール認証**（`POST /auth/email` で登録・変更、`GET /auth/email/verify?token=`）

- メールの正規化: 前後空白除去・小文字化・ドメインは IDN を punycode に。`gmail.com` / `googlemail.com` はローカル部のドットと `+` 以降を除く。使い捨てメールのドメイン一覧（R2 `disposable-domains.txt`）に該当すれば拒否（L3 開始の量産を防ぐ）。
- トークンは 32 バイト乱数の base64url。`Account.issuePending("email", SHA-256(token), now + 24h, email)` に保存し（種別ごとに別キー。再設定と上書きし合わない）、平文はメール本文のリンクにだけ載せる。
- リンクを開くと `consumePending`（1 回限り・期限内）→ `profile.email` 確定・`emailVerifiedAt` 記録・`AccountIndex("email")` 更新 → Lv が 3 未満なら 3 へ（§4.2 の `lvSince` も更新）。
- 変更時は新アドレスで再認証し、認証完了まで旧アドレスを有効のまま残す。Lv は下げない。
- 送信は外部 API（§13-12）。本文は固定文面でユーザー入力を含めない。送信失敗は「しばらくして再送」で `POST /auth/email`（再送は 3 回/日）。

**パスワード再設定**（`POST /auth/reset/request` → メール → `GET/POST /auth/reset?token=`）

- 申請は常に「送信しました」を返す（ID・メールの存在を漏らさない）。メール認証済みのアカウントだけに実際に送る（§13-21）。3 回/日。
- トークンはメール認証と同じ形式で `issuePending("reset", …, now + 1h)`。消費時に `setPassword` → `gen` を進める → ログイン画面へ。
- メール未登録のアカウントは再設定できない。登録時にその旨を表示し、管理者による手動対応（本人確認は管理画面の裁量）を案内する。
- 役割（`admin` / `delete` / `regulate`）を持つアカウントは、メールによる再設定を使えない（申請しても送らない）。手順は改修計画 §3.3。

**退会**（`POST /auth/account?action=delete`。パスワード再入力または Google の再認証が必要）

`status=deleted`、`login` / `email` / `google` / `token` の索引を削除、キャップを失効、`gen` を進める。`uid` の索引は残す（名前欄の `(@ユーザーID)` の再利用防止）。投稿・ログは消さない。

### 3.10 Google OAuth 方式

OpenID Connect の Authorization Code Flow ＋ PKCE を使う。スコープは `openid email` だけ。アクセストークン・リフレッシュトークンは使わず保存もしない。
Turnstile は要求しない（原案「作成時の認証なし」）が、端末申告の取得と突合（§3.7）は行う。

**開始**（`POST /auth/google/start`。`/auth/` の「Google で続ける」ボタン。フォーム POST）

1. 入力: 端末申告・法的投稿確認の同意・`return`・`popup`・新規登録で使うユーザー ID（規則はログイン ID と同じ。既存の Google アカウントでログインするときは空でよい）・（移行なら有効なクライアント ID セッション）。
2. 突合（`declaration_mismatch` / `fingerprint`）。拒否は §3.7 の文言。
3. `state`（32 バイト乱数）・`nonce`（32 バイト乱数）・PKCE `code_verifier`（43〜128 文字）を生成し、
   Cookie `__Host-ch3s_oauth`＝`{state, nonce, verifier, dev, return, popup, link: aid?, uid?, agreedAt}` を `AUTH_SECRET` で署名して保存
   （`Path=/auth/google; Max-Age=600; HttpOnly; Secure; SameSite=Lax`）。
4. `https://accounts.google.com/o/oauth2/v2/auth` へ 302:
   `client_id`・`redirect_uri=https://www.3ch.io/auth/google/callback`・`response_type=code`・`scope=openid email`・
   `state`・`nonce`・`code_challenge=S256(verifier)`・`code_challenge_method=S256`・`prompt=select_account`。

**コールバック**（`GET /auth/google/callback?code=&state=`）

1. `__Host-ch3s_oauth` の署名・期限を検証し、`state` が一致しなければ拒否（`reason=oauth_state`）。Cookie はここで消す（1 回限り）。
   `error=access_denied` は `/auth/?mode=login&error=google_denied` へ戻す。
2. `https://oauth2.googleapis.com/token` へ `code`・`client_id`・`client_secret`・`redirect_uri`・`grant_type=authorization_code`・`code_verifier` を POST。
3. `id_token` を検証する（`bbs/application/auth/GoogleIdToken`。署名以外のクレーム検証は `common` の純粋関数）:
   - 署名: `https://www.googleapis.com/oauth2/v3/certs` の JWKS（`kid` で選ぶ RS256）。JWKS は応答の `Cache-Control` に従い `caches.default` に保存し、`kid` 不明なら 1 回だけ再取得。
   - `iss` ∈ {`https://accounts.google.com`, `accounts.google.com`}、`aud` = `GOOGLE_CLIENT_ID`、`exp` > now、`iat` ≤ now + 300s、`nonce` = Cookie の `nonce`。
   - `email_verified` が `true` でなければ拒否（`reason=google_email`）。`hd` は見ない。
4. `subHash = HMAC-SHA256(AUTH_SECRET, "google:" + sub)` で `AccountIndex("google").lookup`。
   - **既存**: そのアカウントでログイン。状態が `frozen` / `banned` / `deleted` なら拒否。`bindDevice` → `Set-Cookie`。
   - **未登録かつ `link` あり**（クライアント ID からの移行）: `Account.convert("google")` で同じ `aid` を Google 種別にし、Lv を 10 固定（`lvOverride` ではなく種別の規則として）。
     `uid` を入力したユーザー ID にする（旧 `uid` の索引は残す）。`AccountIndex("uid")`・`AccountIndex("google").register(subHash, aid)`。`gen` を進める。
   - **未登録かつ `link` なし**: `aid` を採番し、入力したユーザー ID を `uid` として `AccountIndex("uid")`・`AccountIndex("google")` に登録 → `Account.create`（`kind=google`、`lv=10`、`email` は参考情報として保存し `emailVerifiedAt=now`。`email` 索引には載せない — §13-19）→ `bindDevice` → `Set-Cookie`。
   - 未登録でユーザー ID が空・規則違反・使用済み: `/auth/?mode=register&error=uid` へ戻し、ユーザー ID を入れ直してもらう（Google の認可はやり直す）。
   - `link` ありで `sub` が**別のアカウント**に既に紐づく: 拒否「このGoogleアカウントは既に別のアカウントに連携されています」。移行は行わない。
5. 応答: `popup=1` なら `window.opener` へ `postMessage({type:"ch3s-auth", status:"ok", uid, lv}, 自オリジン)` し、`BroadcastChannel("ch3s-auth")` にも同じ内容を流してから `window.close()` する最小の HTML（CSP は `script-src 'nonce-…'`）。Google を経由した後は COOP により `opener` が `null` になることがあるため（未確認）、親側は §3.8 の予備経路で拾う。
   そうでなければ `return` へ 302。`return` の検証は §3.8 と同じ。

**その他**

- Google アカウント 1 つ（`sub`）につき 3ch アカウント 1 つ。同じ人が ID/Password アカウントも別に持つことは許す（相互移行はしない）。
- 連携解除・Google → 他方式の移行はしない（§13-23）。退会は §3.9 と同じ。
- Google 側でアカウントが消えた・取り消された場合はログインできなくなるだけで、3ch 側のデータは残す。
- `redirect_uri` は要求のオリジンに `/auth/google/callback/` を付けたもの（ルートの末尾スラッシュに合わせる）。本番 `https://www.3ch.io/auth/google/callback/` と開発 `http://localhost:8787/auth/google/callback/` を Google Cloud Console に登録する（Cookie を開発用の HTTP で発行するのは localhost だけ）。
- JWKS は応答の `Cache-Control` の `max-age` の間 isolate のメモリに保持し、`kid` が無ければ 1 回だけ取り直す（`caches.default` は使っていない）。
  `GOOGLE_CLIENT_ID` は `vars`、`GOOGLE_CLIENT_SECRET` は secret（§9.3）。
- エッジ防御（§3.6）のデータセンター ASN 拒否は `start` にも適用する。
- テスト: `GoogleIdToken.verifyClaims(claims, now, nonce, clientId)` を `common` の純粋関数にして JVM でも検証。署名検証・トークン交換・JWKS は `AuthHandlerSuite` で `fetch` を差し替えて確認。

## 4. PHOEBE（Lv）

### 4.1 意味と表示

- Lv はアカウントの信頼度。0〜10。識別子の末尾 `D` に 未ログイン `0`、認証済 `1`〜`9`、10 は `*` を出す（§5）。
- Lv は識別子の `D` だけで出す。日付欄の ID は廃止済み（`BBS_ID_LV` も削除。KB-15 は廃止で解消）。
- `D` は投稿時点の実効 Lv。日中に Lv が上がれば同じ人の `D` が変わる（識別子の他の部分は変わらない。§14）。
- キャップ投稿は現行どおり識別子 `★` のみで Lv を出さない。

### 4.2 昇格

アカウントは `lv`・`lvSince`（現在 Lv になった時刻）・`lvPosts`（それ以降の成功投稿数）を持つ。成功投稿のたびに
`Phoebe.advance(state, now)`（`common` の純粋関数）を呼び、**経過時間 ≥ T(lv) かつ lvPosts ≥ N(lv)** なら 1 つ上げて両方をリセットする。
一度に 2 段以上は上がらない。N(lv) は T(lv) の時間数（例: 23h なら 23 件）。吸い込み（§8.5）と拒否された投稿は数えない。

| 種別 | T(lv) | L1 → L3 | L3 → L10 累計 |
| --- | --- | --- | --- |
| クライアント ID | 23h × 2^(lv−1) | 23h ＋ 46h ＝ 69h（約 3 日） | 23h × (4+8+…+256) ＝ 11,684h（約 487 日。失効は最後の書き込みから 1 年なので、書き込みを続ければ L10 に届く） |
| ID/Password | 23h × max(1, lv−2) | 23h ＋ 23h ＝ 46h | 23h × (1+2+…+7) ＝ 644h（約 27 日） |
| Google | — | L10 固定 | — |

PHOEBE.md の「L0〜L1 → 23h、L1〜L2 → 23×(Lv+1)h」は未ログインが L0 固定になった原案に合わせ、クライアント ID の L1 開始に読み替えた（§13）。

実装（段 C）: `common` の `Phoebe`（T(lv)・N(lv)・開始 Lv）と `AccountLevel.afterPost(kind, now)`。今回の投稿を数えてから条件を見るので、ID/Password の L1 は 23 時間後の 23 件目の投稿で L2 になる。`Account.recordPost` は昇格後の状態を記録と同じ put で保存し、Lv が変わった投稿では Cookie を発行し直す（ヘッダーの Lv 表示用）。

### 4.3 上限・降格・手動設定

- **上限ルール**（管理画面 `/admin/phoebe`）: `対象 userInfo 正規表現<>上限 Lv` の行リスト（R2 `phoebe.cgi`。`deny.cgi` と同じ構文解析）。ASN・県・端末ハッシュ・UA などで該当した投稿では `min(lv, 上限)` を実効 Lv とする。アカウントの `lv` 自体は下げない。
- **手動設定**: 管理者はアカウントの `lvOverride`（固定値）と `status`（`active` / `frozen`＝投稿不可 / `banned`）を設定できる。`lvOverride` がある間は昇格しない。
- **自動降格はしない**。規制対象となった投稿の扱いは管理者の手動操作（凍結・Lv 固定）に任せる。

実装（段 C）: 固定は `AccountState.setLevelOverride` と `Account.setLevel(lvOverride?, now)`（0〜10、未指定で解除）。固定中も `lvPosts` は数える。操作画面は段 D、上限ルール `phoebe.cgi` は §12 のとおり E-2。

### 4.4 `BBS_PHOEBE` ゲート

- 板設定 `BBS_PHOEBE=n`（既定 0。0〜10 の整数。不正値は 0 扱いで投稿は拒否しない）。スレ設定が板設定を上書きする（既存の設定スナップショット機構）。
- 投稿者の実効 Lv が `n` 未満なら拒否。拒否文言は §3.5。権限 `admin` / `delete` / `regulate` は免除（§13）。キャップ投稿は所有アカウントの Lv で判定する。
- `n` は「書き込みに必要な最低 Lv」（利用者指示）。旧 3ch の wiki は「レベル n 以下は書き込めない」（＝ n+1 以上が必要）と 1 ずれているので、`!SETTING:BBS_PHOEBE:n` の意味は §13-5 で確認する。
- **`!バルサン`**: 本文先頭が `!バルサン` ならスレ設定に `BBS_PHOEBE=3` を書く（既に 3 より大きければ変えない）。**`!SETTING:BBS_PHOEBE:n`**: 同様に `n` を書く。使えるのはスレ立て人（Thread DO の `ownerAid` と同じ `aid`。未ログインのスレは `ownerAid` が空で不可）と権限 `admin` / `delete` / `regulate`。**値を下げる操作は `staff` だけ**（権限者が上げた値をスレ立て人が戻せない）。それ以外は無視して通常投稿にする。コマンド行は本文から除かず、そのまま投稿される（2ch 互換）。
- 処理位置は §8.6。スレ設定への書き込みは `Thread.addRes` の排他区間内で dat と同じ `storage.put` に含める。
- `BBS_DISABLE_NUSI=checked` の板ではスレ立て人によるコマンドを無効にする（既存キーの流用）。同じキーで名前欄のスレ主表示 ` (主)` も止まる（実装済み）。

実装（段 C）:
- `BBS_PHOEBE` は `Phoebe.required` で読む。前後の空白を除いた 1〜2 桁の 0〜10 だけを受け付ける。スレ立て時の書き写しから外したので、スレ設定にあるのはコマンドで書いた値（と段 C より前に立ったスレの書き写し）だけ。
- 免除は `PostingAccount.staff`。権限は段 D なので、今は常に偽で誰も免除しない。
- 未ログインの専ブラが Lv 不足のときは、従来どおり `auth:required:login`（`x-auth-required: login`）で認証へ誘導する。認証済の Lv 不足は `x-post-error: auth:required:level`・`x-auth-required: level`・`x-phoebe-required: n`。新 UI は `x-phoebe-required` の値を出し、再送しない（§13-17）。
- コマンドは本文の先頭だけを読み、直後は本文の終わり・半角空白・改行のどれか（`!バルサンです` は対象外）。全角の `！` は読まない。スレ立て人の照合は `ownerAid` を足さず、既存の `owner.account`（`SHA-256(accountKey + ":" + md5Salt)`）と投稿者の同じ値を比べる。ログイン中のスレ立てでは本人をスレ立て人とする。`!バルサン` は staff でも下げない（「既に 3 より大きければ変えない」）。
- スレ設定に値が無いときの現在値は板設定の `BBS_PHOEBE`。投稿処理が板の値を `ThreadPhoebeRequest` に入れ、Thread DO はロック内でスレ設定の値と比べて書くかを決める。
- スレ立て人（staff 以外）が `!バルサン` / `!SETTING:BBS_PHOEBE:n` で求められる Lv は自分の実効 Lv まで。自分の Lv を超える指定は自分の Lv に切り詰めて書く（無視はしない）。自分より高い Lv を要求して自分が書けなくなるスレを作らせないため。staff は上限なし。
- 判定の後にスレ主が `!バルサン` で Lv を上げる競合に備え、投稿処理は投稿者の実効 Lv・免除・板の値を `addRes` の付帯情報 `phoebeAdmission` で渡し、Thread DO がロック内で最新のスレ設定に対して `PhoebeAdmission`（`common` の純粋関数）で判定し直す。足りなければ保存せず `thread-write:level:n` で拒否し、投稿処理は通常の Lv 不足と同じ応答（未ログインはログイン要求、認証済は `auth:required:level`）にする。`phoebeAdmission` が無い呼び出し（旧形式・Raw の書き込み）は判定し直さない。

## 5. 名前欄の識別子

### 5.1 書式

```
JP.AA CC-EEEFFFGGGZZZXXXD
```

| 部分 | 内容 | 算出 |
| --- | --- | --- |
| `JP` | 国コード（`cf.country`。欠損は現行どおり `unknown`） | そのまま |
| `AA` | 県 | 現行 `kenHash` と同じ: `sha1Base64(sha256(ken + group + date + saltHash)).take(2)` |
| `CC` | ASN | 現行 `asnHash` と同じ |
| `EEE`〜`XXX` | 端末情報 5 軸 × 3 桁 | 軸ごとに `sha1Base64(sha256(axisValue + "|" + axisName + group + date + saltHash)).take(3)`。欠損軸は `000` |
| `D` | Lv | 未ログイン `0`、1〜9 はその数字、10 以上 `*` |

`group`・`date`・`salt` は現行 `makeShikibetsu` と同じ（`BBS_ID_GROUP`・`BBS_ID_RESET`・`saltHash`）。`axisName` は軸の位置の文字 `E`・`F`・`G`・`Z`・`X`（実装 段 C）。`XXX` の値は `Client Hints + "|" + Accept-Language`（両方空なら欠損）。全部分が日替わりで変わる点は現行と同じ。
文字集合も現行の 2 桁ハッシュと同じ Base64 文字（`+` `/` を含む）。市区町村ハッシュと 16 桁 UA ハッシュは廃止する。

### 5.2 5 軸の値

| 軸 | 未ログイン（UA 解析） | 認証済（端末ハッシュ） |
| --- | --- | --- |
| `EEE` | OS 名＋メジャー版（Windows 11 / iOS 18 / Android 15 …） | 第 1〜2 軸（論理解像度・DPR） |
| `FFF` | ブラウザまたは専ブラの名前＋メジャー版（`Monazilla/1.00 JaneStyle/4.23` など） | 第 3〜5 軸（コア数・タッチ・色深度） |
| `GGG` | 端末種別（PC / スマートフォン / タブレット / 専ブラ / その他）＋機種系統（iPhone / iPad / Android / Mac …） | 第 6〜7 軸（CPU アーキ・フォント） |
| `ZZZ` | UA 文字列全体 | 第 8 軸（WebGL renderer） |
| `XXX` | Client Hints（現行 `g.userAgent` に追記している `sec-ch-ua-full-version-list` / `sec-ch-ua`）＋ `Accept-Language` | 第 9 軸（`Sec-CH-UA-Model`） |

- UA 解析は v3（PHP）の解析表を `common` の純粋関数 `UserAgentProfile.parse` に移植する（v3 はリポジトリ外。移植時に表を `tests/golden/ua-profile.json` に固定する）。
  - 実装（段 C）: v3 の bbs.php には解析表が無かった（`$platform` は未定義、`$browser` にも代入が無い。引き継ぎの「外部資料」）。表を推測で作らず、R2 の `ua-profiles.json` を `bbs` の `UserAgentTable`（`js.RegExp` を使うので `common` ではなく `bbs/domain`）で読む。書式は `{"os": [{"match", "flags"?, "label"}…], "browser": […], "device": […]}`。表が無いときは OS・ブラウザ・端末種別の軸を `000` にする。読み込んだ表は isolate ごとに 60 秒使い回し、R2 の読み込みや書式の失敗では最後に読めた表を使い続ける（一度も読めていないときだけ `000`）。表ごとの行数は 100 行、`match` は 200 文字までで、超えた行は読まない。表のデータを用意したら `tests/golden/ua-profile.json` に入出力を固定する。
- 認証済の軸値は端末ハッシュ 18 hex を 2 桁ずつ軸に割り当てて連結した文字列。端末ハッシュが空（全欠損）なら 5 軸とも未ログインと同じ UA 解析で埋め、`D` だけ Lv を出す。
- 端末ハッシュは Cookie の `dev`（§3.3）から取る。キーによる専ブラ認証（§3.4 の 2）では `dev` が無いので UA 解析側を使う。

### 5.3 名前欄の `(@ユーザーID)`

`develop` で実装済み（[トリップ・ID廃止とスレ主表示](トリップ・ID廃止とスレ主表示.md) §2.3）。本書の認証で変えるのは認証主体の解決だけ。

- 名前欄の `!id`（直後が英数字・`_` でないもの）を取り除き、投稿者が認証済なら名前の直後・識別子の前に ` </b>(@ユーザーID)<b>` を付ける。ユーザー ID は `uid`（§2）を HTML エスケープしたもの。Web 版はフォーム項目 `showid`（§3.8 のチェックボックス）でも同じ扱い。
- 利用者は `<` `>` を入力できない（エスケープされる）ので、手入力の `(@admin)` とは区別でき、偽装できない。
- 未ログイン・旧キャップ・キャップ投稿では `!id` を取り除くだけで付けない。`BBS_NAME_COUNT` の検査は付ける前の名前で行う。
- dat の第 1 フィールドは `名前 </b>(@ユーザーID)<b> </b>(識別子)<b> (主)` の順。dat 構造は変わらない。
- 認証主体は `PostingUser`（`accountKey` に `aid`、`displayId` に `uid`）で渡す。段 B で `PostingUserResolver.unresolved` を Cookie・専ブラ用認証キー・キャップ（§3.4）からの解決に差し替えると、`!id` とスレ主表示の `account` 照合が有効になる。

### 5.4 設定キーと互換

- `BBS_ID_SHIKIBETSU=phoebe` で新書式。`checked` / `raw` / `ken` / `city` / `as` / `d` は現行のまま。既定値を `phoebe` に切り替えるのは承認後（不変条件 3.4）。
- 投稿ログ（`log`）の `userInfo` は書式を変えず、`UID:` に `uid`（未ログインは空）、`Device:` に端末ハッシュ（未ログインは UA 解析の要約 `os/browser/device`）を入れる。これにより `deny.cgi` の対象正規表現でアカウント・端末を狙える。

## 6. 権限・キャップ・専ブラ用認証キー

### 6.1 権限

| 権限 | できること |
| --- | --- |
| `admin` | 全て。他アカウントの権限付与・Lv 固定・凍結、キャップの無制限発行と他アカウントへの紐づけ |
| `delete` | スレ停止・削除・レス削除（管理画面の該当操作） |
| `regulate` | `deny.cgi` / `ngwords.cgi` / `phoebe.cgi` の編集、端末・アカウント BAN |
| `cap` | キャップでの投稿（キャップを持てる） |

- 付与できるのは ID/Password と Google のアカウントのみ。クライアント ID には付けない。
- 権限者向けの操作は **`/admin/*` に統合**し、Basic 認証をやめて役割で画面と操作を出し分ける（2026-11-02 利用者指示。詳細は [管理画面 改修計画](admin-spa-auth-plan.md)）。入場時にパスワード再入力（Google は再認証）を求め、短命の管理者セッション `__Host-ch3s_admin`（`SameSite=Strict`・無操作 30 分・最長 8 時間）を別に発行する。変更系は `Origin` を検証し、`cache-control: no-store` と CSP を維持する。初回の `admin` は `ADMIN_BOOTSTRAP_TOKEN` で作る。
- 画面と権限の対応（段 D で確定）: スレ停止・再開・削除・レス削除・移動 → `delete`。`deny.cgi` / `ngwords.cgi` / `phoebe.cgi` / `edge-rules.json` の編集、端末・アカウント BAN、乱造の要確認一覧 → `regulate`。アカウントの権限・Lv・状態・キャップ紐づけ、板設定（`SETTING.TXT`・`head.txt`）、`bbsmenu.html`、監査ログ → `admin`。

### 6.2 キャップ

- 発行は権限を 1 つ以上持つアカウントが自分宛に 1 個まで。`admin` は無制限で他アカウント宛にも発行できる。
- 保存: `Account.caps[]`＝`{capId, name, hash, strong, issuedBy, issuedAt, revokedAt}`。`hash` は現行の照合値 `SHA-1(SHA-256(pass+saltHash))` 先頭 27 文字をそのまま使う。
- 互換: KV `CAP`（hash → 表示名）は現行どおり読み書きし、所有者は `AccountIndex("cap")`（hash → `aid`）で引く。所有者のない既存エントリは「旧キャップ」として動き続ける（ログ無し・Lv 無し）。管理画面から既存キャップをアカウントに紐づけられる。
- 強キャップ（表示名末尾 `★`、サイズ検証スキップ）は `admin` だけが発行できる。
- 利用ログ: `Account.log`（§9.1 の書き込み記録）に `via: "cap"` と `capId` 付きで残る。所有者と `admin` が閲覧でき、失効（`revokedAt`）と再発行（新 hash）ができる。失効時は KV `CAP` からも削除する。
- キャップ投稿は所有アカウントとしてログイン扱い（§3.4）。dat 上の表示（`名前＠ハンドル ★` と識別子 `★`）は現行のまま。`(@ユーザーID)` と ` (主)` は付けない。

### 6.3 専ブラ用認証キー

**方針（2026-11-05 利用者指示）**: 不正利用と荒らしを防ぐため、キーは UA（User-Agent）ごとに使わせる。

- キーを最初に使った UA を記録し（紐づけ）、以後は異なる UA からは使わせない。
- 1 つのアカウントで、同じ UA に紐づくキーは 1 個だけにする。同じ UA で別のキーが使われたら、前のキーを無効にする。

**形式と発行**

- 形式は `!#` ＋ 20 文字 `[a-z2-7]`（100 bit）。平文は発行画面で 1 度だけ表示する。
- キーは Web（`/auth/account`）で発行し専ブラで使うので、発行した時点では UA が決まらない。発行したキーは「未使用」で、最初に使われた時点でその UA に紐づく。
- 未使用のキーは同時に 1 個まで。新しく発行すると、前の未使用キーは無効になる。発行から 24 時間使われなければ失効する。
- 紐づいたキーは 1 アカウントにつき最大 5 個（5 つの UA）。上限に達した状態で 6 つ目の UA に紐づけようとすると拒否し、アカウント設定で不要なキーを無効にしてもらう（§13-31）。

**UA の扱い**

- 照合するのは要求の `User-Agent` ヘッダそのもの（前後の空白だけ除く）。現行の `g.userAgent` は Client Hints を後ろに付け足すので使わない。
- 保存するのは `HMAC-SHA256(AUTH_SECRET, "token-ua:" + UA)` だけで、生の UA は保存しない。アカウント設定に出すため、`UserAgentProfile`（§5.2）で解析した表示名（例: `JaneStyle 4.23 · Windows`）を一緒に残す。
- 比較は完全一致。専ブラを更新して UA の版番号が変わると同じキーは使えなくなり、新しいキーの発行が要る（§13-30）。
- 受け付けるのは専ブラ（現行の `isMonazilla`）からの書き込みだけ。Web ブラウザはログインで認証する（§13-32）。
- キー認証は HTTPS の要求に限る（§3.3）。

**使ったときの判定**

判定は `domain/account/DedicatedBrowserTokens` の純粋関数 `use(tokens, tokenId, uaHash, now)` が行い、`Account.useToken` が `blockConcurrencyWhile` の中で読込 → 判定 → 保存する（AGENTS.md 3.3）。同じアカウントへの同時の使用も直列になる。

| キーの状態 | 結果 |
| --- | --- |
| 未使用・期限内 | 許可し、この UA に紐づける。**同じ UA に紐づく別の有効なキーがあれば無効にする**（理由 `superseded`） |
| 紐づき済み・UA が一致 | 許可し、最終利用時刻を更新する |
| 紐づき済み・UA が不一致 | 拒否（`auth:token:ua-mismatch`）。キーは無効にしない（漏れたキーを他人が試しただけで正規の利用者のキーが止まらないようにする）。利用ログに残し、アカウント設定に「別の専用ブラウザから使われそうになりました」と出す |
| 無効化済み・失効 | 拒否（`auth:token:revoked`） |
| 索引に無い | 拒否（`auth:token:unknown`） |
| 未使用だが紐づけ上限 | 拒否（`auth:token:limit`） |
| 専ブラ以外からの使用 | 拒否（`auth:token:not-dedicated`）。キーの状態は変えない |

**Cookie との関係**

- キーで認証できたら通常セッションの Cookie（§3.3）を発行する。この Cookie には紐づけ情報 `bind`（キー ID と UA ハッシュの先頭 16 文字）を入れる。
- その Cookie で書き込むたびに、要求の UA が `bind` と一致することと、`describeForPosting(aid, gen, tokenId)` でキーがまだ有効なことを確かめる。一致しなければ Cookie は無いものとして扱う（未ログイン）。
- そのため、Cookie を別の UA に写しても使えない。キーが無効になる（置き換え・手動の無効化・退会）と、そのキーから発行したセッションも次の書き込みから無効になる。

**保存**

- `Account.tokens`: `[{tokenId, hash, issuedAt, expiresAt, uaHash?, uaLabel?, boundAt?, replacedTokenAt?, lastUsedAt?, recentPosts, revokedAt?, revokeReason?}]`。無効になったものも 30 日は残して一覧に出し、その後は消す。無効にした記録は新しいものから 20 件までにする。発行は 1 アカウント 1 日 10 回まで（`AuthRate` の `token`）。
- `recentPosts` はそのキーでの直近 5 件の書き込み `{t, bbs, key, title, resNum, excerpt, network}`。`excerpt` は本文の先頭 40 文字（`<br>` を空白にしたエスケープ済みの文字列。文字参照の途中では切らない）、`network` は AS 名と県（海外は国）の表示名。書き込みが成功したとき `Account.recordPost`（§4.2）で一緒に更新する。
- キーの平文とハッシュは画面に出さない（平文は発行直後に 1 度だけ）。利用者はキーを UA で見分ける。
- `AccountIndex("token")`: `HMAC-SHA256(AUTH_SECRET, "token:" + キー)` → `{aid, tokenId}`。置き換え・手動の無効化・未使用の失効でキーが無効になったら、Account DO が保存の後に索引から外す（照合の正は Account DO の `tokens` なので、外し損ねても使えない）。
- 許可した使用は利用ログに `tokenId`・`uaLabel`・結果を残す。拒否（UA 不一致などの各拒否コード）は利用ログと分けて `rejections` に新しい順で 20 件まで残し、漏れたキーの試行で書き込み記録を押し出さない。

**アカウント設定（`/auth/account`）**

キーは後から表示できないので、キーそのものではなく**キーを使っている専用ブラウザ（UA）ごとの書き込み**を一覧にして、不正利用に気づけるようにする。モックは `docs/ui/account.html`。

- 1 項目が 1 つの UA。見出しは UA の表示名と OS、使い始めた日時（同じ UA でキーを置き換えたときはその旨）。
- 各項目に最終書き込みの日時・書き込み先（板 › スレタイ、レス番号。スレへのリンク）・本文の先頭・接続元（回線と地域）を出し、折りたたみで直近 5 件を見られるようにする。
- 各項目に「このブラウザのキーを無効にする」ボタンを置く（`POST /auth/account?action=revokeToken&tokenId=…`。押すとそのキーから発行した Cookie も無効になる）。
- 見出しに使用枠（使用中の UA の数 / 5）を出す。UA が一致しない使用があったときは、日時・試した側の表示名・対象の UA を帯で知らせる。
- 未使用のキーは一覧の下に 1 行で出し、失効までの残り時間と「取り消す」を置く。新しいキーの平文は発行直後の枠に 1 度だけ出す。
- キーを無効にした UA は、折りたたみの中に 30 日間（無効にした理由と最終書き込み）出す。
- 怪しい書き込みの自動判定はしない。判断は利用者に任せ、接続元の違いなどで見分けられるようにする。

キャップ利用者は不要（キャップでログイン扱いになるため）。

### 6.4 名無しでの権限使用

権限 `admin` / `delete` / `regulate` を持つ人がキャップを付けずに投稿した場合も、その権限は規制回避（§7）に使われる（原案「キャップ書き込み以外は利用可能」）。
**`cap` 権限は名無しでは使えない**: キャップを付けた投稿（★）でだけ `privileged` の免除を受ける。表示上は一般投稿者と区別がつかない。

## 7. 規制回避レベル

規制ごとに「どの属性なら免除するか」を 1 段階で指定する。上の段階ほど免除される人が少ない。

| 値 | 免除される投稿者 |
| --- | --- |
| `admin` | 権限 `admin` のみ |
| `staff` | `admin` / `delete` / `regulate` |
| `privileged` | `staff` ＋ キャップを付けた投稿（★。旧キャップを含む） |
| `l10` | `privileged` ＋ 実効 Lv 10 |
| `login` | `privileged` ＋ 認証済（§2 の定義。実効 Lv が上限ルールで 0 でも免除） |

適用先と既定値:

| 規制 | 指定場所 | 既定 | 備考 |
| --- | --- | --- | --- |
| `deny.cgi` / `ngwords.cgi` の各行 | 5 列目（省略可） | `privileged` | 現行の「★ 投稿は全規制スルー」に、`staff` の名無し投稿の免除が加わる（§14）。4 列の行はそのまま有効 |
| 連投規制（§8 の 5 種） | 板設定 `BBS_RENTOU_PASS` | `privileged` | スレ設定で上書き可 |
| スレ立て規制 | 板設定 `BBS_THREAD_PASS` | `privileged` | 現行の「キャップ除外」を置き換える。判定もせず履歴にも残さない点は維持 |
| `BBS_PHOEBE` ゲート | 固定 | `staff` | §4.4 |

`saku` 板は現行どおり `deny.cgi` / `ngwords.cgi` をスルーする。スレ立て規制（現行）と連投規制（§8）は `saku` にも適用する。

## 8. 連投規制

**状態: 段 A-1 実装済み（2026-11-06）。** 実装の仕様は [post-rate-limit.md](post-rate-limit.md)。本節との差は各項の「実装」の注記に書いた。

### 8.1 共通

- **適用除外**: `BBS_LIVE` が `checked` または `1` の板・スレ（5 種すべて。既存の強制 sage は `checked` のときだけで変えない）。`BBS_RENTOU_PASS` で免除される投稿者。`saku` 板には適用する。
- **照合キー**: 板設定 `BBS_RENTOU_KEYS`（§8.8）で選ぶ。ごんたくれ（§8.2）とスレ単位（§8.4）に使う（実装）。Samba24（§8.3）は `ip,aid` 固定、広域連投規制（§8.3a）は `shiki` だけで照合する。候補は `ip`（IPv6 は URL 正規化、スレ立て規制と同じ）、`shiki`（識別子。`D` を除いた `JP.AA CC-EEEFFFGGGZZZXXX`。`★` は照合しない）、`aid`（認証済のアカウント。移行しても変わらない）。選んだキーのいずれか 1 つでも一致すれば「同一」。
  既定は `ip,shiki,aid`（原案の `id` は日付欄の ID の廃止で使えなくなったので外した）。値に `id` が書かれていれば無視する。**ただし `shiki` は同じキャリア・同じ地域・同じ機種の別人で一致しうる**（現行の `checked` 識別子は県・市・AS・UA のハッシュ。新書式でも iOS は機種クラス粒度）。Samba24 のような秒単位の板全体規制では巻き添えが起きるため、§13-24 で Samba24 を `ip,aid` に限り、識別子は短い秒数の広域連投規制（§8.3a）で見ることにした。`BBS_ID_SHIKIBETSU=phoebe` の板では UA 解析表が無い間 E/F/G が `000` で市も含まず粗すぎるため、`shiki` は照合しない（`checked` のときだけ使う）。巻き添えの実測は `BBS_FR_LEVEL=log`（下記）を書いた板で行う。
- **段階**: 板設定 `BBS_FR_LEVEL`。`off` は判定も記録もしない。`log` は判定と履歴の記録だけ行い、拒否せず Workers Logs に出す（試行モード）。
  キーが無い（既定 `1`）・1 以上の整数は規制モードで拒否し、広域連投規制（FlyRibbon, FR。§8.3a）も判定する。整数は旧 3ch の広域連投規制の強さで、意味は未実装（型には保持する）。
  `0` と空の値は規制モードだが広域連投規制だけを判定しない（§13-35）。`log` は広域連投規制も判定して記録する。
  それ以外の値（負数・`OFF` などの不正な文字列）はキー無しと同じにし、設定ミスで規制が外れないようにする。前後の空白は除き、`off` / `log` は小文字だけに一致させる（`BBS_LIVE` の `checked` と同じく値をそのまま比べる）。
  **コード既定は規制モード**（2026-11-06 利用者決定。デプロイした時点で全板で拒否が始まることも承認済み）。当初案の `BBS_RENTOU_MODE`（`off` / `log` / `on`、既定 `log`）は置き換え、読まない。
- **保存値**: 全てソルト付き SHA-256（`hash(value + ":" + saltHash)`。スレ立て規制と同じ `digest`）。空値は空のまま保存し照合しない。
- **予約中の扱い**: 有効な予約（§8.7）は確定済みと同じく件数に 1 件として数える（スレ立て規制の「別枠で 1 件ずつ」と違い、ごんたくれの件数窓にも入れる）。
- **判定主体**: 板単位（§8.2・8.3・8.3a）は Board DO、スレ単位（§8.4・8.5）は Thread DO。純粋判定は `domain/posting/moderation/PostRateLimit.scala`。
- **設定の出どころ**: 板単位の規制は**板設定だけ**で判定する（スレ設定の書き写しを見ない）。スレ単位の規制と `BBS_PHOEBE` は「明示的なスレ設定 → 板設定」の順。スレ立て時の設定書き写しから本書の新キーを除く（§8.8）。
- **拒否**: HTTP 200 ＋ `<!-- 2ch_X:error -->`。dat・履歴・Lv を進めない。拒否コードは §8.9。

### 8.2 板ごと: ごんたくれ規制（`timecount` / `timeclose`）

板の確定済み投稿履歴を新しい順に `timecount` 件取り、そのうち **24 時間以内**（`now − 86400 ≤ t`）で同一の投稿が `timeclose` 件以上あれば拒否。
`timecount=0` または `timeclose=0` で無効。既定値は現行の既定設定（`PostingText.settingTxtDefault`）と同じ `timecount=18` / `timeclose=10`。
`timecover` は読まない（§13）。

### 8.3 板ごと: Samba24（`BBS_SAMBA24`）

板の履歴に `now − BBS_SAMBA24 ≤ t` で同一の投稿が 1 件でもあれば拒否。`0` で無効。既定 `10`（§13）。上限 86,400。
同一は `BBS_RENTOU_KEYS` によらず IP か `aid` のどちらかが一致すること（§13-24、2026-11-06 利用者決定）。識別子だけが一致する投稿は §8.3a で見る。

### 8.3a 板ごと: 広域連投規制（FlyRibbon。`BBS_FR_LEVEL` / `BBS_FR_SECOND`）

板の履歴に `now − BBS_FR_SECOND ≤ t` で識別子（`shiki`）が一致する投稿が 1 件でもあれば拒否。`0` で無効。既定 `1`、上限 86,400、不正値は既定値。
有効にするのは `BBS_FR_LEVEL` が 1 以上（キー無し・不正値を含む）か `log` のときだけで、`0`・空なら秒数によらず判定しない（§8.1、§13-35）。
識別子が空や `★`（キャップ。照合値を空にする）なら照合しない。判定は Samba24 の次（ごんたくれ → Samba24 → 広域連投規制）。
同じ地域・キャリア・機種の別人を巻き込みうるので、秒数は短く置く（2026-11-06 利用者決定）。

### 8.4 スレごと（`BBS_THREAD_RENTOU` / `BBS_THREAD_RENTOU_RESET`）

書き込み先スレで `now − RESET ≤ t` の投稿のうち、同一 IP または同一識別子（ID は見ない）が `BBS_THREAD_RENTOU` 件以上なら拒否。
既定 `40` / `3600`。`0` で無効。>>1 には適用しない。
実装: 同一の判定は §8.1 の `BBS_RENTOU_KEYS` を使う（既定では IP・識別子・aid のどれかの一致）。>>1 も `recentPosts` には記録する。

### 8.5 マルチポスト／コピペ吸い込み

スレの dat の末尾 2 行（あぼーん行＝4 フィールド全て `あぼーん` は除く）の本文フィールド（前後の空白を含む `<br>` 整形後の文字列）と、
新しい dat 行の本文フィールドが**完全一致**すれば、時間によらず吸い込む。投稿者を問わない（原案どおり）が、
別人の短い相づち（`ｗ`・`>>1乙`）も黙って捨てる副作用は承知のうえで、同一投稿者に限らないことに決めた（§13-25）。

- 応答は成功画面（「書きこみが終わりました。」、`x-threadkey`、`x-resnum` は採番予定だった番号）と同じ。エラーマーカーも吸い込みを示すヘッダも出さない（スクリプトに検知させないため。新 UI は採番予定番号のレスを取りに行って空振りするだけで、エラーにしない）。
- dat・`log`・subject・headline・過去ログ目次は更新しない。Lv の投稿数も進めない。
- 板の連投履歴は**確定する**（吸い込まれた投稿も連投として数える）。
- >>1 には適用しない。Thread DO の `addRes` は戻り値を `{count, swallowed}` の構造にし（§8.7）、handler が `swallowed` なら成功画面に変換する。

### 8.6 パイプライン位置

```
checkRequest → readForm（名前欄の !id・!no を解釈。既存）→ processCap → boardSettings → threadSettings
→ identify → resolveUser（認証主体の決定 §3.4、Lv・権限の取得。既存の PostingUserResolver を差し替える）
→ Moderation（deny/ngwords。5 列目の回避レベル）
→ checkSizes → 認証要求判定（§3.5）・BBS_PHOEBE ゲート（§4.4）
→ threadCreationLimit（既存）→ makeShikibetsu（識別子と (@ユーザーID)。既存）→ markNusi（スレ主表示。既存）
→ postRateLimit の材料作成（ハッシュ化。§8.1）
→ 本文コマンド（`!バルサン` / `!SETTING:BBS_PHOEBE:n` §4.4）の解釈
→ updateThread: Thread.addRes
     ├ スレ単位 連投判定（§8.4）        ← 拒否なら終了
     ├ Board.reservePost（§8.2・8.3・8.3a） ← 拒否なら終了（`BBS_FR_LEVEL=log` なら記録だけ）
     ├ コピペ判定（§8.5）                ← 吸い込みなら Board.completePost して `{swallowed}` で終了
     ├ storage.put（dat・log・recentPosts・ownerAid（>>1 のみ）・settings（コマンドがあれば））  失敗なら Board.cancelPost
     └ Board.updateThread（既存。引数に予約 key を足して completePost を兼ねる）
→ Account.recordPost（Lv 昇格 §4.2・書き込み記録と観測の追記 §9.1・AccountDirectory の更新。失敗しても投稿は成功扱い）
→ updateSubback → updateHeadline
```

実装: 板の連投予約の確定は `Board.updateThread` に兼ねさせず、別の RPC `completePost` で行う（`updateThread` の引数と失敗時の扱いを変えないため）。
吸い込みでは `put` も `updateThread` もしない（`recentPosts` も進めない）。

`makeShikibetsu` の後に置くのは識別子を照合キーに使うため。スレ立て規制の予約（既存）と板の連投予約は同じ `Board` DO の別キーで、
Thread.addRes 内の順序は「スレ立て予約 → 連投予約 → put → 両方の確定」とする。

### 8.7 保存・同時実行

**Board DO**（既存キーは変更しない）

| キー | 内容 |
| --- | --- |
| `postRateMeta` | `{newest: ページ番号, oldest: ページ番号}`（実装では `count` を持たない。範囲から外したページの件数は読まないと分からないため） |
| `postRateHistory:<n>` | 64 件ごとのページ（追記専用のリングバッファ。`n` は通し番号を 16 で割った余り）。各件 `{key, t, ip, shiki, aid, status}`（`key` は予約 ID＝`{threadKey}:{nonce}`、`status` は `pending` / `completed`） |

- RPC: `reservePost(key, entry, settings): String`（空＝許可、それ以外＝拒否コード）、`completePost(key)`、`cancelPost(key)`。いずれも `DurableLock.serialized`。
- 書き込みは**最新ページと `postRateMeta` だけ**（スレ立て規制の全ページ読み書きを踏襲しない。全投稿で呼ばれるため）。読み込みは判定に要る件数分のページだけ（直近 `timecount` 件と `BBS_SAMBA24`・`BBS_FR_SECOND` の大きい方の秒数以内。最大でも 1,000 件＝16 ページ）。`complete` / `cancel` は `key` を含むページだけ書き換える。
- 判定は確定済みと有効な予約を合わせて件数窓で行う（§8.1）。予約の有効期限は 120 秒。期限切れの予約は確定済みと同じ扱い（保守的）。
- 保持規則: **確定済みの直近 `max(timecount, 1)` 件 ∪ `now − BBS_SAMBA24 ≤ t` の件 ∪ `now − BBS_FR_SECOND ≤ t` の件 ∪ 有効な予約**（秒数の 2 つは大きい方で足りる。板設定だけで決め、予約中は数えず、追記前の履歴で見直すので取消で範囲が戻る。スレ設定の `BBS_FR_LEVEL` で縮めない）。24 時間分を残さない（§8.2 は直近 `timecount` 件しか見ない）。上限 1,024 件（16 ページ）。超えたら古いページから捨てる。**容量で拒否しない**（板全体の投稿停止や意図的な埋め立てを防ぐ）。
- 集約 `PostRateHistory`（純粋）が `reserve` / `complete` / `cancel` / `retained` を持ち、DO は `PostRateCodec` で変換して読み書きするだけ。

**Thread DO**

| キー | 内容 |
| --- | --- |
| `recentPosts` | `{t, ip, shiki, aid}` の配列（新しい順）。`now − BBS_THREAD_RENTOU_RESET ≤ t` の件だけ残し、上限 1,000 件。照合値は先頭 12 文字に短縮（DO の値の上限 128 KiB のため）し、スレの移動で引き継ぐ |
| `ownerAid` | スレ立て人の `aid`（未ログインなら空）。`!バルサン` 等の権限判定に使う（§4.4）。既存の `owner.account`（`SHA-256(accountKey + ":" + saltHash)`。スレ主表示用）と同じ値で照合できるなら、このキーは足さずにそれを使う |

- `addRes` の排他区間内で判定・追記し、`dat`・`log` と同じ `storage.put` で一括保存する。コピペ判定も同じ区間内で `dat` 末尾 2 行を見る。
- 既存の `addRes` 引数 `posting` に `rateLimit`（§8.1 の材料と設定）と `commands`（§4.4 のスレ設定変更）を足し、戻り値を `{count, swallowed}` にする。`rateLimit` が無ければ判定しない（管理画面やテストの既存経路を壊さない）。
  実装（段 A-1）: `rateLimit` を足し、戻り値は `{count, swallowed, observed}`（`observed` は試行モードで記録だけした判定のコード）。
  実装（段 C）: `commands` は `{phoebe: {level, lower, board}}`。スレ設定の `BBS_PHOEBE=` の項目を外して末尾に置き直し、ほかの項目は書かれたまま残す。吸い込んだ投稿では書かない。

### 8.8 設定キー

| SETTING.TXT | 既定 | 範囲 | 意味 |
| --- | --- | --- | --- |
| `timecount` | `18` | 0〜1000 | §8.2 の件数窓。0 で無効 |
| `timeclose` | `10` | 0〜1000 | §8.2 の同一件数。0 で無効 |
| `BBS_SAMBA24` | `10` | 0〜86400 | §8.3 の秒数。0 で無効。照合は `ip,aid` 固定 |
| `BBS_FR_SECOND` | `1` | 0〜86400 | §8.3a の秒数。0 で無効。`BBS_FR_LEVEL` が FR を外していれば見ない |
| `BBS_THREAD_RENTOU` | `40` | 0〜1000 | §8.4 の件数。0 で無効 |
| `BBS_THREAD_RENTOU_RESET` | `3600` | 0〜315360000 | §8.4 の秒数 |
| `BBS_RENTOU_PASS` | `privileged` | §7 の値 | 連投規制の回避レベル |
| `BBS_RENTOU_KEYS` | `ip,shiki,aid` | カンマ区切り | §8.1 の照合キー（ごんたくれとスレ単位）。§13-24 |
| `BBS_FR_LEVEL` | `1`（キー無し） | `off` / `log` / `0`・空 / 1 以上の整数 | §8.1 の段階。1 以上・不正な値は規制＋FR、`0`・空は FR を外した規制 |
| `BBS_LIVE` | 空 | `checked` / `1` | 連投規制の適用除外（既存の強制 sage 動作は `checked` のまま） |
| `BBS_PHOEBE` | `0` | 0〜10 | §4.4 |

実装（段 A-1）: 書き写しから除くのは `BBS_SAMBA24`・`BBS_FR_SECOND`・`BBS_THREAD_RENTOU(_RESET)`・`BBS_RENTOU_PASS`・`BBS_RENTOU_KEYS`・`BBS_FR_LEVEL` だけ。
`BBS_PHOEBE` は段 C で足した。`BBS_THREAD_PASS` は段 D で足す。`timecount` / `timeclose` の書き写しをやめるのは既存の振る舞いを変えるので承認待ちとし、今は書き写す（板単位の規制は板設定だけを読むので効かない）。

読み込みは統合後の `BbsSettings`（計画書 段 0）を使う。不正値は**返信も拒否せず既定値で動く**（`BBS_FR_LEVEL` だけは不正値を規制モード＋FR にする。§8.1。スレ立て規制が不正値で拒否するのと異なる。
連投規制は設定ミスで板全体を止めるより通す方が被害が小さいため。管理画面の保存時には検証する）。
**スレ立て時の設定書き写し**（現行はその時点の板設定を全キー `Thread.settings` に保存し、返信ではそれが板設定を上書きする）から、本書の新キー
（`BBS_PHOEBE`・`BBS_SAMBA24`・`BBS_FR_SECOND`・`BBS_THREAD_RENTOU(_RESET)`・`BBS_RENTOU_PASS`・`BBS_RENTOU_KEYS`・`BBS_FR_LEVEL`・`BBS_THREAD_PASS`）と既存の `timecount` / `timeclose` を除く。
除かないと、荒らしの最中に板設定を変えても既存スレに効かず、Board DO の板単位判定がスレごとに違う設定で動く。スレ設定にこれらのキーがあるのは `!SETTING` 等で明示的に書いた場合だけとし、
スレ単位の規制と `BBS_PHOEBE` はそれを板設定より優先する。既存キーの書き写しは変えない。

### 8.9 拒否コードと文言

コードは DO / ドメインが返し、文言は `PostingText` だけが持つ。待ち秒数のように文言に要る値はコードの末尾に `:値` で付ける。

| コード | 文言 |
| --- | --- |
| `post-rate:board:gontakure` | `連続投稿ですか？？ しばらく待ってから投稿してください。` |
| `post-rate:board:samba24:<残り秒>` | `Samba24 で規制中です。(あと n 秒) しばらく待ってから投稿してください。` |
| `post-rate:board:fr:<残り秒>` | `広域連投規制中です。(あと n 秒) しばらく待ってから投稿してください。` |
| `post-rate:thread:rentou` | `このスレッドへの連続投稿が多すぎます。しばらく待ってから投稿してください。` |
| `post-rate:thread:copipe` | （成功画面。§8.5） |
| `auth:required:login` / `auth:required:level:<n>` | §3.5 の文言＋ URL |
| `auth:frozen` / `auth:banned` | `このアカウントは現在書き込みできません。` |
| `auth:token:ua-mismatch` | `この認証キーは別の専用ブラウザで使われています。アカウント設定で、この専用ブラウザ用のキーを発行してください。` |
| `auth:token:revoked` / `auth:token:unknown` | `認証キーが無効です。アカウント設定で新しいキーを発行してください。`（どちらも同じ文言） |
| `auth:token:limit` | `認証キーを使える専用ブラウザの数が上限です。アカウント設定で不要なキーを無効にしてください。` |
| `auth:token:not-dedicated` | `認証キーは専用ブラウザからの書き込みでだけ使えます。ブラウザではログインしてください。` |
| `auth:token:insecure` | `認証キーは HTTPS で接続したときだけ使えます。` |
| `phoebe:command:denied` | （コマンドを無視して通常投稿。文言なし） |

`x-post-error` ヘッダ（§3.8）にはコードの先頭 3 要素だけを載せる（`post-rate:board:samba24`・`post-rate:board:fr`）。段 A-1 ではこのヘッダはまだ出さない（応答ヘッダの追加は別途承認）。

## 9. ストレージ・バインディング

### 9.1 新 DO

いずれも `new_sqlite_classes` で作る（`wrangler.jsonc` の TODO どおり SQLite バックエンド）。保存 API は既存と同じ KV 形式のストレージ API を使う。

**`Account`**（`idFromName(aid)`）

| キー | 内容 |
| --- | --- |
| `profile` | `{aid, uid, kind: client\|password\|google, createdAt, expiresAt?, loginId?, email?, emailVerifiedAt?, googleSubHash?, agreedAt, agreementVersion, status, generation}` |
| `activation` | 登録準備状態の `pending` / `ready`。凍結等のstatusとは別。準備中は認証不可。既存Clientだけはキー欠損をreadyとして読み、PasswordとGoogleは必須。readyのPasswordには有効なcredentialとUIDに一致するloginIdも必要 |
| `credential` | ID/Password: `{algo: "pbkdf2-sha256", iterations, salt, hash, updatedAt, throttle: {sources: {keyIpHash: {count, since, lockedUntil?}}, account: {count, since, lockedUntil?}}}`。`salt`（16 バイト）と `hash`（32 バイト）は小文字の 16 進数。`sources` は最大 100 接続元で期限切れだけを捨て、`account` は全接続元の失敗数と全体ロック。メール認証・パスワード再設定の保留トークンは別キー（`pending:email` / `pending:reset`） |
| `level` | `{lv, lvSince, lvPosts, lvOverride?}` |
| `roles` | `["admin", ...]` |
| `adminGen` | 管理者セッションの世代（整数）。管理者のログアウト・役割変更・凍結で進める（改修計画 §3.2） |
| `caps` | §6.2 |
| `tokens` | 専ブラ用認証キーの一覧（§6.3 の「保存」） |
| `pending:email` / `pending:reset` | §3.9 の保留トークン（種別ごとに別キー） |
| `devices` | `[{hash, firstSeen, lastSeen, keyIpHash}]` 最大 10 件。超えたら `lastSeen` が最も古いものを捨てる |
| `rejections` | 専ブラキーの照合で拒否した記録（`log:<n>` と同じ 1 件の形）。新しい順に 20 件まで |
| `log:<n>` | 書き込み記録。1 件は `{t, via: web\|token\|cap, tokenId?, capId?, result, bbs, key, title, resNum, excerpt, asn, asName, country, region, uaLabel, deviceHash?, id, shikibetsu}`。`result` は `posted` か拒否コード（キーの UA 不一致など、アカウントに結びつく拒否だけ）。`excerpt` は本文の先頭 40 文字。キーの照合の拒否はここではなく `rejections` に残す。64 件ごとのページで、16 ページ（1,024 件）を超えたら最古のページを消す |
| `seen` | 観測の集計。`as`・`region`・`ua`・`device` は値ごとに `{value, label, count, firstSeen, lastSeen}` を最大 50 件（`lastSeen` が古いものから捨てる）。`ua` の `value` は生の UA（管理画面の詳細でだけ出す。本人の画面には表示名だけ）。`ident` は（日付, 板）ごとに `{date, bbs, shikibetsu, count}` を 60 日分 |
| `directorySyncedAt` | `AccountDirectory` へ最後に反映した時刻 |

RPC: `create`, `describeForPosting(aid, gen)`, `describeForAdmin(aid, gen, adminGen)`, `bumpAdminGen`, `recordPost(now, post, observation)`, `verifyPassword(password, now)`, `setPassword`, `issuePending(kind, hash, expiresAt, email?)` / `consumePending(kind, hash, now)`, `bindGoogle(subHash)`, `convert(kind, uid)`, `issueCap` / `revokeCap`, `issueToken` / `useToken(tokenId, uaHash, now)` / `revokeToken`, `bindDevice`, `setLevel`, `setRoles`, `setStatus`, `bumpGeneration`, `appendLog`, `inspect`。
状態遷移（昇格・失効・移行）は `domain/account/` の純粋関数。

**`AccountIndex`**（`idFromName(kind)`。`kind` ∈ `uid` / `login` / `email` / `google` / `cap` / `token` / `role:admin`）

- `role:admin` は `admin` 役割を持つアカウントの一覧で、キーは `HMAC-SHA256(AUTH_SECRET, "role:admin:" + aid)`。初回の管理者の確保 `claimFirstAdmin(aid, now, breakGlass)` を持つ（改修計画 §3.4）。

- キー `=` 正規化した値の `HMAC-SHA256(AUTH_SECRET, 種別 + ":" + 値)`（`wrangler.jsonc` の `salt` は `vars` にあり秘密ではないので、辞書攻撃を防ぐため secret で鍵付きにする）、値 `=` `{aid, state: pending|confirmed, createdAt}`。
- `register(hash, aid, now)` は既存（`confirmed`、または `pending` で 10 分以内）なら失敗、`confirm(hash)`、`lookup(hash)`、`remove(hash)`。`register` / `confirm` / `remove` は `blockConcurrencyWhile` で直列化し、`lookup` はロックしない。`uid` 種別は `remove` を持たない。
- 1 種別 1 DO にするのは一意性を単一の直列化点で保証するため。件数は数万件規模を想定し、ページ分割は不要。書き込みは登録・移行・退会時だけで、ホットスポットにはならない（`lookup` は読み取りのみ）。

**`AccountDirectory`**（`idFromName("global")`。管理画面のユーザー一覧・検索用）

- `Account` は 1 アカウント 1 DO なので、アカウントをまたいだ並べ替えと検索ができない。そのための写しを 1 つの DO に持つ。正は各 `Account` で、ここは表示用の写し。
- SQLite バックエンドの SQL（`ctx.storage.sql`）を使う。表 `accounts(aid PRIMARY KEY, uid, kind, lv, status, roles, created_at, last_post_at, last_bbs, last_key, last_title, last_res, last_excerpt, last_as_name, last_region, posts_24h, ua_count, device_count, token_count, flagged)` に `last_post_at`・`created_at`・`lv` の索引を張る。表 `observations(kind, value_hash, aid, last_seen, PRIMARY KEY(kind, value_hash, aid))` で AS・UA・端末ハッシュ・識別子・IP ハッシュからアカウントを引く。
- 更新は `Account.recordPost` の後に `ctx.waitUntil` で行い、同じアカウントは 1 分に 1 回まで（`directorySyncedAt`）。状態・役割・Lv の変更と乱造検知の要確認は即時に反映する。反映に失敗しても書き込みは成功扱いで、次の反映で追いつく。
- 書き込みは「アクティブなアカウント数 × 1 回/分」が上限になる。1 つの DO で足りない規模になったら `aid` で分割する（データストレージ改修フェーズで再検討）。
- RPC: `upsert(row, observations)`、`list(sort, filters, cursor, limit)`、`search(kind, valueHash)`。

**`AuthRate`**（`idFromName(keyIp)`）

- `keyIp` ごとの計数。`hit(kind, now): Int`（`kind` ∈ `verify` / `register` / `login` / `reset` / `deviceHash:<hash>`。専ブラ用キーの発行回数 `token` だけは `keyIp` ではなくアカウントごとの HMAC を名前にする）が直近 1 時間・24 時間の件数を返す。記録は `{kind, t}` の配列で 24 時間より古いものを `hit` のたびに捨てる。閾値の判定は `common/domain/account/AbuseThresholds` の純粋関数。
- KV ではなく DO にするのは、原子的な加算と即時の整合が要るため（KV は結果整合で 1 キー 1 回/秒の書き込み上限がある）。応答しないときは認証系の要求を拒否する（§3.7）。

### 9.2 既存 DO への追加キー

§8.7 のとおり。既存キー（`dat`・`log`・`settings`・`subjectList`・`removed`・`threadCreation*`）は変えない。

### 9.3 KV・R2・secret・vars

| 種類 | 名前 | 用途 |
| --- | --- | --- |
| KV | `CAP`（既存） | hash → 表示名。互換のため維持 |
| DO | `AuthRate`（新） | §9.1 |
| R2 | `edge-rules.json`・`ios-profiles.json`・`phoebe.cgi`・`reserved-ids.txt`・`disposable-domains.txt`（新） | §3.6・§3.7・§4.3・§3.9 |
| vars | `TURNSTILE_SITE_KEY`・`GOOGLE_CLIENT_ID`・`AGREEMENT_VERSION`・`ADMIN_BREAK_GLASS_UNTIL`（通常は未設定） | 公開値 |
| secret | `AUTH_SECRET`（32 文字以上。`AGREEMENT_VERSION`・`TURNSTILE_SITE_KEY`・`TURNSTILE_SECRET_KEY` と揃ったときだけ認証を有効にする）・`TURNSTILE_SECRET_KEY`・`GOOGLE_CLIENT_SECRET`・`MAIL_API_KEY`・`ADMIN_SESSION_SECRET`（`_PREV`）・`ADMIN_BOOTSTRAP_TOKEN`（`ADMIN_PASSWORD`・`ADMIN_USER` は廃止） | `wrangler secret put`。`.dev.vars` で開発。コミットしない |

段Bの `wrangler.jsonc` は移行タグ `v2` で `new_sqlite_classes: ["Account", "AccountIndex", "AuthRate"]` と、バインディング `ACCOUNT_DO`・`ACCOUNT_INDEX_DO`・`AUTH_RATE_DO` を追加した。`AccountDirectory` と `AdminAudit` は後続の管理画面で別の移行タグとバインディングを追加する。適用済みの `v2` には追記しない。開発時の設定は [認証の検証設定](auth-integration.md) を参照する。

### 9.4 `deny.cgi` との接続

`userInfo` の `UID:` と `Device:` に実値が入る（§5.3）ので、既存のルール書式のまま `UID:abc123 ` や `Device:p[0-9a-f]{18}` を対象にできる。
書式変更は 5 列目（回避レベル、§7）の追加だけで、`Moderation.parseRules` は 4 列と 5 列の両方を受け付ける。

## 10. ルーティングと画面

ルートは板トップと同じ方式（`bbs/Main.scala` のハンドラに `@route` 注釈、codegen が `app/routes/` を生成。
`IslandRender` は廃止済みで、画面は handler が `c.renderPromise` で描く — `docs/handler-rendering.md`）に従う。
islands は `bbs-client/Main.scala` の `@islands` 注釈で宣言する。
画面の見た目と文言は §3.8 の UI の方針（角丸を使わない、既存画面の部品と文言に合わせる）に従う。

| パス | メソッド | 内容 |
| --- | --- | --- |
| `/auth/` | GET | 認証・登録ページ（SSR ＋ `AuthForm` island）。`embed=1` でモーダル用レイアウト、`mode=login`、`return` で戻り先 |
| `/auth/verify` | POST | Turnstile 検証・端末ハッシュ算出・突合・クライアント ID 作成または既存アカウントへの端末登録。JSON 応答 |
| `/auth/register` / `/auth/login` / `/auth/logout` | POST | ID/Password（§3.9）。`logout` は `gen` を進めて Cookie を消す。セッションが既に無効でも Cookie は消す |
| `/auth/password` | POST | パスワード変更（ログイン済） |
| `/auth/reset/request` / `/auth/reset` | POST / GET+POST | パスワード再設定の申請とリンク先（§3.9） |
| `/auth/email` / `/auth/email/verify` | POST / GET | メールアドレスの登録・変更とメール認証リンク（§3.9） |
| `/auth/google/start` / `/auth/google/callback` | POST / GET | OAuth（§3.10）。`start` が `state`・`nonce`・PKCE を Cookie に保存して Google へ転送、`callback` が検証して Cookie を発行。`popup=1` で callback が `window.opener` へ通知して閉じる |
| `/auth/account/` | GET / POST | 未ログインなら `/auth/?return=/auth/account/` へ 302。アカウントメニュー（SSR ＋ island）: 種別・Lv・ユーザー ID（`uid`）、専ブラキー発行/失効、キャップ発行/失効、利用ログ、方式移行（`upgrade=1`）、ログアウト、退会 |
| `/auth/session` | GET | 現在のセッションの `{uid, lv}` を JSON で返す（`no-store`。§3.8 の予備経路とヘッダーの client 取得用） |
| `/admin/*` | GET / POST / PUT | 管理画面（SPA）と `/admin/api/*`（§6.1、[管理画面 改修計画](admin-spa-auth-plan.md) §5・§6）。`__Host-ch3s_admin` と `Origin` 検証 |
| 全ルート | — | `Accept-CH` 付与ミドルウェア（§3.7）。`app/routes/_middleware.ts` は手動管理（`docs/handler-rendering.md`） |

島の一覧: `AuthForm`（Turnstile ウィジェット・方式選択・同意チェック・`verify` 呼び出し）、`AuthModal`（認証を有効にした板トップにだけ置く）、`AccountNav`、`AccountMenu`（キー発行の 1 回表示とコピー）、`AccountLogout`。

認証 API のルートは `GET /auth/session/` と `/auth/*` の POST だけで、画面（`/auth/`・`/auth/account/`）はファイルルートが描く。認証の設定が揃わない環境では、画面も API も認証導入前と同じ 404 を返す。

`auth`・`admin` は板名として使えない（`PostRouting` の禁止板名に追加）。`/auth/*` の応答は `cache-control: no-store`、CSP（Turnstile の `challenges.cloudflare.com` と Google の必要オリジンだけ許可）、`X-Frame-Options` は付けず
`frame-ancestors 'self'` でモーダル埋め込みを許す。`/auth.js` の固定スクリプトは現行のまま残す。

## 11. 管理画面

画面構成・API・段階は [管理画面 改修計画](admin-spa-auth-plan.md) に従う。見た目は改修計画 §4.2 と本書 §3.8 の UI の方針（角丸を使わない、既存画面の部品と文言に合わせる）に従う。
本書の認証・Lv・規制に関わる追加項目は次のとおり。

| 画面 | 追加内容 |
| --- | --- |
| `/admin/boards/:bbs` | `BBS_PHOEBE`・`timecount`・`timeclose`・`BBS_SAMBA24`・`BBS_THREAD_RENTOU(_RESET)`・`BBS_RENTOU_PASS`・`BBS_RENTOU_KEYS`・`BBS_FR_LEVEL`・`BBS_THREAD_PASS`・`BBS_LIVE` の専用フォーム（既存のスレ立て規制フォームと同じ ETag 条件付き保存） |
| `/admin/rules/access` / `/admin/rules/ngwords` | 5 列目の回避レベルを検証 |
| `/admin/accounts` | ユーザー一覧（既定は直近書き込み順）。ユーザー ID（`uid`）・メール・AS・UA・端末ハッシュ・識別子・IP ハッシュで検索。モック `docs/ui/admin-users.html` |
| `/admin/accounts/:uid` | ユーザー詳細。種別・Lv・`lvOverride`・権限・状態、使われた AS・地域・UA・端末情報・日ごとの識別子（`seen`）、アカウントの認証キーとキーごとの書き込み一覧、書き込み記録（`log`）。凍結・BAN・Lv 固定・キー無効化・旧キャップの紐づけ。モック `docs/ui/admin-user.html` |
| `/admin/phoebe` | Lv 上限ルール（`phoebe.cgi`）、エッジ防御の規則（`edge-rules.json`）、乱造検知の「要確認」一覧 |
| `/admin/boards/:bbs/threads/:key` | 投稿ログの `UID:` / `Device:` から該当アカウント・端末 BAN（`deny.cgi` へ行を追加）へのリンク |

## 12. 実装順序とテスト

作業メモの順序（連投規制 → デプロイ → 認証ほか）に合わせる。実施順は A → B → E-1 → C → D → E-2。段 A の前に [bbs.cgi DDD 化リファクタリング計画](bbs-cgi-ddd-refactoring-plan.md) の段 0〜7 を終える。各段で `sbt test`・`npm test`・`npm run v6:build` を通し、レビュー連鎖（AGENTS.md 3.2）を行う。

| 段 | 内容 | 主なテスト |
| --- | --- | --- |
| A | 連投規制（§8）。A-1（**実装済み 2026-11-06**）: 段階 `BBS_FR_LEVEL` の既定を規制モードにして実装した。当初案の A-2（`log` で 1 週間観測した後に既定を `on` へ）は、既定が最初から規制なので不要になった。観測したい板は `BBS_FR_LEVEL=log` を書く。照合キーは IP・識別子（`checked` 書式）。`aid` は空。前提は計画書の段 0〜7（`PostingCodec` を含む段 6） | `PostRateLimitSuite`（純粋判定: 件数窓・24h・Samba24・スレ 1h/40・コピペ末尾 2 行・あぼーん除外）、`BoardSuite`（予約/確定/取消・保持規則・ページ分割・容量）、`ThreadSuite`（`recentPosts` の一括保存・吸い込み時の dat 不変）、`PostingSuite`（パイプライン順序・文言・成功画面での吸い込み・`BBS_LIVE` 除外・設定不正時の既定値）、`AdminSuite`（フォーム検証）、実 workerd 統合 |
| B | 認証基盤: `Account` / `AccountIndex` DO、Cookie、`/auth/`（クライアント ID・Turnstile・法的確認）、端末ハッシュと突合、`Accept-CH`、専ブラキー、キャップのアカウント紐づけ、`userInfo` の `UID:` / `Device:`、`PostingUserResolver` の差し替え（`!id`・スレ主表示の `account`。§5.3）、新 UI の `SessionReader`・ヘッダー出し分け・`AuthModal` / `AccountNav`・投稿欄のヘッダ分岐（§3.8）、bbs.cgi の `x-auth-required` / `x-post-error` | `common` の `DeviceFingerprintHasher` / `ClientHints` golden、`CookieCodecSuite`、`AccountSuite`（状態遷移・一意性）、`DedicatedBrowserTokensSuite`（未使用の紐づけ・同じ UA での置き換え・UA 不一致・上限・失効・Cookie の `bind`）、`AuthHandlerSuite`（Turnstile はテスト用サイトキーで成功/失敗を固定）、`PostingSuite`（§3.4 の優先順・`!auth`・Web 版の認証要求とヘッダ）、`SessionReaderSuite`、`BoardIndexSuite`（未ログイン/ログイン済のヘッダーと投稿欄の SSR）、`tests/islands-hydration.test.js` に `AuthModal` の `message` → `ch3s:auth-changed` と再送の jsdom 検証 |
| C | PHOEBE: Lv 昇格・`BBS_PHOEBE`・`!バルサン` / `!SETTING:BBS_PHOEBE:n`・識別子 `phoebe` 書式・連投規制への `aid` 追加。**実装済み（2026-11-07）**。UA 解析表のデータは利用者の用意待ち、識別子の既定値は `checked` のまま（§14-1）。`aid` は段 B で連携済み | `PhoebeSuite`（T(lv)/N(lv) 境界・上限・固定）、`IdentifierSuite`（軸ごとの値・欠損 `000`・`D` の `*`）、`PhoebeCommandSuite`、`UserAgentTableSuite`・`UserAgentTableRepositorySuite`（表の書式。v3 表の golden は表が無いため未作成）、`PostingServiceSuite`・`ThreadSuite`・`AccountInfrastructureSuite` |
| D | 権限・回避レベル・管理画面の認証機能画面（§6・§7・§11、改修計画 段 M3）、スレ立て規制の `BBS_THREAD_PASS` 化 | `BypassLevelSuite`、`ModerationSuite`（5 列目）、`AdminSuite` |
| E-1 | ID/Password の登録・ログイン・ロック、Google ログイン（§3.9・§3.10 の基本部分）。管理画面のアカウント認証（改修計画 段 M2）の前提なので段 B の直後に行う。**実装済み（2026-11-07）**。方式移行・パスワード変更と再設定の HTTP・メール欄は E-2 | `PasswordSuite`・`LoginThrottleSuite`・`PasswordLoginSuite`・`GoogleIdTokenSuite`・`OAuthStateSuite`、`AccountPasswordSuite`（直列化・ロック・dummy）、`AccountRegistrationSuite`（確定順・応答喪失・再開）、`AuthHandlerSuite`・`GoogleHandlerSuite`・`GoogleIdTokensSuite`、board-islands、実 workerd |
| E-2 | メール認証・パスワード再設定・方式移行・退会・エッジ防御・乱造検知・Lv 上限ルール | `AccountSuite`（移行）、`EdgeDefenseSuite`、実 workerd |

段 C の識別子既定値の切替は、不変条件 3.4 に触れるため `docs/既知不具合台帳.md` に影響を書き、承認後に独立コミットで行う。

## 13. 要確認事項

| # | 事項 | 本書の仮置き |
| --- | --- | --- |
| 1 | 原案「未ログイン L0 固定」と PHOEBE.md「未認証 L0〜L2」の不一致 | 原案を採用。未ログインは L0 固定で昇格しない |
| 2 | 裁定メモ「Web 版は常に認証必須（Lv3）」の Lv3 | クライアント ID は L1 開始なので「ログイン必須」とだけ読む。Lv3 を要求する板は `BBS_PHOEBE=3` で表す |
| 3 | クライアント ID の 1 年リセット（原案は `？` 付き） | 最後の書き込みから 365 日書き込みが無ければ失効（2026-11-05 利用者指示。発行から 1 年の案を改めた）。再発行は L1 から |
| 4 | 昇格曲線の読み替え（§4.2） | PHOEBE.md の式を L1 開始に合わせて採用。クライアント ID は L10 まで約 487 日かかる（失効は最後の書き込みから 1 年なので、続けて書き込めば届く） |
| 5 | `!バルサン` / `!SETTING:BBS_PHOEBE:n` を使える人と、`n` の意味（最低 Lv か「n 以下は不可」か） | スレ立て人（認証済）＋ `staff`。下げるのは `staff` だけ。`n` は最低 Lv（利用者指示） |
| 6 | `BBS_PHOEBE` ゲートの免除範囲 | `staff` のみ。`cap` 権限者は Lv で判定 |
| 7 | 連投規制の照合キーにアカウント（`aid`）を加えるか（原案は IP・ID・識別子。ID は廃止済み） | 加える（IP 切替での回避を防ぐ） |
| 8 | ごんたくれの既定値 `timecount=18` / `timeclose=10` と、`timecover` を読まず 24 時間固定にすること | 既定設定の値を踏襲。`timecover` は無視 |
| 9 | `BBS_SAMBA24` の既定値（既存の SETTING.TXT にはキーが無く、KB-12 により既定値がそのまま効く） | `10` 秒 |
| 10 | 吸い込まれた投稿を連投履歴に数えるか | 数える |
| 11 | エッジ防御の免除（認証済 Lv3 以上）と RTT 閾値 | 免除あり、400ms |
| 12 | メール送信手段（外部 API の選定）と Google OAuth のクライアント登録 | 未定。段 E まで不要 |
| 13 | 乱造検知の閾値と、検知時に自動 BAN せず「要確認」に載せること | 1 時間 10 回・24 時間 5 ハッシュ、自動 BAN なし |
| 14 | 権限者向け画面の置き場所と管理画面の認証 | `/admin/*` に統合し、Basic 認証をアカウント認証に置き換える（2026-11-02 利用者指示。改修計画 §10 に関連事項） |
| 15 | 識別子の文字集合（現行の Base64 文字のまま `+` `/` を含む） | 現行どおり |
| 16 | 新 UI で未ログインでも投稿欄を描画し、送信時にモーダルで認証する（欄自体を隠す案との比較） | 描画する。下書きを書いてから認証できる方が離脱が少ない |
| 17 | 認証後の自動再送（1 回）と、Lv 不足時は再送しないこと | そのとおり |
| 18 | bbs.cgi の応答ヘッダ追加（`x-auth-required` / `x-phoebe-required` / `x-auth-url` / `x-post-error`）。方針メモの `x-age` と同じ位置づけ | 追加する |
| 19 | メールアドレスを ID/Password アカウント間で一意にするか（複垢の L3 開始を防ぐ） | 一意にする。Google アカウントのメールは索引に載せない |
| 20 | ログインにも毎回 Turnstile を要求すること（端末ハッシュ取得と兼ねる） | 要求する（Managed モードなら通常は非表示で通る） |
| 21 | パスワード再設定はメール認証済みのアカウントだけ（未登録は管理者対応） | そのとおり |
| 22 | ロック閾値（（アカウント, keyIp）10 回/時、アカウント全体は段階的遅延、`keyIp` 30 回/時）と再設定申請 3 回/日 | 仮置き |
| 23 | Google 連携の解除（Google → 他方式）を許さないこと、Google アカウント削除時の扱い | 許さない。退会のみ |
| 24 | 連投規制の照合キー（`BBS_RENTOU_KEYS`）の既定。原案の `ip,id,shiki` は CGNAT・同一機種で別人が一致し、Samba24 で巻き添えが出る（レビュー #1）。`id` は ID 廃止で使えない | **決定（2026-11-06 利用者）**: `id` を外し、`ip,shiki,aid` を既定にする（ごんたくれとスレ単位に使う）。推奨どおり Samba24 は `BBS_RENTOU_KEYS` によらず `ip,aid` に限り、識別子は広域連投規制 `BBS_FR_SECOND`（既定 1 秒、§8.3a）で見る。既定は `BBS_FR_LEVEL=log` の板の観測で決め直す |
| 25 | 吸い込み（§8.5）を同一投稿者（照合キー一致）に限るか | **決定（2026-11-06 利用者）**: 原案どおり投稿者を問わない（別人の投稿の写しも吸い込む） |
| 26 | 初回デプロイを `BBS_RENTOU_MODE=log`（記録のみ）で始め、1 週間後に `on` にする手順 | **決定（2026-11-06 利用者）**: 採らない。設定キーを `BBS_FR_LEVEL` にし、コード既定を規制モードにする（§8.1）。観測は `BBS_FR_LEVEL=log` を書いた板だけで行う |
| 27 | 板 top 等の Cookie による出し分けを SSR で行う（`private, no-cache`）か、ヘッダーだけ client で `/auth/session` を取る（共有キャッシュを保つ）か | SSR で行う |
| 28 | 3chiko.html の Web 版利用者が、認証基盤の公開と同時にログイン必須になること（専ブラは影響なし） | 公開と同時。§14 |
| 29 | Cookie を host-only（`__Host-`）にし、専ブラのキー認証を HTTPS に限ること。サブドメインで Cookie を共有する必要があるか | host-only。要確認 |
| 30 | 専ブラ用キーの UA を完全一致で比べること。専ブラの更新で版番号が変わると、新しいキーの発行が要る | 完全一致。版番号を除いて比べる案は、UA の偽装で別の専ブラに流用されやすくなるので採らない |
| 31 | 紐づけられる UA の上限（1 アカウント 5 個）、未使用キーは同時に 1 個・24 時間で失効 | 仮置き |
| 32 | 専ブラ用キーを専ブラ（`Monazilla/1.00` で始まる UA）以外から使えないようにすること | 使えないようにする |
| 33 | UA が一致しない使用でキーを止めないこと（止めると、漏れたキーを他人が試すだけで正規の利用者が書けなくなる） | 止めない。記録と通知だけ |
| 34 | ユーザー ID の決め方（トリップ・ID 廃止の指示「ID/Password・Google は登録したユーザー ID」）。ログイン ID を公開してよいか、Google 登録でユーザー ID を入力させること、移行でユーザー ID が変わること | **決定（2026-11-06 利用者）**: ID/Password はログイン ID をそのままユーザー ID にする。名前欄への表示は既定で出さず、本人が名前欄の !id（Web 版はユーザー ID 表示のチェックボックス）を付けた投稿だけに出す（develop の実装どおり）。Google は登録時に入力させる。移行では登録した ID に変わり、旧 ID は再利用させない |
| 35 | 広域連投規制（FlyRibbon, FR）の有効・無効を何で決めるか（当初は `BBS_FR_SECOND` だけで決め、`BBS_FR_LEVEL` の数値は使っていなかった） | **決定（2026-11-06 利用者）**: `BBS_FR_LEVEL` が 1 以上のときだけ FR を有効にする。キー無しは既定 `1`。`0` と空は規制モードのまま FR だけを外す。不正値はキー無しと同じ。`log` は FR も判定して記録する。秒数は `BBS_FR_SECOND`（`0` なら判定しない、は併存） |
| 36 | かんたん登録（クライアント ID）は Cookie だけで識別するので、ログアウトするとその端末から同じアカウントに戻れない | **決定（2026-11-07 利用者）**: 注意書きで足りる（ログアウトしたクライアント ID のアカウントは戻せない）。アカウント設定のログアウトの近くに「かんたん登録のアカウントは、ログアウトするとこの端末から戻せません」と出す |
| 37 | Turnstile の検証応答の `action`・`cdata` を照合するか（現在は `success` と `hostname` だけを見る） | 照合しない。要確認 |
| 38 | UA 解析表（§5.2）。v3 に表の実体が無かったため、OS・ブラウザ・端末種別の分け方と表記を決めたデータが要る | 表は R2 の `ua-profiles.json` から読む。データは利用者が用意する。無い間はその 3 軸が `000` |
| 39 | 未ログインの専ブラが `BBS_PHOEBE` 1 以上の板・スレに書いたときの応答 | `x-auth-required: login`（ログインで解決するため。Lv 不足の `level` は認証済だけ） |

## 14. 不変条件への影響（承認が必要な点）

1. **識別子の書式変更**（§5）: `BBS_ID_SHIKIBETSU` の既定値を `phoebe` に切り替えると、全板の名前欄の識別子が変わる。dat の構造は変わらない。切替前の投稿は旧書式のまま残る。
2. **識別子の `D` が日中に変わる**（§4.1）: 同じ人でも Lv が上がった時点から `D` が変わる。識別子の他の部分は同じ日の中で変わらない。専ブラの識別子による NG はその日のうちに途切れうる。
3. **名前欄の `(@ユーザーID)`**（§5.3）: 認証済で `!id` を使った投稿だけに ` </b>(@ユーザーID)<b>` が加わる（`develop` で実装済み。段 B で有効になる）。dat の構造は変わらない。
4. **規制スルーの範囲**（§6.4・§7）: ★ 投稿のスルーは現行どおり。加えて `admin` / `delete` / `regulate` の名無し投稿が `deny.cgi` / `ngwords.cgi` / 連投規制 / スレ立て規制を免除される（原案どおりだが現行にはない挙動）。
5. **連投規制の全板展開**（§8）: コード既定値（`timecount=18` / `timeclose=10` / `BBS_SAMBA24=10` / スレ 40 件/時）は KB-12 により SETTING.TXT に無いキーにもそのまま効く。`BBS_FR_LEVEL` の既定も規制モードなので、デプロイした時点で全板で拒否が始まる。**承認済み（2026-11-06 利用者）**（§13-26）。
6. **吸い込み**（§8.5）: 成功画面を返しながら dat に残さない投稿が生じる。専ブラの書き込み履歴と dat が食い違う。別人の投稿の写しも吸い込む（§13-25）。**承認済み（2026-11-06 利用者）**。
7. **Web 版のログイン必須**（§3.5）: 現行 `3chiko.html` の利用者は、認証基盤の公開時点から未ログインでは書けなくなる（§13-28）。
8. **スレ立て時の設定書き写しの変更**（§8.8）: 新キーと `timecount` / `timeclose` を書き写さない。既存キーの書き写しと `Thread.settings` の形式は変えない。
   段 A-1 では新キーだけを除いた（新キーなので既存の振る舞いは変わらない）。`timecount` / `timeclose` は未決で、今も書き写す。

上記以外（Cookie 追加・新 DO・新ルート・bbs.cgi の応答ヘッダ追加・Board/Thread DO への追加キー・`userInfo` の `UID:` / `Device:` の実値化・`Set-Cookie` の複数化）は、専ブラ互換・Shift_JIS・識別子の計算・既存キーの形式に影響しない。
