import { DurableObject } from "cloudflare:workers";
import { convertSubjectTextTosubjectArray, convertSubjectArrayToSubjectText, type Subject } from "../utils/common_utils";


/* デフォルト板設定 */
export const settingTxtDefault = `BBS_TITLE=<DIR_NAME>＠3ch掲示板
BBS_NONAME_NAME=名無しさん＠３ちゃんねる
BBS_MAX_MENU_THREAD=300
BBS_FORCE_SAGE=
BBS_NINJA2=checked
BBS_ID=checked
BBS_ID_GROUP=
BBS_ID_RESET=86400
BBS_ID_LV=
BBS_ID_SHIKIBETSU=checked
BBS_SUBJECT_COUNT=64
BBS_NAME_COUNT=64
BBS_MAIL_COUNT=64
BBS_MESSAGE_COUNT=4096
BBS_LINE_NUMBER=30
BBS_THREAD_TATESUGI=3/86400
BBS_CREATETHREAD_INTERVAL=5
BBS_COPIPE=3/86400
timecount=18
timeclose=10
timecover=3600
BBS_COOK82=10
BBS_COOK82_POST_LIMIT=60
BBS_LIVE=
BBS_KISEIOFF=
BBS_NO_MADAKANA=
BBS_THREAD_SETTINGS=on
BBS_DISABLE_NUSI=
BBS_FORCE_NONAME=
BBS_ARR=
BBS_HEISA=
`;

/* 
 * 板
 */
export class Bbs extends DurableObject {
  private isLoaded: boolean;
  private settingText: string | undefined;
  private headText: string | undefined;
  private subjectText: string | undefined;
  private lastModified: Date;
  private timeCheck: Record<string, any>; // TODO: より具体的な型を定義
  private tatesugiCheck: Record<string, any>; // TODO: より具体的な型を定義

  constructor(ctx: DurableObjectState, env: CloudflareBindings) {
    super(ctx, env);
    this.isLoaded = false;
    this.lastModified = new Date();
    this.timeCheck = {};
    this.tatesugiCheck = {};
  }

  private async load(): Promise<void> {
    if (this.isLoaded) {
      return;
    }
    this.settingText = (await this.ctx.storage.get<string>("SETTING.TXT")) || settingTxtDefault;
    this.headText = (await this.ctx.storage.get<string>("head.txt")) || "";
    this.subjectText = (await this.ctx.storage.get<string>("subject.txt")) || "";
    this.isLoaded = true;
  }

  async setSettingText(text: string): Promise<void> {
    this.settingText = text;
    await this.ctx.storage.put("SETTING.TXT", text);
  }

  async getSettingText(): Promise<string> {
    await this.load();
    return this.settingText!;
  }

  async setHeadText(text: string): Promise<void> {
    this.headText = text;
    await this.ctx.storage.put("head.txt", text);
  }

  async getHeadText(): Promise<string> {
    await this.load();
    return this.headText!;
  }

  async setSubjectText(text: string): Promise<void> {
    this.subjectText = text;
    await this.ctx.storage.put("subject.txt", text);
    this.lastModified = new Date();
  }

  async getSubjectText(): Promise<string> {
    await this.load();
    return this.subjectText!;
  }

  async getLastModified(): Promise<Date> {
    return this.lastModified;
  }

  /*
   * 入力されたスレ一覧配列をスレ一覧テキストに変換して保存
   * ストレージ書き込みは一定確率(書き込まないときはインメモリ変数のみ)
   * @param {any[]} subjectArray - スレッド一覧配列
   * @param {number} [writeFrequencyMSec=1000] - ストレージ最低書き込み頻度(ミリ秒)
   */
  async saveSubject(subjectArray: Subject[], writeFrequencyMSec: number = 1000): Promise<void> {
    this.subjectText = convertSubjectArrayToSubjectText(subjectArray);

    const currentDate = new Date();
    if (!this.isLoaded || this.lastModified.getTime() + writeFrequencyMSec < currentDate.getTime()) {
      await this.ctx.storage.put("subject.txt", this.subjectText);
      this.lastModified = currentDate;
    }
  }

  async loadSubject(): Promise<Subject[]> {
    await this.load();
    return convertSubjectTextTosubjectArray(this.subjectText!);
  }
}