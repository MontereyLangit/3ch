# 認証・PHOEBE（Lv）・連投規制 設計

2026-11-01。利用者提示の原案（§1）、[PHOEBE.md](bbscgi/PHOEBE.md)、端末フィンガープリントと認証に関する裁定メモ
（リポジトリ外の `ninja_auth_system.md` 系。要点は §3.7 に転記）、および新 UI（`feature/board_top`。
`docs/ui/板top_書き込み欄と投稿後更新の方針.md`・`docs/islands-ssr.md`・`docs/handler-rendering.md`）をもとに、
v6 へ追加する認証・Lv・連投規制と、新 UI での Web 版認証の挙動（§3.8）を設計する。
**状態: 設計案（未実装）。** 実装は §12 の順で行い、各段で本書を実装結果に合わせて訂正する。

既存のスレ立て規制 [thread-creation-regulation.md](thread-creation-regulation.md) の構成（ドメイン純粋関数 ＋ DO はロックと保存のみ ＋
拒否はコード文字列で返し handler が文言化）をそのまま踏襲する。AGENTS.md 3.5 の DO 規約に従う。

## 0. 位置づけと不変条件

| 項目 | 扱い |
| --- | --- |
| 対象 step | 連投規制（§8）は既存 DO へのキー追加のみで先行実装する（`docs/作業用_add_bbs_cgi_regulation_and_auth.md` の順序）。認証・Lv・権限（§3〜§7）は新 DO を伴う step 5 相当 |
| dat 構造・本文前後の空白・エラーマーカー・Range/304 | 変更しない |
| Shift_JIS 出力 | 変更しない。認証画面（`/auth/`）は UTF-8 の新規画面 |
| ID（`ID:xxxx`）・トリップ・キャップの計算 | 変更しない。`BBS_ID_LV=checked` の付加値だけ固定 `10` から実 Lv に直す（KB-15。承認要） |
| 名前欄の識別子 | **書式を変える（§5）。** 既存の `BBS_ID_SHIKIBETSU=checked` は残し、新書式は新しい値 `phoebe` で有効化する。既定値の切替は承認を得てから行う |
| ストレージ | Board / Thread DO の既存キー・形式は変更しない。追加キーと新 DO（`Account`・`AccountIndex`）だけ |
| 管理画面の防御（Basic 認証・no-store・CSP） | 弱めない。権限者向け操作も同じ防御下に置く |

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

## 2. 用語

| 用語 | 意味 |
| --- | --- |
| アカウント | `Account` DO 1 件。公開 ID `uid` を持つ。種別は未ログイン以外の 3 つ（§3.1） |
| `uid` | 公開 ID。ID/Password は利用者が決めたログイン ID、クライアント ID・Google は自動採番 8 文字 `[a-z0-9]`。永久に再利用しない |
| Lv | PHOEBE レベル 0〜10。アカウントに 1 つ。板・スレをまたいで共通 |
| 認証済 | 有効なセッション Cookie（§3.3）または有効な専ブラ認証キー・キャップで投稿していること。未ログインは L0 固定 |
| 権限 | アカウントに付く役割。`admin`（管理者）/ `delete`（削除）/ `regulate`（規制）/ `cap`（キャップ書き込み） |
| 端末ハッシュ | 認証時に取得する 9 軸 18 桁 hex の端末識別値（§3.7）。粒度文字 1 字を先頭に付けて保存 |
| 識別子 | 名前欄末尾の `(JP.AA CC-EEEFFFGGGZZZXXXD)`（§5） |
| 回避レベル | 規制を免除する属性の段階（§7） |

## 3. アカウントと認証方式

### 3.1 種別

| 種別 | 作成時 | 初期 Lv | 上限 | 権限付与 | 有効期限 | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| 未ログイン | — | 0 固定 | 0 | 不可 | — | Web 版からは書けない（§3.5）。専ブラは `BBS_PHOEBE=0` の板・スレに書ける |
| クライアント ID | Turnstile ＋ 法的投稿確認 | 1 | 10（実効は §4.2 の曲線で 1 年以内に L9） | **不可** | 発行から 365 日。失効後は再発行で L1 から | 「かんたんログイン」。Cookie だけで識別する |
| ID/Password | Turnstile ＋ 法的投稿確認 ＋ ID・パスワード登録 | 1（メール認証済なら 3） | 10 | 可 | なし | クライアント ID からの移行先になれる |
| Google 連携 | Google OAuth（Turnstile なし） | 10 固定 | 10 | 可 | なし | 1 つの Google アカウント（`sub`）につき 1 アカウント |

- **移行**: クライアント ID → ID/Password または Google へ移行できる。移行時は `uid` を付け替えず（ログイン ID を新たに登録した場合は、その ID を新しい `uid` とし旧 `uid` は別名として残す）、Lv・端末履歴・ログを引き継ぐ。ID/Password・Google からクライアント ID への移行、ID/Password ⇄ Google の相互移行はしない。
- **ID/Password の規則**: ログイン ID `[a-z0-9_]{3,16}`、パスワード 8〜128 文字。保存は PBKDF2-HMAC-SHA256（Web Crypto、反復 100,000、ソルト 16 バイト）。ログイン失敗は `uid` ごとに 10 回/時でロック。
- **メール認証**: 登録時任意。認証メールのリンク（有効 24 時間、1 回限り）を踏むと `emailVerifiedAt` を記録し、Lv が 3 未満なら 3 に引き上げる。送信手段（Cloudflare Email Routing では送信できないため外部 API）は §13。
- **Google 連携**: OpenID Connect の `id_token` を検証し `sub` と `email_verified` を見る。`email_verified=false` は拒否。
- **法的投稿確認**: 認証画面で利用規約・投稿の法的責任への同意チェックを必須にし、`agreedAt` と同意文書の版を保存する。版を上げたら次回投稿前に再同意させる。

### 3.2 認証フロー

**A. Web 版の初回書き込み**（新 UI。詳細は §3.8。現行 `3chiko.html` は B の誘導のみ）
1. 投稿フォーム送信時に有効なセッションがなければ、認証・登録モーダル（`/auth/?embed=1` を iframe で開く）を出す。
2. 方式を選ぶ（既定: クライアント ID）→ 法的投稿確認 → Turnstile → `POST /auth/verify`。
3. 成功で `Set-Cookie` を返し、モーダルは `postMessage` で親へ通知して閉じる。親は投稿を自動で再送する。

