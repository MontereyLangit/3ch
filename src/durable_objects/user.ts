import { DurableObject } from "cloudflare:workers";
import { extractAndSanitizeUrls } from "../utils/common_utils";

/* 
 * 忍法帖
 * ストレージから取得: fip, deviceInfo, userAgent, created, samba24Registered, tatesugiRegistered
 */
export class User extends DurableObject {
  private isLoaded: boolean;
  private rawData: string | undefined;
  userId: string | undefined;
  fip: string | undefined;
  deviceInfo: string | undefined;
  userAgent: string | undefined;
  created: Date | undefined;
  lastAsNum: string | undefined;
  asChangingCount: number | undefined;
  badCount: number | undefined;
  samba24Registered: Date | undefined;
  tatesugiRegistered: Date | undefined;
  lastPostedTime: Date | undefined;
  samba24Count: number | undefined;
  postCount: number | undefined;
  postLength: number | undefined;
  lastMessage: string | undefined;
  lastUrls: string | undefined;
  lastSubject: string | undefined;
  multiPostingCount: number | undefined;
  tatesugiCount: number | undefined;
  lastCreatedTime: Date | undefined;

  constructor(ctx: DurableObjectState, env: CloudflareBindings) {
    super(ctx, env);
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
    // 0:userId/ninjaId<>1:fip<>2:deviceInfo<>3:userAgent<>4:created<>5:lastAsNum<>6:asChangingCount<>7:badCount<>8:samba24Registered<>9:tatesugiRegistered<>10:lastPostedTime<>11:samba24Count<>12:postCount<>13:postLength<>14:lastMessage<>15:lastUrls<>16:lastSubject<>17:multiPostingCount<>18:tatesugiCount<>19:lastCreatedTime<>
    this.rawData = await this.ctx.storage.get<string>("data") || "";
    const data = (this.rawData || "<><><><><><><><><><><><><><><><><><><><>").trim().split("<>");

    this.userId = data[0] || "";
    this.fip = data[1] || ""; // 本登録時のIPアドレス || 仮登録時のISP情報
    // 認証フローでは、fipに "AUTH<>" や "AUTH2<>" のような文字列が入る
    this.deviceInfo = data[2] || "";
    this.userAgent = data[3] || "";
    this.created = data[4] ? new Date(parseInt(data[4])) : new Date();
    this.lastAsNum = data[5] || ""; // AS番号
    this.asChangingCount = data[6] ? parseInt(data[6]) : 0; // AS番号が変わった回数 4回目で再認証必須
    this.badCount = data[7] ? parseInt(data[7]) : 0;  // 自動規制カウント 4回貯まると自動焼き
    this.samba24Registered = data[8] ? (new Date(parseInt(data[8]))) : new Date(0);
    this.tatesugiRegistered = data[9] ? (new Date(parseInt(data[9]))) : new Date(0);
    this.lastPostedTime = data[10] ? (new Date(parseInt(data[10]))) : new Date(0);
    this.samba24Count = data[11] ? parseInt(data[11]) : 0;
    this.postCount = data[12] ? parseInt(data[12]) : 0;
    this.postLength = data[13] ? parseInt(data[13]) : 0;
    this.lastMessage = data[14] || "";
    this.lastUrls = data[15] || "";
    this.lastSubject = data[16] || "";
    this.multiPostingCount = data[17] ? parseInt(data[17]) : 0;
    this.tatesugiCount = data[18] ? parseInt(data[18]) : 0;
    this.lastCreatedTime = data[19] ? (new Date(parseInt(data[19]))) : new Date(0);
    // ロード済みフラグ
    this.isLoaded = true;
  }

  /* 
   * Storageにデータを保存します
   * 各フィールドの値がStorageに反映されるのはこのメソッドを呼び出したときのみ
   * load()呼び出し前に呼び出すと更新されないので注意すること
   */
  private async save(): Promise<void> {
    if (!this.isLoaded) {
      return;
    }

    const dataString = [
      this.userId,
      this.fip,
      this.deviceInfo,
      this.userAgent,
      this.created?.getTime() ?? 0,
      this.lastAsNum,
      this.asChangingCount,
      this.badCount,
      this.samba24Registered?.getTime() ?? 0,
      this.tatesugiRegistered?.getTime() ?? 0,
      this.lastPostedTime?.getTime() ?? 0,
      this.samba24Count,
      this.postCount,
      this.postLength,
      this.lastMessage,
      this.lastUrls,
      this.lastSubject,
      this.multiPostingCount,
      this.tatesugiCount,
      this.lastCreatedTime?.getTime() ?? 0,
    ].join('<>');
    
    this.rawData = dataString;
    await this.ctx.storage.put("data", dataString);
  }

  async setUserId(userId: string): Promise<void> {
    await this.load();
    this.userId = userId;
    await this.save();
  }

  async getUserId(): Promise<string> {
    await this.load();
    return this.userId!;
  }

  async setFip(fip: string): Promise<void> {
    await this.load();
    this.fip = fip;
    await this.save();
  }

  async getFip(): Promise<string> {
    await this.load();
    return this.fip!;
  }

  async setDeviceInfo(deviceInfo: string): Promise<void> {
    await this.load();
    this.deviceInfo = deviceInfo;
    await this.save();
  }

  async getDeviceInfo(): Promise<string> {
    await this.load();
    return this.deviceInfo!;
  }

  async setUserAgent(userAgent: string): Promise<void> {
    await this.load();
    this.userAgent = userAgent;
    await this.save();
  }

