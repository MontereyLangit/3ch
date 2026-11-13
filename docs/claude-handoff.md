# 認証実装の引き継ぎ

作業ブランチは `ccr-9c002284-dbai2f`。2026-11-07（日本時間）の段Bの成果、段E-1（ID・パスワードとGoogleログイン）と段C（PHOEBE）の完了、段Dのうち管理画面以外（権限・キャップ・回避レベル）とE-2の完了を区切りとして引き継ぐ。ビルドとテストを通した小さな単位ごとにcommit・pushし、完了範囲と残件をこの文書へ記録する。

## 前提と現在の範囲

最初に [AGENTS.md](../AGENTS.md) を読む。`AGENTS.md` が作業規約の原本。[認証設計](auth-phoebe-rate-limit-design.md) §3.8・§6.3・§10・§12、[画面モック](ui/auth.html)、[レビュー記録](auth-implementation-review.md) も参照する。

前回のレビュー修正は `d02da38` を目印にしている。DDD化と段Aの確認では `sbt test` 843件、`npm test` 47件とビルド・型チェックの成功を記録済み。この件数は段Bを含む今回の結果ではない。

段B・E-1途中のレビュー指摘と対応は [レビュー記録](auth-implementation-review.md) の「段 B / E-1 途中のレビュー指摘と対応」にまとめた。

段BではAccount・AccountIndex・AuthRate DO、CookieとSessionReader、端末情報の照合、クライアントIDの登録、Turnstileと同意確認、専ブラ用キー、投稿認証と追加ヘッダーを実装した。新UIには認証ページ、投稿中の認証モーダル、ヘッダーの表示切り替え、アカウントのキー操作を追加した。認証は `AUTH_SECRET`（32文字以上）・`AGREEMENT_VERSION`・Turnstileの2つの鍵が揃ったときだけ有効になり、揃わない環境では従来の投稿・画面を維持する（`/auth/` も404）。

画面へ渡すセッションは `uid`・`lv` のみ。アカウント画面にキー平文・ハッシュを出さない。発行直後のPOST応答の平文だけを一度表示する。認証通知は表示更新用で、権限の判定はサーバーで行う。

## 残件と次の順序

段Bを完了扱いにしない。キャップのアカウントへの紐づけ・発行・失効を操作する画面、利用ログ、退会など、設計書と実装を照合して残件を整理する。アカウント画面は公開ID・登録方法と有効期限・Lv・この端末の粒度・専ブラ用キーの操作・ログアウトまで。Lvの進み具合（次のLvまでの時間と投稿数）は段Cでも画面に出していない。

AS・地域・UA・日別識別子の観測集計（Account.seen）、AccountDirectoryへの反映、管理者用RPC・役割の管理も未実装。専ブラの表示名は製品名・版・既知のOSを抽出する限定的な実装で、識別子のUA解析表（段C）とは別のもの。接続元の表示は現在取得できるAS番号と地域・国を使い、AS名の取得は後続で揃える。iOSの既知プロファイル表はR2から読む口だけを用意しており、本番の表データを作成したわけではない。

次は **管理画面（改修計画 M0〜M4）**。M1 に含む Origin 検証の復活と CSP の変更（`script-src 'self'`）は2026-11-07に利用者が承認した（[改修計画](admin-spa-auth-plan.md) §0・§10-9）。E-1・C・E-2 と、D のうち管理画面以外は完了した（下記「E-1の実装範囲」「Cの実装範囲」「Dの実装範囲」「E-2の実装範囲」）。段Bの残件と境界も引き続き確認する。

| 段 | 次に実装する内容 |
| --- | --- |
| E-1 | 完了。残りはGoogle OAuthのクライアント登録と本番での接続確認（外部設定）だけ |
| C | 完了。残りはUA解析表 `ua-profiles.json` のデータ（利用者が用意する）と、既定の識別子を `phoebe` に切り替える承認（設計書§14-1） |
| D | 管理画面以外は完了。残りは管理画面の作り直し（[改修計画](admin-spa-auth-plan.md) M0〜M4）: bootstrap の HTTP、管理者セッション、役割の付与・Lvの固定・凍結・他人へのキャップ発行と旧キャップの紐づけの画面、`BBS_THREAD_PASS`・`BBS_RENTOU_PASS` の専用フォーム。§14-4（名無しの staff の規制免除）は2026-11-07に承認済み |
| E-2 | 完了。残りはメールの提供元の選定と実装（`MailSenders` に1ファイル足す。外部設定）、要確認の一覧・edge-rules.json・phoebe.cgi を扱う管理画面（M3）、AccountDirectory への要確認の移し替え |