**B. 認証・登録ページ**（`/auth/`）
- 板トップ・read.cgi のリンク、および bbs.cgi の認証要求エラー（§3.5）に載せる URL から開く。`return` クエリで戻り先を持つ（同一オリジンのパスのみ許可）。
- 専ブラ利用者は、ログイン後のアカウントメニュー（`/auth/account`）で専ブラ用認証キーを発行し、専ブラの初回投稿でメール欄に `!#{キー}` を入れる（§6.3）。サーバは照合に成功したら `Set-Cookie` を返し、以後は Cookie で認証済として扱う。

**現行 `3chiko.html` の扱い**: ロジックを変えない方針のため、Web 版の投稿で未認証なら bbs.cgi が `/auth/?return=...` へのリンク付きエラー（HTTP 200 ＋ `<!-- 2ch_X:error -->`）を返す。モーダルは step 4 の新 UI で実装する。

### 3.3 セッション Cookie

| 項目 | 値 |
| --- | --- |
| 名前 | `ch3s_sid` |
| 値 | `1.{uid}.{gen}.{exp}.{lv}.{dev}.{sig}`。`gen` はアカウントの世代番号、`exp` は失効 UNIX 秒、`lv` は発行時点の Lv（表示用の写し）、`dev` は端末ハッシュ（粒度 1 字＋18 hex。無ければ空）、`sig` は `AUTH_SECRET` による HMAC-SHA256 の先頭 128 bit（base64url） |
| 属性 | `Domain=.3chbbs.com; Path=/; Max-Age=31536000; HttpOnly; SameSite=Lax`。`Secure` は現行 `PON` と同じく専ブラ（`Monazilla/`）以外に付ける |
| 検証 | bbs.cgi: 署名 → `exp` → `Account.describeForPosting(uid)` で `gen` 一致・状態（active / frozen / banned）・Lv・権限を得る。DO 呼び出しは投稿 1 回につき 1 回。DO の Lv が Cookie の `lv` と違えば応答で Cookie を発行し直す。SSR 画面（板 top・read.cgi）: 署名と `exp` だけ見て `uid` と `lv` を表示に使い、DO は呼ばない（`SessionReader`、§3.8） |
| 失効 | ログアウトは `gen` を進める（全端末のセッションが無効になる）。クライアント ID は `exp` ＝発行＋365 日 |

既存の `PON` 等の Cookie は現行どおり発行し、意味は変えない。

### 3.4 bbs.cgi での認証主体の決定

優先順は次のとおり。最初に当たったものを採用し、残りは見ない。

1. メール欄の `#パスワード` がキャップに一致 → **キャップの所有アカウントとしてログイン扱い**（所有者のない旧キャップは「権限 `cap` のみの仮主体」）。
2. メール欄の `!#{キー}` が専ブラ認証キーに一致 → そのアカウント。`Set-Cookie` を返す。
3. 有効なセッション Cookie → そのアカウント。
4. いずれもない → 未ログイン（L0）。

メール欄からは `#...`・`!#...`・`!auth` を取り除いてから sage 判定に使う（dat には保存されないので表示影響なし）。

### 3.5 認証要求の発生条件（板設定ゲート原則）

認証要求（`/auth/` の URL 付きエラー）は次の場合**だけ**出す。

| 投稿元 | 条件 |
| --- | --- |
| Web 版（UA に `Monazilla/` を含まない） | 常にログイン必須。未ログインなら認証要求 |
| 専ブラ（`Monazilla/`） | 板設定（スレ設定で上書き）`BBS_PHOEBE` が現在の Lv を上回るときのみ。`BBS_PHOEBE=0`（既定）なら未ログインでも書ける |
| メール欄 `!auth` | 投稿元によらず常に認証要求（広域規制回避・事前認証の導線） |

エラー文言は「この板（スレ）に書き込むには認証が必要です（必要 Lv: n）」＋ URL。どの規則で落ちたかは出さない。
認証要求のエラー応答には HTTP 200・エラーマーカーに加えて応答ヘッダ `x-auth-required: login`（未ログイン）または
`x-auth-required: level` と `x-phoebe-required: n`（Lv 不足）、`x-auth-url: /auth/?...` を付ける。新 UI は本文を解析せず
このヘッダでモーダルを開く（§3.8）。ヘッダの追加は専ブラ互換（`x-threadkey` / `x-resnum` と同じ扱い）に触れない。

### 3.6 エッジ防御（`request.cf`）

`/auth/verify`・登録・未ログインの投稿に適用する。認証済で Lv 3 以上は免除する（VPN 利用の正規利用者の救済。§13）。

| 判定 | 動作 |
| --- | --- |
| `asOrganization` / `asn` がデータセンター（AWS・GCP・Azure・主要ホスティング。R2 `dc-asn.txt` に ASN 一覧を置き管理画面で編集） | 拒否 `reason=datacenter` |
| `country=JP` なのに `clientTcpRtt` が閾値（既定 400ms）超 | Residential Proxy 疑い。拒否ではなく Turnstile の再実行と端末ハッシュ乱造検知（§3.7）の閾値を半分にする |
| `tlsClientHelloLength` 等の TLS 指紋と UA 系統の矛盾（Chrome を名乗るが TLS が curl 系 等） | 拒否 `reason=declaration_mismatch`（§3.7 と同じコード） |
| `country=T1`（Tor） | 現行どおり拒否 |

### 3.7 端末フィンガープリント（裁定メモの要点）

材料・粒度・突合規則は裁定済みのため変えない。本書では v6 で実装する形に絞って書く。

