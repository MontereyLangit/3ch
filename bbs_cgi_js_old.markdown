# 新規投稿、スレッド作成
Route: /test/bbs.cgi, /test/subbbs.cgi
C: index.js
S: bbs_service.js
V: bbs_cgi.template.js
M: bbs_cgi_view_model.js

## auth(request, env, ctx, HAP)
ユーザー認証 `/api/auth/` のエントリーポイントです。

1.  **リクエスト検証**:
    *   `POST` メソッドであるか。
    *   `Content-Type` が `application/x-www-form-urlencoded` を含んでいるか。
    *   `Referer` が `www.3chbbs.com` からであるか。
2.  **フォームデータ取得**:
    *   `cf-turnstile-response`, `HAP`, `validate`, `d` (deviceInformation), `cert`, `time`, `authSig`, `token` などを取得します。
3.  **Turnstile検証**:
    *   Cloudflare Turnstileのトークンを検証し、ボットでないことを確認します。
4.  **必須パラメータとヘッダーの検証**:
    *   `HAP`, `validate`, `deviceInformation`, `cert` が存在するか。
    *   `x-validate`, `x-cert`, `x-authsig`, `x-api-key` といったカスタムヘッダーが正しい値を持っているか検証します。
5.  **`cert`の検証**:
    *   受信したフォームデータから`cert`を再生成し、送信されてきた`cert`と一致するか検証します。
6.  **`authSig`の検証**:
    *   APIキー、IPアドレス、HAPなどから期待される`authSig`を生成し、送信されてきた値と一致するか検証します。
7.  **ユーザーデータ取得と検証**:
    *   `HAP` (忍者ID) をキーにして `USER` (KV) からユーザーデータを取得します。
    *   ユーザーデータが存在しない場合はエラーを返します。
    *   ユーザーデータの状態が `AUTH2` であること、有効期限内（5分以内）であること、`validate`コードが一致すること、回線・地域情報が一致することなどを検証します。
8.  **認証成功処理**:
    *   検証がすべて成功した場合、`USER` (KV) に新しいデバイス情報、UserAgentハッシュ、IPアドレス、現在時刻を保存します。
    *   「認証成功」のテキストを返します。

## generateCertFromFormData(formData)
`FormData`オブジェクトから`cert`（証明書）文字列を生成します。

1.  フォームデータの全エントリをキーでソートします。
2.  `key=value` の形式で改行区切りで結合した文字列を作成します。
3.  結合した文字列をSHA-256でハッシュ化します。
4.  ハッシュ値を16進数文字列に変換して返します。

## bbs_entry(request, env, ctx)
掲示板への書き込み処理 `/test/bbs.cgi` のメイン関数です。一連の書き込み処理を順次呼び出します。

1.  **グローバルオブジェクト `gb` の初期化**:
    *   バージョン情報、時刻、フォームのデフォルト値、リクエスト情報（IP、UAなど）、各種設定値などを格納する `gb` オブジェクトを準備します。
2.  **処理の実行**:
    *   `try...catch...finally` ブロック内で、以下の関数を順番に呼び出します。
    *   各関数は、処理を中断してエラー画面を表示する場合は `0` を、処理を続行する場合は `1` を返します。
        1.  `checkRequest`: リクエストの基本的な正当性をチェックします。
        2.  `readForm`: POSTされたフォームデータを読み込み、`gb.form` に格納します。
        3.  `processCap`: キャップ（特権ユーザー）に関する処理を行います。
        4.  `processTrip`: トリップ（#以降の文字列）を生成します。
        5.  `SetPath`: R2オブジェクトストレージ用のパスを設定します。
        6.  `checkItabetsuSpecial`: 板固有の書き込み制限をチェックします。
        7.  `getBoardSettings`: 板の設定ファイル `SETTING.TXT` を読み込みます。
        8.  `getThreadDataAndThreadSettings`: 書き込み対象スレッドのデータを取得し、スレッド固有の設定を読み込みます。
        9.  `processIdentification`: ユーザー識別（忍法帖システム）に関する処理を行います。
        10. `checkDenyList`: アクセス規制リスト（まだかな）と照合し、書き込みを制限します。
        11. `checkFieldSize`: 名前、本文などの文字数・行数制限をチェックします。
        12. `makeIdAndShikibetuId`: IDと識別IDを生成します。
        13. `getSubject`: スレッドのタイトルを取得します。
        14. `processCommands`: `!SETTING` などのコマンドを処理します。
        15. `processByBySaru`: スレ内連投規制などの処理を行います。
        16. `checkRegulationOfCreatingThread`: スレッド立てすぎなどの規制をチェックします。
        17. `updateSubject`: スレッド一覧ファイル `subject.txt` を更新します。
        18. `updateThreadFile`: スレッドのログファイルを更新します。
        19. `updateSubback`: 過去ログ倉庫のインデックスを更新します。
        20. `updateHeadline`: 全体ヘッドラインを更新します。
