import { DurableObject } from "cloudflare:workers";

export class Headline extends DurableObject {
    constructor(state, env) {
        this.state = state;
        this.env = env;
    }

    async get() {
        if (this.bbsMenu === undefined) {
            const data = await this.state.storage.get("bbsMenu");
            this.bbsMenu = data ? data.split("\n") : [];
        }
        // headline.txt形式の文字列に変換して返す
        return this.bbsMenu.join("\n");
    }

    async update(boardInfo) {
        // データを変更する前に、まず現在の状態を確実にメモリにロードする
        await this.get(); // get()を呼ぶことでbbsMenuがロードされる

        // 同じ板のエントリを削除
        this.bbsMenu = this.bbsMenu.filter(line => line.split('<>')[0] !== boardInfo.bbs);
        
        // boardInfoオブジェクトを<>区切りの文字列に変換
        const boardInfoString = `${boardInfo.bbs}<>${boardInfo.title}<>${boardInfo.time}<>${boardInfo.key}<>${boardInfo.message}`;
        
        // 先頭に追加
        this.bbsMenu.unshift(boardInfoString);
        // 20件に切り詰める
        if (this.bbsMenu.length > 20) {
            this.bbsMenu.length = 20;
        }
        // ストレージに永続化
        await this.state.storage.put("bbsMenu", this.bbsMenu.join("\n"));
    }
}
