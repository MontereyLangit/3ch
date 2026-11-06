# 認証・PHOEBE・連投規制 設計のレビュー記録

2026-11-02。対象は [auth-phoebe-rate-limit-design.md](auth-phoebe-rate-limit-design.md) と
[bbs-cgi-ddd-refactoring-plan.md](bbs-cgi-ddd-refactoring-plan.md)（いずれもコミット `e6b9147` 時点）。
利用者の指示により、AGENTS.md 3.2 のセルフレビューを Opus 5（Claude Code のサブエージェント、読み取り専用）に依頼した。
秘匿値は渡していない（`wrangler.jsonc` の `salt` は読める位置にあるが、レビュー出力には含まれていない）。
Codex CLI / Gemini CLI レビューは未実施。

## 設計書への指摘

| # | 重要度 | 指摘（要約） | 判断・対応 |
| --- | --- | --- | --- |
| 1 | High | 板単位の照合キーに ID・識別子を含めると、CGNAT・同一機種の別人が一致し Samba24 で巻き添えが出る | 一部採用。原案のキーは利用者指示なので既定は維持し、`BBS_RENTOU_KEYS` で板ごとに選べるようにした。試行モード `log` で実測してから有効化する手順と、Samba24 は `ip,aid` に限る推奨を §13-24 に書いた |
| 2 | High | 「24 時間分を残す」と「上限 2,000 件で拒否」の組合せで、活発な板や埋め立てで全投稿が止まる | 採用。保持を「直近 timecount 件 ∪ SAMBA24 秒以内 ∪ 有効な予約」に絞り、容量超過は古い順に捨てて拒否しない（§8.7） |
| 3 | High | スレ立て時に板設定が全キー書き写され返信で上書きされるため、新しい規制キーが既存スレに効かない | 採用。新キーと `timecount` / `timeclose` を書き写しから除き、板単位の規制は板設定だけで判定する（§8.1・§8.8・§14-8） |
| 4 | High | 原案「名無しでは キャップ書き込み以外」に対し、`cap` 権限者の名無し投稿まで免除していた。「現行と同じ」も誤り | 採用。`cap` は★投稿でだけ免除、`privileged`＝`staff`＋★投稿に改めた（§6.4・§7）。`staff` の名無し免除は現行にない挙動として §14-4 に明記 |
| 5 | High | `!uid` → `@uid` の仕様がなく、手入力で偽装できる | 採用。` </b>@uid<b>` をサーバが付ける形で §5.3 を新設。計画書 §7 に差し込み先を追加 |
| 6 | High | ログイン ID＝`uid` だと ID が公開され、列挙防止が無意味。ID を知る第三者がロックで締め出せる | 採用。`uid` は全種別とも乱数採番、ログイン ID は非公開に。ロックは（アカウント, keyIp）単位＋段階的遅延（§2・§3.9） |
| 7 | High | `uid` の一意性索引が無く、退会後に同じ `@uid` で再登録できる。乱数 8 文字とログイン ID の形式が重なる | 採用。`AccountIndex("uid")` を追加し削除しない。#6 により衝突の余地も消えた。`alias` 索引は不要になり削除 |
| 8 | High | セッション Cookie で `/admin/*` に入れると 3.6 の防御が弱まる。画面ごとの権限判定も未定義 | 採用。権限者向けは `/mod/*` に分離、短命 host-only の別 Cookie・再認証・Origin 検証。`/admin` は Basic 認証のみのまま。画面と権限の対応表を §6.1 に追加。**2026-11-02 の利用者指示で変更**: `/mod` は作らず `/admin` に統合し、Basic 認証をアカウント認証（再認証付きの短命管理者セッション＋役割＋Origin 検証）に置き換える（[管理画面 改修計画](admin-spa-auth-plan.md)）。指摘の要点（別 Cookie・短命・再認証・Origin 検証・画面と権限の対応）はそのまま引き継いだ |
| 9 | High | Web 版ログイン必須は UA を変えれば抜けられる。判定が現行の前方一致と違う | 採用。「導線であって防御ではない」と明記し、判定を現行 `isMonazilla` に揃えた（§3.5） |
| 10 | High | KV による計数は原子性・整合性が無く、並列要求ですり抜ける | 採用。`keyIp` ごとの計数 DO `AuthRate` に変更。応答しないときは認証系を拒否（fail-closed）（§3.7・§9.1） |
| 11 | Medium | ログアウトの扱いが 3 か所で矛盾。RPC 名も不一致 | 採用。常に `gen` を進める（全端末）に統一。`describeForPosting(aid, gen)` に揃えた |
| 12 | Medium | `Domain=.3chbbs.com`・専ブラ向け Secure 無し・127.0.0.1 で検証不可・`/auth/*` の CSRF | 採用。`__Host-` の host-only、HTTPS のみ発行、`localhost` で開発、`/auth/*` `/mod/*` の POST で Origin 検証、退会は再認証（§3.3・§3.9）。サブドメイン共有の要否は §13-29 |
| 13 | Medium | 応答ヘッダが `js.Dictionary` のため `Set-Cookie` が 1 本しか出せず PON が消える | 採用（コードで確認: `Responses.scala` の `headers.set`、`PostingHandler.identify`）。`Headers.append` 化を §3.3 と計画書 段 6・§3 に追加 |
| 14 | Medium | 吸い込みが別人の短文も捨てる。存在しない `x-resnum` を返す。RPC がコードしか返さない | 一部採用。同一投稿者限定は利用者指示（投稿者を問わない）と異なるため §13-25 で確認。戻り値は `{count, swallowed}` に構造化。吸い込みを示すヘッダはスクリプトに検知させないため付けず、§14-6 に影響を記載 |
| 15 | Medium | 全投稿で Board DO の全ページを読み書きする負荷 | 採用。追記専用リングバッファで最新ページとメタだけ書き、判定に要るページだけ読む（§8.7） |
| 16 | Medium | 予約中の扱いが未定義、`recentPosts` に aid が無い、段 C の `uid` 表記 | 採用。予約は件数に 1 として数える、`recentPosts` に `aid`、段 C を `aid` に修正 |
| 17 | Medium | デプロイと同時に全板で拒否が始まる。Web のログイン必須化も承認事項 | 採用。`BBS_RENTOU_MODE`（`off` / `log` / `on`）を追加し初回は `log`。段 A を A-1 / A-2 に分割。§14 に 5・7 を追加 |
| 18 | Medium | スレ立て人の保存先・コマンドの処理位置・値を下げる権限・`n` の境界 | 採用。Thread DO に `ownerAid`、§8.6 にコマンド段、下げるのは `staff` のみ。境界は利用者指示（最低 Lv）を採り、旧 wiki との 1 ずれを §13-5 に記載 |
| 19 | Medium | Lv が日中に上がると ID 末尾と `D` が変わり、3.4 から外れる | 一部採用。`BBS_ID_LV` は変えない（KB-15 のまま）ことにして ID 文字列への影響を無くした。`D` は投稿時点の Lv のままとし §14-2 に影響を記載 |
| 20 | Medium | PBKDF2 の反復上限、所要時間の均一化、ロック規則が DO 内にあること | 採用。上限を明記し将来は WASM へ。ダミーも DO を 1 回呼ぶ。ロック規則は `LoginThrottle` の純粋関数に（§3.9） |
| 21 | Medium | 旧キャップの仮主体の位置づけ、`login` と実効 Lv 0 の衝突 | 採用。旧キャップは認証済・`BBS_PHOEBE` と Lv 判定の対象外。「認証済」を実効 Lv と切り離して §2 で定義 |
| 22 | Medium | TLS 指紋の判定表が無い、専ブラへの誤判定、適用先が曖昧 | 採用。規則を R2 `edge-rules.json` に、試行モードで開始、TLS 判定は `/auth/*` のブラウザ経路のみ、適用先と Lv 条件を表に（§3.6） |
| 23 | Medium | COOP で `opener` が消える可能性、CSP の `form-action` / `frame-src`、`targetOrigin`、`Referrer-Policy` | 採用。`BroadcastChannel` と `/auth/session` ポーリングの予備経路、CSP 追記、`targetOrigin` 固定、`no-referrer`（§3.8・§3.10・§10） |
| 24 | Medium | 索引の掃除が作成中のアカウントを消す競合、`register(hash, uid)` の誤記、`lookup` の直列化 | 採用。索引に `pending` / `confirmed` と `createdAt`、10 分で期限切れ、`lookup` はロックなし（§9.1） |
| 25 | Medium | メール正規化が未定義で使い捨てメールで L3 を量産できる | 採用。正規化規則と使い捨てドメイン拒否表、エッジ防御の免除に作成 7 日以上を追加（§3.9・§3.6） |
| 26 | Low | `return` の検証が不十分 | 採用。`new URL` の origin 一致と許可文字に限定（§3.8） |
| 27 | Low | `Accept-CH` は文書応答でしか効かず、304 経路に穴 | 一部採用。全応答に付ける裁定（2026-08-15）は変えないが、効く範囲を明記し Hono ミドルウェアで付けるとした（§3.7） |
| 28 | Low | `salt` と `AUTH_SECRET`、`BBS_LIVE=1`、`BBS_COPIPE` / `timecover`、`saku`、板名 `auth`、SSR の Cache-Control | 採用。索引ハッシュは `AUTH_SECRET` の HMAC、`BBS_LIVE` は `checked` / `1`、`saku` は連投規制の対象、`auth` / `mod` を禁止板名に、個人化 SSR は `private, no-cache` ＋ `Vary: Cookie`（代案は §13-27）。`BBS_COPIPE` / `timecover` は §8.2 で「読まない」と既記 |
| 29 | Low | 拒否コード・文言の不足、`pending` の 1 枠 | 採用。§8.9 をコード表に拡張（Samba の残り秒、frozen / banned）、`pending` を種別ごとのキーに、`devices` と `log` の入れ替え規則を §9.1 に追加 |