- **材料は 9 軸 18 桁 hex**（各軸 2 桁。`common` に `DeviceFingerprintHasher` を置く）。申告 8 軸（画面論理解像度・DPR・コア数・タッチ有無・色深度・CPU アーキ・フォントハッシュ・WebGL renderer）＋サーバ受信の `Sec-CH-UA-Model`。OS 版・UA・deviceMemory は材料に含めず照合専用。申告 8 軸が全欠損なら端末ハッシュは作らない（空）。
- **保存値**は粒度 1 字（`p` 準個体 / `g` 機種クラス / `u` 不明）＋18 hex。解析は旧 16 桁も通す（`DeviceHashRef.parse`）。粒度は申告であり照合可否を決めない。
- **粒度判定**はハードウェア由来のシグナル（Apple GPU ＋タッチ ＝ iOS 実機）を先に見て、それが iOS を示さないときに限り「ヒントが届いた＝準個体」とする。`iOS` / `iPadOS` を名乗るヒントは準個体にしない。
- **突合（`reason=declaration_mismatch`）**: R4・R5（`Sec-CH-UA-Platform` × `navigator.platform`）、R6・R7（`Sec-CH-UA-Platform` / `Sec-CH-UA-Mobile` × UA 文字列。申告 UA が空なら受信 `User-Agent` で代替）、受信 `User-Agent` × `navigator.userAgent` の trim 後完全一致。材料が空なら、その材料を使う規則だけ飛ばす（fail-open）。発火規則は Workers Logs に `console.warn` で残し、応答には出さない。
- **論理破綻検知（`reason=fingerprint`）**: 実在しない組合せ（macOS × RTX、iPhone の画面値 × 旧 iOS 等）。iOS は既知プロファイル表（R2 `ios-profiles.json`、毎年 9 月更新）と照合する。
- **乱造検知**: `keyIp`（IPv4 はそのまま、IPv6 は /64）ごとに KV `AUTH_RATE`（TTL 24h）で verify 回数と相異なる端末ハッシュ数を数え、閾値（既定: 1 時間に 10 回、24 時間に 5 ハッシュ）超で拒否し、`deny.cgi` へ自動追記はせず管理画面の「要確認」一覧に載せる。
- **`Accept-CH` は全応答に付ける**（`AcceptClientHintsMiddleware`。dat・subject.txt・SETTING.TXT・bbs.cgi 応答・bbsmenu・`/admin` を含む）。`Critical-CH` は付けない。
- **`/auth/` の注意事項**は Turnstile・実行ボタンの手前に置く: 推奨環境（Safari・Chrome・Edge・Firefox 最新版）、認証できないことがある例（専ブラ内蔵ブラウザ・一部アプリ内ブラウザ・PC 版サイト表示・UA 変更拡張。いずれも断定しない）。`declaration_mismatch` のエラー文言も同じ導線を示す。
- **用途**: 名前欄識別子の 15 桁（§5）、投稿ログの `Device:`、`deny.cgi` による端末軸の規制、連投規制の照合キー（識別子経由）。優先順位は「規制のオプトアウト阻止 ＞ 巻き込み削減」。

### 3.8 新 UI での Web 版の挙動

新 UI（`feature/board_top` 以降）は scalajs-react の SSR コンポーネント（`bbs/.../components/`）と、`bbs-client` の
`@islands` で hydrate するクライアント部品で構成される。投稿欄は板 top の各スレカードに `details` 方式で置き、
`fetch` で bbs.cgi へ送って `x-threadkey` / `x-resnum` で成否を見る（上記の方針メモ 2 章）。認証はこの構成に次を足す。

**SSR 側（`SessionReader`）**

- 全画面ハンドラは `SessionReader.fromRequest(c)` で Cookie（§3.3）を署名と `exp` だけ検証し、`Option[SessionSnapshot(uid, lv)]` を得る。
  DO は呼ばない。失効・改竄は未ログインとして描画する。
- `HeaderComponent` のアカウント領域（現在「準備中」の仮置き）は snapshot で出し分ける。
  - 未ログイン: 「認証・登録」→ `/auth/?return={現在のパス}`、「ログイン」→ `/auth/?mode=login&return={現在のパス}`。
  - ログイン済: `@uid` と Lv バッジ（`Lv3`、10 以上は `Lv*`）、アカウントメニュー（`AccountNav` island）: アカウント設定 `/auth/account`、
    専ブラ用認証キー、キャップ、方式の移行、ログアウト（`POST /auth/logout`）。
- `PostingFormComponent` は未ログインでも描画する（下書きを書ける）。未ログイン時は送信ボタンの上に
  「書き込みには認証・登録が必要です」と `/auth/?return=...` へのリンクを SSR で出す（JS 無しでも導線がある）。
  スレ設定・板設定の `BBS_PHOEBE` が snapshot の `lv` を上回るときは「このスレは Lv n 以上」と `/auth/account?upgrade=1` への導線を出す。
- props は JSON 化できる JS オブジェクトだけ（`docs/islands-ssr.md`）。`uid`・`lv`・`turnstileSiteKey`・`authUrl` を渡し、Cookie 値や秘密は渡さない。

**クライアント側（islands）**

| island | 役割 |
| --- | --- |
| `AuthModal`（`auth-modal.tsx`、レイアウトに 1 つ） | `<dialog>` に `/auth/?embed=1&return=...` を iframe で開く。同一オリジンの `message`（`{type:"ch3s-auth", status:"ok", uid, lv}`）を受けて閉じ、DOM イベント `ch3s:auth-changed` を発火する |
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
  ボタンで `window.open("/auth/google?popup=1")` を開き、callback 画面が `window.opener` へ `postMessage` して自分を閉じる。
- CSP は `frame-ancestors 'self'`、`script-src` に `challenges.cloudflare.com` を足す。Turnstile のスクリプトは `/auth/` だけが読む。
- `SameSite=Lax` の Cookie は同一オリジンの iframe・`fetch`・ポップアップの top-level 遷移のいずれでも送受信できる。

**JS が動かない環境**

通常の `POST` で bbs.cgi へ送られ、認証要求のエラー画面（§3.5）のリンクで `/auth/?return=/{bbs}/` に移る。
認証後は `return` に戻るが下書きは残らない（島が無いため）。`return` は同一オリジンのパスだけ許可し、`//` 始まりや絶対 URL は捨てる。

**read.cgi（新 UI 化後）**

スレ表示の新 UI も同じ `SessionReader`・`PostingFormComponent`・`AuthModal` を使う。画面固有の差はない。
現行 `3chiko.html` の read.cgi は §3.2 のとおりエラー画面のリンクだけで誘導する。

## 4. PHOEBE（Lv）

### 4.1 意味と表示

- Lv はアカウントの信頼度。0〜10。識別子の末尾 `D` に 未ログイン `0`、認証済 `1`〜`9`、10 は `*` を出す（§5）。
- `BBS_ID_LV=checked` のときの ID 末尾付加は、現行の固定 `10` を実 Lv（`L0`〜`L9` / `10`）にする（KB-15 の解消。承認要）。
- キャップ投稿は現行どおり識別子 `★` のみで Lv を出さない。

