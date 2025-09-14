////@ts-check
import { apiKey } from "../config.js";
import { sha1Base64, md5 } from "../utils/crypto_utils.js";

/**
 * 認証用JSを生成するための事前処理
 * @param {Request} request
 * @param {*} env
 * @param {import("../model/auth_view_model.js").authViewModel} model
 */
export async function prepareAuthJs(request, env, model) {
    const url = new URL(request.url);
    const hap = url.searchParams.get("hap") || "";
    if (!hap) {
        model.error = "HAPがありません";
        return model;
    }

    const ninja = await env.NINJA.get(hap);
    if (!ninja) {
        model.error = "忍者IDが見つかりません";
        return model;
    }

    const userData = ninja.split("<>");
    if (userData[0] !== "AUTH" || !userData[7]) {
        model.error = "不正な忍者IDです";
        return model;
    }

    const timeElapsed = Math.floor(Date.now() / 1000) - parseInt(userData[2]);
    if (timeElapsed > 300) {
        model.error = "認証の有効期限が切れています";
        return model;
    }

    const ip = request.headers.get("CF-Connecting-IP");
    const token = crypto.randomUUID();
    const authSig = await sha1Base64(md5(`${apiKey}.${ip}.${hap}.${token}.${md5(env.SALT)}`));
    const currentUnixTimeString = Math.floor(Date.now() / 1000).toString();

    // KVを更新
    await env.NINJA.put(hap, `AUTH2<>${userData[1]}<>${currentUnixTimeString}<>${userData[3]}<>${userData[4]}<>${userData[5]}<>${userData[6]}<>${userData[7]}`);

    model.time = currentUnixTimeString;
    model.token = token;
    model.authSig = authSig;
}

/**
 * 認証処理
 * @param {Request} request
 * @param {*} env
 * @param {import("../model/auth_view_model.js").authViewModel} model
 */
export async function authenticateUser(request, env, model) {
    const formData = await request.formData();
    model.hap = formData.get("hap") || "";
    model.turnstileToken = formData.get("cf-turnstile-response") || "";
    model.validate = formData.get("validate") || "";
    model.deviceInformation = formData.get("d") || "";
    model.cert = formData.get("cert") || "";
    model.time = formData.get("time") || "";
    model.authSig = formData.get("authSig") || "";
    model.token = formData.get("token") || "";
    model.ip = request.headers.get("CF-Connecting-IP") || "";
    model.asn = request.cf?.asn.toString() ?? "0000";
    model.country = request.cf?.country ?? "unknown";
    model.ken = request.cf?.region ?? "catv?";
    model.city = request.cf?.city ?? "";

    // Turnstile 検証
    const turnstileResult = await verifyTurnstile(model.turnstileToken, env.TURNSTILE_SECRET_KEY, model.ip);
    if (!turnstileResult.success) {
        model.error = "Turnstile認証に失敗しました: " + JSON.stringify(turnstileResult);
        return model;
    }

    // cert 検証
    const expectedCert = await generateCertFromFormData(formData);
    if (expectedCert !== model.cert) {
        model.error = "不正な認証です（cert）";
        return model;
    }

    // authSig 検証
    const expectedAuthSig = await sha1Base64(md5(`${apiKey}.${model.ip}.${model.hap}.${model.token}.${md5(env.SALT)}`));
    if (expectedAuthSig !== model.authSig) {
        model.error = "不正な認証です（authSig）";
        return model;
    }

    // 忍者ID(HAP)の検証
    const ninja = await env.NINJA.get(model.hap);
    if (!ninja) {
        model.error = "認証が早すぎます（忍者IDを取得できませんでした）";
        return model;
    }

    const userData = ninja.split("<>");
    if (userData[0] !== "AUTH2" || !userData[7]) {
        model.error = "不正な認証です（e4）";
        return model;
    }

    const timeElapsed = Math.floor(Date.now() / 1000) - parseInt(userData[2]);
    if (timeElapsed > 300) {
        model.error = "認証が時間切れです。（5分以上経過）";
        return model;
    }

    if (model.validate !== userData[1]) {
        model.error = "認証コードが一致しません";
        return model;
    }

    // 認証成功。NINJA KVを更新
    const userAgentHash = userData[7];
    await env.NINJA.put(model.hap, `${model.deviceInformation}<>${userAgentHash}<>${model.ip}<>${Math.floor(Date.now() / 1000).toString()}`);

    model.body = "認証成功";
}

async function verifyTurnstile(token, secret, ip) {
    const formData = new FormData();
    formData.append('secret', secret);
    formData.append('response', token);
    formData.append('remoteip', ip);
    const result = await fetch('https://challenges.cloudflare.com/turnstile/v0/siteverify', { body: formData, method: 'POST' });
    return await result.json();
}

async function generateCertFromFormData(formData) {
    const concatenated = Array.from(formData.entries()).sort(([a], [b]) => a.localeCompare(b)).map(([key, value]) => key + "=" + value).join("\\n");
    const hashBuffer = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(concatenated));
    return Array.from(new Uint8Array(hashBuffer)).map((b) => b.toString(16).padStart(2, "0")).join("");
}
