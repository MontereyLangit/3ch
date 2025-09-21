import { ViewModel } from "./view_model";

/**
 * 板ビューモデル
 */
export class BbsViewModel implements ViewModel {
    bbs: string; // 板名
    bbsTitle: string; // 板タイトル
    isSJIS: boolean = false; // Shift_JISで表示するかどうか
    bbsDescription: string = ""; // 板説明 head.txtの中身
    threads: Array<{ key: string; title: string; resNum: number; }> = []; // スレッド一覧

    constructor(bbs: string) {
        this.bbs = bbs;
        this.bbsTitle = bbs;
    }
}
