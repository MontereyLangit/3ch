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
  async getDat(bbs = '', key = '0') {
    let dat = await this.state.storage.get('dat');

    // datが存在しない場合で keyが1以上1762009199未満の場合、R2から旧形式スレッドログを取得しdatに変換、記録
    if (!dat && bbs && parseInt(key) > 0 && parseInt(key) < 1762009199) {
      const filename = `${bbs}/thread/${key.slice(0,4)}/${key}.cgi`;
      const DATETIMEFORMAT = new Intl.DateTimeFormat('ja-JP', {year: "numeric", month: "2-digit", day: "2-digit", weekday:"short", hour: "2-digit", minute: "2-digit", second: "2-digit", timeZone: "Asia/Tokyo"});
      let resnum = 0;
      let settings = {};
      dat = "";

      try {
        const object = await this.env.ch3www.get(filename);
        if (object !== null) {
          const log = await object.text();
          const resArray = log.split("\n");

          /* スレの配列に最低3個要素がないとおかしい */
          if(resArray.length < 3) throw new Error("スレッドのログファイルが壊れています。運用情報板で修理してもらってください。");

          /* スレッドタイトルを取得。ログの一行目に入っている。 */
          const subject = resArray.shift();

          /* スレ設定を取得。ログの二行目に入っている。 */
          const settingText = resArray.shift();
          /* NAME=VALUE<>NAME2=VALUE2 */
          const settingsArray = settingText.trim().split("<>");
          for (let i = 0; i < settingsArray.length; i++) {
            const setting = settingsArray[i].split("=");
            if (!setting[0]) continue;
            setting[1] ??= "";
            settings[setting[0]] = setting[1].trim();
          }

          /* 名無し設定 */
          const name774 = settings.BBS_NONAME_NAME ?? "774@3ch";

          /* ログを一行ずつ読んでいく。。。 */
          for (let i = 0; i < resArray.length; i++) {
            /* 0:num<>1:name<>2:sikibetsuId<>3:time<>4:id<>5:MESSAGE<>6:HOST999 ただし6:HOST999はここでは使わない */
            const res = resArray[i].trim().split("<>");

            /* num無し、numが番号でない、num < 1、timeなし、本文部分まで<>がない、削除済み（num=delレス番） -> 表示しない */
            if(!res[0] || !/^[0-9]+$/.test(res[0]) || res[0] < 1 || !res[3] || res[5] == null) continue;
            /* 削除済みレスは表示しない。numの数値チェックのところで弾かれるが、一応。 */
            if(res[0].slice(0,3) === "del") continue;

            /* 名無しをセット */
            let name = res[1] !== "" ? res[1] : name774;

            /* レス削除などによるレス番飛びを検出する。更新前のresnumが前のレスの番号でなければ、空いた分だけ行を挿入する。 */
            if (resnum !== res[0] - 1) {
              for (let j = resnum + 1; j < res[0]; j++) {
                dat += j + "<>あぼーん<>あぼーん<>あぼーん<>あぼーん\n";
              }
            }

            resnum = parseInt(res[0]);
              
            /* 日付欄の処理 */
            let date = "";
            if (res[3]) {
              const DATETIME = new Date(res[3].slice(0,10) * 1000);
              date = DATETIMEFORMAT.format(DATETIME);
              if (res[3].slice(10)) date += `.${res[3].slice(10)}`;
            }

            /* 識別ID欄を名前欄に追加 */
            if (res[2]) name += ` </b>(${res[2]})<b>`;
            
            /* ID欄を日付欄に追加 */
            if (res[4]) date += ` ${res[4]}`;
            
            /* <br>の前後にスペースを挿入。必要あるのかはわからないけど、一応。 */
            const MESSAGE = res[5].replace(/<br>/g, " <br> ");

            dat += `${name}<><>${date}<> ${MESSAGE} <>`;
            dat += resnum === 1 ? `${subject}\n` : "\n";
          }
        }

        // 取得したdatをDOに書き込む
        await this.state.storage.put('dat', dat);
      } catch (e) {
        dat = "";
      }
    }

    return dat || '';
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
    const newDat = await this.getDat() + datLine + "\n";
    const newLog = await this.getLog() + logLine + "\n";
    await this.state.storage.put({ dat: newDat, log: newLog });
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

  /**
   * datを上書きします。
   * @param {string} dat - 上書き内容
   */
  async setDat(dat) {
    await this.state.storage.put('dat', dat);
  }
}
