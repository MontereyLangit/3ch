import { Context } from "hono";
import Encoding from "encoding-japanese";
import { BbsCgiViewModel } from "@/model/bbs_cgi_view_model";
import { decodeSJIS } from "@/utils/common_utils";
import { Bbs } from "@/durable_objects/bbs";

/**
 * bbs.cgiサービス
 * @param {Context<{ Bindings: CloudflareBindings }>} c
 * @param {BbsCgiViewModel} model
 */
export async function post(c: Context<{ Bindings: CloudflareBindings }>, model: BbsCgiViewModel) {
    // モデルを初期化
    model.init(c);

    // 最初のチェック
    initialCheck(c, model);
    if (model.hasErrors()) return;

    // Formの読み込み
    await readForm(c, model);
    if (model.hasErrors()) return;

    // 板設定読み込み
    await loadBoardSettings(c, model);
    if (model.hasErrors()) return;

    // フォームのバリデーション
    // validateForm(model);
    // if (model.hasErrors()) return;

    // キャップの処理
    // await processCap(c, model);
    // if (model.hasErrors()) return;

    // トリップ処理
    // await processTrip(c, model);
    // if (model.hasErrors()) return;
}

/**
 * 一番はじめのチェック
 * UserAgent、CH-UAはここで取得される
 * 歴史的事情や最近の動向がいろいろあるので、更新時には注意すること
 * @param {Context<{ Bindings: CloudflareBindings }>} c
 * @param {BbsCgiViewModel} model
 */
function initialCheck(c: Context<{ Bindings: CloudflareBindings }>, model: BbsCgiViewModel) {
    // POSTメソッド以外はエラー
    if (c.req.method !== "POST") {
        model.status = "error";
        model.errMsg = "POSTメソッド以外は許可されていません";
        return;
    }

    // content-typeがapplication/x-www-form-urlencoded以外はエラー
    const contentType = c.req.header("content-type") || "";
    if (!contentType.includes("application/x-www-form-urlencoded")) {
        model.status = "error";
        model.errMsg = "content-typeがapplication/x-www-form-urlencodedではありません";
        return;
    }

    // refererは/^https?:\/\/www\.3chbbs\.com/にマッチしないとだめ
    const referer = c.req.header("referer") || "";
    if (!referer.match(/^https?:\/\/www\.3chbbs\.com/)) {
        model.status = "error";
        model.errMsg = "不正なリファラです";
        return;
    }

    // UserAgentは7byte~384byteの半角文字のみ可
    const userAgent = c.req.header("user-agent") || "";
    if (userAgent.length < 7 || userAgent.length > 384 || !userAgent.match(/^[\x20-\x7E]+$/)) {
        model.status = "error";
        model.errMsg = "不正なUserAgentです";
        return;
    }

    // User Agent Client Hintsのブラウザ情報部分をUAに追加 (sec-ch-ua-full-version-list、無いときはsec-ch-ua)
    const secChUa = c.req.header("sec-ch-ua-full-version-list") || c.req.header("sec-ch-ua") || "";
    model.userAgent = userAgent + " " + secChUa;

    // acceptヘッダなどのブラウザ情報などをbrowserInfoに追加
    const accept = c.req.header("accept") || "";
    const acceptLanguage = c.req.header("accept-language") || "";
    const acceptEncoding = c.req.header("accept-encoding") || "";
    model.browserInfo = `Accept:${accept}<>Accept-Language:${acceptLanguage}<>Accept-Encoding:${acceptEncoding}`;

    // 機種・OS情報をdeviceInfoに追加(sec-ch-ua-platform、sec-ch-ua-modelが無いときはUserAgentから推測)
    model.deviceInfo = (c.req.header('sec-ch-ua-platform') ?? getPlatformNameByUserAgent(model.userAgent)) + "_";
    model.deviceInfo += (c.req.header('sec-ch-ua-model') ?? getAndroidModelNameByUserAgent(model.userAgent)) + "_";

    // 2ch専用ブラウザ(Monazilla/1.00)かどうかを判定
    model.isMonazilla = /^Monazilla\/1\.00/.test(model.userAgent);

    // IPをモデルにセット、IPv6接続チェック
    model.isIPv6 = model.ip.includes(":");

    // ID生成用の種をセット(IPv6の場合は前4オクテット)
    model.idSeed = model.isIPv6 ? model.ip.split(":").slice(0, 4).join(":") : model.ip;

    // 簡易Proxyチェック
    const clientIp = c.req.header("client-ip") || "";

    if (clientIp) {
        model.status = "error";
        model.errMsg = "Proxyからの書き込みは許可されていません";
        return;
    }
    
    const xForwardedFor = c.req.header("x-forwarded-for") || "";

    if (xForwardedFor && xForwardedFor.split(",")[0].trim() !== model.ip) {
        model.status = "error";
        model.errMsg = "Proxyからの書き込みは許可されていません";
        return;
    }

    const forwarded = c.req.header("forwarded") || "";

    if (forwarded) {
        const fMatch = forwarded.match(/for=([^;]+)/);

        if (fMatch && fMatch[1] && fMatch[1] !== model.ip) {
            model.status = "error";
            model.errMsg = "Proxyからの書き込みは許可されていません";
            return;
        }
    }

    const via = c.req.header("via") || "";

    if (via && !via.includes(model.ip)) {
        model.status = "error";
        model.errMsg = "Proxyからの書き込みは許可されていません";
        return;
    }
}

/**
 * UserAgentからOS名を取得します
 * @param {String} userAgent
 * @return {String} OS名
 */
