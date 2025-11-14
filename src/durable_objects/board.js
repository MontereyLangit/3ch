/**
 * @typedef {object} ThreadData
 * @property {string} key
 * @property {string} title
 * @property {number} resNum
 */

import { DurableObject } from "cloudflare:workers";

/**
 * 板のスレッド一覧を管理するDurable Object (Board)
 * 1つの掲示板に対して1つのインスタンスが対応します。
 */
export class Board extends DurableObject {
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
   * subject.txt形式の文字列を返します。
   * @return {Promise<string>} subject.txt形式の文字列
   */
  async getSubject(bbs = '', pastlog = '0') {
    let subjectList = await this.state.storage.get('subjectList');

    if (!subjectList && bbs && pastlog && parseInt(pastlog) > 0 && parseInt(pastlog) < 1764) {
        const filename = `${bbs}/thread/${pastlog}/subject.txt`;
        try {
            const object = await this.env.ch3www.get(filename);
            if (object !== null) {
                subjectList = await object.text();
                await this.state.storage.put('subjectList', subjectList);
            }
        } catch (e) {
            // エラーが発生した場合、subjectListはnullのままにする
        }
    }

    return subjectList || '';
  }

  /**
   * スレッド一覧を更新します。
   * @param {ThreadData} threadData - 更新対象のスレッド情報
   * @param {boolean} isSage - sage投稿の場合はtrue
   * @param {number} maxThreads - 板の最大スレッド数
   */
  async updateThread(threadData, isSage, maxThreads) {
    maxThreads = maxThreads ?? 700;

    const subjectList = await this.getSubject();
    let lines = subjectList.length > 0 ? subjectList.split('\n') : [];

    if (lines.length > 0 && lines[lines.length - 1] === '') {
      lines.pop();
    }

    const threadKey = threadData.key;
    const threadIndex = lines.findIndex((line) => line.startsWith(`${threadKey}.dat<>`));
    let newThreadLine;

    if (threadIndex !== -1) {
      const existingLine = lines[threadIndex];
      // titleがnullの場合は既存の行からタイトルを抽出して使用する
      const title = threadData.title ?? existingLine.substring(existingLine.indexOf('<>') + 2, existingLine.lastIndexOf(' ('));
      newThreadLine = `${threadKey}.dat<>${title} (${threadData.resNum})`;
      if (isSage) {
        lines[threadIndex] = newThreadLine;
      } else {
        lines.splice(threadIndex, 1);
        lines.unshift(newThreadLine);
      }
    } else {
      newThreadLine = `${threadKey}.dat<>${threadData.title} (${threadData.resNum})`;
      lines.unshift(newThreadLine);
    }

    if (lines.length > maxThreads) {
      lines = lines.slice(0, maxThreads);
    }

    let newSubjectList = lines.join('\n') + (lines.length > 0 ? '\n' : '');
    const encoder = new TextEncoder();
    let encodedList = encoder.encode(newSubjectList);

    const MAX_SUBJECT_LIST_BYTES = 512 * 1024;

    // subjectListが512KBを超えた場合、末尾から行を削除
    while (encodedList.byteLength > MAX_SUBJECT_LIST_BYTES && lines.length > 0) {
      lines.pop(); // 最後の行を削除
      newSubjectList = lines.join('\n') + (lines.length > 0 ? '\n' : '');
      encodedList = encoder.encode(newSubjectList);
    }

    await this.state.storage.put('subjectList', newSubjectList);
  }
}