### 4.2 昇格

アカウントは `lv`・`lvSince`（現在 Lv になった時刻）・`lvPosts`（それ以降の成功投稿数）を持つ。成功投稿のたびに
`Phoebe.advance(state, now)`（`common` の純粋関数）を呼び、**経過時間 ≥ T(lv) かつ lvPosts ≥ N(lv)** なら 1 つ上げて両方をリセットする。
一度に 2 段以上は上がらない。N(lv) は T(lv) の時間数（例: 23h なら 23 件）。吸い込み（§8.5）と拒否された投稿は数えない。

| 種別 | T(lv) | L1 → L3 | L3 → L10 累計 |
| --- | --- | --- | --- |
| クライアント ID | 23h × 2^(lv−1) | 23h ＋ 46h ＝ 69h（約 3 日） | 23h × (4+8+…+256) ＝ 11,684h（約 487 日。1 年失効のため実効上限 L9） |
| ID/Password | 23h × max(1, lv−2) | 23h ＋ 23h ＝ 46h | 23h × (1+2+…+7) ＝ 644h（約 27 日） |
| Google | — | L10 固定 | — |

PHOEBE.md の「L0〜L1 → 23h、L1〜L2 → 23×(Lv+1)h」は未ログインが L0 固定になった原案に合わせ、クライアント ID の L1 開始に読み替えた（§13）。

### 4.3 上限・降格・手動設定

- **上限ルール**（管理画面 `/admin/phoebe`）: `対象 userInfo 正規表現<>上限 Lv` の行リスト（R2 `phoebe.cgi`。`deny.cgi` と同じ構文解析）。ASN・県・端末ハッシュ・UA などで該当した投稿では `min(lv, 上限)` を実効 Lv とする。アカウントの `lv` 自体は下げない。
- **手動設定**: 管理者はアカウントの `lvOverride`（固定値）と `status`（`active` / `frozen`＝投稿不可 / `banned`）を設定できる。`lvOverride` がある間は昇格しない。
- **自動降格はしない**。規制対象となった投稿の扱いは管理者の手動操作（凍結・Lv 固定）に任せる。

### 4.4 `BBS_PHOEBE` ゲート

- 板設定 `BBS_PHOEBE=n`（既定 0。0〜10 の整数。不正値は 0 扱いで投稿は拒否しない）。スレ設定が板設定を上書きする（既存の設定スナップショット機構）。
- 投稿者の実効 Lv が `n` 未満なら拒否。拒否文言は §3.5。権限 `admin` / `delete` / `regulate` は免除（§13）。キャップ投稿は所有アカウントの Lv で判定する。
- **`!バルサン`**: 本文先頭が `!バルサン` ならスレ設定に `BBS_PHOEBE=3` を書く（既に 3 より大きければ変えない）。**`!SETTING:BBS_PHOEBE:n`**: 同様に `n` を書く。使えるのはスレ立て人（>>1 と同じアカウント、未ログインのスレは不可）と権限 `admin` / `delete` / `regulate`。それ以外は無視して通常投稿にする。コマンド行は本文から除かず、そのまま投稿される（2ch 互換）。
- `BBS_DISABLE_NUSI=checked` の板ではスレ立て人によるコマンドを無効にする（既存キーの流用）。

## 5. 名前欄の識別子

### 5.1 書式

```
JP.AA CC-EEEFFFGGGZZZXXXD
```

| 部分 | 内容 | 算出 |
| --- | --- | --- |
| `JP` | 国コード（`cf.country`。欠損は現行どおり `unknown`） | そのまま |
| `AA` | 県 | 現行 `kenHash` と同じ: `sha1Base64(md5(ken + group + date + salt)).take(2)` |
| `CC` | ASN | 現行 `asnHash` と同じ |
| `EEE`〜`XXX` | 端末情報 5 軸 × 3 桁 | 軸ごとに `sha1Base64(md5(axisValue + "|" + axisName + group + date + salt)).take(3)`。欠損軸は `000` |
| `D` | Lv | 未ログイン `0`、1〜9 はその数字、10 以上 `*` |

`group`・`date`・`salt` は現行 `makeId` と同じ（`BBS_ID_GROUP`・`BBS_ID_RESET`・`md5Salt`）。全部分が日替わりで変わる点は現行と同じ。
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
- 認証済の軸値は端末ハッシュ 18 hex を 2 桁ずつ軸に割り当てて連結した文字列。端末ハッシュが空（全欠損）なら 5 軸とも未ログインと同じ UA 解析で埋め、`D` だけ Lv を出す。
- 端末ハッシュは Cookie の `dev`（§3.3）から取る。キーによる専ブラ認証（§3.4 の 2）では `dev` が無いので UA 解析側を使う。

### 5.3 設定キーと互換

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
- 権限者向けの操作画面は現行の `/admin/*` を拡張し、Basic 認証（環境変数の管理者）に加えてセッション Cookie の権限でも入れるようにする。Basic 認証の管理者は `admin` 相当。Origin 検証は現行仕様（意図的に無し）を変えない。

### 6.2 キャップ

- 発行は権限を 1 つ以上持つアカウントが自分宛に 1 個まで。`admin` は無制限で他アカウント宛にも発行できる。
- 保存: `Account.caps[]`＝`{capId, name, hash, strong, issuedBy, issuedAt, revokedAt}`。`hash` は現行の照合値 `SHA-1(SHA-256(MD5(pass)+salt))` 先頭 27 文字をそのまま使う。
- 互換: KV `CAP`（hash → 表示名）は現行どおり読み書きし、所有者は `AccountIndex("cap")`（hash → `uid`）で引く。所有者のない既存エントリは「旧キャップ」として動き続ける（ログ無し・Lv 無し）。管理画面から既存キャップをアカウントに紐づけられる。
- 強キャップ（表示名末尾 `★`、サイズ検証スキップ）は `admin` だけが発行できる。
- 利用ログ: `Account.log` に `{t, kind: "cap", capId, ipHash, uaHash, bbs, key}` を追記（最大 1,000 件、古い順に切り捨て）。所有者と `admin` が閲覧でき、失効（`revokedAt`）と再発行（新 hash）ができる。失効時は KV `CAP` からも削除する。
- キャップ投稿は所有アカウントとしてログイン扱い（§3.4）。dat 上の表示（`名前＠ハンドル ★`、`ID:???`、識別子 `★`）は現行のまま。

