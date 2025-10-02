/**
 * 書き込み処理モデル
 */
export class PostProcessModel {
    // Processed Data
    unixTime: string = "";
    md5Salt: string = "";
    settings: Record<string, any> = {};
    boardSettings: Record<string, any> = {};
    isSage: boolean = false;
    tripKey: string = "";
    tripString: string = "";
    isCapUser: boolean = false;
    isStrongCapUser: boolean = false;
    id: string = "";
    number: number = 0;

    // Output
    private _status: "success" | "error" | "confirmation" = "success";
    errorMessage: string = "";
    confirmationText: string = "";
    headers: Record<string, string> = {};

    get status(): "success" | "error" | "confirmation" {
        return this._status;
    }

    addError(message: string) {
        if (this.hasErrors()) return; // 最初のエラーのみを保持
        this.errorMessage = message;
        this._status = "error";
    }

    hasErrors(): boolean {
        return this._status !== "success";
    }
}