## リファクタリング計画への指摘

| # | 重要度 | 指摘（要約） | 判断・対応 |
| --- | --- | --- | --- |
| 1 | High | スナップショットの比較基準が、未デプロイの統合状態になる | 採用。段 0 を 0a（`develop_v6s` でスナップショット作成）と 0b（統合。差分は意図した変更として記録）に分割 |
| 2 | Medium | 「`PostingHandlerSuite` を改名以外変えない」は両ブランチで件数・期待値が違い実行できない | 採用。段 0b の終わりに統合スイートを基準として確定する記述に変更 |
| 3 | Medium | 段 A が段 8 の `PostingCodec` に依存し、デプロイ時期が決まらない | 採用。`PostingCodec` を段 6 に移し、段 A は段 7 の後。段 0b〜9 はどの段の後でもデプロイ可と明記 |
| 4 | Medium | 正規表現を使う `TripAlgorithm` と `UserAgentProfile` を `common` に置いている | 採用。`TripAlgorithm.classify` は文字単位で書き直して `common`（golden で同値確認）、`UserAgentProfile` は `bbs/domain` |
| 5 | Medium | スナップショットの形式・正規化・`nowTime` の `undefined`・再生成手順・置き場所 | 採用。`tests/snapshots/` に分離、base64、生成コマンド、再生成の承認手順を追記。`g.nowTime` 未設定は KB-39 として台帳に記録 |
| 6 | Medium | `Rejected` にヘッダが無い、`Set-Cookie` 複数、`PostingError.code`、`Swallowed` の番号 | 採用。`PostingOutcome` の定義を修正（§3） |
| 7 | Low | `BBS_HEISA` の正規表現判定との差、§7 の対応表の不足 | 採用。段 8 に境界ケースの追加、§7 に `!uid`・コマンド・`recordPost`・エッジ防御の行を追加 |