### 6.3 専ブラ用認証キー

- 形式: `!#` ＋ 20 文字 `[a-z2-7]`（100 bit）。アカウントにつき有効なキーは 1 つ。再発行で旧キーは失効。
- 保存は `AccountIndex("token")`（`SHA-256(キー + salt)` → `uid`）と `Account.token`＝`{hash, issuedAt, revokedAt}`。平文は発行画面で 1 度だけ表示する。
- 専ブラの初回投稿でメール欄に入れる。一致したら `Set-Cookie` を返し、以後は Cookie（§3.3）で認証する。利用ログは §6.2 と同じ形で `kind: "token"`。
- キャップ利用者は不要（キャップでログイン扱いになるため）。

### 6.4 名無しでの権限使用

権限者がキャップを付けずに投稿した場合も、権限は規制回避（§7）に使われる。使えないのはキャップ表示（★・`ID:???`）だけ。
表示上は一般投稿者と区別がつかない。

## 7. 規制回避レベル

規制ごとに「どの属性なら免除するか」を 1 段階で指定する。上の段階ほど免除される人が少ない。

| 値 | 免除される投稿者 |
| --- | --- |
| `admin` | 権限 `admin` のみ |
| `staff` | `admin` / `delete` / `regulate` |
| `privileged` | 全権限（`cap` を含む） |
| `l10` | 全権限 ＋ 実効 Lv 10 |
| `login` | 全権限 ＋ 認証済（Lv 1 以上） |

適用先と既定値:

| 規制 | 指定場所 | 既定 | 備考 |
| --- | --- | --- | --- |
| `deny.cgi` / `ngwords.cgi` の各行 | 5 列目（省略可） | `privileged` | 現行の「キャップは全規制スルー」と同じ。4 列の行はそのまま有効 |
| 連投規制（§8 の 4 種） | 板設定 `BBS_RENTOU_PASS` | `privileged` | スレ設定で上書き可 |
| スレ立て規制 | 板設定 `BBS_THREAD_PASS` | `privileged` | 現行の「キャップ除外」を置き換える。判定もせず履歴にも残さない点は維持 |
| `BBS_PHOEBE` ゲート | 固定 | `staff` | §4.4 |

`saku` 板の全規制スルー（現行）は変えない。

## 8. 連投規制

### 8.1 共通

- **適用除外**: `BBS_LIVE` が `1` または `checked` の板・スレ（4 種すべて）。`BBS_RENTOU_PASS` で免除される投稿者。`saku` 板。
- **照合キー**: 同一 IP（IPv6 は URL 正規化、スレ立て規制と同じ）、同一 ID（`ID:` の値。`ID:???` と空は照合しない）、同一識別子（`D` を除いた `JP.AA CC-EEEFFFGGGZZZXXX`。`★` は照合しない）。いずれか 1 つでも一致すれば「同一」。認証済は `uid` も照合キーに加える（§13）。
- **保存値**: 全てソルト付き SHA-256（`hash(value + ":" + md5Salt)`。スレ立て規制と同じ `digest`）。空値は空のまま保存し照合しない。
- **判定主体**: 板単位（§8.2・8.3）は Board DO、スレ単位（§8.4・8.5）は Thread DO。純粋判定は `domain/posting/moderation/PostRateLimit.scala`。
- **拒否**: HTTP 200 ＋ `<!-- 2ch_X:error -->`。dat・履歴・Lv を進めない。拒否コードは `post-rate:board:gontakure` / `post-rate:board:samba24` / `post-rate:thread:rentou` / `post-rate:thread:copipe`。

### 8.2 板ごと: ごんたくれ規制（`timecount` / `timeclose`）

板の確定済み投稿履歴を新しい順に `timecount` 件取り、そのうち **24 時間以内**（`now − 86400 ≤ t`）で同一の投稿が `timeclose` 件以上あれば拒否。
`timecount=0` または `timeclose=0` で無効。既定値は現行の既定設定（`PostingText.settingTxtDefault`）と同じ `timecount=18` / `timeclose=10`。
`timecover` は読まない（§13）。

### 8.3 板ごと: Samba24（`BBS_SAMBA24`）

板の履歴に `now − BBS_SAMBA24 ≤ t` で同一の投稿が 1 件でもあれば拒否。`0` で無効。既定 `10`（§13）。上限 86,400。

### 8.4 スレごと（`BBS_THREAD_RENTOU` / `BBS_THREAD_RENTOU_RESET`）

書き込み先スレで `now − RESET ≤ t` の投稿のうち、同一 IP または同一識別子（ID は見ない）が `BBS_THREAD_RENTOU` 件以上なら拒否。
既定 `40` / `3600`。`0` で無効。>>1 には適用しない。

### 8.5 マルチポスト／コピペ吸い込み

スレの dat の末尾 2 行（あぼーん行＝4 フィールド全て `あぼーん` は除く）の本文フィールド（前後の空白を含む `<br>` 整形後の文字列）と、
新しい dat 行の本文フィールドが**完全一致**すれば、投稿者・時間によらず吸い込む。

- 応答は成功画面（「書きこみが終わりました。」、`x-threadkey`、`x-resnum` は採番予定だった番号）と同じ。エラーマーカーは出さない。
- dat・`log`・subject・headline・過去ログ目次は更新しない。Lv の投稿数も進めない。
- 板の連投履歴は**確定する**（吸い込まれた投稿も連投として数える）。
- >>1 には適用しない。Thread DO 内部では `post-rate:thread:copipe` の拒否コードで返し、handler が成功画面に変換する。

### 8.6 パイプライン位置

```
checkRequest → readForm → processCap → processTrip → boardSettings → threadSettings
→ identify（認証主体の決定 §3.4、Lv・権限の取得）
→ Moderation（deny/ngwords。5 列目の回避レベル）
→ checkSizes → 認証要求判定（§3.5）・BBS_PHOEBE ゲート（§4.4）
→ threadCreationLimit（既存）→ makeId（ID・識別子）
→ postRateLimit の材料作成（ハッシュ化。§8.1）
→ updateThread: Thread.addRes
     ├ スレ単位 連投判定（§8.4）        ← 拒否なら終了
     ├ Board.reservePost（§8.2・8.3）    ← 拒否なら終了
     ├ コピペ判定（§8.5）                ← 吸い込みなら Board.completePost して終了
     ├ storage.put（dat・log・recentPosts）  失敗なら Board.cancelPost
     └ Board.updateThread（既存。引数に予約 key を足して completePost を兼ねる）
→ Account.recordPost（Lv 昇格 §4.2。失敗しても投稿は成功扱い）
→ updateSubback → updateHeadline
```