## 外部資料

端末ハッシュの原本は `MontereyLangit/v6-base` の `develop`。`common` の端末関連実装、`common/src/jsMain/kotlin/fc/pages/AuthPage.kt` の `collectFp`・`detectFonts`、`docs/feature/ninja_auth_system.md` を参照した。OS軸はUA、arch軸は `navigator.platform`。候補フォント、順序、計測文字列などは互換材料なので、独自の別方式へ置き換えない。

設計が参照していたv3のUA解析表は存在しなかった。`MontereyLangit/v6-base` の `docs/v3_legacy/bbs.php` では、解析結果の `$platform` は1109行目にしか現れない未定義の変数で、`$browser` にも代入が無い（同リポジトリの `docs/feature/v3_regulation_gap_analysis.md` A16 も同じ結論）。v6-baseの `BrowserName.kt` もv6で新しく作った表で、v3の移植ではない。表は推測で作らず、R2の `ua-profiles.json` から読む形にした（下記「Cの実装範囲」）。メール送信APIの選定も後続作業。Google OAuthのクライアント登録はまだで、本番のGoogleとの接続は確認していない（[認証の検証設定](auth-integration.md)）。

## E-1の実装範囲

E-1はID・パスワードの登録・ログイン・ロックと、Googleでのログイン・登録まで。設計書§3.9・§3.10の基本部分にあたる。レビューと設計からの差分は [レビュー記録](auth-implementation-review.md) の「段 E-1」にまとめた。

純粋モデルはcommonのLoginId、Password、LoginThrottle、PasswordCredential、GoogleIdToken（クレームの検証）、OAuthState（認可Cookieの書式）と、AccountStateの照合・利用開始・パスワード変更。PBKDF2はPBKDF2-HMAC-SHA256・100,000回・16バイトのソルトで、プラットフォームの上限は未測定。

Account DOのRPCは `create`（資格情報付き）、`activate`、`verifyPassword`、`setPassword`、`describeGoogle`。`verifyPassword` はDOのロック内でPBKDF2を計算し、接続元ごとの失敗計数とロックを同じ直列化の中で保存する。ロック中も計算を省かない。存在しないIDは固定の `Account("dummy")` に同じRPCを送り、固定の資格情報で計算して失敗にする（dummyのDOには何も保存しない）。応答の遅延（5回目から1秒ずつ、上限8秒）はDOのロック外、HTTP層で待つ。

登録は uid と login（Googleなら google）の索引を予約し、作成→login（google）索引の確定→uid索引の確定→利用開始（activate）の順に進める。uidの確定が拒否されたら両方の予約を外し、応答だけが失われたらuidの所有者を確かめてから続ける。利用開始の前に止まったアカウントは、正しいパスワード（Googleなら検証済みのIDトークン）での次のログインで確定を再開する。既存の10分の予約期限は変えていない。[実装メモ](auth-password-implementation-notes.md) §4の候補（Accountに確定要求を記録する方式）は採らなかった。

HTTPは `POST /auth/register`・`/auth/login`・`/auth/google/start` と `GET /auth/google/callback/`。登録とログインはTurnstileと端末申告の突合を必須にし、AuthRateの register（1日5件）・login（1時間30回）を接続元ごとに数え、計数できなければ拒否する。ログインの失敗（未登録・不一致・ロック中）は同じ401と文言。使えないユーザーID（予約語・R2の `reserved-ids.txt`・既存の板名・使用済み）は同じ409と文言。

GoogleはOpenID Connectの認可コード＋PKCE。state・nonce・verifierを署名付きの `ch3s_oauth` Cookie（`Path=/auth/google`、10分）に保存し、callbackで一度だけ使う。IDトークンはGoogleのJWKS（RS256）で署名を検証してからクレームを確かめ、subのHMACで `AccountIndex("google")` を引く。未登録なら入力したユーザーIDでLv10のアカウントを作る。`GOOGLE_CLIENT_ID` と `GOOGLE_CLIENT_SECRET` が揃わない環境では画面は「準備中」で、開始とcallbackは404。

認証画面は「新しく登録する」「ログイン」のタブで、登録方法にかんたん登録・ID・パスワード・Googleを並べる。メールアドレス欄はメール認証を実装するE-2まで出さない。認証モーダルは同じ画面を埋め込むので、ID・パスワードもそのまま使える。Googleは埋め込み時に別のウィンドウで開き、BroadcastChannelと既存のポーリングで親へ伝える。

