import { Context } from 'hono';

export class PostViewModel {
    status: "success" | "confirmation" | "error" = "success";
    FROM = "";
    mail = "";
    MESSAGE = "";
    subject = "";
    bbs = "";
    key = "";
    host: string = "";
    isSJIS: boolean = false;
    confirmationText: string = "";
    errMsg: string = "";

    init(c: Context<{ Bindings: CloudflareBindings }>) {
        const ip = c.req.header("CF-Connecting-IP") || "";
        this.host = ip;
    }

    // エラーまたは確認画面表示の場合、true
    hasErrors(): boolean {
        return this.status !== "success";
    }

    get charset(): "UTF-8" | "Shift_JIS" {
        return this.isSJIS ? "Shift_JIS" : "UTF-8";
    }
}
