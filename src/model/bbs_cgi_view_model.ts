import { Context } from 'hono';

// Define CloudflareBindings type if not already defined or import it from your types
type CloudflareBindings = Record<string, unknown>;

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
    version: string = "2025/7/31";
    error: string = "";
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
    isIPv6: number = 0;
    isPC: number = 0;
    isMonazilla: number = 0;
    isSJIS: boolean = false;
    idnotane: string = "";
    md5Salt: string = "";
    tripKey: string = "";
    tripString: string = "";
    cap: number = 0;
    strongCap: number = 0;
    sage: number = 0;
    path: string = "PATH";
    logPath: string = "LOGPATH";
    subject: string = "";
    number: number = 0;
    logFile: string = "LOGDAT";
    logData: string = "";
    logEtag?: string;
    outdat: string = "";
    settings: Record<string, any> = {};
    boardSettings: Record<string, any> = {};
    origSettings: Record<string, any> = {};
    headers: Record<string, string> = {};
    confirmationText: string = "";
    errMsg: string = "";
    bbs: string = "";
    key: string = "";

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
        this.md5Salt = c.env.SALT as string; // HonoのContextから環境変数を取得
    }

    hasErrors(): boolean {
        return this.error !== "";
    }

    get charset(): "UTF-8" | "Shift_JIS" {
        return this.isSJIS ? "Shift_JIS" : "UTF-8";
    }
}