E-1に含めなかったもの: クライアントIDからの方式移行（E-2）、パスワード変更・再設定・メール認証のHTTP（E-2）、Googleの開始へのエッジ防御（E-2）。ログイン中に新規登録すると別のアカウントになり、Cookieは新しいアカウントに切り替わる（移行はE-2）。

## Cの実装範囲

設計書§4・§5・§8.6・§8.7の段C。レビューと設計からの差分は [レビュー記録](auth-implementation-review.md) の「段 C」にまとめた。

Lvの昇格はcommonの `Phoebe`（T(lv)・N(lv)・開始Lv）と `AccountLevel.afterPost`。成功投稿を数えた後、経過時間と投稿数の両方が揃えば一段だけ上げて数え直す。クライアントIDは23h×2^(lv−1)、ID・パスワードは23h×max(1, lv−2)、GoogleはL10固定。固定値（`lvOverride`）がある間は数えるだけで上げない。Account DOの `recordPost` が昇格後の状態を同じputで保存し、Lvが変わった投稿ではCookieを発行し直してヘッダーの表示を揃える。固定の設定・解除は `Account.setLevel` のRPCとリポジトリまでで、操作画面は段D。

`BBS_PHOEBE` は `Phoebe.required` で0〜10の整数だけを読み、不正値は0。スレ設定（`!SETTING` 等で明示的に書いた値）を板設定より優先し、スレ立て時の書き写しから外した。免除は `PostingAccount.staff` で判定し、権限が無い今は誰も免除しない。未ログインの専ブラは従来どおり `x-auth-required: login`、認証済のLv不足は `x-auth-required: level` と `x-phoebe-required`。新UIは `x-phoebe-required` の値で「Lvn以上が必要です」を出し、認証モーダルも再送もしない。

`!バルサン`（Lv3）と `!SETTING:BBS_PHOEBE:n`（n＝0〜10）は本文の先頭だけを読む。使えるのはスレ立て人（Thread DOの `owner.account` と投稿者のアカウントの照合値が一致。`ownerAid` は足していない）とstaff。スレ立て人は上げるだけ、下げられるのはstaffの `!SETTING` だけ。スレ設定に値が無ければ板の値を現在値とし、Thread DOが `addRes` のロック内で `ThreadPhoebeRequest.next` を呼んで、datと同じputでスレ設定を書く。使えない投稿者・未ログイン・`BBS_DISABLE_NUSI` の板ではコマンドを無視して通常の投稿にする（文言なし）。コマンド行は本文に残る。

識別子は `BBS_ID_SHIKIBETSU=phoebe` の板だけ `国.県 AS-EEEFFFGGGZZZXXXD`。既定は `checked` のまま。軸の種は `値|軸名(E〜X)＋グループ＋日付＋ソルト`。認証済で端末ハッシュがあれば第1〜2・3〜5・6〜7・8・9軸を割り当て、無ければUA解析表のOS・ブラウザ・端末種別、UA全体、Client Hintsと `Accept-Language` を使う。空の軸は `000`、Dは未ログイン0・10は `*`。

UA解析表はR2の `ua-profiles.json`（`{"os": [{"match", "flags"?, "label"}…], "browser": […], "device": […]}`。各表は上から最初に一致した行の `label` を使い、`$1`〜`$9` を捕捉で置き換える。flagsは `i`・`u`・`s` だけ）。表が無い・読めない環境ではOS・ブラウザ・端末種別の軸が `000` になる。**表のデータは利用者の用意待ち**。用意するときは `tests/golden/ua-profile.json` に入出力を固定する（設計書§5.2）。iOSの既知プロファイル `ios-profiles.json` も段Bと同じく読む口だけで、データは用意していない。

連投規制の `aid` は段Bの時点で認証主体の `accountKey` を渡していた（§13-7）。段Cで追加の変更はない。

段Cに含めなかったもの: Lvの進み具合の表示、上限ルール `phoebe.cgi`（§4.3。§12でE-2）、staffと回避レベル `l10`・`login` のLv連携（段D）、投稿ログ `Device:` のUA解析の要約（§5.4。表のデータが無いので書式を変えていない）、既定の識別子の切替（§14-1の承認待ち）。

## Dの実装範囲

設計書§6.1・§6.2・§6.4・§7のうち、管理画面を除く部分。管理画面の作り直し（SPA・アカウント認証への切替・操作画面）は改修計画 M0〜M4 として残す。レビューと設計からの差分は [レビュー記録](auth-implementation-review.md) の「段 D」にまとめた。

