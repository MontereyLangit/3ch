import { Context } from "hono";
import { Bbs } from "@/durable_objects/Bbs";
import { PostProcessModel } from "@/model/PostProcessModel";
import { PostForm, PostFormSchema } from "@/model/form/PostForm";
import { Poster } from "@/model/user/Poster";
import { PostViewModel } from "@/model/view/PostViewModel";
import Encoding from "encoding-japanese";
import { Thread } from "@/durable_objects/Thread";
import { User } from "@/durable_objects/User";

/**
 * 書き込みサービス
 */
export class PostService {
    c: Context<{ Bindings: CloudflareBindings }>;
    processModel: PostProcessModel;
    postForm: PostForm;
    poster: Poster;
    bbs?: Bbs;
    thread?: Thread;
    user?: User;

    /**
     * コンストラクタ
     * @param {Context<{ Bindings: CloudflareBindings }>} c Hono Context
     * @param {PostProcessModel} processModel 書き込み処理モデル
     * @param {Poster} poster 投稿者モデル
     */
    constructor(c: Context<{ Bindings: CloudflareBindings }>, processModel: PostProcessModel, poster: Poster) {
        this.c = c;
        this.processModel = processModel;
        this.poster = poster;
        this.postForm = {
            FROM: "",
            mail: "",
            MESSAGE: "",
            subject: "",
            bbs: "",
            key: "",
            time: "",
            submit: "",
        };
    }

    /**
     * リクエスト情報をPostProcessModelにセットします
     */
    preparePostProcessModel() {
        this.processModel.unixTime = Math.floor(new Date().getTime() / 1000).toString();
        this.processModel.md5Salt = this.c.env.salt;

        this.initialCheck();
    }

    /**
     * 投稿者情報を設定します
     * @param poster 投稿者モデル
     */
    preparePosterInfo() {
        // UserAgentは7byte~384byteの半角文字のみ可
        const userAgent = this.c.req.header("user-agent") || "";
        if (userAgent.length < 7 || userAgent.length > 384 || !userAgent.match(/^[\x20-\x7E]+$/)) {
            this.processModel.addError("不正なUserAgentです");
            return;
        }

        // User Agent Client Hintsのブラウザ情報部分をUAに追加 (sec-ch-ua-full-version-list、無いときはsec-ch-ua)
        const secChUa = this.c.req.header("sec-ch-ua-full-version-list") || this.c.req.header("sec-ch-ua") || "";
        this.poster.userAgent = userAgent + " " + secChUa;

        // acceptヘッダなどのブラウザ情報などをbrowserInfoに追加
        const accept = this.c.req.header("accept") || "";
        const acceptLanguage = this.c.req.header("accept-language") || "";
        const acceptEncoding = this.c.req.header("accept-encoding") || "";
        this.poster.browserInfo = `Accept:${accept}<>Accept-Language:${acceptLanguage}<>Accept-Encoding:${acceptEncoding}`;

        // 機種・OS情報をdeviceInfoに追加(sec-ch-ua-platform、sec-ch-ua-modelが無いときはUserAgentから推測)
        this.poster.deviceInfo = (this.c.req.header('sec-ch-ua-platform') ?? getPlatformNameByUserAgent(this.poster.userAgent)) + "_";
        this.poster.deviceInfo += (this.c.req.header('sec-ch-ua-model') ?? getAndroidModelNameByUserAgent(this.poster.userAgent)) + "_";

        // 2ch専用ブラウザ(Monazilla/1.00)かどうかを判定
        this.poster.isMonazilla = /^Monazilla\/1\.00/.test(this.poster.userAgent);

        // IPをモデルにセット、IPv6接続チェック
        this.poster.isIPv6 = this.poster.ip.includes(":");

        // ID生成用の種をセット(IPv6の場合は前4オクテット)
        this.poster.idSeed = this.poster.isIPv6 ? this.poster.ip.split(":").slice(0, 4).join(":") : this.poster.ip;
    }

    /**
     * 板Durable Objectを生成します
     */
    private createBbsObject() {
        if (!this.postForm) {
            throw new Error("Exception: postForm is not created.");
        }

        const postForm = this.postForm;
        this.bbs = this.c.env.BBS.getByName(postForm.bbs) as unknown as Bbs;
    }

    /**
     * 投稿処理を実行します
     * 投稿規制で書き込み処理モデルにエラーがセットされた場合、その時点で処理を中断します
     */
    async post() {
        if (!this.postForm) {
            throw new Error("Exception: postForm is not created.");
        }

        // Durable Objects生成
        this.createBbsObject();
        await this.createThreadObject();
        // 板設定読み込み
        await this.loadBoardSettings();
        // スレ設定読み込み
        await this.loadThreadSettings();
        // フォームのバリデーション
        this.validateForm();
        if (this.processModel.hasErrors()) return;
        // 規制チェック
        await this.executeRegulationChecks();
        if (this.processModel.hasErrors()) return;
        // ID、識別ID生成
        this.createPosterIdentifier();
        // コマンド処理
        await this.processCommands();
        // データ更新
        await this.saveData();
    }