  async getUserAgent(): Promise<string> {
    await this.load();
    return this.userAgent!;
  }

  async setCreated(created: Date): Promise<void> {
    await this.load();
    this.created = created;
    await this.save();
  }

  async getCreated(): Promise<Date> {
    await this.load();
    return this.created!;
  }

  /*
   * 仮の忍法帖を新規作成します
   * @param userId
   * @param ispInfo AS番号国県市区町村 -> "9008JPShizuokaHamamatsu"
   * @param userAgent
   */
  async create(userId: string, ispInfo: string, userAgent: string): Promise<void> {
    await this.load();
    this.userId = userId;
    this.fip = ispInfo;
    this.userAgent = userAgent;
    await this.save();
  }

  /*
   * 忍法帖を本登録します
   * @param userId
   * @param ip IPアドレス
   * @param deviceInfo
   */
  async register(userId: string, ip: string, deviceInfo: string, userAgentHash: string = ""): Promise<void> {
    await this.load();
    this.userId = userId;
    // 認証成功後は、fipにIPアドレスを格納
    this.fip = ip; 
    this.deviceInfo = deviceInfo;
    if (userAgentHash) {
      this.userAgent = userAgentHash;
    }
    await this.save();
  }

  // auth_service.jsから呼び出すためのヘルパーメソッド
  async getRawData(): Promise<string> {
    await this.load();
    // fipに "AUTH..." が入っている場合は、それをそのまま返す
    if (this.fip?.startsWith("AUTH")) {
      return this.fip;
    }
    return this.rawData!;
  }

  async updateAuthStatus(authString: string): Promise<void> {
    // 認証ステータスをfipに一時的に保存
    await this.ctx.storage.put("data", `<>${authString}<>`);
  }

  /* 
   * 規制用の各フィールドに更新した値をセットします
   * @param message 本文
   * @param subject スレタイ
   * @param postLength 名前+メール+本文+スレタイの合計byte
   */
  async modifyUserDataOfRegulation(message: string = "", subject: string = "", postLength: number = 0): Promise<void> {
    const currentTime = new Date();
    const lastUrls = extractAndSanitizeUrls(message);
    this.lastPostedTime = currentTime;  // 最終投稿時刻
    this.postCount!++;
    this.postLength! += postLength;
    this.lastMessage = message;

    if (lastUrls) {
      this.lastUrls = lastUrls;
    }

    if (subject) {
      this.lastSubject = subject;
      this.tatesugiCount!++;
      this.lastCreatedTime = currentTime; // 最終スレ立て時刻 N回で tatesugiRegistered に登録
    }
  }

  /* 
   * 規制されていないかどうか判定
   * @param userAgent
   * @param asNum
   * @param newThreadFlg
   * @return エラーメッセージ
   */
  async checkBanned(userAgent: string, asNum: string, newThreadFlg: boolean = false): Promise<string> {
    await this.load();
    const currentTimeStamp = new Date().getTime();

    if (/^BAN:/.test(this.userId!)) {
      return "999 BANされています。";
    }

    if (userAgent !== this.userAgent) {
      return "001 登録されているブラウザ情報と現在のブラウザ情報が一致しません。お手数ですが再認証してください。<br>荒らし対策にご協力をお願いします。";
    }

    if (asNum !== this.lastAsNum) {
      this.asChangingCount!++;
      if (this.asChangingCount! >= 4) {
        return "002 不審なISP切り替えを検知しました。お手数ですが再認証してください。<br>荒らし対策にご協力をお願いします。";
      }
    }

    if (this.badCount! >= 4 || (this.samba24Registered && this.samba24Registered.getTime() > currentTimeStamp)) {
      return "900 しばらくお断りしております。<br><br>何度も書こうとすると規制期間が伸びますよ。。。";
    }

    if (newThreadFlg && (this.badCount! >= 2 || (this.tatesugiRegistered && this.tatesugiRegistered.getTime() > currentTimeStamp))) {
      return "600 あたなのアカウントでは、しばらくスレッドが立てられません。またの機会にどうぞ。。。<br><br>何度も書こうとすると規制期間が伸びますよ。。。";
    }

    return "";
  }

  /* 
   * スレッド立てすぎ
   * @param regSec 規制秒数
   * @param maxRegCount 規制回数
   * @return エラーメッセージ
   */
  async checkTatesugi(regSec: number, maxRegCount: number): Promise<string> {
    await this.load();
    const currentTime = new Date();
    const keika = Math.floor(currentTime.getTime() / 1000) - Math.floor(this.lastCreatedTime!.getTime() / 1000);
    const cautionRegCount = maxRegCount > 4 ? maxRegCount - 2 : 3;

    // 1時間以内のスレ立てのみ判定
    if (keika < 3600) {
      if (this.tatesugiCount! > maxRegCount) {
        this.tatesugiRegistered = new Date(currentTime.getTime() + 24 * 60 * 60 * 1000); // 24時間規制
        this.badCount!++;
        await this.save();
        return "694 スレッド立てすぎです。。。あなたは、規制リストに追加されました。";
      } else if (this.tatesugiCount! > cautionRegCount) {
        return "699 スレッド立てすぎです。。。もうそろそろ規制リストに入れますよ。。(￣ー￣)ニヤリッ<br>";
      } else if (keika < regSec) {
        return `693 短時間にスレッド立てすぎです。前回のスレ立てから${regSec} sec たたないと立てられません。(${keika} sec しかたってない)<br>`;
      }
    } else {
      // リセット
      this.lastCreatedTime = new Date(0);
      this.tatesugiCount = 0;
    }

    return "";
  }
}