# 認証・PHOEBE 実装のレビュー記録

## 段 B の前提となる実装の確認（2026-11-06）

対象は 2137a44 から 9d2eee8 までの DDD 化の段 5〜9 と連投規制の段 A

| 観点 | 指摘と対応 |
| --- | --- |
| 確定後の障害 | 試行モードのログ出力が失敗すると、保存済みの投稿まで内部エラーになっていた。記録先の障害を投稿の成否から切り離し、目次の更新も続ける |
| 規制の無効化 | Thread.addRes が off の材料を受け取ったときも判定・履歴保存を続けていた。ドメインの有効性判定を使って除外する |
| 予約の取消 | Board DO の予約保存後に RPC 応答だけ失われると、失敗した投稿の予約が残っていた。dat 保存前の障害でスレ立て・連投の両予約を取り消す。確定済みの履歴は残す |
| DDD・FP | ModerationService が規制ファイルの取得を呼び出していた。ファイルの読込順序は application の PostingModeration に移し、domain は免除・Tor・ルールの純粋な判定を持つ |
| 依存方向 | BoardRepository が handler の既定設定を参照していた。既定設定を BbsSettings に置き、画面側から参照する |
| UI・設計 | 板設定モックと管理画面の設計が BBS_FR_LEVEL の未設定・空・0・log の意味と一致していなかった。既定 1、0 と空では FR だけ無効、FR 秒数を指定できる形に揃えた |
| Scala・画面 | Claude の追加分に TS/TSX と bbs-client の変更はなく、新しいクラスコンポーネントや Hooks の違反はない。モックは既存部品を使い、角丸を追加しない |
| Scaladoc | 変更した公開メソッドの説明を補い、日本語の短い説明、末尾の句点なしに揃えた。経緯はこの文書に残す |
| テスト | 障害後の応答、予約 RPC の応答喪失、保存失敗、同時投稿、off、規制ファイルの短絡、拒否コードと未知の障害、スレ主の遅延復号を追加で検証。sbt test 843 件、npm test 47 件、ビルド・型チェックが成功。既存のスナップショットと golden は変更していない |

## 段 B の外部資料

端末ハッシュの算出方式と v3 の UA 解析表は設計書でリポジトリ外の資料を参照しているため、原本を確認してから移植する
メール送信 API は設計書で未定のため、段 E-2 では送信先の設定と送信ポートを分離する

## 段Bの認証画面・投稿連携（2026-11-07、日本時間）

段Bは部分実装。クライアントIDの認証、Cookie、端末情報、専ブラ用キー、投稿時の認証と画面の連携を追加した。キャップのアカウント操作画面など、設計書の全項目を完了した状態ではない。設定と残件は [認証の検証設定](auth-integration.md) と [引き継ぎ](claude-handoff.md) を参照する。

| 観点 | 実装・確認対象 |
| --- | --- |
| 画面設計 | `docs/ui/auth.html`・`auth-modal.html`・`account.html` を参照。既存ヘッダーの配色と部品を使い、角丸を追加しない。ID・パスワードとGoogleの操作は準備中として無効にする |
| Scala・Hooks | AuthForm・AuthModal・AccountNav・AccountMenuは関数コンポーネント。表示状態とTurnstile、イベント、タイマーの副作用をHooksで管理する。業務処理をTSへ移さない |
| DDD・FP | 登録・キー発行の順序と補償はapplicationのClientAuthentication・TokenIssuingへ移した。DOはロック・変換・保存を担当し、状態遷移と期限はcommonの純粋モデルに置く。投稿の認証結果は不変値で後段へ渡す |
| 失効と障害 | 期限更新と再認証の両方で専ブラCookieのbindingを維持する。保存後のアカウント記録失敗で投稿を失敗扱いにしない。予約応答の喪失と補償失敗も検証する |
| 境界の検証 | 索引の欠損を明示した応答だけを旧キャップとして扱う。不正なRPC値は型変換の前に拒否し、保存済みプロフィールの状態・世代の欠損も拒否する |
| Workerの配線 | 変更できないFetch応答を複製してAccept-CHを付ける。Viteの最終エントリにも新DOを公開し、ソースだけでなく生成物と実workerdで確認する |
| SSR | SessionReaderはCookieの署名と期限から表示情報を読み、Account DOを呼ばない。クライアントpropsは `uid`・`lv` と公開設定・利用履歴だけ。Cookie、`aid`、キーハッシュを渡さない |
| キャッシュ | 認証を有効にした板画面は `private, no-cache` と `Vary: Cookie`。認証ページとAPIは保存しない |
| モーダル | 通知のoriginとiframeのcontentWindowを照合する。BroadcastChannelと2秒ごと・最長5分のポーリングはセッションを再確認し、表示通知だけで投稿を許可しない。キャンセル・終了・アンマウントで監視を解除する |
| 投稿 | 最初の送信内容を固定し、認証後に一度だけ再送する。Lv不足・拒否・キャンセルは下書きを残す。送信待ちに追記した本文も成功時に消さない。JSなしの通常POSTと認証リンクを維持する |
| 専ブラ用キー | 平文は発行直後のstateだけで保持する。使用枠、UA表示名、日時、直近5件、未使用キーの期限、失効理由、不一致の警告を公開情報から表示する |
| 追加テスト | BoardIndexSuiteで未ログイン・ログイン・Lv不足と埋め込みSSRを検証。RendererShellSuite・HonoRenderSuiteは共通モーダルのSSRを検証。board-islands.test.jsに通知の送信元照合、再送上限、下書き保持、hydration一致、Turnstileと同意、キー平文の保存防止を追加。最終の実行結果は引き継ぎに記録する |