    /**
     * 投稿結果を表示するためのViewModelを作成します
     * @param {PostViewModel} viewModel
     */
    prepareViewModel(viewModel: PostViewModel) {
        if (this.processModel.hasErrors()) {
            viewModel.status = this.processModel.status;
            viewModel.confirmationText = this.processModel.confirmationText;
            viewModel.errMsg = this.processModel.errorMessage;
        } else {
            // ToDo: 成功時のViewModelの値を設定
            viewModel.status = "success";
        }

        if (this.postForm) {
            viewModel.FROM = this.postForm.FROM;
            viewModel.mail = this.postForm.mail;
            viewModel.MESSAGE = this.postForm.MESSAGE;
            viewModel.subject = this.postForm.subject;
            viewModel.bbs = this.postForm.bbs;
            viewModel.key = this.postForm.key;
        }

        viewModel.isSJIS = this.poster.isSJIS;
        viewModel.init(this.c);
    }

    /**
     * 投稿処理前のチェックを行い、必要なデータを処理用モデルに詰める
     * UserAgent、CH-UAはここで取得される
     * 歴史的事情や最近の動向がいろいろあるので、更新時には注意すること
     */
    private initialCheck() {
        // POSTメソッド以外はエラー
        if (this.c.req.method !== "POST") {
            this.processModel.addError("POSTメソッド以外は許可されていません");
            return;
        }

        // content-typeがapplication/x-www-form-urlencoded以外はエラー
        const contentType = this.c.req.header("content-type") || "";
        if (!contentType.includes("application/x-www-form-urlencoded")) {
            this.processModel.addError("content-typeがapplication/x-www-form-urlencodedではありません");
            return;
        }

        // refererは/^https?:\/\/www\.3chbbs\.com/にマッチしないとだめ
        const referer = this.c.req.header("referer") || "";
        if (!referer.match(/^https?:\/\/www\.3chbbs\.com/)) {
            this.processModel.addError("不正なリファラです");
            return;
        }

        // 簡易Proxyチェック
        const clientIp = this.c.req.header("client-ip") || "";

        if (clientIp) {
            this.processModel.addError("Proxyからの書き込みは許可されていません");
            return;
        }

        const xForwardedFor = this.c.req.header("x-forwarded-for") || "";

        if (xForwardedFor && xForwardedFor.split(",")[0].trim() !== this.poster.ip) {
            this.processModel.addError("Proxyからの書き込みは許可されていません");
            return;
        }

        const forwarded = this.c.req.header("forwarded") || "";

        if (forwarded) {
            const fMatch = forwarded.match(/for=([^;]+)/);

            if (fMatch && fMatch[1] && fMatch[1] !== this.poster.ip) {
                this.processModel.addError("Proxyからの書き込みは許可されていません");
                return;
            }
        }

        const via = this.c.req.header("via") || "";

        if (via && !via.includes(this.poster.ip)) {
            this.processModel.addError("Proxyからの書き込みは許可されていません");
            return;
        }
    }

    /**
     * Formを取得します(UTF-8以外にも対応)
     */
    async preparePostForm() {
        // bodyTextを取得 -> Encoding.urlDecode -> UTF8以外ならEncoding.convert -> formにセット
        const bodyText = await this.c.req.text();

        // SJISかどうかだけ先に判定してしまいposter.isSJISにセット
        this.poster.isSJIS = Encoding.detect(bodyText) === "SJIS";

        // CONTENT_LENGTHが65535バイト以上はエラー
        if (bodyText.length > 65535) {
            this.processModel.addError("文長杉！");
            return;
        }
        const form: Record<string, string> = {};
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
                this.processModel.addError(`送信された内容が破損しています: ${e instanceof Error ? e.message : String(e)}`);
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

            form[key] = value;
        }

        const parsedForm = PostFormSchema.safeParse(form);
        if (!parsedForm.success) {
            this.processModel.addError("フォームの入力内容が不正です。");
            return;
        }
        Object.assign(this.postForm, parsedForm.data);

        // 偽キャップ、偽トリップ変換
        this.postForm.FROM = this.postForm.FROM.replace(/★/g, "☆").replace(/◆/g, "◇").replace(/＃/g, "#");
        this.postForm.mail = this.postForm.mail.replace(/＃/g, "#");

