import { DurableObject } from "cloudflare:workers";

/**
 * スレッドデータを管理するDurable Object (Thread)
 * 1つのスレッドに対して1つのインスタンスが対応します。
 */
export class Thread extends DurableObject {
  /**
   * @param {DurableObjectState} state
   * @param {object} env
   */
  constructor(state, env) {
    super(state, env);
    this.state = state;
    this.env = env;
  }

  /**
   * dat形式の全スレッドデータを文字列として返します。
   */
  async getDat() {
    return (await this.state.storage.get('dat')) || '';
  }

  /**
   * log形式の全投稿者ログを文字列として返します。
   */
  async getLog() {
    return (await this.state.storage.get('log')) || '';
  }

  /**
   * スレ設定文字列を返します。
   */
  async getSettings() {
    return (await this.state.storage.get('settings')) || '';
  }

  /**
   * スレ設定を更新します。
   * @param {string} newSettings - 新しいスレ設定文字列
   */
  async setSettings(newSettings) {
    await this.state.storage.put('settings', newSettings);
  }

  /**
   * 新しいレスを追加します。
   * @param {string} datLine - dat形式の新しい行データ
   * @param {string} logLine - log形式の新しい行データ
   */
  async addRes(datLine, logLine) {
    const newDat = await this.getDat() + datLine + "\n";console.log(datLine)
    const newLog = await this.getLog() + logLine + "\n";
    await this.state.storage.put({ dat: newDat, log: newLog });
    console.log({ dat: newDat, log: newLog });
  }

  /**
   * 指定されたレス番号をあぼーん化します。
   * @param {number} resNum - あぼーん化するレス番号 (1-based)
   */
  async deleteRes(resNum) {
    const dat = await this.getDat();
    const lines = dat.split('\n');
    if (resNum > 0 && resNum < lines.length) { // 最後の空行を考慮
      lines[resNum] = 'あぼーん<>あぼーん<>あぼーん<>あぼーん<>あぼーん';
      await this.state.storage.put('dat', lines.join('\n'));
    }
  }
}