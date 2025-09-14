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

    // Output
    error: "",
    status: 200,
    headers: {},
    body: "",
};