        // １行データからは改行を削ってタグを閉じます
        this.postForm.subject = this.postForm.subject.replace(/ ?<br> ?/g, "");
        this.postForm.FROM = this.postForm.FROM.replace(/ ?<br> ?/g, "");
        this.postForm.mail = this.postForm.mail.replace(/ ?<br> ?/g, "");
        this.postForm.submit = this.postForm.submit.replace(/ ?<br> ?/g, "");

        this.postForm.bbs = this.postForm.bbs.replace(/\W/g, "");
        this.postForm.key = this.postForm.key.replace(/\D/g, "");
        this.postForm.time = this.postForm.time.replace(/\D/g, "");

        // 板名が空でないこと
        if (!this.postForm.bbs) {
            this.processModel.addError("板名が指定されていません");
            return;
        }

        // 本文が空でないこと
        if (!this.postForm.MESSAGE) {
            this.processModel.addError("本文が空です");
            return;
        }

        // keyとsubjectが両方空、または両方ある場合はエラー
        // keyは10桁の数字であること
        if ((this.postForm.key && this.postForm.subject) || (!this.postForm.key && !this.postForm.subject)
            || (this.postForm.key && !/^\d{1,10}$/.test(this.postForm.key))) {
            this.processModel.addError("keyとsubjectの指定が不正です");
            return;
        }

        if (!this.postForm.time || !/^\d+$/.test(this.postForm.time)) {
            this.processModel.addError("timeの指定が不正です");
            return;
        }