`makeId` の後に置くのは ID と識別子を照合キーに使うため。スレ立て規制の予約（既存）と板の連投予約は同じ `Board` DO の別キーで、
Thread.addRes 内の順序は「スレ立て予約 → 連投予約 → put → 両方の確定」とする。

### 8.7 保存・同時実行

**Board DO**（既存キーは変更しない）

| キー | 内容 |
| --- | --- |
| `postRatePageCount` | ページ数 |
| `postRateHistory:<n>` | 64 件ごとのページ。各件 `{key, t, ip, id, shiki, uid, status}`（`key` は予約 ID＝`{threadKey}:{nonce}`、`status` は `pending` / `completed`） |

- RPC: `reservePost(key, entry, settings): String`（空＝許可、それ以外＝拒否コード）、`completePost(key)`、`cancelPost(key)`。いずれも `DurableLock.serialized`。
- 判定はスレ立て規制と同じく「確定済みは件数窓で、有効な予約は 1 件ずつ別枠で」行う。予約の有効期限は 120 秒。期限切れの予約は確定済みと同じ扱い（保守的）。
- 保持規則: 新しい順に `max(timecount, 1)` 件、または `now − max(86400, BBS_SAMBA24) ≤ t` の件を残す。上限 2,000 件（超えたら新規予約を `post-rate:capacity` で拒否）。
- 集約 `PostRateHistory`（純粋）が `reserve` / `complete` / `cancel` / `retained` を持ち、DO は `PostRateCodec` で変換して読み書きするだけ。

**Thread DO**

| キー | 内容 |
| --- | --- |
| `recentPosts` | `{t, ip, shiki}` の配列（新しい順）。`now − BBS_THREAD_RENTOU_RESET ≤ t` の件だけ残し、上限 1,000 件 |

- `addRes` の排他区間内で判定・追記し、`dat`・`log` と同じ `storage.put` で一括保存する。コピペ判定も同じ区間内で `dat` 末尾 2 行を見る。
- 既存の `addRes` 引数 `posting` に `rateLimit`（§8.1 の材料と設定）を足す。無ければ判定しない（管理画面やテストの既存経路を壊さない）。

### 8.8 設定キー

| SETTING.TXT | 既定 | 範囲 | 意味 |
| --- | --- | --- | --- |
| `timecount` | `18` | 0〜1000 | §8.2 の件数窓。0 で無効 |
| `timeclose` | `10` | 0〜1000 | §8.2 の同一件数。0 で無効 |
| `BBS_SAMBA24` | `10` | 0〜86400 | §8.3 の秒数。0 で無効 |
| `BBS_THREAD_RENTOU` | `40` | 0〜1000 | §8.4 の件数。0 で無効 |
| `BBS_THREAD_RENTOU_RESET` | `3600` | 0〜315360000 | §8.4 の秒数 |
| `BBS_RENTOU_PASS` | `privileged` | §7 の値 | 連投規制の回避レベル |
| `BBS_LIVE` | 空 | `1` / `checked` | 連投規制の適用除外（既存の強制 sage 動作は維持） |
| `BBS_PHOEBE` | `0` | 0〜10 | §4.4 |

読み込みは `ThreadCreationSettings.settingsMap`（trim・後勝ち）を使う。不正値は**返信も拒否せず既定値で動く**（スレ立て規制が不正値で拒否するのと異なる。
連投規制は設定ミスで板全体を止めるより通す方が被害が小さいため。管理画面の保存時には検証する）。
スレ設定（`Thread.settings`）に同名キーがあれば板設定を上書きする（既存の仕組み）。

### 8.9 文言

| コード | 文言 |
| --- | --- |
| `post-rate:board:gontakure` | `連続投稿ですか？？ しばらく待ってから投稿してください。` |
| `post-rate:board:samba24` | `Samba24 で規制中です。(n秒) しばらく待ってから投稿してください。` |
| `post-rate:thread:rentou` | `このスレッドへの連続投稿が多すぎます。しばらく待ってから投稿してください。` |
| `post-rate:capacity` | `連投規制の記録が一杯です。しばらく待ってから投稿してください。` |
| `post-rate:thread:copipe` | （成功画面） |

`PostingText` に定数として置き、handler だけが使う。

## 9. ストレージ・バインディング

### 9.1 新 DO

いずれも `new_sqlite_classes` で作る（`wrangler.jsonc` の TODO どおり SQLite バックエンド）。保存 API は既存と同じ KV 形式のストレージ API を使う。

**`Account`**（`idFromName(uid)`）

| キー | 内容 |
| --- | --- |
| `profile` | `{uid, kind: client\|password\|google, createdAt, expiresAt?, loginId?, passwordHash?, email?, emailVerifiedAt?, googleSub?, agreedAt, agreementVersion, status, generation, aliases[]}` |
| `level` | `{lv, lvSince, lvPosts, lvOverride?}` |
| `roles` | `["admin", ...]` |
| `caps` | §6.2 |
| `token` | §6.3 |
| `devices` | `[{hash, firstSeen, lastSeen, keyIpHash}]` 最大 10 件 |
| `log:<n>` | 利用ログ 64 件ページ（§6.2） |

RPC: `create`, `describeForPosting(gen)`, `recordPost(now)`, `login(passwordHash)`, `issueCap` / `revokeCap`, `issueToken` / `revokeToken`, `bindDevice`, `setLevel`, `setRoles`, `setStatus`, `bumpGeneration`, `appendLog`, `inspect`。
状態遷移（昇格・失効・移行）は `domain/account/` の純粋関数。

**`AccountIndex`**（`idFromName(kind)`。`kind` ∈ `login` / `email` / `google` / `cap` / `token` / `alias`）

- キー `=` 正規化した値のハッシュ、値 `=` `uid`。`register(hash, uid)` は既存なら失敗、`lookup(hash)`、`remove(hash)`。`blockConcurrencyWhile` で直列化。
- 1 種別 1 DO にするのは一意性を単一の直列化点で保証するため。件数は数万件規模を想定し、ページ分割は不要。