function getPlatformNameByUserAgent(userAgent: string): string {
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

/**
 * UserAgentからAndroidのモデル名を取得します
 * @param {String} userAgent
 * @return {String} モデル名
 */
function getAndroidModelNameByUserAgent(userAgent: string): string {
    // Androidでない場合はスルー
    if (!/Android/.test(userAgent)) return "";

    // Mozilla/5.0 (Linux; Android 11; Pixel 4a Build/RP1A.200720.011; wv) ...
    const match = userAgent.match(/\((?:Linux; )?Android [\d\.]+; ([^;]+?)\s+Build/i);

    if (match && match[1]) {
        return match[1].trim();
    }

    // 機種名が Build より前にない場合
    const altMatch = userAgent.match(/\((?:Linux; )?Android [^;]+; ([^;]+)\)/i);

    if (altMatch && altMatch[1]) {
        return altMatch[1].trim();
    }

    return "Unknown";
}

/**
 * Formを取得します(UTF-8以外にも対応)
 * @param {Context<{ Bindings: CloudflareBindings }>} c
 * @param {BbsCgiViewModel} model
 */
async function readForm(c: Context<{ Bindings: CloudflareBindings }>, model: BbsCgiViewModel) {
    // bodyTextを取得 -> Encoding.urlDecode -> UTF8以外ならEncoding.convert -> formにセット
    const bodyText = await c.req.text();

    // CONTENT_LENGTHが65535バイト以上はエラー
    if (bodyText.length > 65535) {
        model.status = "error";
        model.errMsg = "文長杉！";
        return;
    }

    const pairs = bodyText.split("&");

    for (const pair of pairs) {
        if (!pair.includes("=")) continue;

        const [key, v] = pair.split("=");
        if (!key) continue;

        let value = v || "";

        try {
            if (Encoding.detect(value) === "UTF8") {
                value = decodeURIComponent(value.replace(/\+/g, " "));
            } else {
                const decoded = Encoding.urlDecode(value);
                value = Encoding.convert(decoded, {
                    to: "UTF8",
                    type: "string",
                    fallback: "html-entity"
                }).replace(/\+/g, " ");
            }
        } catch (e) {
            model.status = "error";
            model.errMsg = `送信された内容が破損しています: ${e instanceof Error ? e.message : String(e)}`;
            return;
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
            .replace(/[\x00-\x1F\x7F]/g, "");

        if (key in model.form) {
            model.form[key as keyof typeof model.form] = value;
        }
    }

    // 偽キャップ、偽トリップ変換
    model.form.FROM = model.form.FROM.replace(/★/g, "☆").replace(/◆/g, "◇").replace(/＃/g, "#");
    model.form.mail = model.form.mail.replace(/＃/g, "#");

    // １行データからは改行を削ってタグを閉じます
    model.form.subject = model.form.subject.replace(/ ?<br> ?/g, "");
    model.form.FROM = model.form.FROM.replace(/ ?<br> ?/g, "");
    model.form.mail = model.form.mail.replace(/ ?<br> ?/g, "");
    model.form.submit = model.form.submit.replace(/ ?<br> ?/g, "");

    model.form.bbs = model.form.bbs.replace(/\W/g, "");
    model.form.key = model.form.key.replace(/\D/g, "");
    model.form.time = model.form.time.replace(/\D/g, "");

    // 板名が空でないこと
    if (!model.form.bbs) {
        model.status = "error";
        model.errMsg = "板名が指定されていません";
        return;
    }

    // 本文が空でないこと
    if (!model.form.MESSAGE) {
        model.status = "error";
        model.errMsg = "本文が空です";
        return;
    }

    // keyとsubjectが両方空、または両方ある場合はエラー
    // keyは10桁の数字であること
    if ((model.form.key && model.form.subject) || (!model.form.key && !model.form.subject)
            || (model.form.key && !/^\d{1,10}$/.test(model.form.key))) {
        model.status = "error";
        model.errMsg = "keyとsubjectの指定が不正です";
        return;
    }

    // timeが空、または数字以外が含まれている場合はエラー
    if (!model.form.time || !/^\d+$/.test(model.form.time)) {
        model.status = "error";
        model.errMsg = "timeの指定が不正です";
        return;
    }

    // subjectがある場合、keyにmodel.unixtimeをセット
    if (model.form.subject) {
        model.form.key = model.unixtime;
    }
}

/**
 * URLデコード関数（Shift_JIS用）
 * @param {string} encodedURIComponent
 * @return {string} デコード化された文字列
 */
function decodeSjisURIComponent(encodedURIComponent: string): string {
    const bytes: number[] = [];
    for (let i = 0; i < encodedURIComponent.length;) {
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



/**
 * 板Durable_Objectsから板設定を取得し、model.boardSettingsにセットします
 * @param {Context<{ Bindings: CloudflareBindings }>} c
 * @param {BbsCgiViewModel} model
 */
async function loadBoardSettings(c: Context<{ Bindings: CloudflareBindings }>, model: BbsCgiViewModel) {
    // スレ立て時以外はスルー
    if (!model.form.subject) return;

    const bbs = c.env.BBS.getByName(model.form.bbs) as unknown as Bbs;
    const settingText = await bbs.getSettingText();
    const lines = settingText.split("\n");

    for (const line of lines) {
        const [key, value] = line.split("=");
        if (key) {
            model.boardSettings[key.trim()] = value ? value.trim() : "";
        }
    }
}

/**
 * Formのバリデーション
 * @param {BbsCgiViewModel} model
 */
function validateForm(model: BbsCgiViewModel) {
    const bbsNameCount = parseInt(model.settings.BBS_NAME_COUNT || "64");

    // FROMが bbsNameCount 以上はエラー
    if (model.form.FROM.length > bbsNameCount) {
        model.status = "error";
        model.errMsg = "名前が長すぎます";
        return;
    }
}
