import { DurableObject } from "cloudflare:workers";
import { convertDatToThreadArray, convertThreadArrayToDat } from "../utils/common_utils.js";

/* 
 * スレッド
 */
export class Thread extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);

    // インメモリのみ変数
    this.ctx = ctx;
    this.lastModified = 0;
    this.resNum = 0;
  }

  /*
   * Storageからデータを取得します
   */
  async load() {
    if (this.loaded) {
      return;
    }

    // ストレージから取得
    this.settingText = (await this.ctx.storage.get("SETTING.TXT")) || "";
    this.dat = (await this.ctx.storage.get("dat")) || "";
    this.logText = (await this.ctx.storage.get("logText")) || "";
    // ロード済みフラグ
    this.isLoaded = true;
  }

  async setSettingText(settingText) {
    this.settingText = settingText;
    await this.ctx.storage.put("settingText", this.settingText);
  }

  async getSettingText() {
    await this.load();
    return this.settingText;
  }

  async setDat(dat) {
    this.dat = dat;
    await this.ctx.storage.put("dat", this.dat);
  }

  async getDat() {
    await this.load();
    return this.dat;
  }

  /* 
   * スレッドを更新します
   * @param {array} threadArray スレッド配列
   * @param {string} logText スレッドのログテキスト
   * @param {number} [writeFrequencyMSec=1000] - ストレージ最低書き込み頻度(ミリ秒)
   */
  async saveThread(threadArray, logText, writeFrequencyMSec = 1000) {
    const currentDate = new Date();
    this.dat = convertThreadArrayToDat(threadArray);
    this.logText = logText;
    
    if (this.getLastModified().getTime() + writeFrequencyMSec < currentDate.getTime()) {
      await this.ctx.storage.put("dat", this.dat);
      await this.ctx.storage.put("logText", this.logText);
      this.lastModified = currentDate;
    }
  }

  async setLogText(logText) {
    this.logText = logText;
    await this.ctx.storage.put("logText", this.logText);
  }

  async getLogText() {
    await this.load();
    return this.logText;
  }

  /* 
   * スレッド配列を取得します
   * @return {array} スレッド配列
   */
  async getThreadArray() {
    await this.load();
    return convertDatToThreadArray(this.dat);
  }

  async getLastModified() {
    return this.lastModified || await this.loadThreadLastModified();
  }

  async getResNum() {
    return this.resNum || await this.loadThreadResNum();
  }

  /* 
   * 最終更新日時をdatから取得します
   * 最終更新日時のフィールドも同時に更新します
   * @return {Date} 最終更新時刻
   */
  async loadThreadLastModified() {
    await this.load();
    // スレッドの最後のレスの日時を返す
    const threadArray = convertDatToThreadArray(this.dat);

    if (!threadArray || threadArray.length === 0) {
      return new Date();
    }

    const lastRes = threadArray[threadArray.length - 1];
    if (lastRes && lastRes.date) {
      this.lastModified = new Date(lastRes.date);
      return this.lastModified;
    }

    return new Date();
  }

  /* 
   * レス数をdatから取得します
   * レス数のフィールドも同時に更新します
   * @return {int} レス数
   */
  async loadThreadResNum() {
    await this.load();
    const threadArray = convertDatToThreadArray(this.dat);
    this.resNum = threadArray ? threadArray.length : 0;
    return this.resNum;
  }
}
