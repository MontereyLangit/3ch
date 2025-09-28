import { Context } from "hono";
import { AuthViewModel } from "@/model/view/AuthViewModel";
import { sha1Base64, md5 } from "@/utils/cryptoUtils";
import { User } from "@/durable_objects/User";

export const apiKey = "6cde9d4cef741d82da3de7d179fe015bb0da538f334e3e98664f1277c23d8bd3";

/**
 * 認証用JSを生成するための事前処理
 * @param {Context<{ Bindings: CloudflareBindings }>} c
 * @param {AuthViewModel} model
 */
export async function prepareAuthJs(c: Context<{ Bindings: CloudflareBindings }>, model: AuthViewModel) {
    const url = new URL(c.req.url);
    model.hap = url.searchParams.get("hap") || "";

    if (!model.hap) {
        model.error = "HAP is not found.";
        return;
    }

    const doId = c.env.USER.idFromString(model.hap);
    const userObject = c.env.USER.get(doId) as unknown as User;
    model.userData = await userObject.getRawData(); // getRawDataはuser.tsに新規追加

    const validation = model.validateViewModel('prepare');

    if (!validation.valid) {
        model.error = validation.errorMsg || "不明なエラー";
        return;
    }

    // バリデーションが通れば、ninjaデータは有効
    const userData = model.userData.split("<>");

    const ip = c.req.header("CF-Connecting-IP") || "";
    const token = crypto.randomUUID();
    const authSig = await sha1Base64(md5(`${apiKey}.${ip}.${model.hap}.${token}.${md5(c.env.salt)}`));
    const currentUnixTimeString = Math.floor(Date.now() / 1000).toString();

    // Durable Objectを更新
    await userObject.updateAuthStatus(`AUTH2<>${userData[1]}<>${currentUnixTimeString}<>${userData[3]}<>${userData[4]}<>${userData[5]}<>${userData[6]}<>${userData[7]}`);

    model.time = currentUnixTimeString;
    model.token = token;
    model.authSig = authSig;
}

/**
 * 認証処理
 * @param {Context<{ Bindings: CloudflareBindings }>} c
 * @param {AuthViewModel} model
 * @returns {Promise<void>}
 */
export async function authenticateUser(c: Context<{ Bindings: CloudflareBindings }>, model: AuthViewModel): Promise<void> {
    const formData = await c.req.formData();
    model.hap = (formData.get("hap") as string) || "";
    model.turnstileToken = (formData.get("cf-turnstile-response") as string) || "";
    model.validate = (formData.get("validate") as string) || "";
    model.deviceInformation = (formData.get("d") as string) || "";
    model.cert = (formData.get("cert") as string) || "";
    model.time = (formData.get("time") as string) || "";
    model.authSig = (formData.get("authSig") as string) || "";
    model.token = (formData.get("token") as string) || "";
    model.ip = c.req.header("CF-Connecting-IP") || "";
    model.asn = c.req.raw.cf?.asn?.toString() ?? "0000";
    model.country = typeof c.req.raw.cf?.country === "string" ? c.req.raw.cf.country : "unknown";
    model.ken = typeof c.req.raw.cf?.region === "string" ? c.req.raw.cf.region : "catv?";
    model.city = typeof c.req.raw.cf?.city === "string" ? c.req.raw.cf.city : "";

    if (!model.hap) {
        model.error = "HAP is not found.";
        return;
    }

    const doId = c.env.USER.idFromString(model.hap);
    const userObject = c.env.USER.get(doId) as unknown as User;
    model.userData = await userObject.getRawData(); // getRawDataはuser.tsに新規追加

    const validation = model.validateViewModel('authenticate');

    if (!validation.valid) {
        model.error = validation.errorMsg || "不明なエラー";
        return;
    }

    // バリデーションが通れば、ninjaデータは有効
    // 認証成功。USER Durable Objectを更新
    await userObject.register(model.hap, model.ip, model.deviceInformation);

    model.body = "認証成功";
}