## 採用しなかった、または保留にした点

- 設計 #1 の「識別子と ID を板単位の照合から外す」: 原案の指示に反するため既定は変えず、設定で選べる形と試行モードで代替した（§13-24）。
- 設計 #14 の「同一投稿者に限る」: 原案に反するため §13-25 で確認する。
- 設計 #18 の `n` の境界: 利用者指示（最低 Lv）を優先し、§13-5 で確認する。
- 設計 #27 の「`Accept-CH` は HTML に限る」: 2026-08-15 の裁定を変えない。

## ID 廃止に伴う変更（2026-11-05）

トリップと日付欄の ID の廃止・`!id`・スレ主表示（[トリップ・ID廃止とスレ主表示](トリップ・ID廃止とスレ主表示.md)）に合わせ、設計書の次の判断を改めた。

- 設計 #1: 照合キーの候補から `id` を外し、`BBS_RENTOU_KEYS` の既定を `ip,shiki,aid` にした（§8.1・§8.8・§13-24）。日付欄の ID が無くなり照合に使えないため。原案の指示（`ip,id,shiki`）からは `id` だけが抜ける。巻き添えの懸念は `shiki` について残り、試行モードで実測する手順は変えない。
- 設計 #5: `!uid` と ` </b>@uid<b>` は `!id` と ` </b>(@ユーザーID)<b>` に、並びは名前の直後・識別子の前に変わった（§5.3。`develop` で実装済み）。
- 設計 #6: 利用者の指示で ID/Password・Google は登録したユーザー ID を表示するので、ログイン ID は非公開にできなくなった。ログイン ID をユーザー ID として公開し、列挙と締め出しへの備えは（アカウント, keyIp）単位のロックと段階的遅延、所要時間の均一化だけにする（§2・§3.9・§13-34）。
- 設計 #7: ログイン ID と自動採番の `uid` が同じ形式で重なりうるので、`AccountIndex("uid")` に両方を載せて一意にする。移行で使わなくなった `uid` も削除しない（§3.9・§3.10）。

