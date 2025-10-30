/**
 * @typedef {object} ThreadData
 * @property {string} key
 * @property {string} title
 * @property {number} resNum
 */

/**
 * 板のスレッド一覧を管理するDurable Object (Board)
 * 1つの掲示板に対して1つのインスタンスが対応します。
 */
export class Board {
  /**
   * @param {DurableObjectState} state
   */
  constructor(state) {
    this.state = state;
  }

  /**
   * subject.txt形式の文字列を返します。
   */
  async getSubject() {
    return (await this.state.storage.get('subjectList')) || '';
  }

  /**
   * スレッド一覧を更新します。
   * @param {ThreadData} threadData - 更新対象のスレッド情報
   * @param {boolean} isSage - sage投稿の場合はtrue
   * @param {number} maxThreads - 板の最大スレッド数
   */
  async updateThread(threadData, isSage, maxThreads) {
    // 最大スレッド数のデフォルト値を適用
    maxThreads = maxThreads ?? 700;

    const subjectList = await this.getSubject();
    let lines = subjectList.length > 0 ? subjectList.split('\n') : [];

    // 最後の空行を除外
    if (lines.length > 0 && lines[lines.length - 1] === '') {
      lines.pop();
    }

    const threadKey = threadData.key;
    const newThreadLine = `${threadKey}.dat<>${threadData.title} (${threadData.resNum})`;
    const threadIndex = lines.findIndex((line) => line.startsWith(`${threadKey}.dat<>`));

    if (threadIndex !== -1) {
      // 既存スレッドの更新
      if (isSage) {
        // sage: レス数のみ更新
        lines[threadIndex] = newThreadLine;
      } else {
        // age: 一覧の先頭に移動
        lines.splice(threadIndex, 1);
        lines.unshift(newThreadLine);
      }
    } else {
      // 新規スレッド作成: 先頭に追加
      lines.unshift(newThreadLine);
    }

    // 最大スレッド数を超えた分を削除
    if (lines.length > maxThreads) {
      lines = lines.slice(0, maxThreads);
    }

    let newSubjectList = lines.join('\n') + (lines.length > 0 ? '\n' : '');
    const encoder = new TextEncoder();
    let encodedList = encoder.encode(newSubjectList);

    const MAX_SUBJECT_LIST_BYTES = 512 * 1024; // 512KB

    // subjectListが512KBを超えた場合、末尾から行を削除
    while (encodedList.byteLength > MAX_SUBJECT_LIST_BYTES && lines.length > 0) {
      lines.pop(); // 最後の行を削除
      newSubjectList = lines.join('\n') + (lines.length > 0 ? '\n' : '');
      encodedList = encoder.encode(newSubjectList);
    }

    await this.state.storage.put('subjectList', newSubjectList);
  }
}
