////@ts-check
import { dispError } from "./service.js";

/**
 * リクエストの初期検証を行い、結果をViewModelに格納します。
 * @param {import("../model/bbs_cgi_view_model.js").BbsCgiViewModel} model
 * @param {Request} request
 * @returns {boolean}
 */
export function checkRequest(model, request) {
    // 1. POSTメソッド、Content-Type、Refererの検証
    if (request.method !== 'POST') {
        return dispError(model, "POST メソッドを使ってください。<br>\nRecieve GET METHOD");
    }

    const contentType = request.headers.get("content-type") || "";
    if (!contentType.includes("application/x-www-form-urlencoded")) {
        return dispError(model, "送信された内容が破損しています。->" + contentType + "<br>\nBroken request body. ");
    }

    const referer = request.headers.get('referer') || '';
    if (!/^https?:\/\/www\.3chbbs\.com/.test(referer)) {
        return dispError(model, "あなた騙されてますよ？->" + referer + "<br>\nReferer is invalid.");
    }

    // 2. User-Agentの長さと文字種の検証
    if (model.userAgent.length < 7 || model.userAgent.length > 384 || /[^ -~]/.test(model.userAgent)) {
        return dispError(model, "ブラウザ変ですよん。(ua)<br>UserAgent: " + model.userAgent + "<br>\nUserAgent is invalid.");
    }

    // 3. User Agent Client Hintsの取得と追加
    model.userAgent += request.headers.get('sec-ch-ua-full-version-list') ?? request.headers.get('sec-ch-ua') ?? "";

    // 4. 2ch専用ブラウザ Monazilla/1.00 かどうかを判定
    if (/^Monazilla\/1.00/.test(model.userAgent)) {
        model.isMonazilla = true;
    }

    // 5. IPv6アドレスかどうかを判定し、ID生成の元になる文字列 (idnotane) を設定
    if (model.ip.includes(":")) {
        model.isIPv6 = true;
        const octet = model.ip.split(":");
        model.idnotane = `${octet[0]}:${octet[1]}:${octet[2]}:${octet[3]}`;
    } else {
        model.idnotane = model.ip;
    }

    // 6. 簡易的なプロキシチェック
    if (request.headers.get("client-ip")) {
        return dispError(model, "変な情報が送られて来ました。<br>Client_IP: " + request.headers.get("client-ip"));
    }

    model.host = model.ip;
    const xForwardedFor = request.headers.get("x-forwarded-for");
    if (xForwardedFor && model.ip !== xForwardedFor) {
        model.host += `<${xForwardedFor}>`;
    } else {
        const forwarded = request.headers.get("forwarded");
        if (forwarded && model.ip !== forwarded) {
            model.host += `<${forwarded}>`;
        } else {
            const via = request.headers.get("via");
            if (via && model.ip !== via) {
                model.host += `<${via}>`;
            }
        }
    }

    return true;
}

/**
 * User-Agent文字列からOSのプラットフォーム名を取得するヘルパー関数
 * @param {string} userAgent
 */
function getPlatformByUserAgent(userAgent) {
    if (/iPad|iPhone|iPod/.test(userAgent)) return "iOS";
    if (/Android/.test(userAgent)) return "Android";
    if (/Windows/.test(userAgent)) return "Windows";
    if (/Macintosh/.test(userAgent)) return "Mac";
    if (/Linux/.test(userAgent)) return "Linux";
    return "Other";
}
