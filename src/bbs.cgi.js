////@ts-check
import { sjis, hexToBinary, sha1Base64, hash, md5, crypt, translateSaltChar } from "./utils/crypto_utils.js";
import { decodeSJIS } from './utils/common_utils.js';

// 認証用APIKey
export const apiKey = "6cde9d4cef741d82da3de7d179fe015bb0da538f334e3e98664f1277c23d8bd3";

/* デフォルト板設定 */
//古いので要修正
export const settingTxtDefault = `BBS_TITLE=<DIR_NAME>＠3ch掲示板
BBS_NONAME_NAME=名無しさん＠３ちゃんねる
BBS_MAX_MENU_THREAD=300
BBS_FORCE_SAGE=
BBS_NINJA2=checked
BBS_ID=checked
BBS_ID_GROUP=
BBS_ID_RESET=86400
BBS_ID_LV=
BBS_ID_SHIKIBETSU=checked
BBS_SUBJECT_COUNT=64
BBS_NAME_COUNT=64
BBS_MAIL_COUNT=64
BBS_MESSAGE_COUNT=4096
BBS_LINE_NUMBER=30
BBS_THREAD_TATESUGI=3/86400
BBS_CREATETHREAD_INTERVAL=5
BBS_COPIPE=3/86400
timecount=18
timeclose=10
timecover=3600
BBS_COOK82=10
BBS_COOK82_POST_LIMIT=60
BBS_LIVE=
BBS_KISEIOFF=
BBS_NO_MADAKANA=
BBS_THREAD_SETTINGS=on
BBS_DISABLE_NUSI=
BBS_FORCE_NONAME=
BBS_ARR=
BBS_HEISA=
`;

/* 投稿確認の文章 */
export const ConfirmationText = `
・投稿者は、投稿に関して発生する責任が全て投稿者に帰すことを承諾します。<br>
・投稿者は、話題と無関係な広告の投稿に関して、相応の費用を支払うことを承諾します<br>
・<font color="red">＊</font>投稿者は、投稿された内容及びこれに含まれる知的財産権、（著作権法第21条ないし第28条に規定される権利も含む）
その他の権利につき（第三者に対して再許諾する権利を含みます。）、掲示板運営者に対し、無償で譲渡することを
承諾します。ただし、投稿が別に定める削除ガイドラインに該当する場合、投稿に関する知的財産権その他の権利、
義務は一定期間投稿者に留保されます。<br>
・掲示板運営者は、投稿者に対して日本国内外において無償で非独占的に複製、公衆送信、
頒布及び翻訳する権利を投稿者に許諾します。また、投稿者は掲示板運営者が指定する第三者に対して、一切の権利（第三者に対して再許諾する権利を含みます）を許諾しないことを承諾します。<br>
・投稿者は、掲示板運営者あるいはその指定する者に対して、著作者人格権を一切行使しないことを承諾します。<br>
<font color="red">
・投稿者および掲示板運営者は、上記の承諾事項に加えて、各掲示板（板）のローカルルール（head.txt）に掲載された規定を、あわせて承諾します。<br>
・投稿者および掲示板運営者は、上記のうち＊のついた承諾事項と、各掲示板のローカルルールにおける規定が相反する場合、各掲示板の規定が優先されることを承諾します。<br>
（例：ローカルルールに投稿者が著作権等を保持する規定のある掲示板では、＊の規定は適用されません）
</font>
<br>
`;

/* 
 * 認証
 * www.3chbbs.com/api/auth/
 */
export async function auth(request, env, ctx, HAP) {
  if (request.method !== 'POST') return respondPlainText("不正な認証です（e1）");
  
  const contentType = request.headers.get("content-type") || "";
  if (!contentType.includes("application/x-www-form-urlencoded")) return respondPlainText("不正な認証です（e2）");

  const referer = request.headers.get('referer') || '';
  if (!/^https?:\/\/www\.3chbbs\.com/.test(referer)) return respondPlainText("不正な認証です（e3）");

  // HAPとvalidateはPOSTのbodyに入っているので取り出す
  const formData = await request.formData();
  const turnstileToken = formData.get("cf-turnstile-response") || "";
  const HAP = formData.get("HAP") || "";
  const validate = formData.get("validate") || "";  //HAP検証用
  const deviceInformation = formData.get("d") || "";
  const cert = formData.get("cert") || "";  // HAP,validate,dをハッシュ化した検証用の値
  const currentUnixTimeString = formData.get("time") || "";
  const embedString = formData.get("authSig") || "";
  const token = formData.get("token") || "";
  const asn = request.cf.asn ?? "0000";
  const country = request.cf.country ?? "unknown";
  const ken = request.cf.region ?? "catv?";
  const city = request.cf.city ?? "";
  let ip = request.headers.get("CF-Connecting-IP");
  let U;

  // turnstile検証
  let turnstileFormData = new FormData();
  turnstileFormData.append('secret', "0x4AAAAAABribbo2W_ILtAFcb7uaCehTDqM");
  turnstileFormData.append('response', turnstileToken);
  turnstileFormData.append('remoteip', ip);

  const url = 'https://challenges.cloudflare.com/turnstile/v0/siteverify';
  const result = await fetch(url, {
    body: turnstileFormData,
    method: 'POST',
  });

  const outcome = await result.json();

  if (!outcome.success) return respondPlainText("Turnstile認証に失敗しました: " + JSON.stringify(outcome));

  // どれか一つでも欠けていたら，e0
  if (!HAP || !validate || !deviceInformation || !cert) return respondPlainText("不正な認証です（e0）");

  if (!request.headers.get('x-validate') || request.headers.get('x-validate') !== validate) return respondPlainText("不正な認証です（e0-v）");
  if (!request.headers.get('x-cert') || request.headers.get('x-cert') !== cert) return respondPlainText("不正な認証です（e0-c1）");
  if (!request.headers.get('x-authsig') || request.headers.get('x-authsig') !== embedString) return respondPlainText("不正な認証です（e0-e1）");
  if (!request.headers.get('x-api-key') || request.headers.get('x-api-key') !== apiKey) return respondPlainText("不正な認証です（e0-a）");

  // certの検証
  const expectedCert = await generateCertFromFormData(formData);
  if (expectedCert !== cert) return respondPlainText("不正な認証です（e0-c2）");

  // 埋め込み文字列(authSig)の検証
  const expectedEmbed = await sha1Base64(md5(`${apiKey}.${ip}.${HAP}.${token}.${md5(env.salt)}`));
  if (expectedEmbed !== embedString) return respondPlainText("不正な認証です（e0-e2）");

  /* IPv6接続かどうかをチェックする */
  if (ip.includes(":")) {
    const octet = ip.split(":");
    ip = `${octet[0]}:${octet[1]}:${octet[2]}:${octet[3]}`;
  }

  const ninja = await env.NINJA.get(HAP);

  if (ninja) {
    // AUTH<>${validate}<>${gb.unixtime}<>${gb.asn}<>${gb.country}<>${gb.ken}<>${gb.city}<>${gb.userAgent}
    const userData = ninja.split("<>");

    // 一旦auth.js読み込み時にAUTH=>AUTH2へ更新されるためAUTHではダメ
    if (userData[0] !== "AUTH2" || !userData[7]) return respondPlainText("不正な認証です（e4）");

    // 5分以上経過で時間切れ
    if (parseInt(userData[2]) > 300) return respondPlainText("認証が時間切れです。（5分以上経過）");

    U = userData[7]; // UはUserAgentのハッシュ値

    if (validate !== userData[1]) return respondPlainText("認証コードが一致しません");
    if (userData[2] !== currentUnixTimeString) return respondPlainText("不正な認証です（e5）");
    if (asn !== userData[3] || country !== userData[4] || ken !== userData[5] || city !== userData[6]) return respondPlainText("認証に失敗しました（回線・地域情報が一致しません）");
  } else {
    return respondPlainText("認証が早すぎます（忍者IDを取得できませんでした）");
  }

  // 認証成功
  // deviceInformation<>U<>ip<>currentTime
  await env.NINJA.put(HAP, `${deviceInformation}<>${U}<>${ip}<>${Math.floor(new Date().getTime() / 1000).toString()}`);
  respondPlainText("認証成功");
}

