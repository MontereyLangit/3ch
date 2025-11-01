import { sjis, respondPlainText, hexToBinary, sha1Base64, hash, md5, crypt, translateSaltChar,respond500 } from "./common.js";

/* デフォルト板設定 */
//古いので要修正
export const settingTxtDefault = `BBS_TITLE=<DIR_NAME>＠3ch掲示板
BBS_NONAME_NAME=名無しさん＠３ちゃんねる
BBS_MAX_MENU_THREAD=300
BBS_FORCE_SAGE=
BBS_ADD_THREAD=on
BBS_SHIKIBETSU=checked
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
const ConfirmationText = `
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
  /* HAP=識別子，D=端末固有情報，U=UAハッシュ値，PON=IP，time=timestamp，PIN=乱数，cert=HAP+D+U+PON+time+PIN+salt=>ハッシュ値 */
  gb.cookies = {HAP:"", D:"", U:"", PON:"", time:"", PIN:"", cert:""};
  gb.userData = {D:"", U:"", firstIp:"", lastIp:"", lv:40, exp:0, created:0, lastModified:0, postCount:0, as:{}};
  gb.host = request.headers.get("CF-Connecting-IP");
  gb.ip = request.headers.get("CF-Connecting-IP");
  gb.userAgent = request.headers.get("user-agent") ?? "";
  gb.browserInfo = "";
  gb.deviceInfo = request.headers.get("x-ch3-device-information") ?? ""; /* 端末固有情報->412,915,2,24,2,Android,Pixel 7 */
  gb.asn = request.cf.asn ?? "0000";
  gb.country = request.cf.country ?? "unknown";
  gb.ken = request.cf.region ?? "catv?";
  gb.city = request.cf.city ?? "";
  gb.deviceId = ""; /* 端末ID->16桁の端末固有情報ハッシュ値 */
  gb.uid = ""; /* 識別子 */
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
  gb.subjectText = "";
  gb.subjectEtag = undefined;
  gb.addedSubjectText = "";/* createNextThread で立てられた次スレsubjectがここに入る */
  gb.settings = {};/* 板設定・スレ設定データ */
  gb.boardSettings = {};/* 板設定スデータ */
  gb.headers = {};

    /* 最初のチェック */
    if (!checkRequest(gb, request, env)) return respondError(gb);
    /* Formの読み込み */
    if (!await readForm(gb, request)) return respondError(gb);

    /* キャップ処理 */
    await processCap(gb, request, env);

    /* トリップ処理 */
    if (!await processTrip(gb, request, env)) return respondError(gb);

    /* R2用のpathをセット */
    SetPath(gb);

    /* SETTING.TXT（板設定）読み込み */
    if (!await getBoardSettings(gb, request, env)) return respondError(gb);

    /* 書き込み先スレッドのデータ取得＆スレ設定読み込み */
    if (!await getThreadDataAndThreadSettings(gb, request, env)) return respondError(gb);

    /*
     * ユーザー識別処理（識別子システム）
     * Cookieと端末固有情報（デバイスフィンガープリント）の取得、認証、Cookie処理、Googleアカウント登録、識別子由来のBAN規制や各種制限判定処理
     */
    if (!await processIdentification(gb, request)) return respondError(gb);

    // ログに格納されるユーザ情報(規制にも使用)
    gb.userInfo = "UID:" + gb.uid + " IP:" + gb.host + " AS" + gb.asn + '-' + gb.country + ',' + gb.ken + ',' + gb.city + "Device:" + gb.deviceInfo + " UA:" + gb.userAgent;

    /* まだかな（アクセス規制） */
    if (!await checkDenyList(gb, env)) return respondError(gb);

    /* フィールドサイズチェック */
    if (!checkFieldSize(gb)) return respondError(gb);

    /* ID＆識別ID生成 */
    await makeIdAndShikibetuId(gb);

    /* コマンド類（ユーザーコマンド、管理コマンド）と>20以降のスレ内連投規制（バイバイさるさん）の処理 */
    //if (!await processCommandsAndByBySaru(gb, request, env)) return;

    /* 各種スレ立て規制 と subject更新 */
    if (!await suretateTotalCheckAndUpdateSubject(gb, env)) return respondError(gb);

    /* スレッドファイル更新 */
    if (!await updateThreadFile(gb, env)) return respondError(gb);

    /* スレ立て時のthread/..../subject.txtへの追加＆thread/subject.txt更新 */
    if (!await updateSubback(gb, env)) return respondError(gb);

    /* 全体headline更新 */
    if (!await updateHeadline(gb, env)) return respondError(gb);

    /* メインルーチン終わり。 */
    /*
     * 書き込みました。 / 書き込み確認 / ＥＲＲＯＲ！
     * UTF-8(read.cgi経由など)かShift_JIS(大半の2ch専ブラ)かを各関数内で判定し(gb.isSJIS)、適切なHTMLを出力する
     * return の内容はここで上書きされる
     */
    if (gb.error === "") return respondEndHtml(gb);
    if (gb.error === "ConfirmationPhase") return respondConfirmationPhase(gb);
    else return respondError(gb);
}

