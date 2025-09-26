import { Context } from 'hono';

export interface BbsCgiForm {
    FROM: string;
    mail: string;
    MESSAGE: string;
    subject: string;
    bbs: string;
    key: string;
    time: string;
    submit: string;
    [key: string]: string;
}

export class BbsCgiViewModel {
    status: "success" | "confirmation" | "error" = "success";
    time: string = "";
    unixtime: string = "";
    form: BbsCgiForm = {
        FROM: "",
        mail: "",
        MESSAGE: "",
        subject: "",
        bbs: "",
        key: "",
        time: "",
        submit: ""
    };
    cookies: Record<string, string> = { HAP: "", PON: "", time: "", PIN: "" };
    host: string = "";
    ip: string = "";
    userAgent: string = "";
    browserInfo: string = "";
    deviceInfo: string = "";
    asn: string | number = "0000";
    country: string = "unknown";
    ken: string = "catv?";
    city: string = "unknown";
    deviceId: string = "";
    userData: { D: string; U: string; firstIp: string; created: string; } = { D: "", U: "", firstIp: "", created: "" };
    userInfo: string = "";
    shikibetsuId: string = "";
    id: string = "";
    isIPv6: boolean = false;
    isMonazilla: boolean = false;
    isSJIS: boolean = false;
    idSeed: string = "";
    md5Salt: string = "";
    tripKey: string = "";
    tripString: string = "";
    isCapUser: boolean = false;
    isStrongCapUser: boolean = false;
    isSage: boolean = false;
    path: string = "PATH";
    logPath: string = "LOGPATH";
    threadTitle: string = "";
    number: number = 0;
    logFile: string = "LOGDAT";
    logData: string = "";
    logEtag?: string;
    outdat: string = "";
    settings: Record<string, any> = {};
    boardSettings: Record<string, any> = {};
    headers: Record<string, string> = {};
    confirmationText: string = "";
    errMsg: string = "";

    init(c: Context<{ Bindings: CloudflareBindings }>) {
        this.time = new Date().getTime().toString();
        this.unixtime = Math.floor(parseInt(this.time) / 1000).toString();
        this.ip = c.req.header("CF-Connecting-IP") || "";
        this.host = this.ip;
        this.userAgent = c.req.header("user-agent") || "";
        const cf = (c.req.raw as any).cf || {};
        this.asn = cf.asn || "0000";
        this.country = cf.country || "unknown";
        this.ken = cf.region || "catv?";
        this.city = cf.city || "unknown";
        this.userData.U = this.userAgent;
        this.userData.firstIp = this.ip;
        this.userData.created = this.unixtime;
        this.md5Salt = c.env.salt as string; // HonoのContextから環境変数を取得
    }

    // エラーまたは確認画面表示の場合、true
    hasErrors(): boolean {
        return this.status !== "success";
    }

    get charset(): "UTF-8" | "Shift_JIS" {
        return this.isSJIS ? "Shift_JIS" : "UTF-8";
    }
}
