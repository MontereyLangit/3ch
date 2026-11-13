# 認証の開発・検証設定

段Bの実装範囲と未実装部分は [引き継ぎ](claude-handoff.md) と [レビュー記録](auth-implementation-review.md) を参照する。本番への反映は別作業。

## 認証の有効化

認証は次の4つがすべて揃い、`AUTH_SECRET` が32文字以上のときだけ有効になる。1つでも欠けていれば、既存の投稿処理と画面を維持し、`/auth/` は認証導入前と同じ404を返す。板トップに認証モーダルも置かない。

| 設定 | 内容 |
| --- | --- |
| `AUTH_SECRET` | Cookie署名と索引HMACに使う秘密鍵。32文字以上の乱数を環境ごとに用意する |
| `AGREEMENT_VERSION` | 現在の利用規約・法的確認の版。画面に渡した版とPOSTの同意版を照合する |
| `TURNSTILE_SITE_KEY` | 認証ページに渡す公開サイトキー。検証するホストをTurnstile側でも登録する |
| `TURNSTILE_SECRET_KEY` | Turnstileのサーバー検証用秘密鍵 |

ローカルの値はGit管理外の `.dev.vars` に設定する。秘密鍵を `wrangler.jsonc`、文書、ログに書かない。

手動確認には検証用のTurnstile設定を用意し、対象のホストを登録する。サーバーは検証応答のhostnameと要求先を完全一致で照合する。[Cloudflareのテスト用キー](https://developers.cloudflare.com/turnstile/troubleshooting/testing/) を使う場合も、その応答仕様を確認し、本番のキーと混ぜない。自動テストは外部認証の応答だけを差し替え、実際のHTTP処理とDOを呼ぶ。本番の秘密鍵は必要ない。

## Googleログイン

`GOOGLE_CLIENT_ID`（vars）と `GOOGLE_CLIENT_SECRET`（secret）が両方あるときだけ、認証画面のGoogleを選べるようにし、`/auth/google/start` と `/auth/google/callback/` を受け付ける。どちらかが無ければ画面は「準備中」、この2つは404にする。

Google Cloud ConsoleでOAuthクライアント（ウェブアプリケーション）を作り、承認済みのリダイレクトURIに `https://www.3ch.io/auth/google/callback/` と、開発用の `http://localhost:8787/auth/google/callback/` を登録する。末尾のスラッシュも含めて一致させる。スコープは `openid email` だけ。2026-11-07の時点でクライアントは未登録で、実際のGoogleとの接続は確認していない。自動テストはトークンの交換とJWKSの応答を差し替え、テスト用のRSA鍵で署名したIDトークンで確かめている。

## 登録に使えないユーザーID

固定の予約語（`admin`・`root`・`test`・`3ch`・`saku`・`auth`・`mod`・`noname`）と既存の板名（R2の `{ID}/SETTING.TXT` がある名前）に加え、R2の `reserved-ids.txt` に1行1つで書いたIDを拒否する。空行と `#` で始まる行は読み飛ばす。R2を読めないときは登録しない。

## メール（認証・パスワードの再設定）

送信先が設定されているときだけ、登録のメール欄、アカウント設定のメールアドレスとパスワードの再設定、`/auth/email`・`/auth/email/verify`・`/auth/reset/request`・`/auth/reset` を使える。無ければ画面は「準備中」、API は503を返す。パスワードの変更（`/auth/password`）はメールと関係なく使える。

メールの提供元は未定。いまの実装が読むのは `MAIL_SENDER` だけで、`send({to, subject, text})` を RPC で受ける別の Worker を service binding で渡す。提供元を選んだら、`bbs/.../repository/MailSenders.scala` に secret `MAIL_API_KEY`（設計書 §9.3）を読む実装を足す。本文は固定の文面とトークン付きのリンクだけで、利用者の入力を含めない。

使い捨てメールのドメインは R2 の `disposable-domains.txt`（1行1ドメイン、空行と `#` の行は読み飛ばす。サブドメインも拒否）。R2を読めないときはメールアドレスを受け付けない。

## エッジ防御・Lv の上限

R2 の `edge-rules.json` に規則を置く。

```json
{"mode": "log", "datacenterAsns": [16509, 14618], "rttMs": 400, "tls": [{"ua": "Chrome/", "min": 500, "max": 2000}]}
```

`mode` は `log`（記録だけ。既定）か `on`（拒否）。判定は Workers Logs に `{"event":"edge",...}` の1行で残る。ファイルが無い・読めない・壊れているときは規則なしで通す。データセンターの AS は認証（verify・register・login・Google の開始）と、認証を有効にした環境の投稿に当てる。`tls` は UA が `ua` に一致するブラウザの ClientHello の長さの範囲で、認証画面の要求だけに当てる。免除は Lv3 以上で作成から7日以上のアカウント。

Lv の上限は R2 の `phoebe.cgi`（`対象 userInfo の正規表現<>上限 Lv` の行。空行と `#` の行は読み飛ばす）。当てはまる行の上限のうち最も低いものを、その投稿の実効 Lv にする。壊れていれば上限なしで通し、行番号を Workers Logs に残す。

乱造検知の「要確認」は、名前が `review` の AuthRate DO に残る（RPC `flagged`。管理画面は後続）。

## ローカルで確認する

```bash
npm ci
npm run v6:build
npm run dev
```

ブラウザでは `http://localhost:8787/` を開く。セッションは `__Host-ch3s_sid`、`Secure; HttpOnly; SameSite=Lax; Path=/` で発行する。開発時のHTTP発行を許すのは `localhost` だけで、`127.0.0.1` や任意のHTTPホストでは発行しない。本番はHTTPSを使う。

`/auth/` で同意とTurnstile確認を行い、認証後のヘッダー、投稿の認証モーダル、`/auth/account/` のキー発行・失効とログアウトを確認する。ID・パスワードは「ID・パスワード」で登録し、ログアウト後に「ログイン」タブから入り直せることを確認する。未ログインで `/auth/account/` を開くと `/auth/?return=/auth/account/` へ移る。キーの平文は発行直後に一度だけ表示する。GET、SSR、ブラウザの保存領域には残さない。

## Durable Objects

段Bの追加バインディングは `ACCOUNT_DO`、`ACCOUNT_INDEX_DO`、`AUTH_RATE_DO`。`wrangler.jsonc` の移行タグ `v2` は `new_sqlite_classes: ["Account", "AccountIndex", "AuthRate"]` の3クラスを追加する。既存のBoard・Threadの保存形式は変えない。

`AccountDirectory` は後続の管理画面の段で追加する。適用済みの `v2` に追記せず、別の移行タグを使う。`AdminAudit` も後続の管理画面の対象。

## 検証コマンド

```bash
npm run v6:build
sbt test
npm test
npm run v6:typecheck
```

`npm test` は事前にScala.jsをリンクし、実workerdの統合テストとSSR・hydrationを検証する。本番のストレージは使わない。最新の実行結果は [引き継ぎ](claude-handoff.md) に記録する。