### 9.2 既存 DO への追加キー

§8.7 のとおり。既存キー（`dat`・`log`・`settings`・`subjectList`・`removed`・`threadCreation*`）は変えない。

### 9.3 KV・R2・secret・vars

| 種類 | 名前 | 用途 |
| --- | --- | --- |
| KV | `CAP`（既存） | hash → 表示名。互換のため維持 |
| KV | `AUTH_RATE`（新） | `keyIp` ごとの verify 回数・端末ハッシュ数。TTL 24h |
| R2 | `dc-asn.txt`・`ios-profiles.json`・`phoebe.cgi`（新） | §3.6・§3.7・§4.3 |
| vars | `TURNSTILE_SITE_KEY`・`GOOGLE_CLIENT_ID`・`AGREEMENT_VERSION` | 公開値 |
| secret | `AUTH_SECRET`・`TURNSTILE_SECRET_KEY`・`GOOGLE_CLIENT_SECRET`・`MAIL_API_KEY` | `wrangler secret put`。`.dev.vars` で開発。コミットしない |

`wrangler.jsonc` には `migrations` に `{tag: "v2", new_sqlite_classes: ["Account", "AccountIndex"]}`、`durable_objects.bindings` に `ACCOUNT_DO`・`ACCOUNT_INDEX_DO`、`kv_namespaces` に `AUTH_RATE` を足す。

### 9.4 `deny.cgi` との接続

`userInfo` の `UID:` と `Device:` に実値が入る（§5.3）ので、既存のルール書式のまま `UID:abc123 ` や `Device:p[0-9a-f]{18}` を対象にできる。
書式変更は 5 列目（回避レベル、§7）の追加だけで、`Moderation.parseRules` は 4 列と 5 列の両方を受け付ける。

## 10. ルーティングと画面

ルートは `feature/board_top` の方式（`bbs/Main.scala` のハンドラに `@route` 注釈、codegen が `app/routes/` を生成。
`IslandRender` は廃止済みで、画面は handler が `c.renderPromise` で描く — `docs/handler-rendering.md`）に従う。
islands は `bbs-client/Main.scala` の `@islands` 注釈で宣言する。

| パス | メソッド | 内容 |
| --- | --- | --- |
| `/auth/` | GET | 認証・登録ページ（SSR ＋ `AuthForm` island）。`embed=1` でモーダル用レイアウト、`mode=login`、`return` で戻り先 |
| `/auth/verify` | POST | Turnstile 検証・端末ハッシュ算出・突合・クライアント ID 作成または既存アカウントへの端末登録。JSON 応答 |
| `/auth/register` / `/auth/login` / `/auth/logout` | POST | ID/Password。`logout` は `gen` を進めて Cookie を消す |
| `/auth/google` / `/auth/google/callback` | GET | OAuth。`state` は HMAC 付き nonce。`popup=1` で callback が `window.opener` へ通知して閉じる |
| `/auth/email/verify` | GET | メール認証リンク |
| `/auth/account` | GET / POST | アカウントメニュー（SSR ＋ island）: 種別・Lv・`uid`、専ブラキー発行/失効、キャップ発行/失効、利用ログ、方式移行（`upgrade=1`）、ログアウト |
| 全ルート | — | `Accept-CH` 付与ミドルウェア（§3.7）。`app/routes/_middleware.ts` は手動管理（`docs/handler-rendering.md`） |

島の一覧: `AuthForm`（Turnstile ウィジェット・方式選択・同意チェック・`verify` 呼び出し）、`AuthModal`、`AccountNav`、`AccountMenu`（キー発行の 1 回表示とコピー）。

`/auth/*` の応答は `cache-control: no-store`、CSP（Turnstile の `challenges.cloudflare.com` と Google の必要オリジンだけ許可）、`X-Frame-Options` は付けず
`frame-ancestors 'self'` でモーダル埋め込みを許す。`/auth.js` の固定スクリプトは現行のまま残す。

## 11. 管理画面

| 画面 | 追加内容 |
| --- | --- |
| `/admin/board` | `BBS_PHOEBE`・`timecount`・`timeclose`・`BBS_SAMBA24`・`BBS_THREAD_RENTOU(_RESET)`・`BBS_RENTOU_PASS`・`BBS_THREAD_PASS`・`BBS_LIVE` の専用フォーム（既存のスレ立て規制フォームと同じ ETag 条件付き保存） |
| `/admin/access` / `/admin/ngwords` | 5 列目の回避レベルを検証 |
| `/admin/accounts` | `uid`・ログイン ID・メール・端末ハッシュ・IP ハッシュで検索 |
| `/admin/account?uid=` | 種別・Lv・`lvOverride`・権限・状態・端末履歴・キャップ・専ブラキー・利用ログ。旧キャップの紐づけ |
| `/admin/phoebe` | Lv 上限ルール（`phoebe.cgi`）、データセンター ASN 一覧（`dc-asn.txt`）、乱造検知の「要確認」一覧 |
| `/admin/thread` | 投稿ログの `UID:` / `Device:` から該当アカウント・端末 BAN（`deny.cgi` へ行を追加）へのリンク |

## 12. 実装順序とテスト

作業メモの順序（連投規制 → デプロイ → 認証ほか）に合わせる。段 A の前に [bbs.cgi DDD 化リファクタリング計画](bbs-cgi-ddd-refactoring-plan.md) の段 0〜7 を終える。各段で `sbt test`・`npm test`・`npm run v6:build` を通し、レビュー連鎖（AGENTS.md 3.2）を行う。