async function generateCertFromFormData(formData) {
  // FormData → [key, value]配列に変換してソート
  const entries = Array.from(formData.entries()).sort(([a], [b]) =>
    a.localeCompare(b)
  );

  // key=value の形で結合（改行区切り）
  const concatenated = entries
    .map(([key, value]) => key + "=" + value)
    .join("\n");

  // SHA-256計算
  const encoder = new TextEncoder();
  const data = encoder.encode(concatenated);
  const hashBuffer = await crypto.subtle.digest("SHA-256", data);

  // バイト列 → hex文字列
  return Array.from(new Uint8Array(hashBuffer))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

/*
 * 書き込み
 * www.3chbbs.com/test/bbs.cgi
 * 各ルーチンで、return dispError("エラー"); => ERROR: エラー を出して終了
 *              return 0; => 書き込みました を出して終了
 *              return 1; => 続行、次のルーチンへ進む
 */
export async function bbs_entry(request, env, ctx) {
  let gb = {};
  gb.version = "2025/7/31";
  gb.error = "";
  gb.time = new Date().getTime().toString();
  gb.unixtime = Math.floor(new Date().getTime() / 1000).toString(); /* 現在時刻をGBに得る */
  gb.form = {FROM:"", mail:"", MESSAGE:"", subject:"", bbs:"", key:"", time:"", submit:""};
  /* HAP=忍者ID，D=端末固有情報，U=UAハッシュ値，PON=IP，time=timestamp，PIN=乱数，cert=HAP+D+U+PON+time+PIN+salt=>ハッシュ値 */
  gb.cookies = {HAP:"", PON:"", time:"", PIN:""};
  gb.host = request.headers.get("CF-Connecting-IP");
  gb.ip = request.headers.get("CF-Connecting-IP");
  gb.userAgent = request.headers.get("user-agent") ?? "";
  gb.browserInfo = "";
  gb.deviceInfo = ""; /* 端末固有情報->412,915,2,24,2,Android,Pixel 7 */
  gb.asn = request.cf.asn ?? "0000";
  gb.country = request.cf.country ?? "unknown";
  gb.ken = request.cf.region ?? "catv?";
  gb.city = request.cf.city ?? "";
  gb.deviceId = ""; /* 端末ID->16桁の端末固有情報ハッシュ値 */
  gb.userData = {D:"", U:gb.userAgent, firstIp:gb.ip, created:gb.unixtime}; /* ユーザ(忍者2.0)の情報 */
  gb.userInfo = "";/* ログに格納されるユーザ情報(規制にも使用)->識別子,IP,AS番号,地域,端末固有情報,UA,Sec-Ch-UA */
  gb.shikibetsuId = "";/* 識別ID->"country,ken asn-deviceId" ex:9605 JP,Tokyo-3a4cb8742c4294b0 */
  gb.id = "";/* ID->Vp9H8a2bL0 */
  gb.isIPv6 = 0;
  gb.isPC = 0;
  gb.isMonazilla = 0;
  gb.isSJIS = 0;
  gb.idnotane = ""; /* ID生成の種 */
  gb.md5Salt = md5(env.salt);
  gb.tripKey = "";
  gb.tripString = "";
  gb.cap = 0;/* キャップかどうか */
  gb.strongCap = 0;/* 強キャップかどうか */
  gb.sage = 0;
  gb.path = "PATH";
  gb.logPath = "LOGPATH";
  gb.subject = "";
  gb.number = 0;
  gb.logFile = "LOGDAT";
  gb.logData = "";/* 現行のスレッドログファイルの内容がここに入る. 規制などに使用 */
  gb.logEtag = undefined;
  gb.outdat = "";
  gb.settings = {};/* スレ設定データ */
  gb.boardSettings = {};/* 板設定データ */
  gb.origSettings = {};
  gb.headers = {};

  try {
    /* 最初のチェック */
    if (!checkRequest(gb, request)) return;

    /* Formの読み込み */
    if (!await readForm(gb, request)) return;

    /* キャップ処理 */
    await processCap(gb, request, env);
    
    /* トリップ処理 */
    await processTrip(gb, request, env);

    /* R2用のpathをセット */
    SetPath(gb);

    /* 板ごとの制限 */
    if (!checkItabetsuSpecial(gb, request, env)) return;

    /* SETTING.TXT（板設定）読み込み */
    if (!await getBoardSettings(gb, request, env)) return;
    
    /* 書き込み先スレッドのデータ取得＆スレ設定読み込み */
    if (!await getThreadDataAndThreadSettings(gb, request, env)) return;

    /*
     * ユーザー識別処理（識別子システム）
     * Cookieと端末固有情報（デバイスフィンガープリント）の取得、認証、Cookie処理、Googleアカウント登録、識別子由来のBAN規制や各種制限判定処理
     */
    if (!await processIdentification(gb, request, env)) return;
    
    // ログに格納されるユーザ情報(規制にも使用)
    gb.userInfo = "HAP:" + gb.cookies.HAP + "FIP:" + gb.userData.firstIp + " IP:" + gb.host + " AS" + gb.asn + '-' + gb.country + ',' + gb.ken + ',' + gb.city + " Device:" + gb.deviceInfo + " UA:" + gb.userAgent;
    gb.userInfo += " client-port:" + request.headers.get("x-client-port") + " server-ip:" + request.headers.get("x-server-ip") + " server-port:" + request.headers.get("x-server-port");

    /* まだかな（アクセス規制） */
    if (!await checkDenyList(gb, env)) return;

    /* フィールドサイズチェック */
    if (!checkFieldSize(gb)) return;

    /* ID＆識別ID生成 */
    await makeIdAndShikibetuId(gb);

    /* subject取得 */
    if (!await getSubject(gb, env)) return;

    /* コマンド類（ユーザーコマンド、管理コマンド） */
    if (!await processCommands(gb, request, env)) return;

    /* >20以降のスレ内連投規制（バイバイさるさん）+スレッド修復の処理 */
    if (!await processByBySaru(gb, request, env)) return;

    /* 各種スレ立て規制 */
    if (!await checkRegulationOfCreatingThread(gb, env)) return;

    /* Subject更新 */
    if (!await updateSubject(gb, env)) return;

    /* スレッドファイル更新 */
    if (!await updateThreadFile(gb, env)) return;

    /* スレ立て時のthread/..../subject.txtへの追加＆thread/subject.txt更新 */
    if (!await updateSubback(gb, env)) return;

    /* 全体headline更新 */
    if (!await updateHeadline(gb, env)) return;

    /* メインルーチン終わり。 */
    return;
  }
  catch(e) {
    if (e.includes("(reading 'length')")) {
        gb.error = "非対応の文字コードが検出されています";
    } else {
        gb.error = `bbs.cgi System Error:${e}`;
    }
  }
  finally {
    /*
     * 書き込みました。 / 書き込み確認 / ＥＲＲＯＲ！
     * UTF-8(read.cgi経由など)かShift_JIS(大半の2ch専ブラ)かを各関数内で判定し(gb.isSJIS)、適切なHTMLを出力する
     * return の内容はここで上書きされる
     */
    if (gb.error === "") return respondEndHtml(gb);
    if (gb.error === "ConfirmationPhase") return respondConfirmationPhase(gb);
    else return respondError(gb);
  }
}

/*  
 * 一番はじめのチェック
 * UserAgent、CH-UAはここで取得される
 * 歴史的事情や最近の動向がいろいろあるので、更新時には注意すること
 */
function checkRequest(gb, request) {
  /* Cloudflare Rewirte Rulesで入ってこないようにしているけど、一応 */
  if (request.method !== 'POST') return dispError(gb, "POST メソッドを使ってください。<br>\nRecieve GET METHOD");

  const contentType = request.headers.get("content-type") || "";
  if (!contentType.includes("application/x-www-form-urlencoded")) return dispError(gb, "送信された内容が破損しています。->" + contentType + "<br>\nBroken request body. ");

  const referer = request.headers.get('referer') || '';
  if (!/^https?:\/\/www\.3chbbs\.com/.test(referer)) return dispError(gb, "ERROR: あなた騙されてますよ？->" + referer + "<br>\nReferer is invalid.");

  /* UserAgentは7byte~384byteの半角文字のみ可 */
  if (gb.userAgent.length < 7 || gb.userAgent.length > 384 || /[^ -~]/.test(gb.userAgent)) return dispError(gb, "ERROR: ブラウザ変ですよん。(ua)<br>UserAgent: " + gb.userAgent + "<br>\nUserAgent is invalid.");

  /* User Agent Client Hintsのブラウザ情報部分をUAに追加 */
  gb.userAgent += request.headers.get('sec-ch-ua-full-version-list') ?? request.headers.get('sec-ch-ua') ?? "";

  /* ヘッダなどのブラウザ情報などをbrowserInfoに追加 */
  gb.browserInfo += "Accept:" + (request.headers.get('accept') ?? "");
  gb.browserInfo += "Accept-Language:" + (request.headers.get('accept-language') ?? "");
  gb.browserInfo += "Accept-Encoding:" + (request.headers.get('accept-encoding') ?? "");

  /* 機種・OS情報をdeviceInfoに追加 */
  gb.deviceInfo += (request.headers.get('sec-ch-ua-platform') ?? getPlatformByUserAgent(gb.userAgent)) + "_";
  gb.deviceInfo += (request.headers.get('sec-ch-ua-model') ?? getAndroidModelByUserAgent(gb.userAgent)) + "_";

  /* 2ch専用ブラウザ(Monazilla/1.00)かどうかを判定 */
  if (/^Monazilla\/1.00/.test(gb.userAgent)) gb.isMonazilla = 1;

  /* IPv6接続かどうかをチェックする */
  if (gb.ip.includes(":")) {
    gb.isIPv6 = 1;
    const octet = gb.ip.split(":");
    gb.idnotane = `${octet[0]}:${octet[1]}:${octet[2]}:${octet[3]}`;
  }
  else gb.idnotane = gb.ip;

  /* 簡易Proxyチェック */
  if (request.headers.get("client-ip")) return dispError(gb, "変な情報が送られて来ました。<br>Client_IP: " + request.headers.get("client-ip"));
  
  gb.host = gb.ip;
  if (request.headers.get("x-forwarded-for") && gb.ip !== request.headers.get("x-forwarded-for"))
  {
    gb.host += `<${request.headers.get("x-forwarded-for")}>`;
  }else if (request.headers.get("forwarded") && gb.ip !== request.headers.get("forwarded"))
  {
    gb.host += `<${request.headers.get("forwarded")}>`;
  }else if (request.headers.get("via") && gb.ip !== request.headers.get("via"))
  {
    gb.host += `<${request.headers.get("via")}>`;
  }

  return 1;
}

function getPlatformByUserAgent(userAgent) {
  // iOS判定 -> Android判定 -> Windows判定 -> Mac判定 -> Linux判定 -> その他
  if (/iPad|iPhone|iPod/.test(userAgent)) {
    return "iOS";
  } else if (/Android/.test(userAgent)) {
    return "Android";
  } else if (/Windows/.test(userAgent)) {
    return "Windows";
  } else if (/Macintosh/.test(userAgent)) {
    return "Mac";
  } else if (/Linux/.test(userAgent)) {
    return "Linux";
  } else {
    return "Other";
  }
}

function getAndroidModelByUserAgent(userAgent) {
  // Androidでない場合はスルー
  if (!/Android/.test(userAgent)) return "";

  // Mozilla/5.0 (Linux; Android 11; Pixel 4a Build/RP1A.200720.011; wv) ...
  const match = userAgent.match(/\((?:Linux; )?Android [\d\.]+; ([^;]+?)\s+Build/i);

  if (match && match[1]) {
    return match[1].trim();
  }

  // 機種名が Build より前にない場合のフォールバック
  const altMatch = userAgent.match(/\((?:Linux; )?Android [^;]+; ([^;]+)\)/i);
  if (altMatch && altMatch[1]) {
    return altMatch[1].trim();
  }

  return "Unknown";
}

/* 初期情報を取得（ＰＯＳＴ） */
async function readForm(gb, request) {
  const bodyBytes = new Uint8Array(await request.arrayBuffer());

  /* Shift JIS -> UTF-8 -- for POST by Monazilla/1.00 */
  const bodyText = new TextDecoder("utf-8").decode(bodyBytes);

  if (/submit=([^&]+)/.test(bodyText)) {
    // UTF-8 での「書」のバイト列 => %E6%9B%B8
    if (bodyText.toLowerCase().includes("submit=%e6%9b%b8")) {
      gb.isSJIS = 0;
    }
    else {
      gb.isSJIS = 1;
      // Shift_JISはMonazilla/1.00のみ
      if (!gb.isMonazilla) {
        return dispError(gb, "書き込みボタンを押忍べし<br>2ch専用ブラウザからの書き込みでこのエラーが出た場合、お使いの専用ブラウザはMonazilla/1.00で始まるUserAgentを使用していません。<br>認証時の問題などもありますので、2ch専用ブラウザではMonazilla/1.00で始まるUserAgentを使用してください。<br>(Monazilla/1.00以外のUserAgentでsubmit=%E6%9B%B8…でない場合にこのエラーが出ます。)<br>\n&apos;Submit&apos; must require &apos;書&apos; with Web browser&apos;s post.");
      }
    }
  } else {
    return dispError(gb, "書き込みボタンを押忍べし<br>\nNo submit");
  }

  const pairs = bodyText.split("&");

  for (const pair of pairs) {
    if (!pair.includes("=")) continue; // キーと値のペアでない場合はスキップ

    const [key, v] = pair.split("=");

    if (!key) continue; // キーがない場合はスキップ

    let value = v || "";

    if (gb.isSJIS) {
      value = decodeSjisURIComponent(value.replace(/\+/g, " "));
    } else {
      value = decodeURIComponent(value.replace(/\+/g, " "));
    }

    value = value
      .replace(/&(?!#[a-zA-Z0-9]+;)/g, "&amp;")
      .replace(/"/g, "&quot;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/'/g, '&apos;')
      .replace(/\t/g, " ")
      .replace(/&#0*1[03];/g, "<br>")
      .replace(/&#[xX]0*[aAdD];/g, "<br>")
      .replace(/\r\n?|\n/g, "<br>")
      .replace(/[\x00-\x1F\x7F]/g, "");// "

    gb.form[key] = value;
  }

  /* 偽キャップ、偽トリップ変換 */
  gb.form.FROM = gb.form.FROM.replace(/★/g, "☆");
  gb.form.FROM = gb.form.FROM.replace(/◆/g, "◇");
  gb.form.FROM = gb.form.FROM.replace(/＃/g, "#");
  gb.form.mail = gb.form.mail.replace(/＃/g, "#");

  /* トリップ取り出し */
  if (/^([^#]*?)(?<!&#[xX]?[0-9a-fA-F]{1,8});?#(.+)$/.test(gb.form.FROM)) {
    const match = gb.form.FROM.match(/^([^#]*?)(?<!&#[xX]?[0-9a-fA-F]{1,8});?#(.+)$/);
    gb.form.FROM = match[1];
    gb.tripKey = match[2];
    if (gb.tripKey.length > 1023) return dispError(gb, "トリップキー長杉！<br>\nYour tripkey must be less than 1023 bytes.");
  }

  /* !newthread */
  if (/^!newthread([^\r\t\n\f\v<]+)(.*)$/.test(gb.form.MESSAGE)) {
    const match = gb.form.MESSAGE.match(/^!newthread([^\r\t\n\f\v<]+)(.*)$/);
    gb.form.subject = match[1];
    gb.form.MESSAGE = match[2];
    gb.form.key = "";
  }

  /* １行データからは改行を削ってタグを閉じます */
  gb.form.subject = gb.form.subject.replace(/ ?<br> ?/g, "");
  gb.form.FROM = gb.form.FROM.replace(/ ?<br> ?/g, "");
  gb.form.mail = gb.form.mail.replace(/ ?<br> ?/g, "");

  gb.form.bbs = gb.form.bbs.replace(/\W/g, "");
  gb.form.key = gb.form.key.replace(/\D/g, "");
  gb.form.time = gb.form.time.replace(/\D/g, "");

  gb.form.submit = gb.form.submit.replace(/ ?<br> ?/g, "");

  /* 何かが変 */
  if (gb.form.bbs === "" || gb.form.bbs === "test" || gb.form.bbs === "js" || gb.form.bbs === "css" || gb.form.bbs === "html" || gb.form.bbs === "images") return dispError(gb, "板の指定が不正です。<br>\nThe board directory is incorrect...");
  if (gb.form.MESSAGE === "") return dispError(gb, "本文がありません！");
  if ((gb.form.key === "" || !/^[0-9]+$/.test(gb.form.key)) && gb.form.subject === "") return dispError(gb, "subjectが存在しないかキー情報が不正です。key=" + gb.form.key + "<br>\nThe subject is empty and key is wrong.");
  if (gb.form.time === "" || !/^[0-9]+$/.test(gb.form.time)) return dispError(gb, "ブラウザ変ですよん。(time)<br>\ntime is empty. time=" + gb.form.time);
  // 専ブラ不具合につき1時間先まで許容する
  if (gb.form.time > gb.unixtime + 3600) return dispError(gb, "投稿時刻がおかしいです。時計が狂っていませんか？<br>送信されたtime値が現在時刻より1時間以上未来です。端末の時刻を確認して、ブラウザを立ち上げなおしてみてください。<br>2ch専用ブラウザの場合は、不具合の可能性がありますので、開発元に報告をお願いします。");
  if (gb.form.time < gb.unixtime - 3600) return dispError(gb, "投稿時刻が時間切れです。スレッドをリロード（再読み込み）してください。<br>現在時刻より1時間以上前のtime値は無効です。");

  if (gb.form.key !== "") gb.form.subject = "";

  /* スレ立て時は現在時刻を key に */
  if (gb.form.subject) gb.form.key = gb.unixtime;

  return 1;
}

/**
 * URLデコード関数（Shift_JIS用）
 * @param {string} encodedURIComponent
 * @return {string} デコード化された文字列
 */
function decodeSjisURIComponent(encodedURIComponent) {
  const bytes = [];
  for (let i = 0; i < encodedURIComponent.length; ) {
    if (encodedURIComponent[i] === '%') {
      const hex = encodedURIComponent.slice(i + 1, i + 3);
      if (/^[0-9a-fA-F]{2}$/.test(hex)) {
        bytes.push(parseInt(hex, 16));
        i += 3;
        continue;
      }
    }
    // %でなければASCII文字として1バイト追加
    bytes.push(encodedURIComponent.charCodeAt(i));
    i++;
  }
  return decodeSJIS(new Uint8Array(bytes));
}

/* キャップの処理 */

/*
 * トリップの処理
 * Shift_JIS-10桁&12桁対応暫定版 by 忍
 */
async function processTrip(gb, request, env) {
  if (gb.tripKey === "") return;

  // トリップキーをSJISとしてバイト配列に変換し、それを文字コードとして解釈し直す
  const handlePass = decodeSJIS(new TextEncoder().encode(gb.tripKey));

  if (handlePass.length >= 12) {
    const mark = handlePass.charAt(0);
    if (mark === '#' || mark === '$') {
      const match = handlePass.match(/^#([0-9a-fA-F]{16})([./0-9A-Za-z]{0,2})$/);
      if (match) {
        const hex = match[1];
        const salt = (match[2] || '..').padEnd(2, '.');
        const bin = hexToBinary(hex);
        gb.tripString = crypt(bin, salt).slice(-10);
      } else {
        gb.tripString = '???'; /* 将来の拡張用 */
      }
    } else {
      const tripBase64 = await sha1Base64(handlePass);
      gb.tripString = tripBase64.slice(0, 12).replace(/\+/g, '.');
    }
  } else {
    let salt = handlePass.slice(1, 2) + "H";
    salt = salt.replace(/[\x00-\x20\x7B-\xFF]/g, '.');
    /* strtrの代替。:;<=>?@[\]^_` を A B C D E F G a b c d e f に置換 */
    const fromChars = ":;<=>?@[\\]^_`";
    const toChars = "ABCDEFGabcdef";
    salt = salt.split('').map(c => {
      const idx = fromChars.indexOf(c);
      return idx >= 0 ? toChars.charAt(idx) : c;
    }).join('');
    gb.tripString = crypt(handlePass, salt).slice(-10);
  }
  gb.form.FROM += ` </b>◆${gb.tripString}</b>`;
}

function SetPath(gb) {
  gb.path = `${gb.form.bbs}/`;
  gb.logPath = `${gb.path}thread/${gb.form.key.slice(0,4)}/`;
  gb.logFile = `${gb.logPath}${gb.form.key}.cgi`;
}

function checkItabetsuSpecial(gb, request, env) {
  if (gb.form.bbs === "madakana") {
    if (!gb.cap) return dispError(gb, "アクセス規制情報板は一般書き込み禁止です。");
  }

  if (gb.form.bbs === "admin") {
    if (!gb.cap) return dispError(gb, "管理室板は一般書き込み禁止です。");
  }
}

/* 板設定取得 */
async function getBoardSettings(gb, request, env) {
  // スレ立て時以外はスルー
  if (!gb.form.subject) return 1;

  const filename = `${gb.form.bbs}/SETTING.TXT`;
  let settingTxt = settingTxtDefault;

  try {
    const object = await env.TEST.get(filename);

    if (object) {
      settingTxt = await object.text();
    }

    settingTxt = settingTxt.replace(/<DIR_NAME>/g, gb.form.bbs);

    /* name1=value1\nname2=value2 */
    const settingsArray = settingTxt.trim().split("\n");

    for (let i = 0; i < settingsArray.length; i++) {
      const setting = settingsArray[i].split("=");
      if (!setting[0]) continue;
      setting[1] ??= "";
      gb.settings[setting[0]] = setting[1].trim();
    }
    gb.boardSettings = gb.settings;

    return 1;
  } catch(e) {
    return dispError(gb, `getBoardSettings error. 不明なエラーが発生しました。(time:${gb.time} bbs:${gb.form.bbs} key:${gb.form.key} number:${gb.number} errmsg:getBoardSettings->${e})<br>このメッセージをコピペして、運用情報板で報告していただけるとありがたいです。`);
  }
}

/* 
 * スレッドデータ＆スレ設定取得
 * 300超え時の処理なども
 */
async function getThreadDataAndThreadSettings(gb, request, env) {
  try {
    const object = await env.ch3www.get(gb.logFile);

    if (gb.form.subject) {
      // スレ立て時の処理

      /* スレ立て時、書き込み先ファイルがすでにあったらお断りする。 */
      if (object !== null) return dispError(gb, "別の人が同時刻にスレッドを立てようとしています。ごめんなさい。");

      gb.logData = `${gb.form.subject}\n`;
      gb.logEtag = null;
      gb.number = 1;
      gb.subject = gb.form.subject;

      return 1;
    }
    else if (object === null) return dispError(gb, "該当するスレッドがありません。" + gb.logFile);

    // スレッドデータ取得
    gb.logData = await object.text();
    gb.logEtag = object.etag;
    const reses = gb.logData.split("\n");
    gb.number = reses.length - 2;
    if (gb.number < 1) return dispError(gb, "スレッドのログファイルが壊れています。運用情報板で修理してもらってください。");

    // スレッドタイトルを取得。ログの一行目に入っている。
    gb.subject = reses.shift()?.trim();

    // スレ設定を取得。ログの二行目に入っている。
    const settingText = reses.shift();
    // NAME=VALUE<>NAME2=VALUE2
    const settingsArray = settingText.trim().split("<>");

    for (let i = 0; i < settingsArray.length; i++) {
      const setting = settingsArray[i].split("=");
      if(!setting[0]) continue;
      setting[1] ??= "";
      gb.settings[setting[0]] = setting[1].trim();
    }

    gb.origSettings = gb.settings;

    // 300超え時の処理
    if (gb.number === 300) {
      gb.form.MESSAGE += "<hr>このスレッドは300を超えました。次スレッドに移行してくださいです。。。";
    }
    else if (gb.number > 300) return dispError(gb, "このスレッドは300を超えたのでもう書けません。");

    // 停止
    if (gb.settings.BBS_HEISA === "checked") return dispError(gb, "この板・スレッドは停止されています。");

    return 1;
  } catch(e) {
    return dispError(gb, `getThreadDataAndThreadSettings error. 不明なエラーが発生しました。(unixtime:${gb.unixtime} bbs:${gb.form.bbs} key:${gb.form.key} number:${gb.number} errmsg:getThreadDataAndThreadSettings->${e})<br>このメッセージをコピペして、運用情報板で報告していただけるとありがたいです。`);
  }
}

/* 
 * ユーザー識別処理（識別子システム） by 忍
 * Cookie関連の処理、忍法帖、デバイスフィンガープリンティング、認証、Googleアカウント登録を統合
 * Cookieと端末固有情報（デバイスフィンガープリント）の取得、認証、Cookie処理、Googleアカウント登録、識別子由来の規制判定処理
 */
async function processIdentification(gb, request, env) {
  // 識別子システム構築完了まで無効化 by 忍 2025/07/03
  gb.settings.BBS_NINJA2 = "off";

  /* cookie取得 */
  {
    const HTTP_COOKIE = request.headers.get("cookie") ?? "";
    if (HTTP_COOKIE !== "")
    {
      for(const pair of HTTP_COOKIE.split(/[;,]\s*/))
      {
        const [key, val] = pair.split("=", 2);
        if (key && val) gb.cookies[key] = val;
      }
    }
  }

  const PON = await sha1Base64(await hash("SHA-256", md5(gb.ip) + gb.md5Salt));
  const secure = !gb.isMonazilla ? "Secure;" : "";  // https非対応専ブラ用
  gb.headers["Set-Cookie"] = `PON=${PON}; Max-Age=63072000; path=/; Domain=.3chbbs.com; ${secure} HttpOnly`;

  /*
   * 忍法帖システムが有効な板かどうか判定
   * 有効でなければ(BBS_NINJA2=off)、Cookie(PON)食いチェックだけして、return
   */
  if (gb.settings.BBS_NINJA2 === "off") {
    if(!gb.cookies.PON) return dispError(gb, "ConfirmationPhase");
    
    return 1;
  }

  // PINチェック
  const isCookieValid = gb.cookies.PON && gb.cookies.HAP && gb.cookies.time && gb.cookies.PIN && gb.cookies.PIN === md5(gb.cookies.PON + gb.cookies.HAP + gb.cookies.time + gb.userAgent + gb.md5Salt);

  const HAP = isCookieValid ? gb.cookies.HAP : Math.floor(Math.random() * 90000000 + 10000000).toString();
  gb.headers["Set-Cookie"] = `HAP=${HAP}; Max-Age=63072000; path=/; Domain=.3chbbs.com; ${secure} HttpOnly`;
  // HAP Cookie不正利用防止のためのtimeとPIN
  gb.headers["Set-Cookie"] = `time=${gb.time}; Max-Age=63072000; path=/; Domain=.3chbbs.com; ${secure} HttpOnly`;
  const PIN = md5(PON + HAP + time + gb.userAgent + gb.md5Salt);
  gb.headers["Set-Cookie"] = `PIN=${PIN}; Max-Age=63072000; path=/; Domain=.3chbbs.com; ${secure} HttpOnly`;

  try {
    // 識別子システム（忍者データベース問い合わせ -> 各種処理）
    // 忍者ID=HAP(8桁の数字),認証トークン=validate(6桁の数字)
    const ninja = gb.cookies.HAP ? await env.NINJA.get(gb.cookies.HAP) : null;

    // ninjaの中を調べて認証確認
    // <>区切りで、0:D, 1:U, 2:firstIp, 3:created
    if (ninja !== null) {
      const userData = ninja.split("<>");
      let deviceInfo;

      // userData[0]===""のとき、認証できていない
      if (userData[0] === "AUTH") return dispError(gb, "認証できていません。<br>下のURLをWebブラウザから開き、6桁の認証コード「" + userData[1] + "」を入力して認証を行ってください<br>https://www.3chbbs.com/auth/#!" + HAP);
      if (userData[0] === "" || !userData[3]) return dispError(gb, "Cookie の内容が壊れていますのでいったん削除してください。");

      [deviceInfo, gb.userData.U, gb.userData.firstIp, gb.userData.created] = userData;

      gb.deviceInfo += deviceInfo;

      // UAが変わっているときはだめ
      if (gb.userData.U !== gb.userAgent) return dispError(gb, "忍者IDが失効しています。Cookieを削除して再認証してください。");

    } else {
      const validate = Math.floor(Math.random() * 900000 + 100000).toString(); // 6桁のランダムな数字
      await env.NINJA.put(newHAP, `AUTH<>${validate}<>${gb.unixtime}<>${gb.asn}<>${gb.country}<>${gb.ken}<>${gb.city}<>${gb.userAgent}`);

      return dispError(gb, "ようこそ：貴方の忍者IDを作成します。２０秒～１分ほどかかるので少しの間だけ待ってちょ。。。 <br>bot・スパムでないことを確認するために認証を行う必要があります。<br><b>１分ほど待ってから</b>下のURLをWebブラウザから開き、6桁の認証コード「" + validate + "」を入力して認証を行ってください<br>https://www.3chbbs.com/auth/#!" + HAP);
    }
  } catch (e) {
    // 忍法帖システムに依存する機能を止める
    gb.settings.BBS_ID_SHIKIBETSU = "off";

    if (gb.form.FROM.includes("!debug")) {
      gb.form.MESSAGE += "<hr>" + e;
    }
  }

  return 1;
}

/**
 * アクセス規制・NGワード（まだかな）
 */
async function checkDenyList(gb, env)
{
  // ★はスルー
  if(gb.cap) return 1;
  
  // 削除系の板はスルー
  if(gb.form.bbs === "saku") return 1;
  
  // torはダメ
  if(gb.country === "T1") return "ERROR: 変なホスト規制中！！ 変なホストです。<br>Tor is not allowed.";

  // アクセス規制リストで判定
  const object = await env.TEST.get("madakana");
  if(object !== null)
  {
    const DENYLIST = await object.text();
    // 書式->板dir名正規表現<>規制対象正規表現<>NGワード正規表現<>規制内容(mark -> Marked, TateDame -> スレ立て禁止, それ以外・省略 -> 規制)
    // アクセス規制 -> ^(livegalileo|livejupiter|liveuranus|news4?vip)$<>(^UID:nuoiyUX6m+irC5rvB4QUSycfHGc|IP:192\..+AS1234-JP,Shizuoka,Hamamatsu|AS1234-JP,Shizuoka,\S+\sDevice:2160,1440,1.5,24,0,Android,Nexus\s7)<>^[ゆ|う]めめ&#[a-zA-Z0-9]{5};?$<>
    // NGワード -> ^poverty$<><>(アフィサイトの宣伝です！|h?ttps?:\/\/afi-ks\.com)<>mark
    // 規制対象となるユーザ情報(ログに格納されているものと同じ)
    // UID:nuoiyUX6m+irC5rvB4QUSycfHGc IP:192.168.2.1<192.168.2.0> AS1234-JP,Shizuoka,Hamamatsu Device:2160,1440,1.5,24,0,Android,Nexus 7 UA:Mozilla/5.0 (iPhone; CPU iPhone OS 18_4_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.4 Mobile/15E148 Safari/604.1" Not A;Brand";v="99.0.0.0", "Chromium";v="98.0.4750.0", "Google Chrome";v="98.0.4750.0"
    
    const RIP = DENYLIST.split("\n");

    for(let i = 0; i < RIP.length; i++)
    {
      const tmp = RIP[i].trim();
      
      if(tmp === '') continue;
      if(/^#/.test(tmp)) continue;
      
      const MATCH = tmp.match(/^(\S*)<>(\S*)<>(\S*)<>(\S*)$/);
      if(MATCH !== null)
      {
        const BBS = new RegExp(MATCH[1] || ".+");
        const KISEI = new RegExp(MATCH[2] || ".+");
        const NGWORD = new RegExp(MATCH[3] || ".+");
        const EXEC = MATCH[4];
        const NGTARGET = `FROM=${gb.form.FROM} mail=${gb.form.mail} MESSAGE=${gb.form.MESSAGE} subjeect={gb.form.subject}`;
        
        if(BBS.test(gb.form.bbs) && KISEI.test(gb.userInfo) && NGWORD.test(NGTARGET))
        {
          // リストにあった

          // TateDame(スレ立て禁止)
          if(EXEC === "TateDame")
          {
            if (gb.number === 1) {
              return "ERROR: Madakana:このホスト/地域/端末では、スレッドが立てられません。。。（" + tmp + "）<br><a href=\"https://www.3chbbs.com/madakana/\">ここで告知されています。</a>";
            }else continue;
          }

          // 拒否られマークをつけるフラグ
          let deniedmark = 0;
        
          // mark指定の規制や、BBS_NO_MADAKANA=checkedの板・スレでは、拒否られマーク+規制情報をつけたうえで書き込みを許可する
          if(EXEC === "mark" || gb.settings.BBS_NO_MADAKANA === "checked") deniedmark = 1;

          if (deniedmark) {
            if (NGWORD.source !== ".+")
            // NGワード系の場合
            {
              gb.form.MESSAGE += ` <small style="color: #999;">👀<br>Madakana(NGWord): Caution(${md5(tmp)})</small>`; 
            }
            else
            {
              gb.form.FROM = "</b>[´・ω・｀]<b> " + gb.form.FROM;
              gb.form.MESSAGE += `<hr><var style="color:blue;">Madakana:${md5(tmp)}</var>`; 
            }

            return "";
          }
          // 上記のもの以外はエラー
          else
          {
            // エラー種別
            let ErrMsg = "アクセス規制中です！！（" + tmp + "）<br><a href=\"https://www.3chbbs.com/madakana/\">ここで告知されています。</a>";
            if(NGWORD.source !== ".+")
            // NGワード系の場合
            {
              // NGワード規制はBBS_NGWORD_PASS=checkedでスルー
              if(gb.settings.BBS_NGWORD_PASS === "checked") continue;
              ErrMsg = `NGワード禁止！(${tmp})<br><a href="https://www.3chbbs.com/madakana/">ここで告知されています。</a>`;
            }

            return "ERROR: Madakana:" + ErrMsg;
          }
        }
      }
    }
  }
  return 1;
}

function checkFieldSize(gb) {
  // 強い★はスルー
  if (gb.strongCap) return 1;

  const BBS_SUBJECT_COUNT = gb.settings.BBS_SUBJECT_COUNT ?? 64;
  const BBS_NAME_COUNT = gb.settings.BBS_NAME_COUNT ?? 64;
  const BBS_MAIL_COUNT = gb.settings.BBS_MAIL_COUNT ?? 64;
  const BBS_LINE_NUMBER = gb.settings.BBS_LINE_NUMBER ?? 30;
  let BBS_MESSAGE_COUNT = gb.settings.BBS_MESSAGE_COUNT ?? 4096;

  if (gb.form.subject.length > BBS_SUBJECT_COUNT) return dispError(gb, `サブジェクトが長すぎます！(${gb.form.subject.length}/${BBS_SUBJECT_COUNT})`);
  if (gb.form.FROM.length > BBS_NAME_COUNT) return dispError(gb, `名前が長すぎます！(${gb.form.FROM.length}/${BBS_NAME_COUNT})`);
  if (gb.form.mail.length > BBS_MAIL_COUNT) return dispError(gb, `メールアドレスが長すぎます！(${gb.form.mail.length}/${BBS_MAIL_COUNT})`);

  // ここから先は管理コマンド使用時はスルー
  if (/^!ADMIN:/.test(gb.form.MESSAGE)) return 1;

  // >>1は65,536文字(4096文字×16レス分)までOK
  if (gb.number === 1 && BBS_MESSAGE_COUNT < 65536) BBS_MESSAGE_COUNT = 65536;

  // 本文長制限
  if (gb.form.MESSAGE.length > BBS_MESSAGE_COUNT) return dispError(gb, `本文が長すぎます！(${gb.form.MESSAGE.length}/${BBS_MESSAGE_COUNT})`);

  // 行数制限(>>2以降)
  if (gb.number >= 2 && gb.form.MESSAGE.split("<br>").length > BBS_LINE_NUMBER * 2) return dispError(gb, `改行が多すぎます！(${gb.form.MESSAGE.split("<br>").length}/${BBS_LINE_NUMBER * 2})`);

  return 1;
}

/* 
 * IDのところに表示する文字列と、識別IDを生成する
 * IDのところに表示する文字列は gb.id に格納され、
 * 識別IDは gb.sikibetsuId に格納される。
 */
async function makeIdAndShikibetuId(gb) {
  /* IDを生成する */
  const BBS_ID_GROUP = gb.settings.BBS_ID_GROUP ? gb.settings.BBS_ID_GROUP : gb.form.bbs;
  const BBS_ID = gb.settings.BBS_ID ? gb.settings.BBS_ID : "checked";
  const BBS_ID_RESET = gb.settings.BBS_ID_RESET ? gb.settings.BBS_ID_RESET : "86400";
  const BBS_ID_LV = gb.settings.BBS_ID_LV ? gb.settings.BBS_ID_LV : "";
  const BBS_ID_DATE = Math.floor((parseInt(gb.unixtime) + 32400)/BBS_ID_RESET);

  // キャップはID:???
  if (gb.cap) {
    gb.id = "ID:???";
  }
  // 投稿時IPからIDを生成
  else if (BBS_ID === "ip") {
    let idString = await getSha1Base64Id(gb.idnotane, BBS_ID_GROUP, BBS_ID_DATE, gb.md5Salt, 8);
    gb.id = `ID:${idString}`;
  }
  // 忍者IDに紐づいた初回IPからIDを生成
  else if (BBS_ID === "checked") {
    let idString = await getSha1Base64Id(gb.userData.firstIp, BBS_ID_GROUP, BBS_ID_DATE, gb.md5Salt, 8);
    gb.id = `ID:${idString}`;
  }
  // siberiaは発信元IPアドレスを表示
  else if (BBS_ID === "siberia") {
    gb.id = `発信元:${gb.ip}`;
  }

  /* 識別IDを生成する */
  const BBS_ID_SHIKIBETSU = gb.settings.BBS_ID_SHIKIBETSU ? gb.settings.BBS_ID_SHIKIBETSU : "checked";

  // deviceInfo の形式: OS_model_画面サイズ_maxTouchPoints_colorDepth_webgl_canvas
  // 識別IDの生成では0~1(OS~model)、2~4(画面サイズ~colorDepth)、5(webgl)、6(canvas)でそれぞれ4桁ずつハッシュ値を生成

  // キャップは★
  if (gb.cap) {
    gb.shikibetsuId = "★";
  }
  else if (BBS_ID_SHIKIBETSU === "checked") {
    const d = gb.deviceInfo.split("_");
    const d1 = d[0] + (d[1] ?? "");
    const d2 = (d[2] ?? "") + (d[3] ?? "") + (d[4] ?? "");
    const d3 = (d[5] ?? "");
    const d4 = (d[6] ?? "");
    const deviceId = md5(d1+BBS_ID_GROUP+BBS_ID_DATE+gb.md5Salt).slice(0,4) + md5(d2+BBS_ID_GROUP+BBS_ID_DATE+gb.md5Salt).slice(0,4) + md5(d3+BBS_ID_GROUP+BBS_ID_DATE+gb.md5Salt).slice(0,4) + md5(d4+BBS_ID_GROUP+BBS_ID_DATE+gb.md5Salt).slice(0,4);
    // 例:JP.vE.J2 xH-7ca00d39db6fd3e6
    gb.shikibetsuId = `${gb.country}.${await getSha1Base64Id(gb.ken, BBS_ID_GROUP, BBS_ID_DATE, gb.md5Salt, 2)}.${await getSha1Base64Id(gb.city+"c", BBS_ID_GROUP, BBS_ID_DATE, gb.md5Salt, 2)} ${await getSha1Base64Id(gb.asn, BBS_ID_GROUP, BBS_ID_DATE, gb.md5Salt, 2)}-${deviceId}`;
  }
  else if (BBS_ID_SHIKIBETSU === "raw") {
    const d = gb.deviceInfo.split("_");
    const d1 = d[0] + (d[1] ?? "");
    const d2 = (d[2] ?? "") + (d[3] ?? "") + (d[4] ?? "");
    const d3 = (d[5] ?? "");
    const d4 = (d[6] ?? "");
    const deviceId = md5(d1).slice(0,4) + md5(d2).slice(0,4) + md5(d3).slice(0,4) + md5(d4).slice(0,4);
    gb.shikibetsuId = `${gb.country}.${gb.ken} ${gb.asn}-${deviceId}`;
  }
  else if (BBS_ID_SHIKIBETSU === "ken") {
    gb.shikibetsuId = `${gb.country}.${gb.ken}`;
  }
  else if (BBS_ID_SHIKIBETSU === "city") {
    gb.shikibetsuId = `${gb.country}.${gb.ken}.${gb.city}`;
  }
  else if (BBS_ID_SHIKIBETSU === "as") {
    gb.shikibetsuId = `${gb.asn}`;
  }
  else if (BBS_ID_SHIKIBETSU === "d") {
    const d = gb.deviceInfo.split("_");
    const d1 = d[0] + (d[1] ?? "");
    const d2 = (d[2] ?? "") + (d[3] ?? "") + (d[4] ?? "");
    const d3 = (d[5] ?? "");
    const d4 = (d[6] ?? "");
    const deviceId = md5(d1).slice(0,4) + md5(d2).slice(0,4) + md5(d3).slice(0,4) + md5(d4).slice(0,4);
    gb.shikibetsuId = `${deviceId}`;
  }
}

async function getSha1Base64Id(tane, group, date, salt, length) {
  const md5Id = md5(tane+group+date+salt);
  const sha1Base64Id = await sha1Base64(md5Id);
  return sha1Base64Id.slice(0, length);
}

/* 
 * ユーザコマンド・管理コマンドの処理
 */
async function processCommands(gb, request, env) {
  let isThreadOwner = false;
  const BBS_THREAD_SETTINGS = gb.settings.BBS_THREAD_SETTINGS ? gb.settings.BBS_THREAD_SETTINGS : "on";
  const BBS_DISABLE_NUSI = gb.settings.BBS_DISABLE_NUSI ? gb.settings.BBS_DISABLE_NUSI : "";
  if (gb.settings.BBS_THREAD_SETTINGS !== "off") {
    if (gb.number === 1) {
      isThreadOwner = true;
    }
    if (BBS_DISABLE_NUSI !== "checked") {
      // gb.logDataから>>1の情報を取得
      const firstResStr = gb.logData.split("\n")[2];
      const firstRes = (firstResStr ?? "").split("<>");
      // [5] userDataのFIPで判定
      if (firstRes[5] && /^FIP:.+\sIP:/.test(firstRes[5])) {
        const firstResIp = firstRes[5].match(/^FIP:(.+)\sIP:/)[1];
        if (gb.ip === firstResIp) {
          isThreadOwner = true;
        }
      }
    }
  }

  // スレ主、★のみ
  if (!gb.cap && !isThreadOwner) return;
  // !SETTING:NAME=VALUE スレ設定変更コマンド
  const settingCommandMatches = [...gb.form.MESSAGE.matchAll(/!SETTING:(.+?)=(.*?)(<br>|$)/g)];
  // settingCommandMatches: array
  if (settingCommandMatches) {
    for (let i = 0; i < settingCommandMatches.length; i++) {
      const setting = settingCommandMatches[i];
      gb.origSettings[setting[1]] = setting[2].trim();
    }
  }

  // 管理コマンド
  // admin板でのみ有効
  if (!gb.cap || gb.form.bbs !== "admin") return 1;

  // ログ堀り
  if (/^!LOG:/.test(gb.form.MESSAGE)) {
    gb.form.MESSAGE += "<hr>";
    // !LOG:bbs:key:n
    const logCommandMatches = [...gb.form.MESSAGE.matchAll(/^!LOG:(.+?):(.+?):(.+?)$/)];
    if (logCommandMatches) {
      for (const logCommand of logCommandMatches) {
        const bbs = logCommand[1];
        const key = logCommand[2];
        const n = logCommand[3].trim();
        const logFile = `${bbs}/thread/${key.slice(0, 4)}/${key}.cgi`;
        const object = await env.ch3www.get(logFile);
        if (object) {
          // 行をループしてn番のレスを取得（レス番は最初の<>）
          const logDat = await object.text();
          const log = logDat.split("\n");
          for (let i = 0; i < log.length; i++) {
            const res = log[i].split("<>");
            if (res[0] === n) {
              gb.form.MESSAGE += `<br>${bbs}/${key}/${n}: ${log[i]}`;
              break;
            }
          }
        } else {
          gb.form.MESSAGE += `もうないよ`;
        }
      }
    }
  }
  // 削除

  // まだかなの管理（追加，行削除，全削除など）

  // 板設定変更

  // キャップ管理

}

async function processByBySaru(gb, request, env) {
  // gb.logData.split("\n")の配列=レス配列をループし，各種規制処理＆壊れたレス（レス番重複など）の除去をする

}

/* 
 * 各種スレ立て規制 
 * D1のsubjectテーブルを利用
 */
async function checkRegulationOfCreatingThread(gb, env) {
  const BBS_THREAD_TATESUGI = gb.boardSettings.BBS_THREAD_TATESUGI ? gb.boardSettings.BBS_THREAD_TATESUGI : "3/86400";
  const TATESUGI_COUNT = parseInt(BBS_THREAD_TATESUGI.split("/")[0] ? BBS_THREAD_TATESUGI.split("/")[0] : "0");
  const TATESUGI_DURATION = parseInt(BBS_THREAD_TATESUGI.split("/")[1] ? BBS_THREAD_TATESUGI.split("/")[1] : "86400");

  // スレ立てでないときはスルー
  if (!gb.form.subject) return 1;

  // 新しいスレッドキーがすでに使われていたら、お断りする
  const sameKeyThread = await env.DB
    .prepare(`
      SELECT 1
      FROM subject
      WHERE bbs = ?
        AND key = ?
    `)
    .bind(gb.form.bbs, gb.form.key)
    .first();

  if (sameKeyThread) {
    return dispError(gb, "別の人が同時刻にスレッドを立てようとしています。ごめんなさい。");
  }

  // 同じスレッド
  const sameTitleThread = await env.DB
    .prepare(`
      SELECT 1
      FROM subject
      WHERE bbs = ?
        AND title = ?
      LIMIT 1;
    `)
    .bind(gb.form.bbs, gb.form.subject)
    .first();

  if (sameTitleThread) {
    return dispError(gb, "すでに同じタイトルのスレッドが存在しています。");
  }

  // スレッド立てすぎ
  const count = await env.DB
    .prepare(`
      SELECT COUNT(*) AS cnt
      FROM subject
      WHERE bbs = ?
        AND uid = ?
        AND thread_id >= ?
    `)
    .bind(gb.form.bbs, gb.id, parseInt(gb.unixtime) - TATESUGI_DURATION)
    .first();

  if (Number(count.cnt) >= TATESUGI_COUNT) {
    return dispError(gb, `スレッド立てすぎです。。。(${gb.id}: ${count.cnt}/${TATESUGI_COUNT.toString()})`);
  }

  return 1;
}

async function updateSubject(gb, env) {
  // sageの処理（スレ立て時はsage無効）
  if (gb.number >= 2) {
    //if (gb.number < 10) sage = 1;//>>10未満は強制sage（age荒らし防止）
    if (gb.form.mail.includes("sage")) gb.sage = 1;
    if (parseInt(gb.form.key) + parseInt(gb.settings.BBS_FORCE_SAGE ? gb.settings.BBS_FORCE_SAGE : "9999999999") < parseInt(gb.unixtime)) gb.sage = 1;
    if (gb.settings.BBS_LIVE && gb.settings.BBS_LIVE === "checked" && Math.random() * 10 >= 1) gb.sage = 1; // 実況板、実況スレでは10分の9の確率でsage
  }

  // sageならsubject.txtを更新しない
  if (gb.sage) return 1;

  try {
    await env.DB.batch([
      // トランザクション開始
      env.DB.prepare("BEGIN TRANSACTION"),

      // スレ追加 or 更新
      env.DB.prepare(`
        INSERT INTO subject (
          bbs, key, title, resnum, lastmodified, uid
        )
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT(bbs, key) DO UPDATE SET
          resnum = excluded.resnum,
          lastmodified = excluded.lastmodified
      `).bind(gb.form.bbs, gb.form.key, gb.subject, gb.number, gb.unixtime, gb.id),

      // 古いスレ削除
      env.DB.prepare(`
        DELETE FROM subject
        WHERE id IN (
          SELECT id
          FROM subject
          WHERE bbs = ?
          ORDER BY lastmodified DESC
          LIMIT -1 OFFSET ?
        )
      `).bind(gb.form.bbs, parseInt(gb.boardSettings.BBS_MAX_MENU_THREAD ? gb.boardSettings.BBS_MAX_MENU_THREAD : "300")),

      // トランザクション終了
      env.DB.prepare("COMMIT")
    ]);

    return 1;
  } catch (e) {
    return dispError(gb, `updateSubject error. スレ一覧を更新できませんでした。(unixtime:${gb.unixtime} bbs:${gb.form.bbs} key:${gb.form.key} number:${gb.number} errmsg:updateSubject->${e})<br>このメッセージをコピペして、運用情報板で報告していただけるとありがたいです。`);
  }
}

async function updateThreadFile(gb, env) {
  try {
    // スレ立て時は、新しく作成（すでにあればエラー）
    if (!gb.logEtag) {
      let threadSettingsText = "";

      Object.keys(gb.origSettings).forEach(function (key) {
        threadSettingsText += `${key}=${gb.origSettings[key]}<>`;
      });

      gb.logData += `${threadSettingsText}\n${gb.number}<>${gb.form.FROM}<>${gb.shikibetsuId}<>${gb.time}<>${gb.id}<>${gb.form.MESSAGE}<>${gb.userInfo}\n`;

      const result = await env.ch3www.put(gb.logFile, gb.logData, {
        onlyIf: { doesNotExist: true },
      });

      if (!result?.uploaded) return dispError(gb, `updateThreadFile error. 板飛びそうなので、またの機会にどうぞ。。。(unixtime:${gb.unixtime} bbs:${gb.form.bbs} key:${gb.form.key} number:${gb.number} errmsg:updateThreadFile->Thread-LogFile_create_conflicted!)`);

      return 1;
    }

    // 最大リトライ数
    const maxAttempts = 1;

    for (let attempt = 0; attempt < maxAttempts; attempt++) {
      // 0:num<>1:name<>2:sikibetsuId<>3:time<>4:id<>5:MESSAGE<>6:HOST999
      gb.logData += `${gb.number}<>${gb.form.FROM}<>${gb.shikibetsuId}<>${gb.time}<>${gb.id}<>${gb.form.MESSAGE}<>${gb.userInfo}\n`;

      // 取得したEtagと書き込み先のEtagが同じなら，書き込まれて終了
      const result = await env.ch3www.put(gb.logFile, gb.logData, {
        onlyIf: { etagMatches: gb.logEtag },
      });

      if (result?.uploaded) return 1;

      // 競合していたら、0.1～0.3秒待ってからもう一度試みる
      await new Promise(r => setTimeout(r, 100 + Math.random() * 200));
      const object = await env.ch3www.get(gb.logFile);
      // スレ立て時でないのにスレファイルがないのはおかしい
      if (object === null) break;
      gb.logData = await object.text();
      gb.logEtag = object.etag;
    }
  } catch (e) {
    return dispError(gb, `updateThreadFile error. ログファイルに書けませんでした。(unixtime:${gb.unixtime} bbs:${gb.form.bbs} key:${gb.form.key} number:${gb.number} errmsg:updateThreadFile->${e})<br>このメッセージをコピペして、運用情報板で報告していただけるとありがたいです。`);
  }

  // 通常モードでは、ここでエラー
  // 実況モードでは、D1のキューに格納
  return dispError(gb, `updateThreadFile error. 板飛びそうなので、またの機会にどうぞ。。。(unixtime:${gb.unixtime} bbs:${gb.form.bbs} key:${gb.form.key} number:${gb.number} errmsg:updateThreadFile->Thread-LogFile_update_conflicted!)`);
}

async function updateSubback(gb, env) {
  // スレ立てでない時は実行しない
  if (gb.number > 1) return 1;

  const subbackFIle = `${gb.logPath}subject.txt`;
  const kakologFile = `${gb.path}thread/subject.txt`;

  try {
    const subbackObj = await env.ch3www.get(subbackFIle);
    let subbackFileEtag = subbackObj ? subbackObj.etag : undefined;
    let subbackText;

    if (subbackObj !== null) {
      const subbackArray = (await subbackObj.text()).split("\n");
      subbackArray.shift(); // 先頭のLastModified行を取り除く
      subbackText = subbackArray.join("\n");
    } else {
      subbackText = "";
    }

    // 0:key<>1:title<>2:名前<>3:sikibetsuId<>4:id<>5:>>1の本文
    subbackText = `${gb.unixtime}\n${gb.form.key}<>${gb.form.subject}<>${gb.form.FROM}<>${gb.shikibetsuId}<>${gb.id}<>${gb.form.MESSAGE}\n${subbackText}`;

    if (subbackObj === null) {
      const kakologFileObj = await env.ch3www.get(kakologFile);
      let kakologList = "";
      let kakologFileEtag = undefined;

      if (kakologFileObj) {
        kakologFileEtag = kakologFileObj.etag;
        const kakologArray = (await kakologFileObj.text()).split("\n");
        kakologArray.shift(); // 先頭のLastModified行を取り除く
        kakologList = kakologArray.join("\n");
      }

      kakologList = `${gb.unixtime}\n${gb.form.key.slice(0,4)}<>${gb.form.key}\n${kakologList}`;

      let resultUpdateKakologFile;

      if (kakologFileEtag) {
        resultUpdateKakologFile = await env.ch3www.put(kakologFile, kakologList, {
          onlyIf: { etagMatches: kakologFileEtag },
        });
      } else {
        resultUpdateKakologFile = await env.ch3www.put(kakologFile, kakologList, {
          onlyIf: { doesNotExist: true },
        });
      }

      if (!resultUpdateKakologFile?.uploaded) return dispError(gb, `updateSubback error. 板飛びそうなので、またの機会にどうぞ。。。(unixtime:${gb.unixtime} bbs:${gb.form.bbs} key:${gb.form.key} number:${gb.number} errmsg:updateSubback->kakologList_update_conflicted!)`);

      const result = await env.ch3www.put(subbackFIle, subbackText, {
        onlyIf: { doesNotExist: true },
      });

      if (!result?.uploaded) return dispError(gb, `updateSubback error. 板飛びそうなので、またの機会にどうぞ。。。(unixtime:${gb.unixtime} bbs:${gb.form.bbs} key:${gb.form.key} number:${gb.number} errmsg:updateSubback->SubbackFIle_update_conflicted!)`);
    } else {
      const result = await env.ch3www.put(subbackFIle, subbackText, {
        onlyIf: { etagMatches: subbackFileEtag },
      });

      if (!result?.uploaded) return dispError(gb, `updateSubback error. 板飛びそうなので、またの機会にどうぞ。。。(unixtime:${gb.unixtime} bbs:${gb.form.bbs} key:${gb.form.key} number:${gb.number} errmsg:updateSubback->SubbackFIle_update_conflicted!)`);
    }

    return 1;
  } catch (e) {
    return dispError(gb, `updateSubback error. 不明なエラーが発生しました。(unixtime:${gb.unixtime} bbs:${gb.form.bbs} key:${gb.form.key} number:${gb.number} errmsg:updateSubback->${e})<br>このメッセージをコピペして、運用情報板で報告していただけるとありがたいです。`);
  }
}

async function updateHeadline(gb, env) {
  const filename = `headline.txt`;

  // sageならsubject.txtを更新しない
  if (gb.sage) return 1;

  try {
    const object = await env.ch3www.get(filename);
    let headlineEtag = object ? object.etag : undefined;
    const msg = gb.form.MESSAGE.slice(0,30);
    let headlineText = `${gb.unixtime}\n${gb.form.bbs}<>${gb.boardSettings.BBS_TITLE}<>${gb.unixtime}<>${gb.form.key}<>${msg}\n`;

    if (object !== null) {
      const headLineArray = (await object.text()).split("\n");
      headLineArray.shift(); // 先頭のLastModified行を取り除く
      
      for (let i = 0; i < headLineArray.length; i++) {
        // 300板まで表示
        if (i >= 300) break;

        // 0:dir_name<>1:板名<>2:最終更新時刻<>3:最終書き込みスレッド名<>4:最終書き込みレスの一部
        const headLine = headLineArray[i].trim().split("<>");

        // 0無し、0が^[a-zA-Z0-9\-_]+$でない、1無し、4まで<>がない -> 表示しない
        if (!headLine[0] || !/^[a-zA-Z0-9\-_]+$/.test(headLine[0]) || !headLine[1] || headLine[4] == null) continue;

        // 現在の板は載せない
        if (headLine[0] === gb.form.bbs) continue;

        headlineText += `${headLine[0]}<>${headLine[1]}<>${headLine[2]}<>${headLine[3]}<>${headLine[4]}\n`;
      }
    }

    // 競合していたら書き込まない（sage扱い）
    await env.ch3www.put(filename, headlineText, {
      onlyIf: { etagMatches: headlineEtag },
    });

    return 1;
  } catch (e) {
    return dispError(gb, `updateHeadline error. 不明なエラーが発生しました。(unixtime:${gb.unixtime} bbs:${gb.form.bbs} key:${gb.form.key} number:${gb.number} errmsg:updateHeadline->${e})<br>このメッセージをコピペして、運用情報板で報告していただけるとありがたいです。`);
  }
}

function dispError(gb, errMsg) {
  gb.error = errMsg;
  return 0;
}

/*
 * 書き込みました。
 */
function respondEndHtml(gb) {
  const charset = gb.isSJIS ? "Shift_JIS" : "UTF-8";
  const html = `<html lang="ja">
<head>
<title>書きこみました。</title>
<meta http-equiv="Content-Type" content="text/html; charset=${charset}">
<meta content=5;URL=/test/read.cgi/${gb.form.bbs}/${gb.form.key}/l50  http-equiv=refresh>
</head>
<body>書きこみが終わりました。<br><br>
画面を切り替えるまでしばらくお待ち下さい。<br><br>
<br><br><br><br><br>
<center>
</center>
</body>
</html>
`;
  /* 出力ヘッダ */
  gb.headers["x-threadkey"] = gb.form.key;
  gb.headers["x-resnum"] = String(gb.number);

  return respondPlainText(html, "html", charset, "", new Date(), "", gb.headers);
}

/* 
 * 書き込み確認画面
 */
function respondConfirmationPhase(gb) {
  const charset = gb.isSJIS ? "Shift_JIS" : "UTF-8";
  const html = `<html><!-- 2ch_X:cookie -->
<head>
<title>■ 書き込み確認 ■</title>
<META http-equiv="Content-Type" content="text/html; charset=${charset}">
<meta name="viewport" content="width=device-width,initial-scale=1.0,minimum-scale=1.0,maximum-scale=1.6,user-scalable=yes" />
</head>
<body bgcolor=#EEEEEE>
<font size=+1 color=#FF0000><b>書きこみ＆クッキー確認</b></font>
<ul><br><br>
<b>${gb.form.subject} </b><br>
名前： <b>${gb.form.FROM}</b><br>
E-mail： ${gb.form.mail}<br>
内容：<br>${gb.form.MESSAGE}<br><br></ul>
<b>
投稿確認<br>
${ConfirmationText}
</b>
(3chiko.htmlの場合はリロード（再読み込み）してください)<br>
変更する場合はキャンセルして書き直して下さい。<br><br>
現在、荒らし対策でクッキーを設定していないと書きこみできないようにしています。<br>
<font size=-1>(cookieを設定するとこの画面はでなくなります。)</font><br>
</body>
</html>
`;
  return respondPlainText(html, "html", charset, "", new Date(), "", gb.headers);
}

/*
 * ＥＲＲＯＲ！
 */
function respondError(gb) {
  const charset = gb.isSJIS ? "Shift_JIS" : "UTF-8";
  const html = `<html>
<head>
<title>ＥＲＲＯＲ！</title>
<meta http-equiv="Content-Type" content="text/html; charset=${charset}">
</head>
<body bgcolor="#FFFFFF"><!-- 2ch_X:error -->
<font size=+1 color=#FF0000><b>ERROR: ${gb.error}</b></font>
<ul>
<br>ホスト<b>${gb.host}</B><br><b>${gb.form.subject} </b><br>
名前： <b>${gb.form.FROM}</b><br>E-mail： ${gb.form.mail}<br>
内容：<br>${gb.form.MESSAGE}<br><br>
</ul>
<a href="https://wiki.3chbbs.com/index.php/%E3%82%A2%E3%82%AF%E3%82%BB%E3%82%B9%E8%A6%8F%E5%88%B6%E4%B8%AD%E3%81%A7%E3%82%82%E6%9B%B8%E3%81%91%E3%82%8B%E6%9D%BF">★ アクセス規制中でも書ける板 ★</a><br><br>
<hr>
こちらでリロードしてください。<a href="/test/read.cgi/${gb.form.bbs}/${gb.form.key}/l50"> GO! </a><br>
自分で解決してみよう! <a href="https://wiki.3chbbs.com/index.php/%E6%9B%B8%E3%81%8D%E8%BE%BC%E3%82%81%E3%81%AA%E3%81%84%E6%99%82%E3%81%AE%E6%97%A9%E8%A6%8B%E8%A1%A8">書き込めない時の早見表\</a><br>
分からないことがあったら<a href="https://www.3chbbs.com/html/guide/">３ちゃんねるガイド</a>へ。。。<br><br>

<p>
</body>
</html>
`;
  return respondPlainText(html, "html", charset, "", new Date(), "", gb.headers);
}
