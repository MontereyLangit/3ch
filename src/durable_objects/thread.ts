import { DurableObject } from "cloudflare:workers";
import { convertDatToThreadArray, convertThreadArrayToDat, type ThreadRes } from "@/utils/commonUtils";
import { settingTxtDefault } from "./Bbs";

/* 
 * スレッド
 */
export class Thread extends DurableObject {
  private lastModified: Date;
  private resNum: number;
  private isLoaded: boolean;
  private settingText: string = settingTxtDefault;
  private dat: string = "";
  private logText: string = "";

  constructor(ctx: DurableObjectState, env: CloudflareBindings) {
    super(ctx, env);

    // インメモリのみ変数
    this.lastModified = new Date();
    this.resNum = 0;
    this.isLoaded = false;
  }

  /*
   * Storageからデータを取得します
   */
  private async load(): Promise<void> {
    if (this.isLoaded) {
      return;
    }

    // ストレージから取得
    this.settingText = (await this.ctx.storage.get<string>("SETTING.TXT")) || "";
    this.dat = (await this.ctx.storage.get<string>("dat")) || "";
    this.logText = (await this.ctx.storage.get<string>("logText")) || "";
    // ロード済みフラグ
    this.isLoaded = true;
  }

  async setSettingText(settingText: string): Promise<void> {
    this.settingText = settingText;
    await this.ctx.storage.put("SETTING.TXT", this.settingText);
  }

  async getSettingText(): Promise<string> {
    await this.load();
    return this.settingText!;
  }

  async setDat(dat: string): Promise<void> {
    this.dat = dat;
    await this.ctx.storage.put("dat", this.dat);
  }

  async getDat(): Promise<string> {
    await this.load();
    return this.dat!;
  }

  /* 
   * スレッドを更新します
   * @param {any[]} threadArray スレッド配列
   * @param {string} logText スレッドのログテキスト
   * @param {number} [writeFrequencyMSec=1000] - ストレージ最低書き込み頻度(ミリ秒)
   */
  async saveThread(threadArray: ThreadRes[], logText: string, writeFrequencyMSec: number = 1000): Promise<void> {
    const currentDate = new Date();
    this.dat = convertThreadArrayToDat(threadArray);
    this.logText = logText;
    
    const lastModifiedTime = this.lastModified instanceof Date ? this.lastModified.getTime() : this.lastModified;
    if (lastModifiedTime + writeFrequencyMSec < currentDate.getTime()) {
      await this.ctx.storage.put("dat", this.dat);
      await this.ctx.storage.put("logText", this.logText);
      this.lastModified = currentDate;
    }
  }

  async setLogText(logText: string): Promise<void> {
    this.logText = logText;
    await this.ctx.storage.put("logText", this.logText);
  }

  async getLogText(): Promise<string> {
    await this.load();
    return this.logText!;
  }

  /* 
   * スレッド配列を取得します
   * @return {any[]} スレッド配列
   */
  async getThreadArray(): Promise<ThreadRes[]> {
    await this.load();
    return convertDatToThreadArray(this.dat!);
  }

  async getLastModified(): Promise<Date | number> {
    return this.lastModified || await this.loadThreadLastModified();
  }

  async getResNum(): Promise<number> {
    return this.resNum || await this.loadThreadResNum();
  }

  async loadThreadLastModified(): Promise<Date> {
    await this.load();
    const threadArray = convertDatToThreadArray(this.dat);
    if (!threadArray || threadArray.length === 0) return new Date();
    const lastRes = threadArray[threadArray.length - 1];
    this.lastModified = (lastRes && lastRes.date) ? new Date(lastRes.date) : new Date();
    return this.lastModified;
  }

  async loadThreadResNum(): Promise<number> {
    await this.load();
    const threadArray = convertDatToThreadArray(this.dat);
    this.resNum = threadArray ? threadArray.length : 0;
    return this.resNum;
  }
}
