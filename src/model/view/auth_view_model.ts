import { ViewModel } from "@/model/view/view_model";
import { ContentfulStatusCode } from "hono/utils/http-status";

/**
 * 認証ビューモデル
 */
export class AuthViewModel implements ViewModel {
    // Input
    hap: string = "";
    turnstileToken: string = "";
    validate: string = "";
    deviceInformation: string = "";
    cert: string = "";
    time: string = "";
    authSig: string = "";
    token: string = "";
    ip: string = "";
    asn: string | number = "";
    country: string = "";
    ken: string = "";
    city: string = "";
    userAgent: string = "";
    userData: string | null = null; // USER DOから取得した生データ

    // Output
    error: string = "";
    status: ContentfulStatusCode = 200;
    headers: Record<string, string> = {};
    body: string = "";

    /**
     * ビューモデルの検証
     * @param scenario 'prepare' | 'authenticate'
     * @returns { valid: boolean, errorMsg?: string }
     */
    validateViewModel(scenario: 'prepare' | 'authenticate'): { valid: boolean; errorMsg?: string } {
        if (scenario === 'prepare') {
            if (!this.hap) {
                return { valid: false, errorMsg: "HAPがありません" };
            }
            if (!this.userData) {
                return { valid: false, errorMsg: "忍者IDが見つかりません" };
            }
            const userData = this.userData.split("<>");
            if (userData[0] !== "AUTH" || !userData[7]) {
                return { valid: false, errorMsg: "不正な忍者IDです" };
            }
            const timeElapsed = Math.floor(Date.now() / 1000) - parseInt(userData[2]);
            if (timeElapsed > 300) {
                return { valid: false, errorMsg: "認証の有効期限が切れています" };
            }
            return { valid: true };
        }

        if (scenario === 'authenticate') {
            // 必須項目のチェック
            const requiredFields: (keyof AuthViewModel)[] = ['hap', 'turnstileToken', 'validate', 'deviceInformation', 'cert', 'time', 'authSig', 'token'];
            for (const field of requiredFields) {
                if (!this[field]) {
                    return { valid: false, errorMsg: `${field}がありません` };
                }
            }
            if (!/^\d+$/.test(this.time)) {
                return { valid: false, errorMsg: "timeが不正です" };
            }

            // ninjaデータのチェック
            if (!this.userData) {
                return { valid: false, errorMsg: "認証が早すぎます（忍者IDを取得できませんでした）" };
            }
            const userData = this.userData.split("<>");
            if (userData[0] !== "AUTH2" || !userData[7]) {
                return { valid: false, errorMsg: "不正な認証です（e4）" };
            }
            const timeElapsed = Math.floor(Date.now() / 1000) - parseInt(userData[2]);
            if (timeElapsed > 300) {
                return { valid: false, errorMsg: "認証が時間切れです。（5分以上経過）" };
            }
            if (this.validate !== userData[1]) {
                return { valid: false, errorMsg: "認証コードが一致しません" };
            }

            return { valid: true };
        }

        return { valid: false, errorMsg: "不明な検証シナリオです" };
    }
}