3.  **最終的なレスポンス生成**:
    *   `finally` ブロックで、`gb.error` の状態に応じて以下のいずれかのHTMLを生成して返します。
        *   エラーがない場合: 書き込み完了画面 (`respondEndHtml`)
        *   `gb.error` が "ConfirmationPhase" の場合: 書き込み確認画面 (`respondConfirmationPhase`)
        *   上記以外のエラーがある場合: エラー画面 (`respondError`)

## checkRequest(gb, request)
リクエストの初期検証を行います。

1.  `POST` メソッドであるか、`Content-Type` が正しいか、`Referer` が正当なドメインかをチェックします。
2.  User-Agentの長さと文字種を検証します。
3.  User Agent Client Hints (`sec-ch-ua-*`) を取得し、User-Agent文字列やデバイス情報に追加します。
4.  2ch専用ブラウザ `Monazilla/1.00` かどうかを判定します。
5.  IPv6アドレスかどうかを判定し、ID生成の元になる文字列 (`idnotane`) を設定します。
6.  簡易的なプロキシチェックを行います。

## getPlatformByUserAgent(userAgent) / getAndroidModelByUserAgent(userAgent)
User-Agent文字列からOSのプラットフォーム名やAndroidのモデル名を取得するヘルパー関数です。

## readForm(gb, request)
POSTされたフォームデータを読み込み、解析して `gb.form` オブジェクトに格納します。

1.  リクエストボディをバイト配列として取得します。
2.  `submit` ボタンの値から、文字コードが `Shift_JIS` か `UTF-8` かを判定します (`gb.isSJIS`)。
3.  ボディを `&` で分割し、各キーと値のペアを処理します。
4.  `gb.isSJIS` の値に応じて、URLデコードと文字コード変換を行います。
5.  HTMLエンティティ化、改行コードの `<br>` への変換、制御文字の削除などのサニタイズ処理を行います。
6.  偽キャップ(`★`→`☆`)や偽トリップ(`◆`→`◇`)の変換を行います。
7.  名前欄からトリップキー (`#`以降) を分離します。
8.  `!newthread` コマンドを検出し、スレッドタイトルと本文を分離します。
9.  各フォームフィールドの不要な改行を削除し、型を検証します。
10. 必須項目（本文、スレタイorキー、time）の存在と形式をチェックします。
11. `time` の値が現在時刻から大きくずれていないかチェックします。
12. スレッド作成時は、`key` に現在時刻のUnixタイムスタンプを設定します。

## decodeSjisURIComponent(encodedURIComponent)
Shift_JISでURLエンコードされた文字列をデコードします。

## processTrip(gb, request, env)
トリップキーからトリップを生成します。

1.  トリップキーをShift_JISとしてバイト配列に変換し、再度文字列として解釈します。
2.  キーの長さや形式（10桁、12桁、#付きなど）に応じて、異なるアルゴリズム（`crypt` または `sha1Base64`）でトリップ文字列を生成します。
3.  生成したトリップを名前欄 (`gb.form.FROM`) に追加します。

## SetPath(gb)
R2オブジェクトストレージにアクセスするためのパスを `gb` オブジェクトに設定します。

## checkItabetsuSpecial(gb, request, env)
板固有の特殊な書き込み制限（例: `madakana` 板や `admin` 板はキャップのみ書き込み可）をチェックします。

## getBoardSettings(gb, request, env)
板の設定ファイル `SETTING.TXT` を読み込み、`gb.boardSettings` に格納します。スレッド作成時のみ実行されます。

## getThreadDataAndThreadSettings(gb, request, env)
書き込み対象のスレッドのデータを取得し、スレッド固有の設定を読み込みます。

1.  **スレッド作成時**:
    *   対象のログファイルが既に存在する場合はエラーとします。
    *   `gb.logData` にスレッドタイトルを初期値として設定します。
2.  **レス書き込み時**:
    *   R2からログファイルを取得します。存在しない場合はエラーとします。
    *   ログの1行目からスレッドタイトル、2行目からスレ設定を読み込み、`gb.settings` に格納します。
    *   レス数が300を超えている場合はエラー、ちょうど300の場合は警告メッセージを追加します。
    *   スレッドが停止されている (`BBS_HEISA`) 場合はエラーとします。

## processIdentification(gb, request, env)
ユーザー識別システム（忍法帖）に関する処理です。Cookieの取得、認証、各種制限判定などを行います。

1.  Cookie (`PON`, `HAP`, `time`, `PIN`) を取得します。
2.  `PON` Cookie（IPハッシュ）を生成し、レスポンスヘッダーにセットします。
3.  忍法帖システムが無効な板では、`PON` Cookieの有無のみチェックして処理を終了します。
4.  Cookieの正当性を `PIN` を使って検証します。
5.  `HAP` (忍者ID) をキーに `USER` (KV/Durable Object) からユーザーデータを取得します。
6.  **ユーザーデータが存在する場合**:
    *   認証状態をチェックします。`AUTH` 状態なら認証ページへ誘導します。
    *   User-Agentが登録時と変わっている場合はエラーとします。
