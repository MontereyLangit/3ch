import { DurableObject } from "cloudflare:workers";

/**
 * ヘッドラインDurable Objectクラス
 */
export class Headline extends DurableObject {
    constructor(state, env) {
        super(state, env);
        this.state = state;
        this.env = env;
    }

    /**
     * ヘッドラインを取得します。
     * @return {Promise<string>} ヘッドライン生テキスト
     */
    async get() {
        let data = await this.state.storage.get("bbsMenu");

        if (!data) {
            const R2_HEADLINE_FILENAME = "headline.txt";

            try {
                const object = await this.env.ch3www.get(R2_HEADLINE_FILENAME);
                if (object !== null) {
                    const fullText = await object.text();
                    const lines = fullText.split("\n");

                    if (lines.length > 1) {
                        lines.shift(); // 1行目はタイムスタンプ
                        data = lines.join("\n");
                        await this.state.storage.put("bbsMenu", data);
                    }
                }
            } catch (e) {
                // エラーが発生した場合、dataはnullのままにする
            }
        }

        return data || '';
    }

    /**
     * ヘッドラインを更新します。
     * @param {object} boardInfo 板情報(bbs, title, time, key, message)
     */
    async update(boardInfo) {
        let currentBbsMenuText = await this.state.storage.get("bbsMenu");
        let lines = currentBbsMenuText ? currentBbsMenuText.split("\n") : [];

        lines = lines.filter(line => line.split('<>')[0] !== boardInfo.bbs);
        const boardInfoString = `${boardInfo.bbs}<>${boardInfo.title}<>${boardInfo.time}<>${boardInfo.key}<>${boardInfo.message}`;
        lines.unshift(boardInfoString);

        if (lines.length > 20) {
            lines.length = 20;
        }

        await this.state.storage.put("bbsMenu", lines.join("\n"));
    }
}
