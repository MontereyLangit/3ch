////@ts-check
import { apiKey } from "../config.js";
import { validateAuthViewModel } from "../model/auth_view_model.js";
import { sha1Base64, md5 } from "../utils/crypto_utils.js";

/**
 * 認証用JSを生成するための事前処理
 * @param {Request} request
 * @param {*} env
 * @param {import("../model/auth_view_model.js").authViewModel} model
 */
export async function prepareAuthJs(request, env, model) {
    const url = new URL(request.url);    model.hap = url.searchParams.get("hap") || "";

    const doId = env.USER.idFromString(model.hap);
    const userObject = env.USER.get(doId);
    model.userData = await userObject.getRawData(); // getRawDataはuser.jsに新規追加

    const validation = validateAuthViewModel(model, 'prepare');
    
    if (!validation.valid) {
        model.error = validation.errorMsg;
        return;
    }
    
    // バリデーションが通れば、ninjaデータは有効
    const userData = model.userData.split("<>");

    const ip = request.headers.get("CF-Connecting-IP");
    const token = crypto.randomUUID();
    const authSig = await sha1Base64(md5(`${apiKey}.${ip}.${model.hap}.${token}.${md5(env.SALT)}`));
    const currentUnixTimeString = Math.floor(Date.now() / 1000).toString();

    // Durable Objectを更新
    await userObject.updateAuthStatus(`AUTH2<>${userData[1]}<>${currentUnixTimeString}<>${userData[3]}<>${userData[4]}<>${userData[5]}<>${userData[6]}<>${userData[7]}`);

    model.time = currentUnixTimeString;
    model.token = token;
    model.authSig = authSig;
}

/**
 * 認証処理
 * @param {Request} request
 * @param {*} env
 * @param {import("../model/auth_view_model.js").authViewModel} model
 * @returns {Promise<void>}
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

    const doId = env.USER.idFromString(model.hap);
    const userObject = env.USER.get(doId);
    model.userData = await userObject.getRawData(); // getRawDataはuser.jsに新規追加

    const validation = validateAuthViewModel(model, 'authenticate');

    if (!validation.valid) {
        model.error = validation.errorMsg;
        return;
    }

    // バリデーションが通れば、ninjaデータは有効
    const userData = model.userData.split("<>");

    // 認証成功。USER Durable Objectを更新
    const userAgentHash = userData[7];
    await userObject.register(model.hap, model.ip, model.deviceInformation);

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