7.  **ユーザーデータが存在しない場合**:
    *   新しい `HAP` と認証コードを生成し、`USER` に `AUTH` 状態で保存します。
    *   認証ページへ誘導するエラーメッセージを表示します。

## checkDenyList(gb, env)
アクセス規制リスト `madakana` と照合し、書き込みを制限します。

1.  キャップユーザーはチェックをスキップします。
2.  R2から `madakana` ファイルを取得します。
3.  ファイルの内容を1行ずつ読み込み、正規表現で板、規制対象ユーザー情報、NGワードを照合します。
4.  マッチした場合、規制内容（`TateDame`, `mark`, 通常の書き込み禁止）に応じて処理を分岐します。
    *   `TateDame`: スレッド作成時のみエラーとします。
    *   `mark`: 拒否られマークを付けて書き込みを許可します。
    *   その他: エラーを返して書き込みを禁止します。

## checkFieldSize(gb)
名前、メールアドレス、本文、スレッドタイトルの文字数や本文の行数制限をチェックします。板設定 (`BBS_*_COUNT`など) に基づいて判定します。

## makeIdAndShikibetuId(gb)
IDと識別IDを生成します。

1.  **ID生成**:
    *   板設定 (`BBS_ID`) に応じて、投稿時IPまたは初回IPからハッシュ値を計算し、`gb.id` に `ID:xxxxxx` の形式で格納します。
2.  **識別ID生成**:
    *   板設定 (`BBS_ID_SHIKIBETSU`) に応じて、国、地域、AS番号、デバイス情報などからハッシュ値を計算し、`gb.shikibetsuId` に `JP.vE.J2 xH-7ca00d39db6fd3e6` のような形式で格納します。

## getSha1Base64Id(...)
`makeIdAndShikibetuId` で使われるヘルパー関数で、与えられた情報からID用のハッシュ文字列を生成します。

## processCommands(gb, request, env)
ユーザーコマンドや管理コマンドを処理します。

*   `!SETTING:NAME=VALUE`: スレ主またはキャップがスレッド設定を変更できます。
*   `!LOG:bbs:key:n`: 管理者が指定したレスの生ログを取得できます。

## processByBySaru(gb, request, env)
スレッド内の連投規制（バイバイさるさん）や、壊れたレスの修復などを行います。（このファイルではまだ実装されていません）

## checkRegulationOfCreatingThread(gb, env)
スレッド作成時の規制をチェックします。

1.  スレッド作成時でなければ処理をスキップします。
2.  D1データベースを検索し、同じキーや同じタイトルのスレッドが既に存在しないかチェックします。
3.  板設定 (`BBS_THREAD_TATESUGI`) に基づき、短期間にスレッドを立てすぎていないかチェックします。

## updateSubject(gb, env)
スレッド一覧 (`subject` テーブル) を更新します。

1.  `sage` 判定を行います（メール欄に "sage"、古いスレ、実況板など）。
2.  `sage` の場合は更新せずに処理を終了します。
3.  D1データベースの `subject` テーブルに、スレッド情報（新規作成またはレス数・最終更新日時の更新）を書き込みます。
4.  板設定 (`BBS_MAX_MENU_THREAD`) に基づき、古いスレッドの情報を削除します。

## updateThreadFile(gb, env)
スレッドのログファイル（datファイル）を更新します。

1.  新しいレスのデータを `gb.logData` に追記します。
2.  R2に書き込みます。この際、`onlyIf: { etagMatches: gb.logEtag }` オプションを使い、読み込み後に他の書き込みがされていないか（競合していないか）をチェックします。
3.  競合した場合は、リトライ処理を行いますが、最終的に失敗した場合はエラーとします。

## updateSubback(gb, env)
スレッド作成時に、過去ログ倉庫のインデックスファイル (`thread/..../subject.txt` と `thread/subject.txt`) を更新します。

## updateHeadline(gb, env)
サイト全体のヘッドラインファイル (`headline.txt`) を更新します。`sage` でない書き込みがあった場合に実行されます。

## dispError(gb, errMsg)
エラーメッセージを `gb.error` に設定し、処理中断を示す `0` を返すヘルパー関数です。

## respondEndHtml(gb) / respondConfirmationPhase(gb) / respondError(gb)
最終的なHTMLレスポンスを生成する関数群です。`gb.isSJIS` の値に応じて `Shift_JIS` または `UTF-8` のHTMLを返します。
*   `respondEndHtml`: 書き込み完了画面
*   `respondConfirmationPhase`: 書き込み確認画面（Cookieがない場合など）
*   `respondError`: エラー画面
