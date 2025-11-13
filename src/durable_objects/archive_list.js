import { DurableObject } from "cloudflare:workers";

export class ArchiveList extends DurableObject {
    constructor(state, env) {
        super(state, env);
        this.state = state;
        this.env = env;
    }

    async get(bbs = '', pastlog = '0') {
        if (this.threads === undefined) {
            let data = await this.state.storage.get("threads");

            // DOにデータがなく、bbsとpastlog引数があって、かつpastlog<1764の場合にのみR2から取得
            if (!data && bbs && pastlog && parseInt(pastlog) > 0 && parseInt(pastlog) < 1764) {
                const filename = `${bbs}/kako/${pastlog}/subject.txt`;
                try {
                    const object = await this.env.ch3www.get(filename);
                    if (object !== null) {
                        const text = await object.text();
                        // R2のデータは改行区切りの文字列なので、配列に変換
                        data = text.split("\n").filter(line => line); // 空行を除去
                        // 取得したデータをDOに書き込む
                        await this.state.storage.put("threads", data);
                    }
                } catch (e) {
                    data = [];
                }
            }
        }
        // 配列を改行で連結した文字列として返す
        return data ? data.join("\n") : "";
    }

    async add(threadLine) {
        // データを変更する前に、まず現在の状態を確実にメモリにロードする
        await this.get(); // get()を呼ぶことでthreadsがロードされる

        // 先頭に追加
        this.threads.unshift(threadLine);
        
        await this.state.storage.put("threads", this.threads);
    }
}