権限は Account の `roles`（admin / delete / regulate / cap）。`Roles.staff` は cap 以外の役割を持つこと、`Roles.manages` は「admin は誰でも、ほかの staff は staff でない相手だけ」を操作できる規則。役割を変えられるのは admin だけで（`AccountState.assignRoles`）、Account DO の `setRoles(roles, actorRoles, now)` がロック内で判定する。admin の付け外しは application の `AccountRoles` が `AccountIndex("role:admin")` に反映する。初回の管理者は `AccountIndex("role:admin").claimFirstAdmin(hash, aid, now, breakGlass)` が有効な admin（確定済みで、Account の `adminStatus` が真）と他人の期限内の予約を数え、居なければ（非常用期間なら居ても）予約する。続く `Account.grantFirstAdmin` → `confirm` と失敗時の予約の取り消しは `AccountRoles.claimFirstAdmin`。非常用期間の判定は `FirstAdminClaim.breakGlass`（現在より先で24時間以内）。bootstrap の HTTP・トークンの照合・監査ログは M2。

投稿では `PostingAccount.roles` を認証結果の役割から埋め、`staff`（BBS_PHOEBE の免除と `!SETTING` で下げる権限）は役割から決まる。回避レベル `BypassLevel` は `BypassPoster`（役割・★・認証済・実効 Lv）で判定する。適用先は deny.cgi / ngwords.cgi の5列目（省略・空欄は privileged、不正な値は書式の誤り）、`BBS_RENTOU_PASS`、`BBS_THREAD_PASS`（キャップ除外を置き換え。スレ設定に書き写さない）。admin の投稿はルールファイルを読まない。既定の段階（privileged）で免除される投稿者は、ルールファイルが壊れている・読めないときも通す（キャップの従来の挙動を保つため）。Tor の拒否はキャップだけを免除する（staff は免除しない）。saku 板は従来どおり deny.cgi / ngwords.cgi をスルーする。

キャップは本人が `/auth/account` のキャップ欄で発行・パスワードの再発行・失効できる（`/auth/cap/issue`・`reissue`・`revoke`。発行は一日十回まで）。パスワードは `#` ＋20文字で、発行した応答にだけ出す。KV の `CAP` に従来の照合キー（`CapKey.of`。投稿処理と共有）で表示名を書き、`AccountIndex("cap")` に照合キーの HMAC → aid を載せる。失効では KV を消してから索引を外す（KV を消せなかったら索引を残し、所有者の失効の記録で照合を拒否する）。役割保持者は自分用に1個まで、admin は無制限・強キャップ・他人宛て（他人宛ては `CapIssuing.issue` まで。画面は M3）。自分で発行したキャップは役割を全て外すと使えない。旧キャップ（索引に無い KV の値）は従来どおり動く。キャップ欄には直近5件の書き込みを出す（Account の書き込み記録のうち `via=cap` の行）。

段Dに含めなかったもの: 管理画面（M0〜M4）、`adminGen`（管理者セッションの世代。M2）、旧キャップの紐づけ、`BBS_THREAD_PASS`・`BBS_RENTOU_PASS` の管理画面の専用フォーム（M3）。

## E-2の実装範囲

設計書§3.6・§3.7（乱造検知の「要確認」）・§3.9・§3.10（移行と退会）・§4.3（上限ルール）。レビューと設計からの差分は [レビュー記録](auth-implementation-review.md) の「段 E-2」にまとめた。

- メール: 送信は application の `MailSender` ポート。送信先は service binding `MAIL_SENDER`（`send({to, subject, text})`）だけを読み、提供元の実装（secret `MAIL_API_KEY`）は未選定。送信先が無い間は登録のメール欄・アカウント設定のメールアドレス・パスワードの再設定を「準備中」にし、API は503。
- メール認証: `EmailAddress` で正規化（前後の空白・小文字・punycode、gmail のドットと `+` 以降）、R2 の `disposable-domains.txt` を拒否。保留トークンは Account の `pending:email`（HMAC・24時間）。確認で `email` 索引を予約・確定してから Lv を3にする（下げない）。変更は確認まで前のアドレスを残し、確認後に前の索引を外す。
- パスワード: `/auth/password` は現在のパスワードを照合して置き換え、この端末だけに新しい世代の Cookie を出す。役割があれば16文字以上。再設定は `/auth/reset/request`（ユーザーIDとTurnstile。結果を区別しない）→ メール → `/auth/reset/?token=` の画面 → `POST /auth/reset`。メール認証済みで役割の無いアカウントだけに送り、接続元ごととアカウントごとに一日三回。
- 移行: アカウント設定から `/auth/?upgrade=1`。ID・パスワードは `/auth/register` に `upgrade=1`、Googleは認可の Cookie（版2。目的と aid を持つ）で同じアカウントのセッションに限る。aid・Lv・書き込み数・端末・履歴を引き継ぎ、uid は登録したIDに替わり、旧 uid の索引は残る。Google は Lv10。Google から他方式へは移さない。
- 退会: アカウント設定の「ログアウト・退会」から `POST /auth/account`（`action=delete`）。ID・パスワードはパスワード、Googleは認可のやり直し、かんたん登録は確認の印。deleted にして世代を進め、キャップと専ブラ用キーを失効させ、login・email・google・token の索引とキャップの KV を外す。uid・cap の索引と書き込みは残る。
- エッジ防御: R2 の `edge-rules.json`（既定は試行モード `log`）。データセンターの AS は認証4経路と投稿、TLS の ClientHello の長さと UA の矛盾は `/auth/*` だけ。免除は Lv3 以上で作成から7日以上。国内で RTT が閾値を超えると乱造検知の閾値を半分にする。
- 乱造検知: 閾値（1時間10回・24時間5端末、登録は一日5件）を超えたら拒否し、名前が `review` の AuthRate に「要確認」として残す（自動では規制しない）。
- Lv 上限: R2 の `phoebe.cgi` に当てはまる投稿の実効 Lv を抑える。アカウントの Lv は下げない。

