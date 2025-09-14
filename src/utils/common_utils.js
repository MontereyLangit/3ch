import { encode, decode } from 'encoding-japanese';

/**
 * 文字列をSJISのバイト配列にエンコードします。
 * @param {string} str UTF-8文字列
 * @returns {Uint8Array} SJISのバイト配列
 */
export function encodeSJIS(str) {
  return new Uint8Array(encode(str, 'SJIS'));
}

/**
 * SJISのバイト配列をUTF-8文字列にデコードします。
 * @param {Uint8Array | Array<number>} bytes SJISのバイト配列
 * @returns {string} UTF-8文字列
 */
export function decodeSJIS(bytes) {
  return decode(bytes, 'SJIS');
}

/**
 * 本文からURL部分(先頭のttps?除去済み)を取り出します
 * @param {string} text 本文
 * @return {string} URL
 */
export const extractAndSanitizeUrls = function (text) {
  // http:// または https:// で始まるURLを検索する正規表現
  const urlRegex = /(h?ttps?:\/\/[^\s]+)/g;

  // URLを抽出して格納する配列
  let urls = [];
  let match;

  // 正規表現にマッチするすべてのURLをループして抽出
  while ((match = urlRegex.exec(text)) !== null) {
    urls.push(match[1]);
  }

  // 抽出したURLのプロトコル部分を削除
  const sanitizedUrls = urls.map(url => {
    return url.replace(/h?ttps?:\/\//, '');
  });

  return sanitizedUrls.join("");
}

/**
 * 入力したスレッド一覧テキスト(subject.txt)をスレッド一覧配列に変換します
 * @param {string} subjectText スレッド一覧テキスト(subject.txt)
 * @return {Array<Object>} スレッド一覧配列
 */
export const convertSubjectTextTosubjectArray = function (subjectText) {
  if (!subjectText) return [];
  let subjectArray = [];

  const array = subjectText.split("\n");
  for (let i = 0; i < array.length; i++) {
    // ${key}.dat<>${subject} (${resnum})\n
    const thread = array[i].trim().split("<>");
    // 壊れた行を取り除く
    if (!thread[0] || !/^[0-9]{10}\.dat$/.test(thread[0]) || !thread[1] || !/.*\s\([0-9]+\)$/.test(thread[1])) continue;

    const match = thread[1].match(/(.*)\s\(([0-9]+)\)$/);
    if (match) {
        const subjectObject = { key: thread[0].replace('.dat', ''), subject: match[1], resnum: parseInt(match[2], 10) };
        subjectArray.push(subjectObject);
    }
  }

  return subjectArray;
}

/**
 * 入力したスレッド一覧配列をスレッド一覧テキスト(subject.txt)に変換します
 * @param {Array<Object>} subjectArray スレッド一覧配列
 * @return {string} スレッド一覧テキスト(subject.txt)
 */
export const convertSubjectArrayToSubjectText = function (subjectArray) {
  if (!subjectArray || subjectArray.length === 0) return "";
  
  return subjectArray.map(thread => 
    `${thread.key}.dat<>${thread.subject} (${thread.resnum})`
  ).join("\n") + "\n";
}

/**
 * 入力したdatをスレッド配列に変換します
 * @param {string} dat
 * @return {Array<Object>} スレッド配列
 */
export const convertDatToThreadArray = function (dat) {
    if (!dat) return [];
    const lines = dat.trim().split('\n');
    const threadArray = [];

    for (const line of lines) {
        const parts = line.split('<>');
        if (parts.length >= 6) { // name, mail, date, message, title
            threadArray.push({
                name: parts[0],
                mail: parts[1],
                date: parts[2],
                message: parts[3],
                title: parts[4]
            });
        }
    }
    return threadArray;
}

/**
 * 入力したスレッド配列をdat形式のテキストに変換します
 * @param {Array<Object>} threadArray スレッド配列
 * @return {string} dat
 */
export const convertThreadArrayToDat = function (threadArray) {
  if (!threadArray || threadArray.length === 0) return "";

  return threadArray.map(res =>
      [res.name, res.mail, res.date, res.message, res.title].join('<>')
  ).join('\n') + '\n';
}
