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

  // Validation failures must not reset the DO (blockConcurrencyWhile rejects reset it).
  async exclusive(callback) {
    const result = await this.state.blockConcurrencyWhile(async () => {
      try { return { value: await callback() }; }
      catch (error) { return { error: error.message }; }
    });
    if (result.error) throw new Error(result.error);
    return result.value;
  }

  /**
   * dat形式の全スレッドデータを文字列として返します。
   */
  async getDat(bbs = '', key = '0') {
    if (await this.state.storage.get('hidden')) return '';
    let dat = await this.state.storage.get('dat');
    if (!dat && bbs && Number(key) > 1761990000 && Number(key) < 1762087165) {
      const legacy = this.env.THREAD_DO.get(this.env.THREAD_DO.idFromName(key));
      dat = await legacy.getDat();
      if (dat) await this.state.storage.put({ dat, log: await legacy.getLog(), settings: await legacy.getSettings() });
    }

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
          await this.state.storage.put('legacyLog', log);
          const resArray = log.split("\n");

          /* スレの配列に最低3個要素がないとおかしい */
          if(resArray.length < 3) throw new Error("スレッドのログファイルが壊れています。運用情報板で修理してもらってください。");

          /* スレッドタイトルを取得。ログの一行目に入っている。 */
          const subject = resArray.shift();

          /* スレ設定を取得。ログの二行目に入っている。 */
          const settingText = resArray.shift();
          await this.state.storage.put('settings', settingText);
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
  async getPublicData(bbs, key) {
    const dat = await this.getDat(bbs, key);
    return { dat, modifiedAt: await this.state.storage.get('modifiedAt'), edited: !!await this.state.storage.get('edited') };
  }

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
  async addRes(datLine, logLine, posting) {
    return this.exclusive(async () => {
      if (await this.state.storage.get('hidden') || await this.state.storage.get('stopped') || await this.state.storage.get('movingTo')) throw new Error('このスレッドは停止・移動または削除されています。');
      const dat = await this.getDat(posting?.bbs, posting?.key);
      if (/(?:^|<>)BBS_HEISA=checked(?:<>|$)/.test(await this.getSettings())) throw new Error('このスレッドは停止されています。');
      if (posting?.create && dat) throw new Error('同じkeyのスレッドが存在します。');
      if (posting && !posting.create && !dat) throw new Error('スレッドがありません。');
      const count = dat.trimEnd().split('\n').filter(Boolean).length + 1;
      if (count >= 1000) throw new Error('このスレッドは1000を超えたのでもう書けません。');
      const newLog = await this.getLog() + logLine.replace(/^\d+<>/, `${count}<>`) + '\n';
      const update = { dat: dat + datLine + '\n', log: newLog, modifiedAt: Date.now() };
      if (posting?.create) update.settings = posting.initialSettings || '';
      await this.state.storage.put(update);
      if (posting) await this.env.BOARD_DO.get(this.env.BOARD_DO.idFromName(posting.bbs)).updateThread({ key: posting.key, title: posting.title, resNum: count }, posting.sage, posting.maxThreads);
      return count;
    });
  }

  async inspect(bbs, key) {
    await this.getDat(bbs, key);
    const data = await this.state.storage.get(['dat', 'log', 'legacyLog', 'settings', 'hidden', 'stopped', 'movedTo', 'movingTo', 'importedFrom']);
    if (!data.has('legacyLog') && bbs && key) {
      const legacy = await this.env.ch3www.get(`${bbs}/thread/${key.slice(0,4)}/${key}.cgi`);
      if (legacy) {
        data.set('legacyLog', await legacy.text());
        await this.state.storage.put('legacyLog', data.get('legacyLog'));
      }
    }
    return Object.fromEntries(data);
  }

  async moderate(action, input) {
    if (action === 'move') {
      const target = `${input.targetBoard}/${input.targetKey}`;
      if (target === `${input.bbs}/${input.key}`) throw new Error('移動元と移動先が同じです。');
      const destination = await this.env.THREAD_DO.get(this.env.THREAD_DO.idFromName(target)).inspect(input.targetBoard, input.targetKey);
      if ((destination.dat || destination.hidden) && destination.importedFrom !== `${input.bbs}/${input.key}`) throw new Error('移動先のkeyは使用済みです。');
    }
    return this.exclusive(async () => {
      const dat = await this.state.storage.get('dat');
      if (!dat) throw new Error('スレッドがありません。');
      const board = this.env.BOARD_DO.get(this.env.BOARD_DO.idFromName(input.bbs));
      if (await this.state.storage.get('pendingMove')) throw new Error('移動先の準備中です。移動元から同じ移動を再実行してください。');
      if (action !== 'move' && await this.state.storage.get('movingTo')) throw new Error('移動処理中です。同じ移動先で再実行してください。');
      if (action === 'delete') {
        await this.state.storage.put('hidden', true);
        await board.removeThread(input.key);
      } else if (action === 'stop' || action === 'resume') {
        await this.state.storage.put('stopped', action === 'stop');
        const settings = (await this.getSettings()).split('<>').filter(x => x && !x.startsWith('BBS_HEISA='));
        settings.push(`BBS_HEISA=${action === 'stop' ? 'checked' : ''}`);
        await this.state.storage.put('settings', settings.join('<>'));
      } else if (action === 'editRes' || action === 'deleteRes') {
        const lines = dat.trimEnd().split('\n');
        const n = Number(input.number);
        if (!Number.isInteger(n) || n < 1 || n > lines.length) throw new Error('レス番号が不正です。');
        const fields = lines[n - 1].split('<>');
        if (action === 'deleteRes') fields.splice(0, 4, 'あぼーん', 'あぼーん', 'あぼーん', 'あぼーん');
        else {
          const escape = value => String(value ?? '').replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;').replace(/\r?\n/g, ' <br> ');
          fields[0] = escape(input.name); fields[1] = escape(input.mail); fields[3] = escape(input.message);
        }
        lines[n - 1] = fields.join('<>');
        await this.state.storage.put({ dat: lines.join('\n') + '\n', modifiedAt: Date.now(), edited: true });
      } else if (action === 'move') {
        const target = `${input.targetBoard}/${input.targetKey}`;
        const movingTo = await this.state.storage.get('movingTo');
        if (movingTo && movingTo !== target) throw new Error('処理中の移動先と異なります。');
        if (await this.state.storage.get('hidden') && !movingTo) throw new Error('削除・移動済みスレッドは移動できません。');
        if (target === `${input.bbs}/${input.key}`) throw new Error('移動元と移動先が同じです。');
        const stub = this.env.THREAD_DO.get(this.env.THREAD_DO.idFromName(target));
        const source = `${input.bbs}/${input.key}`;
        await this.state.storage.put('movingTo', target);
        try {
          await stub.receiveMove({ dat, log: await this.getLog(), legacyLog: await this.state.storage.get('legacyLog') || '', settings: await this.getSettings(), stopped: !!await this.state.storage.get('stopped'), edited: !!await this.state.storage.get('edited'), modifiedAt: Date.now() }, source);
        } catch (error) {
          if (error.message.includes('移動先のkeyは使用済み')) await this.state.storage.delete('movingTo');
          throw error;
        }
        await this.state.storage.put({ hidden: true, movedTo: target });
        await board.removeThread(input.key);
        await stub.publishMove(source, input.targetBoard, input.targetKey);
        await this.state.storage.delete('movingTo');
      } else throw new Error('操作が不正です。');
    });
  }

  async receiveMove(data, source) {
    return this.exclusive(async () => {
      if (await this.state.storage.get('importedFrom') === source) return;
      if (await this.state.storage.get('dat') || await this.state.storage.get('hidden')) throw new Error('移動先のkeyは使用済みです。');
      await this.state.storage.put({ ...data, importedFrom: source, hidden: true, pendingMove: true });
    });
  }

  async publishMove(source, bbs, key) {
    return this.exclusive(async () => {
      if (await this.state.storage.get('importedFrom') !== source) throw new Error('移動元が一致しません。');
      if (!await this.state.storage.get('pendingMove')) return;
      const dat = await this.state.storage.get('dat');
      await this.env.BOARD_DO.get(this.env.BOARD_DO.idFromName(bbs)).updateThread({ key, title: dat.split('\n')[0].split('<>')[4], resNum: dat.trimEnd().split('\n').length }, false);
      await this.state.storage.put({ hidden: false, pendingMove: false });
    });
  }

  /**
   * 指定されたレス番号をあぼーん化します。
   * @param {number} resNum - あぼーん化するレス番号 (1-based)
   */
  async deleteRes(resNum) {
    const dat = await this.getDat();
    const lines = dat.split('\n');
    if (resNum > 0 && resNum < lines.length) {
      const lineNum = resNum - 1; // 配列なので先頭ズレるはず
      lines[lineNum] = 'あぼーん<>あぼーん<>あぼーん<>あぼーん<>あぼーん';
      await this.state.storage.put('dat', lines.join('\n'));
    }
  }

  /**
   * datを上書きします。
   * @param {string} dat - 上書き内容
   */
  async setDat(dat) {
    if (await this.state.storage.get('hidden')) return;
    await this.state.storage.put('dat', dat);
  }
}