| 段 | 内容 | 主なテスト |
| --- | --- | --- |
| A | 連投規制（§8）。照合キーは現行の IP・ID・識別子（`checked` 書式）。`uid` は空 | `PostRateLimitSuite`（純粋判定: 件数窓・24h・Samba24・スレ 1h/40・コピペ末尾 2 行・あぼーん除外）、`BoardSuite`（予約/確定/取消・保持規則・ページ分割・容量）、`ThreadSuite`（`recentPosts` の一括保存・吸い込み時の dat 不変）、`PostingSuite`（パイプライン順序・文言・成功画面での吸い込み・`BBS_LIVE` 除外・設定不正時の既定値）、`AdminSuite`（フォーム検証）、実 workerd 統合 |
| B | 認証基盤: `Account` / `AccountIndex` DO、Cookie、`/auth/`（クライアント ID・Turnstile・法的確認）、端末ハッシュと突合、`Accept-CH`、専ブラキー、キャップのアカウント紐づけ、`userInfo` の `UID:` / `Device:`、新 UI の `SessionReader`・ヘッダー出し分け・`AuthModal` / `AccountNav`・投稿欄のヘッダ分岐（§3.8）、bbs.cgi の `x-auth-required` / `x-post-error` | `common` の `DeviceFingerprintHasher` / `ClientHints` golden、`CookieCodecSuite`、`AccountSuite`（状態遷移・一意性）、`AuthHandlerSuite`（Turnstile はテスト用サイトキーで成功/失敗を固定）、`PostingSuite`（§3.4 の優先順・`!auth`・Web 版の認証要求とヘッダ）、`SessionReaderSuite`、`BoardIndexSuite`（未ログイン/ログイン済のヘッダーと投稿欄の SSR）、`tests/islands-hydration.test.js` に `AuthModal` の `message` → `ch3s:auth-changed` と再送の jsdom 検証 |
| C | PHOEBE: Lv 昇格・`BBS_PHOEBE`・`!バルサン` / `!SETTING:BBS_PHOEBE:n`・識別子 `phoebe` 書式・`BBS_ID_LV` の実 Lv・連投規制への `uid` 追加 | `PhoebeSuite`（T(lv)/N(lv) 境界・上限・固定）、`IdentifierSuite`（軸ごとの値・欠損 `000`・`D` の `*`）、`UserAgentProfileSuite`（v3 表の golden） |
| D | 権限・回避レベル・管理画面（§6・§7・§11）、スレ立て規制の `BBS_THREAD_PASS` 化 | `BypassLevelSuite`、`ModerationSuite`（5 列目）、`AdminSuite` |
| E | ID/Password・メール認証・Google 連携・方式移行・エッジ防御・乱造検知・Lv 上限ルール | `AccountSuite`（移行）、`EdgeDefenseSuite`、実 workerd |

段 C の識別子既定値の切替と `BBS_ID_LV` の変更は、不変条件 3.4 に触れるため `docs/既知不具合台帳.md` に影響を書き、承認後に独立コミットで行う。

## 13. 要確認事項

| # | 事項 | 本書の仮置き |
| --- | --- | --- |
| 1 | 原案「未ログイン L0 固定」と PHOEBE.md「未認証 L0〜L2」の不一致 | 原案を採用。未ログインは L0 固定で昇格しない |
| 2 | 裁定メモ「Web 版は常に認証必須（Lv3）」の Lv3 | クライアント ID は L1 開始なので「ログイン必須」とだけ読む。Lv3 を要求する板は `BBS_PHOEBE=3` で表す |
| 3 | クライアント ID の 1 年リセット（原案は `？` 付き） | 発行から 365 日で失効、再発行は L1 から |
| 4 | 昇格曲線の読み替え（§4.2）と、クライアント ID が 1 年で L10 に届かないこと | PHOEBE.md の式を L1 開始に合わせて採用。実効上限 L9 を許容 |
| 5 | `!バルサン` / `!SETTING:BBS_PHOEBE:n` を使える人 | スレ立て人（認証済）＋ `staff` |
| 6 | `BBS_PHOEBE` ゲートの免除範囲 | `staff` のみ。`cap` 権限者は Lv で判定 |
| 7 | 連投規制の照合キーに `uid` を加えるか（原案は IP・ID・識別子） | 加える（IP 切替での回避を防ぐ） |
| 8 | ごんたくれの既定値 `timecount=18` / `timeclose=10` と、`timecover` を読まず 24 時間固定にすること | 既定設定の値を踏襲。`timecover` は無視 |
| 9 | `BBS_SAMBA24` の既定値（既存の SETTING.TXT にはキーが無く、KB-12 により既定値がそのまま効く） | `10` 秒 |
| 10 | 吸い込まれた投稿を連投履歴に数えるか | 数える |
| 11 | エッジ防御の免除（認証済 Lv3 以上）と RTT 閾値 | 免除あり、400ms |
| 12 | メール送信手段（外部 API の選定）と Google OAuth のクライアント登録 | 未定。段 E まで不要 |
| 13 | 乱造検知の閾値と、検知時に自動 BAN せず「要確認」に載せること | 1 時間 10 回・24 時間 5 ハッシュ、自動 BAN なし |
| 14 | 権限者が `/admin/*` にセッション Cookie で入る（Basic 認証との併用） | 併用。Basic 認証の管理者は `admin` 相当 |
| 15 | 識別子の文字集合（現行の Base64 文字のまま `+` `/` を含む） | 現行どおり |
| 16 | 新 UI で未ログインでも投稿欄を描画し、送信時にモーダルで認証する（欄自体を隠す案との比較） | 描画する。下書きを書いてから認証できる方が離脱が少ない |
| 17 | 認証後の自動再送（1 回）と、Lv 不足時は再送しないこと | そのとおり |
| 18 | bbs.cgi の応答ヘッダ追加（`x-auth-required` / `x-phoebe-required` / `x-auth-url` / `x-post-error`）。方針メモの `x-age` と同じ位置づけ | 追加する |

## 14. 不変条件への影響（承認が必要な点）

1. **識別子の書式変更**（§5）: `BBS_ID_SHIKIBETSU` の既定値を `phoebe` に切り替えると、全板の名前欄の識別子が変わる。dat の構造は変わらない。切替前の投稿は旧書式のまま残る。
2. **`BBS_ID_LV=checked` の付加値**（§4.1）: 固定 `10` → 実 Lv。現在この設定を使う板があるか確認し、KB-15 の状態を更新する。
3. **キャップの規制スルー**（§7）: 既定 `privileged` で現行と同じ挙動。`deny.cgi` に 5 列目を書いた行だけ挙動が変わる。
4. **スレ立て規制のキャップ除外**（§7）: `BBS_THREAD_PASS` の既定 `privileged` で現行と同じ。

上記以外（Cookie 追加・新 DO・新ルート・bbs.cgi の応答ヘッダ追加・Board/Thread DO への追加キー・`userInfo` の `UID:` / `Device:` の実値化）は、専ブラ互換・Shift_JIS・ID 計算・既存キーの形式に影響しない。