/*  
 * 一番はじめのチェック
 * UserAgent、CH-UAはここで取得される
 * 歴史的事情や最近の動向がいろいろあるので、更新時には注意すること
 */
function checkRequest(gb, request, env) {
  /* Cloudflare Rewirte Rulesで入ってこないようにしているけど、一応 */
  if (request.method !== 'POST') return dispError(gb, "POST メソッドを使ってください。<br>\nRecieve GET METHOD");

  const contentType = request.headers.get("content-type") || "";
  if (!contentType.includes("application/x-www-form-urlencoded")) return dispError(gb, "送信された内容が破損しています。->" + contentType + "<br>\nBroken request body. ");

  const referer = request.headers.get('referer') || '';
  if (!/^https?:\/\/www\.3chbbs\.com/.test(referer) && !/^http:\/\/127\.0\.0\.1:8787/.test(referer)) return dispError(gb, "ERROR: あなた騙されてますよ？->" + referer + "<br>\nReferer is invalid.");

  /* UserAgentは7byte~384byteの半角文字のみ可 */
  if (gb.userAgent.length < 7 || gb.userAgent.length > 384 || /[^ -~]/.test(gb.userAgent)) return dispError(gb, "ERROR: ブラウザ変ですよん。(ua)<br>UserAgent: " + gb.userAgent + "<br>\nUserAgent is invalid.");

  /* User Agent Client Hintsのブラウザ情報部分をUAに追加 */
  gb.userAgent += request.headers.get('sec-ch-ua-full-version-list') ?? request.headers.get('sec-ch-ua') ?? "";

  /* ヘッダなどのブラウザ情報などをbrowserInfoに追加 */
  gb.browserInfo += "Accept:" + (request.headers.get('accept') ?? "");
  gb.browserInfo += "Accept-Language:" + (request.headers.get('accept-language') ?? "");
  gb.browserInfo += "Accept-Encoding:" + (request.headers.get('accept-encoding') ?? "");

  /* 2ch専用ブラウザ(Monazilla/1.00)かどうかを判定 */
  if (/^Monazilla\/1.00/.test(gb.userAgent)) gb.isMonazilla = 1;

  /* IPv6接続かどうかをチェックする */
  if (gb.ip?.includes(":")) {
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

  /* !newthread 924スレのみ */
  if (/^9/.test(gb.form.key) && /^!newthread([^\r\t\n\f\v<]+)(.*)$/.test(gb.form.MESSAGE)) {
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

  /* 924・900スレッド */
  if (/^(924|900)/.test(gb.form.key)) gb.form.bbs = "924";

  /* 何かが変 */
  if (gb.form.bbs === "" || gb.form.bbs === "test" || gb.form.bbs === "js" || gb.form.bbs === "css" || gb.form.bbs === "html" || gb.form.bbs === "images") return dispError(gb, "板の指定が不正です。<br>\nThe board directory is incorrect...");
  if (gb.form.MESSAGE === "") return dispError(gb, "本文がありません！");
  if ((gb.form.key === "" || !/^[0-9]+$/.test(gb.form.key)) && gb.form.subject === "") return dispError(gb, "subjectが存在しないかキー情報が不正です。key=" + gb.form.key + "<br>\nThe subject is empty and key is wrong.");
  if (gb.form.time === "" || !/^[0-9]+$/.test(gb.form.time)) return dispError(gb, "ブラウザ変ですよん。(time)<br>\ntime is empty. time=" + gb.form.time);

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
  return new sjis().decode(bytes);
}

/* キャップの処理 */
async function processCap(gb, request, env) {
  if (!gb.form.mail) return;
  const rawCapPass = gb.form.mail.split("#");

  if (rawCapPass[1]) {
    /* キャップあるかな？ */
    /* 先頭に!で旧仕様パスワード */
    const capPass = rawCapPass[1].slice(0,1).includes("!") ? rawCapPass[1].slice(1) : await sha1Base64(await hash("SHA-256", md5(rawCapPass[1]) + gb.md5Salt));
    let handle = await env.CAP.get(capPass);

    if (!handle) return;

    /* キャップがあったら、まずキャップフラグを立てる */
    gb.cap = 1;
    /* 次にキャップの種別をチェックする */
    if (/★$/.test(handle)) {
      /* ★つきハンドル（強力キャップ） */
      gb.strongCap = 1;
      handle = handle.replace(/★.*/, "");
    }
    /* 名前入れていたら 名前＠ハンドル ★ */
    /* 名前なしの時は ハンドル ★ */
    if (gb.form.FROM !== "")
    {
      gb.form.FROM = gb.form.FROM + "＠" + handle + " ★";
    }
    else
    {
      gb.form.FROM = handle + " ★";
    }
  }
}

/*
 * トリップの処理
 * Shift_JIS-10桁&12桁対応暫定版 by 忍
 */
async function processTrip(gb, request, env) {
  if (gb.tripKey === "") return 1;

  const sjisEncoder = new sjis();
  const sjisBytes = sjisEncoder.encode(gb.tripKey);
  const handlePass = String.fromCharCode(...sjisBytes);

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
  return 1;
}

function SetPath(gb) {
  gb.path = `${gb.form.bbs}/`;
  gb.logPath = `${gb.path}thread/${gb.form.key.slice(0,4)}/`;
  gb.logFile = `${gb.logPath}${gb.form.key}.cgi`;
}

/* 板設定取得 */
async function getBoardSettings(gb, request, env) {
  const filename = `${gb.form.bbs}/SETTING.TXT`;
  let settingTxt = settingTxtDefault;

  try {
    const object = await env.ch3www.get(filename);

    if (object !== null) {
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
 * 1000超え時の処理なども
 */
async function getThreadDataAndThreadSettings(gb, request, env) {
  // スレッドキーからDurable Objectのスタブを取得
  const threadId = env.THREAD_DO.idFromName(gb.form.key);
  const threadStub = env.THREAD_DO.get(threadId);

  try {
    if (gb.form.subject) {
      // スレ立て時の処理
      const initialDat = await threadStub.getDat();

      /* スレ立て時、書き込み先ファイルがすでにあったらお断りする。 */
      if (initialDat) return dispError(gb, "別の人が同時刻にスレッドを立てようとしています。ごめんなさい。");

      gb.logData = `${gb.form.subject}\n`;
      gb.number = 1;
      gb.subject = gb.form.subject;

      return 1;
    }

    // スレッドデータ取得
    // dat, log, settingsを並行で取得
    const [dat, settingsText] = await Promise.all([
      threadStub.getDat(),
      threadStub.getSettings(),
    ]);

    if (!dat) return dispError(gb, "該当するスレッドがありません。" + gb.logFile);

    // 元の.cgi形式（datとlogが結合された形式）をメモリ上で再構築する
    // これにより、後続の処理が古いデータ形式を前提としていても動作する
    const datLines = dat.split('\n');

    // datの1行目(1レス目)の末尾からスレッドタイトルを取得
    const firstLine = datLines[0];
    if (!firstLine) return dispError(gb, "スレッドのログファイルが壊れています。(空です)");
    const firstLineFields = firstLine.split('<>');
    // 最後のフィールドがスレッドタイトル
    gb.subject = firstLineFields.pop() || '';
    if (!gb.subject) return dispError(gb, "スレッドのログファイルが壊れています。(タイトル取得失敗)");

    // レス数
    gb.number = datLines ? datLines.length : 0;
    // スレ設定を取得。ログの二行目に入っている。
    if (settingsText) {
      const settingsArray = settingsText.trim().split("<>");
      for (const settingPair of settingsArray) {
        const [key, value = ""] = settingPair.split("=");
        if (key) gb.settings[key] = value.trim();
      }
    }

    // 1000超え時の処理
    if (gb.number >= 1000) return dispError(gb, "このスレッドは1000を超えたのでもう書けません。");

    // 停止
    if (gb.settings.BBS_HEISA === "checked") return dispError(gb, "この板・スレッドは停止されています。");

    return 1;
  } catch(e) {
    return dispError(gb, `getThreadDataAndThreadSettings error. 不明なエラーが発生しました。(unixtime:${gb.time} bbs:${gb.form.bbs} key:${gb.form.key} number:${gb.number} errmsg:getThreadDataAndThreadSettings->${e})<br>このメッセージをコピペして、運用情報板で報告していただけるとありがたいです。`);
  }
}

/* 
 * ユーザー識別処理（識別子システム） by 忍
 * Cookie関連の処理、忍法帖、デバイスフィンガープリンティング、認証、Googleアカウント登録を統合
 * Cookieと端末固有情報（デバイスフィンガープリント）の取得、認証、Cookie処理、Googleアカウント登録、識別子由来の規制判定処理
 */
async function processIdentification(gb, request) {
  // 識別子システム構築完了まで無効化 by 忍 2025/07/03
  gb.settings.BBS_SHIKIBETSU = "off";

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
  const secure = !gb.isMonazilla ? "Secure;" : "";

  /*
   * 識別子システムが有効な板かどうか判定
   * 有効でなければ(BBS_SHIKIBETSU=off)、Cookie(PON)食いチェックだけして、return
   */
  if (gb.settings.BBS_SHIKIBETSU === "off") {
    gb.headers["Set-Cookie"] = `PON=${PON}; Max-Age=63072000; path=/; Domain=.3chbbs.com; ${secure} HttpOnly`;
    
    //if(!gb.cookies.PON) return dispError(gb, "ConfirmationPhase");

    return 1;
  }

  try {
    // 識別子システム（忍者データベース問い合わせ -> 各種処理）
    // ここの実装
  } catch (e) {
    // 識別子データ類に仮の値を入れる
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
  // アクセス規制リストはR2じゃなくてKVでも良いのではないかと思ったりする。更新頻度低めかつ反映も遅くていいし、読み取りが早い方が良いので
  // というかSETTING.TXTやhead.txtもKVでよくね？
  const object = await env.ch3www.get("deny.cgi");
  if(object !== null)
  {
    const DENYLIST = await object.text();
    // 書式->板dir名正規表現<>規制対象正規表現<>NGワード正規表現<>規制内容(mark -> Marked, TateDame -> スレ立て禁止, それ以外・省略 -> 規制, md5 -> 規制(非公開))
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
              return "ERROR: Madakana:このホスト/地域/端末では、スレッドが立てられません。。。（" + tmp + "）<br><a href=\"https://www.3chbbs.com/madakana/\">ここで告知されています。</a><br><br>Sorry Your ISP, region, and device are disallowed from starting a new thread due to repeated abuse. ->" + tmp;
            }else continue;
          }

          // 拒否られマークをつけるフラグ
          let deniedmark = 0;
        
          // mark指定の規制や、BBS_NO_MADAKANA=checkedの板・スレでは、拒否られマーク+規制情報をつけたうえで書き込みを許可する
          if(EXEC === "mark" || gb.settings.BBS_NO_MADAKANA === "checked") deniedmark = 1;

          const target = (EXEC !== "md5" && !deniedmark) ? tmp : md5(tmp);

          if (deniedmark) {
            if (NGWORD.source !== ".+")
            // NGワード系の場合
            {
              gb.form.MESSAGE += ` <small style="color: #999;">👀<br>Madakana(NGWord): Caution(${target})</small>`; 
            }
            else
            {
              gb.form.FROM = "</b>[´・ω・｀]<b> " + gb.form.FROM;
              gb.form.MESSAGE += `<hr><var style="color:blue;">Madakana:${target}</var>`; 
            }

            return 1;
          }
          // 上記のもの以外はエラー
          else
          {
            // エラー種別
            let ErrMsg = "アクセス規制中です！！（" + target + "）<br><a href=\"https://www.3chbbs.com/madakana/\">ここで告知されています。</a><br>Sorry Your ISP, region, and device are disallowed from posting due to repeated abuse. ->" + target;
            if(NGWORD.source !== ".+")
            // NGワード系の場合
            {
              // NGワード規制はBBS_NGWORD_PASS=checkedでスルー
              if(gb.settings.BBS_NGWORD_PASS === "checked") continue;
              ErrMsg = `NGワード禁止！(${target})<br><a href=\"https://www.3chbbs.com/madakana/\">ここで告知されています。</a><br>Your post contains forbidden words. -> ${target}`;//"
            }

            return dispError(gb, "Madakana:" + ErrMsg);
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

  //!ADMIN:DELETE=(レス番号)で該当レス削除

  // >>1は65,536文字(4096文字×16レス分)までOK
  if (gb.number === 1 && BBS_MESSAGE_COUNT < 65536) BBS_MESSAGE_COUNT = 65536;

  // 本文長制限
  if (gb.form.MESSAGE.length > BBS_MESSAGE_COUNT) return dispError(gb, `本文が長すぎます！(${gb.form.MESSAGE.length}/${BBS_MESSAGE_COUNT})`);

  // 行数制限(>>2以降)
  if (gb.number >= 2 && gb.form.MESSAGE.split("<br>").length > BBS_LINE_NUMBER * 2) return dispError(gb, `改行が多すぎます！(${gb.form.MESSAGE.split("<br>").length}/${BBS_LINE_NUMBER * 2})`);

  // 名無しをセット
  if (!gb.form.FROM) gb.form.FROM = gb.settings.BBS_NONAME_NAME || "名無しさん＠３ちゃんねる";

  return 1;
}

/* 
 * IDのところに表示する文字列と、識別IDを生成する
 * IDのところに表示する文字列は gb.id に格納され、
 * 識別IDは gb.sikibetsuId に格納される。
 */
async function makeIdAndShikibetuId(gb) {
  const idGroup = gb.settings.BBS_ID_GROUP ? gb.settings.BBS_ID_GROUP : gb.form.bbs;
  const BBS_ID = gb.settings.BBS_ID ? gb.settings.BBS_ID : "checked";
  const BBS_ID_RESET = gb.settings.BBS_ID_RESET ? gb.settings.BBS_ID_RESET : "86400";
  const BBS_ID_LV = gb.settings.BBS_ID_LV ? gb.settings.BBS_ID_LV : "";
  
  if (gb.cap) {
    gb.id = "ID:???";
  } else if (BBS_ID === "ip") {
    let idString = await getMd5IdString(gb.idnotane, idGroup, Math.floor((parseInt(gb.unixtime) + 32400)/BBS_ID_RESET), gb.md5Salt);
    gb.id = `ID:${idString}`;
    if (BBS_ID_LV === "checked") gb.id += gb.userData.lv >= 10 ? "10" : "L" + gb.userData.lv;
  }
  // 識別子からIDを生成
  else if (BBS_ID === "checked") {
    let idString = await getMd5IdString(gb.idnotane, idGroup, Math.floor((parseInt(gb.unixtime) + 32400)/BBS_ID_RESET), gb.md5Salt);
    gb.id = `ID:${idString}`;
    if (BBS_ID_LV === "checked") gb.id += gb.userData.lv >= 10 ? "10" : "L" + gb.userData.lv;
  } else if (BBS_ID === "siberia") {
    gb.id = `発信元:${gb.ip}`;
    if (BBS_ID_LV === "checked") gb.id += " " + (gb.userData.lv >= 10 ? "10" : "L" + gb.userData.lv);
  } else {
    if (BBS_ID_LV === "checked") gb.id += gb.userData.lv >= 10 ? "10" : "L" + gb.userData.lv;
  }
  const BBS_ID_SHIKIBETSU = gb.settings.BBS_ID_SHIKIBETSU ? gb.settings.BBS_ID_SHIKIBETSU : "checked";
  if (gb.cap) {
    gb.shikibetsuId = "★";
  } 
  else if (BBS_ID_SHIKIBETSU === "checked") {
    gb.shikibetsuId = `${gb.country}.${await getSha1Base64Id(gb.ken, idGroup, Math.floor((parseInt(gb.unixtime) + 32400)/BBS_ID_RESET), gb.md5Salt, 2)}.${await getSha1Base64Id(gb.city+"c", idGroup, Math.floor((parseInt(gb.unixtime) + 32400)/BBS_ID_RESET), gb.md5Salt, 2)} ${await getSha1Base64Id(gb.asn, idGroup, Math.floor((parseInt(gb.unixtime) + 32400)/BBS_ID_RESET), gb.md5Salt, 2)}-${md5(gb.userAgent+idGroup+Math.floor((parseInt(gb.unixtime) + 32400)/BBS_ID_RESET).toString()+gb.md5Salt).slice(0,16)}`; 
  }
  else if (BBS_ID_SHIKIBETSU === "raw") {
    gb.shikibetsuId = `${gb.country}.${gb.ken} ${gb.asn}`;
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
    gb.shikibetsuId = ``;
  }
  // 名前欄に加える
  gb.form.FROM += ` </b>(${gb.shikibetsuId})<b>`;
  // タイトルにIDを加える
  if (gb.number === 1) gb.subject += ` [${gb.id}★]`;
}

async function getSha1Base64Id(tane, group, date, salt, length) {
  const md5Id = md5(tane+group+date+salt);
  const sha1Base64Id = await sha1Base64(md5Id);
  return sha1Base64Id.slice(0, length);
}

async function getMd5IdString(tane, group, date, salt) {
  const md5Id = md5(tane+group+date+salt);
  const sha1Base64Id = await sha1Base64(md5Id);
  return sha1Base64Id.slice(0,8);
}

async function getSubject(gb, env) {
  const boardId = env.BOARD_DO.idFromName(gb.form.bbs);
  const boardStub = env.BOARD_DO.get(boardId);

  try {
    // Board DOからsubject.txtの内容を取得
    gb.subjectText = await boardStub.getSubject();
    gb.subjectEtag = "dummy-etag"; // EtagチェックはDO側で行うため、ここではダミーを設定

    return 1;
  } catch (e) {
    return dispError(gb, `getSubject error. 不明なエラーが発生しました。(unixtime:${gb.nowTime} bbs:${gb.form.bbs} key:${gb.form.key} number:${gb.number} errmsg:getSubject->${e})<br>このメッセージをコピペして、運用情報板で報告していただけるとありがたいです。`);
  }
}

/* 
 * 各種スレ立て規制 と subjectText更新 
 * gb.subjectText を利用する
 */
async function suretateTotalCheckAndUpdateSubject(gb, env) {
  const BBS_FORCE_SAGE = parseInt(gb.settings.BBS_FORCE_SAGE ? gb.settings.BBS_FORCE_SAGE : "9999999999");

  // sageの処理（スレ立て時はsage無効）
  if (gb.number >= 2) {
    if (gb.form.mail.includes("sage")) gb.sage = 1;
    if (parseInt(gb.form.key) + BBS_FORCE_SAGE < parseInt(gb.unixtime)) gb.sage = 1;
    if (gb.settings.BBS_LIVE && gb.settings.BBS_LIVE === "checked" && Math.random() * 10 >= 1) gb.sage = 1; // 実況板、実況スレでは10分の9の確率でsage
  }

  // Board DOに渡すスレッド情報を作成
  const threadData = {
    key: gb.form.key,
    title: gb.subject,
    resNum: gb.number,
  };
  // Board DOのスタブを取得
  const boardId = env.BOARD_DO.idFromName(gb.form.bbs);
  const boardStub = env.BOARD_DO.get(boardId);

  try {
    // Board DOにスレッド一覧の更新を依頼
    await boardStub.updateThread(threadData, gb.sage, parseInt(gb.boardSettings.BBS_MAX_MENU_THREAD || "700"));
    return 1;
  } catch (e) {
    return dispError(gb, `suretateTotalCheckAndUpdateSubject error. スレ一覧を更新できませんでした。(unixtime:${gb.time} bbs:${gb.form.bbs} key:${gb.form.key} number:${gb.number} errmsg:suretateTotalCheckAndUpdateSubject->${e})<br>このメッセージをコピペして、運用情報板で報告していただけるとありがたいです。`);
  }
}

// 
async function updateThreadFile(gb, env) {
  const threadId = env.THREAD_DO.idFromName(gb.form.key);
  const threadStub = env.THREAD_DO.get(threadId);

  try {
    // スレ立て時
    if (gb.number === 1) {
      let threadSettingsText = "";
      Object.keys(gb.settings).forEach(function (key) {
        threadSettingsText += `${key}=${gb.settings[key]}<>`;
      });
      // スレ設定を書き込む
      await threadStub.setSettings(threadSettingsText);
    }

    // 新しいレスのデータを作成
    const DATETIMEFORMAT = new Intl.DateTimeFormat('ja-JP', {year: "numeric", month: "2-digit", day: "2-digit", weekday:"short", hour: "2-digit", minute: "2-digit", second: "2-digit", timeZone: "Asia/Tokyo"});
    const date = DATETIMEFORMAT.format(new Date(parseInt(gb.unixtime) * 1000));

    const datLine = `${gb.form.FROM}<><>${date} ${gb.id}<>${gb.form.MESSAGE}<>${gb.number === 1 ? gb.subject : ''}`;
    const logLine = `${gb.userInfo}`;
    // Thread DOにレスの追加を依頼
    await threadStub.addRes(datLine, logLine);

    return 1;
  } catch (e) {
    return dispError(gb, `updateThreadFile error. ログファイルに書けませんでした。(unixtime:${gb.time} bbs:${gb.form.bbs} key:${gb.form.key} number:${gb.number} errmsg:updateThreadFile->${e})<br>このメッセージをコピペして、運用情報板で報告していただけるとありがたいです。`);
  }
}

// ？
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

      if (!resultUpdateKakologFile?.uploaded) return dispError(gb, `updateSubback error. 板飛びそうなので、またの機会にどうぞ。。。(unixtime:${gb.nowTime} bbs:${gb.form.bbs} key:${gb.form.key} number:${gb.number} errmsg:updateSubback->kakologList_update_conflicted!)`);

      const result = await env.ch3www.put(subbackFIle, subbackText, {
        onlyIf: { doesNotExist: true },
      });

      if (!result?.uploaded) return dispError(gb, `updateSubback error. 板飛びそうなので、またの機会にどうぞ。。。(unixtime:${gb.nowTime} bbs:${gb.form.bbs} key:${gb.form.key} number:${gb.number} errmsg:updateSubback->SubbackFIle_update_conflicted!)`);
    } else {
      const result = await env.ch3www.put(subbackFIle, subbackText, {
        onlyIf: { etagMatches: subbackFileEtag },
      });

      if (!result?.uploaded) return dispError(gb, `updateSubback error. 板飛びそうなので、またの機会にどうぞ。。。(unixtime:${gb.nowTime} bbs:${gb.form.bbs} key:${gb.form.key} number:${gb.number} errmsg:updateSubback->SubbackFIle_update_conflicted!)`);
    }

    return 1;
  } catch (e) {
    return dispError(gb, `updateSubback error. 不明なエラーが発生しました。(unixtime:${gb.nowTime} bbs:${gb.form.bbs} key:${gb.form.key} number:${gb.number} errmsg:updateSubback->${e})<br>このメッセージをコピペして、運用情報板で報告していただけるとありがたいです。`);
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
    return dispError(gb, `updateHeadline error. 不明なエラーが発生しました。(unixtime:${gb.nowTime} bbs:${gb.form.bbs} key:${gb.form.key} number:${gb.number} errmsg:updateHeadline->${e})<br>このメッセージをコピペして、運用情報板で報告していただけるとありがたいです。`);
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
