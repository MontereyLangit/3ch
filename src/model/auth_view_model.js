////@ts-check

/**
 * 認証ビューモデル
 */
export const authViewModel = {
    // Input
    hap: "",
    turnstileToken: "",
    validate: "",
    deviceInformation: "",
    cert: "",
    time: "",
    authSig: "",
    token: "",
    ip: "",
    asn: "",
    country: "",
    ken: "",
    city: "",
    userAgent: "",
    userData: null, // USER DOから取得した生データ

    // Output
    error: "",
    status: 200,
    headers: {},
    body: "",
};

/*
 * ビューモデルの検証
 * @param {import("./auth_view_model.js").authViewModel} viewModel
 * @param {'prepare' | 'authenticate'} scenario
 * @returns {{valid: boolean, errorMsg?: string}}
 */
export const validateAuthViewModel = (viewModel, scenario) => {
    if (scenario === 'prepare') {
        if (!viewModel.hap) {
            return { valid: false, errorMsg: "HAPがありません" };
        }
        if (!viewModel.userData) {
            return { valid: false, errorMsg: "忍者IDが見つかりません" };
        }
        const userData = viewModel.userData.split("<>");
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
        if (!viewModel.hap) {
            return { valid: false, errorMsg: "HAPがありません" };
        }
        if (!viewModel.turnstileToken) {
            return { valid: false, errorMsg: "Turnstileトークンがありません" };
        }
        if (!viewModel.validate) {
            return { valid: false, errorMsg: "認証コードがありません" };
        }
        if (!viewModel.deviceInformation) {
            return { valid: false, errorMsg: "デバイス情報がありません" };
        }
        if (!viewModel.cert) {
            return { valid: false, errorMsg: "certがありません" };
        }
        if (!viewModel.time || !/^\d+$/.test(viewModel.time)) {
            return { valid: false, errorMsg: "timeが不正です" };
        }
        if (!viewModel.authSig || !viewModel.token) {
            return { valid: false, errorMsg: "署名またはトークンがありません" };
        }

        // ninjaデータのチェック
        if (!viewModel.userData) {
            return { valid: false, errorMsg: "認証が早すぎます（忍者IDを取得できませんでした）" };
        }
        const userData = viewModel.userData.split("<>");
        if (userData[0] !== "AUTH2" || !userData[7]) {
            return { valid: false, errorMsg: "不正な認証です（e4）" };
        }
        const timeElapsed = Math.floor(Date.now() / 1000) - parseInt(userData[2]);
        if (timeElapsed > 300) {
            return { valid: false, errorMsg: "認証が時間切れです。（5分以上経過）" };
        }
        if (viewModel.validate !== userData[1]) {
            return { valid: false, errorMsg: "認証コードが一致しません" };
        }

        return { valid: true };
    }

    return { valid: false, errorMsg: "不明な検証シナリオです" };
};
