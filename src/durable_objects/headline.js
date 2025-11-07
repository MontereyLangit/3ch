export class Headline {
    constructor(state, env) {
        this.state = state;
        this.env = env;
        this.latestBoards = undefined; // 遅延読み込みのためundefinedで初期化
    }

    async get() {
        if (this.latestBoards === undefined) {
            const data = await this.state.storage.get("latestBoards");
            this.latestBoards = data || [];
        }
        // headline.txt形式の文字列に変換して返す
        return this.latestBoards.map(b => 
            `${b.bbs}<>${b.title}<>${b.time}<>${b.key}<>${b.message}`
        ).join("\n");
    }

    async update(boardInfo) {
        // データを変更する前に、まず現在の状態を確実にメモリにロードする
        await this.get(); // get()を呼ぶことでlatestBoardsがロードされる

        // 同じ板のエントリを削除
        this.latestBoards = this.latestBoards.filter(b => b.bbs !== boardInfo.bbs);
        // 先頭に追加
        this.latestBoards.unshift(boardInfo);
        // 20件に切り詰める
        if (this.latestBoards.length > 20) {
            this.latestBoards.length = 20;
        }
        // ストレージに永続化
        await this.state.storage.put("latestBoards", this.latestBoards);
    }
}