E-2に含めなかったもの: メールの提供元、要確認・規則の管理画面（M3）、AccountDirectory、RTT 条件での Turnstile の再実行、移行・退会の監査ログ（管理画面の段）。

## 設定と検証

[認証の検証設定](auth-integration.md) に従う。`AUTH_SECRET` がないと認証は有効にならない。登録には `AGREEMENT_VERSION`、`TURNSTILE_SITE_KEY`、`TURNSTILE_SECRET_KEY` も必要。Googleログインには `GOOGLE_CLIENT_ID` と `GOOGLE_CLIENT_SECRET` を足す。秘密鍵は `.dev.vars` または環境のsecretで管理し、この文書やコミットへ書かない。

ローカルは `http://localhost:8787/` を使う。Secure CookieのHTTP開発例外はlocalhostだけで、127.0.0.1では発行しない。SQLite DOの `v2` はAccount・AccountIndex・AuthRateの3クラス。AccountDirectoryは後続で別の移行タグを追加し、適用済みタグを書き換えない。

通常の開発環境では次のコマンドを使う。今回の環境専用のラッパーは前提にしない。

```bash
npm ci
npm run v6:build
sbt test
npm test
npm run v6:typecheck
```

| 検証 | この区切りの結果 |
| --- | --- |
| Scala単体テスト | E-2の区切りで sbt test 1,345件成功（commonJVM 209、commonJS 212、codegen 27、bbsClient 2、bbs 895）。既存の投稿スナップショット・PostingSuite・goldenは変更なし。`-no-indent` のTest/compileも成功。段Dの区切りで sbt test 1,291件成功（commonJVM 194、commonJS 197、codegen 27、bbsClient 2、bbs 871）。既存の投稿スナップショット・PostingSuite・goldenは変更なし。`-no-indent` のTest/compileも成功。段Cの区切りで sbt test 1,246件成功（commonJVM 183、commonJS 186、codegen 27、bbsClient 2、bbs 848）。既存の投稿スナップショット・PostingSuite・goldenは変更なし。`-no-indent` のTest/compileも成功。E-1の区切りは1,181件 |
| workerd・SSR・hydration | E-2の区切りで npm test 76件成功、skipなし（実workerdに、差し替えた送信先で登録→メール認証→Lv3、再設定、退会、かんたん登録からの移行を1件）。段Dの区切りで npm test 75件成功、skipなし（board-islandsにキャップ欄3件、実workerdにキャップの発行→★の書き込み→失効を1件）。段Cの区切りで npm test 71件成功、skipなし（E-1の70件にLv不足の表示と再送しないことのboard-islandsを1件追加）。E-1の区切りでは実workerdで登録→ログアウト→ログイン→投稿、未登録・不一致の同じ応答、十回の失敗による接続元のロックと応答の遅延、別の接続元からのログイン、認証画面の方式の表示を検証した |
| ビルド | npm run v6:build成功 |
| 型チェック | npm run v6:typecheck成功 |
| コミット | E-1はRPC・登録とログイン・HTTP・画面・Google・文書の順に、CはLvの純粋関数・昇格の保存・BBS_PHOEBE・コマンド・識別子の純粋関数・識別子とUA解析表・文書の順に小さく分けてコミットした（pushはオーケストレーターが行う） |

本番へのデプロイ、secretの登録、本番データの変更はこの区切りには含めない。