        // subjectがある場合、keyにmodel.unixTimeをセット
        if (this.postForm.subject) {
            this.postForm.key = this.processModel.unixTime;
        }
    }

    /**
     * 板Durable_Objectsから板設定を取得し、model.boardSettingsにセットします
     */
    private async loadBoardSettings() {
        if (!this.postForm) return;
        // スレ立て時以外はスルー
        if (!this.postForm.subject) return;

        if (this.bbs === undefined) {
            throw new Error("Exception: bbs is undefined");
        }

        const settingText = await this.bbs.getSettingText();
        const lines = settingText.split("\n");

        for (const line of lines) {
            const [key, value] = line.split("=");
            if (key) {
                this.processModel.boardSettings[key.trim()] = value ? value.trim() : "";
            }
        }
    }

    /**
     * スレッドDurable Objectを生成します
     */
    private async createThreadObject() {
        if (!this.postForm) {
            throw new Error("Exception: postForm is not created.");
        }

        const postForm = this.postForm;
        this.thread = this.c.env.THREAD.getByName(postForm.bbs + postForm.key) as unknown as Thread;

        // スレ立て時、レス数が1以上ならエラー
        if (postForm.subject && (await this.thread.getResNum()) > 0) {
            this.processModel.addError("別の人が同時刻にスレッドを立てようとしています。ごめんなさい。");
            return;
        }

        // 非スレ立て時、レス数が0ならエラー
        if (postForm.subject === "" && (await this.thread.getResNum()) === 0) {
            this.processModel.addError("該当するスレッドがありません。" + postForm.bbs + postForm.key);
            return;
        }
    }

    /**
     * スレDurable_Objectsからスレ設定を取得し、model.settingsにセットします
     * スレ立て時はmodel.boardSettingsを使用
     */
    private async loadThreadSettings() {
        if (!this.postForm) return;
        // スレ立て時はmodel.boardSettingsを使用
        if (this.postForm.subject) {
            this.processModel.settings = { ...this.processModel.boardSettings };
            return;
        }

        // スレDurable_Objectsからスレ設定を取得
        if (this.thread === undefined) {
            throw new Error("Exception: thread is undefined");
        }

        const settingText = await this.thread.getSettingText();
        const lines = settingText.split("\n");

        for (const line of lines) {
            const [key, value] = line.split("=");
            if (key) {
                this.processModel.boardSettings[key.trim()] = value ? value.trim() : "";
            }
        }
    }



    /**
     * TODO: Formのバリデーション
     */
    private validateForm() {
        if (!this.postForm) return;
        const bbsNameCount = parseInt(this.processModel.settings.BBS_NAME_COUNT || "64");

        // FROMが bbsNameCount 以上はエラー
        if (this.postForm.FROM.length > bbsNameCount) {
            this.processModel.addError("名前が長すぎます");
            return;
        }
    }

    /**
     * 投稿に関する各種規制チェックを実行します
     */
    private async executeRegulationChecks() {
        // アクセス規制
        await this.regulatePosterByDenyList();
        if (this.processModel.hasErrors()) return;
        // 板・スレの処理（規制等）
        await this.processBoardAndThread();
        if (this.processModel.hasErrors()) return;
        // 投稿者処理（規制等）
        await this.processPoster();
        if (this.processModel.hasErrors()) return;
        // スレ立てチェック
        this.checkStartingNewThread();
        if (this.processModel.hasErrors()) return;
    }

    /**
     * 規制など板・スレの各処理を行います
     */
    private async processBoardAndThread() {
        if (this.thread === undefined) {
            throw new Error("Exception: thread is undefined");
        }

        // bbs = madakana, adminはcapUserでないと書き込めないこと
        if (this.postForm.bbs === "madakana" || this.postForm.bbs === "admin") {
            if (!this.poster.isCapUser) {
                this.processModel.addError("書き込み権限がありません");
                return;
            }
        }

        // レス数が BBS_THREAD_LIMIT 件以上の場合書き込めないこと
        if (await this.thread.getResNum() >= parseInt(this.processModel.settings.BBS_THREAD_LIMIT || "1000")) {
            this.processModel.addError("レス数が上限を超えています");
            return;
        }

        // BBS_HEISA=checkedの場合書き込めないこと(capUser除く)
        if (this.processModel.settings.BBS_HEISA === "checked") {
            if (!this.poster.isCapUser) {
                this.processModel.addError("書き込み権限がありません");
                return;
            }
        }

        // 連投規制
        // ThreadArrayの最新 settings.timecount 件中、 timeclose 回ID一致で規制
        const threadArray = await this.thread.getThreadArray();
        const timecount = parseInt(this.processModel.settings.timecount || "18");
        const timeclose = parseInt(this.processModel.settings.timeclose || "10");
        const timecover = parseInt(this.processModel.settings.timecover || "3600");

        if (threadArray.length > 0) {
            const recentPosts = threadArray.slice(-timecount);
            
            const { idMatchCount, shikibetsuIdMatchCount } = recentPosts.reduce(
                (counts, post) => {
                    // IDの一致をチェック (dateから " ID:xxxx" を抽出)
                    const idMatch = post.date.match(/\sID:(\S+)/);
                    if (idMatch && idMatch[1] === this.processModel.id) {
                        counts.idMatchCount++;
                    }

                    // 識別IDの一致をチェック (nameから " </b>(xxxx)<b>" を抽出)
                    const shikibetsuMatch = post.name.match(/\s<\/b>\(([^)]+)\)<b>/);
                    if (shikibetsuMatch && shikibetsuMatch[1] === this.poster.id) {
                        counts.shikibetsuIdMatchCount++;
                    }
                    return counts;
                },
                { idMatchCount: 0, shikibetsuIdMatchCount: 0 }
            );

            if (idMatchCount >= timeclose) {
                this.processModel.addError("短期間に連続投稿しすぎです。(ID)");
                return;
            }
            // 最新 timecount 件中、 timecover 回識別ID一致で規制
            if (shikibetsuIdMatchCount >= timecover) {
                this.processModel.addError("短期間に連続投稿しすぎです。(識別ID)");
                return;
            }
        }
    }

    /**
     * アクセス規制
     * 設定を上書きするため他規制処理の前に実行すること
     */
    private async regulatePosterByDenyList() {
        // KV.TESTのdenyListを使用
        const denyList = await this.c.env.TEST.get("denyList");
        if (!denyList) {
            return;
        }

        // denyList フォーマット
        // 規制対象（正規表現）<>対象板・スレ（板名、スレ名、板名dir 正規表現）<>規制種別（denyPost | mark | denyCreateNewThread | overWriteSetting=* 複数可）<>期限（TimeStamp, 任意）<>備考（任意）\n

        // TODO: Implement

    }

    /**
     * 投稿者処理（規制等）
     */
    private async processPoster() {
        if (this.user === undefined) {
            throw new Error("Exception: user is undefined");
        }
        
        const resultCheckBanned = await this.user.checkBanned(this.poster.userAgent, this.c.req.raw.cf?.asn?.toString() || "unknown", this.postForm.subject !== "");
        if (resultCheckBanned) {
            this.processModel.addError(resultCheckBanned);
            return;
        }

        // ToDo: 連投規制
    }

    /**
     * スレ立てチェック
     */
    private checkStartingNewThread() {
        // TODO: Implement
    }

    /**
     * ID、識別ID生成
     */
    private createPosterIdentifier() {
        // TODO: Implement
    }

    /**
     * コマンド処理
     */
    private async processCommands() {
        // TODO: Implement
    }

    /**
     * 投稿データを永続化します
     */
    private async saveData() {
        // Durable Objects（板、スレッド、ユーザー）データを更新
        await this.saveDurableObjectsData();
        // KVデータ（bbsmenu、subback）を更新
        await this.saveKVData();
    }

    /**
     * Durable Objects（板、スレッド、ユーザー）データを更新
     */
    private async saveDurableObjectsData() {
        // TODO: Implement
    }

    /**
     * KVデータ（bbsmenu、subback）を更新
     */
    private async saveKVData() {
        // TODO: Implement
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
