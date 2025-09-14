import { DurableObject } from "cloudflare:workers";
import { settingTxtDefault } from "../bbs.cgi.js";
import { convertSubjectTextTosubjectArray, convertSubjectArrayToSubjectText } from "../utils/common_utils.js";

/* 
 * 板
 */
export class Bbs extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.isLoaded = false;

    // インメモリのみの変数
    this.ctx = ctx;
    this.lastModified = new Date();
    this.timeCheck = {};  // 各投稿の連投規制用データ:unixtime<>IP<>FIP<>識別IDのraw
    this.tatesugiCheck = {};  // 各投稿のスレ立て制限用データ:unixtime<>IP<>FIP<>識別IDのraw
  }

  /*
   * Storageからデータを取得します
   */
  async load() {
    if (this.isLoaded) {
      return;
    }

    // ストレージと同期する変数
    this.settingText = (await this.ctx.storage.get("SETTING.TXT")) || settingTxtDefault;
    this.headText = (await this.ctx.storage.get("head.txt")) || "";
    this.subjectText = (await this.ctx.storage.get("subject.txt")) || "";
    // ロード済みフラグ
    this.loaded = true;
  }

  async setSettingText(text) {
    this.settingText = text;
    await this.ctx.storage.put("SETTING.TXT", text);
  }

  async getSettingText() {
    await this.load();
    return this.settingText;
  }

  async setHeadText(text) {
    this.headText = text;
    await this.ctx.storage.put("head.txt", text);
  }

  async getHeadText() {
    await this.load();
    return this.headText;
  }

  async setSubjectText(text) {
    this.subjectText = text;
    await this.ctx.storage.put("subject.txt", text);
    this.lastModified = new Date();
  }

  async getSubjectText() {
    await this.load();
    return this.subjectText;
  }

  /* 
   * 入力されたスレ一覧配列をスレ一覧テキストに変換して保存
   * ストレージ書き込みは一定確率(書き込まないときはインメモリ変数のみ)
   * @param {Array} subjectArray - スレッド一覧配列
   * @param {number} [writeFrequencyMSec=1000] - ストレージ最低書き込み頻度(ミリ秒)
   */
  async saveSubject(subjectArray, writeFrequencyMSec = 1000) {
    this.subjectText = convertSubjectArrayToSubjectText(subjectArray);

    const currentDate = new Date();
    if (!this.loaded || this.lastModified.getTime() + writeFrequencyMSec < currentDate.getTime()) {
      await this.ctx.storage.put("subject.txt", this.subjectText);
      this.lastModified = currentDate;
    }
  }

  async loadSubject() {
    await this.load();
    return convertSubjectTextTosubjectArray(this.subjectText);
  }
  
  async getLastModified() {
    return this.lastModified;
  }
}