## 連投規制の段階の変更（2026-11-06）

利用者の決定で、レビュー #17 で足した `BBS_RENTOU_MODE`（`off` / `log` / `on`、既定 `log`）を `BBS_FR_LEVEL` に置き換えた（§8.1・§8.8）。

- 値は `off`（判定も記録もしない）・`log`（判定と記録だけ）・空・1 以上の整数。キーが無いときと、`0`・負数・不正な文字列も規制モードにする。
  整数は旧 3ch の広域連投規制の数値で、意味は未実装。`PostRateLevel.Enforce` に保持だけしておく。
- `off` / `log` は小文字だけに一致させた。`BBS_LIVE`（`checked` / `1`）や `BbsSettings.checked` と同じく値をそのまま比べる慣習に合わせ、
  大文字の `OFF` などは不正な値として規制側に倒す。前後の空白は他の設定と同じく除く。
- コード既定が規制モードになり、デプロイした時点で全板で拒否が始まる（§14-5、承認済み）。段 A-2（既定を `on` へ）は不要になった。
- 吸い込みを同一投稿者に限らないこと（#14、§13-25）と吸い込み自体（§14-6）も承認された。`timecount` / `timeclose` の書き写し（§14-8）は未決のまま現状維持。
- 既存のテストのうち、同じ投稿者（IP・識別子・認証主体）が 10 秒以内に続けて書くものは、既定の Samba24 に当たるようになった。
  期待値は変えず、テストの板設定に `BBS_FR_LEVEL=off` を足して元の動きを確かめるようにした。
  `PostingSuite` と管理画面の統合テストは独立したコミットにした。スナップショットの 3 件は、変更前だと `BBS_FR_LEVEL` がスレ設定に書き写されて
  期待値とずれるので、書き写しから除く変更と同じコミットにした。

| テスト | 当たった規制と理由 |
| --- | --- |
| `PostingSuite` のスレ立て規制（同一 IP・タイトル・UA・AS＋県、RESET 経過後、設定不正、設定キーの前後空白） | 同じ IP・UA から 1〜3 秒おきにスレ立てと返信をする。Samba24 |
| `PostingSuite` の dat 行フォーマット（返信）・行数チェック・主（8 件）・threadSettings・BBS_MAX_MENU_THREAD・updateSubback の競合注入・dat 1000 行・全板共通スレ 900 | 同じ投稿者が時刻を進めずにスレ立てと返信を続ける。Samba24（主の 8 件は IP・識別子・認証主体のどれかが一致する組み合わせ） |
| スナップショット `create-creation-limit-zero`・`create-creation-after-reset`・`error-creation-spaced-keys` | 前提の投稿と同じ IP から 1〜2 秒後にスレ立てする。Samba24 |
| `tests/admin.integration.test.js` | 同じ IP からスレ立てと返信を続ける。Samba24 |

スナップショットの期待値ファイル（`tests/snapshots/posting/`）は変えていない。連投規制のケースは、規制モードをキー無し（`error-post-rate-gontakure`・`error-post-rate-thread`・`reply-post-rate-live`）と
空の値（ほか）で、試行モードを `BBS_FR_LEVEL=log` で設定するように改めた。

## レビューで未確認とされた事項

workerd の PBKDF2 反復上限、COOP による `window.opener` 喪失、主要な専ブラが host-only の `Set-Cookie` を保持するか、
DO の RPC 変更と段階的デプロイの関係。いずれも実装段で実機確認する。
