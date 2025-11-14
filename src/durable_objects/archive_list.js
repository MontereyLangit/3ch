import { DurableObject } from "cloudflare:workers";

/**
 * 過去ログ倉庫Durable Objectクラス
 */
export class ArchiveList extends DurableObject {
    constructor(state, env) {
        super(state, env);
        this.state = state;
        this.env = env;
    }

    /**
     * 過去ログのスレ一覧を取得します。
     * @param {string} bbs 対象板
     * @param {string} pastlog 対象期間(unixtime上位4桁)
     * @returns {Promise<string>} 過去ログのスレ一覧(生テキスト)
     */
    async get(bbs = '', pastlog = '0') {
        if (this.threads === undefined) {
            let data = await this.state.storage.get("threads");

            if (!data && bbs && pastlog && parseInt(pastlog) > 0 && parseInt(pastlog) < 1764) {
                const filename = `${bbs}/kako/${pastlog}/subject.txt`;
                try {
                    const object = await this.env.ch3www.get(filename);
                    if (object !== null) {
                        const text = await object.text();
                        data = text.split("\n").filter(line => line);
                        await this.state.storage.put("threads", data);
                    }
                } catch (e) {
                    data = [];
                }
            }
        }
        return data ? data.join("\n") : "";
    }

    /**
     * 入力されたスレッド(行)を過去ログのスレ一覧に追加します。
     * @param {string} threadLine 追加スレッド情報
     */
    async add(threadLine) {
        await this.get();

        this.threads.unshift(threadLine);
        
        await this.state.storage.put("threads", this.threads);
    }
}
