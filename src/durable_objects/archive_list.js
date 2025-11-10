export class ArchiveList {
    constructor(state, env) {
        this.state = state;
        this.env = env;
    }

    async get() {
        if (this.threads === undefined) {
            const data = await this.state.storage.get("threads");
            this.threads = data || [];
        }
        // 配列を改行で連結した文字列として返す
        return this.threads.join("\n");
    }

    async add(threadLine) {
        // データを変更する前に、まず現在の状態を確実にメモリにロードする
        await this.get(); // get()を呼ぶことでthreadsがロードされる

        // 先頭に追加
        this.threads.unshift(threadLine);
        
        await this.state.storage.put("threads", this.threads);
    }
}