端末情報の原本は `MontereyLangit/v6-base` の `develop` を確認した。ブラウザ収集は `common/src/jsMain/kotlin/fc/pages/AuthPage.kt` の `collectFp` と `detectFonts` を参照し、OS軸にはUA、arch軸には `navigator.platform` をそのまま使う。フォント候補・順序・計測文字列・WebGLの材料を変えない。関連設計は同リポジトリの `docs/feature/ninja_auth_system.md`。v3のUA解析表全体はまだ確認できていない。

## 段Bの端末情報の空白処理（2026-11-07、日本時間）

原本のKotlin 2.3.21のtrimとScalaのString.trimで空白の範囲が異なり、NBSPや全角空白付きの機種名で端末ハッシュが変わる問題を修正した。Client Hints、申告UA、粒度、旧保存値の前後空白にも同じ原本の規則を適用する。

KotlinJSの [生成済み空白表](https://github.com/JetBrains/kotlin/blob/v2.3.21/libraries/stdlib/js/src/generated/_WhitespaceChars.kt) の28文字をcommonのDeviceStringsで固定する。範囲は0009〜000D・001C〜0020・00A0・1680・2000〜200A・2028・2029・202F・205F・3000。KotlinJVMのCharacter.isWhitespaceまたはisSpaceCharとも一致する。NUL・NEL・BOMは含まない。既存のJsTextはECMAScriptのtrim用でBOMを取り除くため流用しない。

新しいgoldenのdevice-whitespace-reference.jsonに出典と期待値を追加し、モデル、引用符内外のClient Hints、UAの完全一致、粒度、粒度付き新形式と公開鍵付き旧形式をJVMとJSで照合する。既存のgoldenと投稿スナップショット、パスワードとAuthHmacの空白処理は変更しない。

## 段 B / E-1 途中のレビュー指摘と対応（2026-11-07、日本時間）

対象は 9d2eee8 から 4d90edc まで（段 B の認証基盤と E-1 の純粋モデル・PBKDF2・保存形式）。

| 指摘 | 対応 |
| --- | --- |
| 画面からのログアウトが常に 400 | ヘッダーの fetch が本文を付けず Content-Type が無かった。ログアウトの送信を AuthClient にまとめてフォーム形式の空の本文を付け、board-islands に本文の形を確かめるテストを足した。統合テストは URLSearchParams を付けていたので検出できなかった |
| 同意の版を上げると再同意できない | 同意の版が古い投稿の拒否にも `x-auth-required: login` を付け、認証モーダルの verify で同意し直せるようにした。文言も認証・登録の画面へ誘導する形にした |
| 未ログインの /auth/account が JSON 401 | `/auth/?return=/auth/account/` へ 302 にした。確認の途中で、ルートが `auth/account.ts` のため末尾スラッシュ付きの `/auth/account/` が描画されず公開ハンドラの 404 になっていたこと、API の `app.all("/auth/*")` が画面の GET を 405 にしていたことが分かった。ルートを `auth/account/index.ts` にし、API は `GET /auth/session/` と POST だけにした |
| ログアウトで無効なセッションの Cookie が残る | セッションの検証や保存の結果にかかわらず Cookie を消す |
| メール欄コマンドの解釈の重複 | common の `MailCommands` に `!#`・キャップのパスワード・`!auth` の読み取りと除去をまとめた。キャップのパスワードは従来の CapHandle と同じ範囲（最初の `#` から次の `#` の手前まで、空白を含む）で、メール欄からも同じ範囲を除く |
| 層の依存が逆 | Cookie の検証・発行を `PostingCredentials` の口にし、handler の `SessionCredentials` が実装する。投稿の認証と記録は application の `PostingAccountService`、組み立ては handler の Posting。認証結果は domain の `AuthenticatedPoster`。`PostingUserSource` は元の形に戻し、認証を有効にした環境だけ `PostingAccounts` を渡す |
| 種別を文字列で表す | AuthHandler の拒否理由を `AuthApiError`、本人認証の失敗を `AuthServiceFailure`、AccountRepository の失敗を `AccountStoreException(AccountRejection)` にした |
| 保存量に上限が無い | キー発行を AuthRate で 1 アカウント 1 日 10 回、無効にしたキーの記録を 20 件、ログイン失敗は接続元ごとの計数（最大 100 接続元）に、AuthRate の履歴は種類ごと 120 件・端末 20 件までにした。無効になったキーは Account DO が保存の後に token 索引から外す。キー照合の拒否は書き込みログと分けて `rejections` に 20 件まで残す |
| UI の不一致 | 日時を日本時間（common の `JstTime`）にし、無効化の理由を `TokenRevokeReason` で読む。期限切れの判定はサーバの結果だけを使う。アカウント画面に登録方法・有効期限・この端末の粒度と登録台数・ログアウトを足し、かんたん登録はログアウトで戻せないことを出す。リンク先の無いキャップをメニューから外した。認証画面に「認証の前にお読みください」を足した。Lv の進み具合はデータが無いので出していない |
| 認証設定なしでも認証モーダルを置く・設定の一部だけで Web 版が書けない | `AUTH_SECRET`（32 文字以上）・`AGREEMENT_VERSION`・`TURNSTILE_SITE_KEY`・`TURNSTILE_SECRET_KEY` が揃ったときだけ認証を有効にする。揃わない環境は `/auth/` も 404 で、モーダルも置かない |
| log の Device: が空になる | 端末ハッシュの無い認証投稿と匿名投稿は従来の端末情報を記録する |
| Set-Cookie が 2 本付く | 投稿後に発行し直した Cookie は認証時のものと差し替える。セッションを持たないキャップの投稿には発行しない |
| verify の細部 | `return` をやめ、IP の確認と keyIp の算出を Turnstile の検証より前にした。本文の不正な `%` は 400 |
| Effect 内の var | 認証フォームと認証モーダルを useRef にした |
| strict 引数・Scaladoc・抜粋 | 未使用の strict を外し、AuthRateEvent と投稿の口の Scaladoc を直した。抜粋は文字参照の途中で切らない |
| 統合テストの compatibility_date | 本番の `wrangler.jsonc` は 2026-10-02 で、テスト用の設定と一致しているので変えていない。AGENTS.md 1.3 の 2026-08-04 が古い |
| Turnstile の action・cdata | 設計に要求が無いので照合していない。設計書 §13-37 に要確認として記録した |

利用者の決定（2026-11-07）: かんたん登録のログアウトは注意書きで対応する（設計書 §13-36）。

メール欄の扱いで認証を有効にした環境だけ従来と変わる点: `#cap sage` のようにパスワードの後ろに空白で続けた語は、キャップの照合と同じくパスワードの一部として除くので、sage として効かない（以前の除去では ` sage` が残っていた）。`#a#b` の 2 つ目の `#` 以降は残す。`!#` の終わりは文字の空白判定（Char.isWhitespace）で、キーの切り出しと揃えた。認証を有効にしていない環境は変えていない。

## 段 E-1（2026-11-07、日本時間）

対象は ID・パスワードの登録・ログイン・ロックと Google でのログイン・登録。範囲と残件は [引き継ぎ](claude-handoff.md) の「E-1の実装範囲」を参照する。

| 観点 | 実装・確認 |
| --- | --- |
| DO はインフラのみ | Account DO はロック・読込・PBKDF2 の呼出し・保存だけを持つ。照合前のロック確認、失敗計数への反映、利用開始、パスワード変更、Google の照合は `AccountState` の純粋関数。照合の結果は enum（`PasswordAttempt`・`SignInResult`・`LoginOutcome`・`GoogleOutcome`）で返し、文言は handler で組み立てる |
| 列挙の防止 | 未登録・不一致・ロック中は同じ 401 と文言。未登録は固定の `Account("dummy")` に同じ RPC を送り、PBKDF2 を 1 回計算する（AccountPasswordSuite で計算回数を数えた）。ログイン ID の書式が不正なときも dummy で照合する。パスワードの書式（長さ等）が不正なときは計算せずに失敗を返すが、これは入力した本人が分かることでアカウントの有無は漏れない |
| 遅延の偏り | 失敗の遅延は Account DO ではなく、存在する ID でも無い ID でも同じ `AuthRate` DO に数える接続元ごとの失敗数（`loginFail`、直近 1 時間）から決める（5 回目から 1 秒ずつ、上限 8 秒）。ID の有無で遅延は変わらない。Account DO の計数（接続元ごとの 10 回ロックとアカウント全体の 100 回ロック）は応答の遅延に使わず、ロック中も失敗と同じ応答にする |
| ロックの回避 | 接続元ごとに失敗数と窓の開始時刻を持ち、他の接続元の失敗が挟まっても数え損なわない。保持は最大 100 接続元で、捨てるのは期限切れだけ。上限まで埋まったときの新しい接続元はロック扱いにする。接続元を回す総当たりは、アカウント全体の失敗 100 回で全接続元を 1 時間ロックして止める（本人も 1 時間ログインできなくなる。パスワード再設定は E-2）。永続形式は `throttle: {sources: {keyIpHash: {count, since, lockedUntil?}}, account: {count, since, lockedUntil?}}` で、本 PR で導入した形式なので移行は不要 |
| OAuth state Cookie | `__Host-ch3s_oauth`（Path=/・Secure・HttpOnly・SameSite=Lax・Domain なし）にして、兄弟サブドメインから攻撃者の署名済み Cookie を植えられないようにした。同意は規約の版を state に署名して保存し、callback で現在の版と一致しなければ同意のやり直しにする |
| 専ブラのキーに結び付いたセッション | `!#` キー由来（binding あり）のセッションは投稿とログアウトにだけ使う。キーの発行・失効・同意の更新・アカウント画面は未ログイン扱い |
| 登録途中の復旧 | 予約が期限切れでも、他の所有者に取られていなければ同じ aid は uid・索引を確定できる（`resume`）。端末申告の無いかんたん登録は毎回別の端末として数える |
| dummy の直列化 | 未登録 ID の照合は 1 つの DO に集まり、PBKDF2 を直列に計算する。大量の試行で未登録 ID の応答が遅れうるが、AuthRate の上限の内側に収まる |
| 登録の確定順 | login → uid → activate の順にし、uid の確定の応答喪失は所有者の確認で続ける。activate の前で止まった登録は本人確認後に再開する（実装メモ §7）。10 分の予約期限は変えていない |
| 入力の順 | 入力規則は Turnstile の前に確かめる。設計 §3.9 の手順 2 は Turnstile → 突合 → 乱造検知 → 入力規則の順だが、入力の誤りで Turnstile のトークンを使い切らないよう前に出した。使えない ID の判定（R2 を読む）と計数は Turnstile の後 |
| メール | 登録画面のメール欄は E-2 まで出さない。検証しないメールを保存すると、メールの一意性（§13-19）と L3 開始の扱いが決まらないため |
| Google | redirect_uri は末尾スラッシュ付き（ルートに合わせる）。JWKS は `caches.default` ではなく isolate のメモリに `max-age` の間保持する。`error=access_denied` からの戻り先は、ログインとして始めたときだけ `mode=login` にする。開始への AuthRate とエッジ防御は設計に無い・E-2 なので付けていない |
| 移行 | クライアント ID からの移行（§3.1・§3.9・§3.10 の `link`）は §12 で E-2 なので実装していない。ログイン中に登録すると別のアカウントになる |
| UI | 既存の認証画面の部品と配色のまま、タブ・方式の選択・入力欄を足した。角丸なし。見出しはモックどおり「認証・登録」。Turnstile のウィジェットはタブを替えても同じものを使う。Google の開始はフォームの遷移で送り、埋め込み時は別のウィンドウで開く（ポップアップの小さな通知スクリプトだけは callback の HTML に置く） |
| Scala・Hooks | AuthFormIsland は関数コンポーネントのまま、状態を useState、ウィジェットを useRef で持つ。業務処理は TS に置いていない（`app/server.ts` は callback のルートを足しただけ） |
| テスト | common に PasswordLoginSuite・GoogleIdTokenSuite・OAuthStateSuite、LoginThrottleSuite に 1 件。bbs に AccountPasswordSuite・AccountRegistrationSuite・GoogleIdTokensSuite・GoogleHandlerSuite と AuthHandlerSuite の 5 件、BoardIndexSuite に 1 件。board-islands に 2 件、実 workerd の統合テストに 1 件。既存のスナップショット・PostingSuite・golden は変えていない |

## 段 C（2026-11-07、日本時間）

対象は PHOEBE の Lv 昇格・`BBS_PHOEBE`・`!バルサン` / `!SETTING:BBS_PHOEBE:n`・識別子の `phoebe` 書式。範囲と残件は [引き継ぎ](claude-handoff.md) の「Cの実装範囲」を参照する。

| 観点 | 実装・確認 |
| --- | --- |
| DO はインフラのみ | 昇格の条件は common の `Phoebe` と `AccountLevel.afterPost`、Lv の固定は `AccountState.setLevelOverride`。Thread DO はロック内でスレ設定の値を読み、`ThreadPhoebeRequest.next` の結果を dat と同じ put で書くだけ。保存形式の一項目の差し替えは `durable/codec/ThreadSettingsCodec` |
| 種別 | コマンドは enum `PhoebeCommand`、識別子の軸は enum `IdentifierAxis`、書式は `ShikibetsuMode.Phoebe`。拒否は既存の `PostingError.AuthenticationLevel` と `phoebe:command:denied`（文言なしで通常投稿にするので型は足していない） |
| 正規表現の置き場所 | UA 解析表は `js.RegExp` で照合するので `bbs/domain/posting/UserAgentTable`。表の JSON の読み取りは repository。表が無い・読めないときは空の表にして投稿を止めない（規制ファイルと違い、識別子の一部が `000` になるだけなので fail-open） |
| 既存の振る舞い | 認証を有効にしていない環境ではコマンドを解釈せず、`BBS_PHOEBE` も見ない。`BBS_ID_SHIKIBETSU` が `phoebe` 以外の板の識別子・dat・log は変えていない。既存の投稿スナップショット・PostingSuite・golden は変更なし。`BBS_ID_LV` は読まないまま |
| 変えたもの | `phoebe` は以前は知らない値として識別子を出さなかった（Hidden）。今は新しい書式を出す。スレ立て時のスレ設定に `BBS_PHOEBE` を書き写さない（§14-8 の新キー）。Thread.addRes の RPC 引数に `commands` が増え、外形のテストを直した。Lv が変わった投稿では Cookie を発行し直す（期限の延長だけでなく） |
| 設計からの差分 | v3 の UA 解析表は存在しなかったので移植せず、R2 の表を読む形にした（§13-38）。`ownerAid` は足さず `owner.account` で照合した（§8.7 の注記どおり）。`!バルサン` は staff でも下げない。未ログインの専ブラの Lv 不足は `login` のまま（§13-39）。staff は差し込み口だけで、段 D まで誰も免除しない |
| UI | 新 UI の Lv 不足は応答の `x-phoebe-required` を表示に使う（スレ設定の値は板トップの props に無いため）。既存の文言と部品のまま。モーダルを開かず再送しないことを board-islands で確かめた |
| テスト | common に PhoebeSuite・PhoebeCommandSuite・IdentifierSuite、ShikibetsuSuite の phoebe。bbs に UserAgentTableSuite・UserAgentTableRepositorySuite、PostingServiceSuite（ゲート 5 件・コマンド 3 件・識別子 3 件）、ThreadSuite 2 件、PostingCodecSuite 1 件、AccountInfrastructureSuite・PostingAccountServiceSuite・ThreadOwnerSuite 各 1 件。board-islands に 1 件 |
