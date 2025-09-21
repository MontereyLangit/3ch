import Encoding from 'encoding-japanese';

/**
 * 文字列をSJISのバイト配列にエンコードします。
 * @param str UTF-8文字列
 * @returns SJISのバイト配列
 */
export function encodeSJIS(str: string): Uint8Array {
  // encodeの戻り値は number[] なので Uint8Array に変換
  return new Uint8Array(Encoding.convert(Encoding.stringToCode(str), { to: 'SJIS', from: 'UNICODE' }));
}

/**
 * SJISのバイト配列をUTF-8文字列にデコードします。
 * @param bytes SJISのバイト配列
 * @returns UTF-8文字列
 */
export function decodeSJIS(bytes: Uint8Array | number[]): string {
  return Encoding.codeToString(Encoding.convert(bytes, { to: 'UNICODE', from: 'SJIS' }));
}

/**
 * 本文からURL部分(先頭のttps?除去済み)を取り出します
 * @param text 本文
 * @return URL
 */
export const extractAndSanitizeUrls = function (text: string): string {
  // http:// または https:// で始まるURLを検索する正規表現
  const urlRegex = /(h?ttps?:\/\/[^\s]+)/g;

  // URLを抽出して格納する配列
  const urls: string[] = [];
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

interface Subject {
    key: string;
    subject: string;
    resnum: number;
}

/**
 * 入力したスレッド一覧テキスト(subject.txt)をスレッド一覧配列に変換します
 * @param subjectText スレッド一覧テキスト(subject.txt)
 * @return スレッド一覧配列
 */
export const convertSubjectTextTosubjectArray = function (subjectText: string): Subject[] {
  if (!subjectText) return [];
  const subjectArray: Subject[] = [];

  const array = subjectText.split("\n");
  for (let i = 0; i < array.length; i++) {
    // ${key}.dat<>${subject} (${resnum})\n
    const thread = array[i].trim().split("<>");
    // 壊れた行を取り除く
    if (!thread[0] || !/^[0-9]{10}\.dat$/.test(thread[0]) || !thread[1] || !/.*\s\([0-9]+\)$/.test(thread[1])) continue;

    const match = thread[1].match(/(.*)\s\(([0-9]+)\)$/);
    if (match) {
        const subjectObject: Subject = { key: thread[0].replace('.dat', ''), subject: match[1], resnum: parseInt(match[2], 10) };
        subjectArray.push(subjectObject);
    }
  }

  return subjectArray;
}

/**
 * 入力したスレッド一覧配列をスレッド一覧テキスト(subject.txt)に変換します
 * @param subjectArray スレッド一覧配列
 * @return スレッド一覧テキスト(subject.txt)
 */
export const convertSubjectArrayToSubjectText = function (subjectArray: Subject[]): string {
  if (!subjectArray || subjectArray.length === 0) return "";
  
  return subjectArray.map(thread => 
    `${thread.key}.dat<>${thread.subject} (${thread.resnum})`
  ).join("\n") + "\n";
}

interface ThreadRes {
    name: string;
    mail: string;
    date: string;
    message: string;
    title: string;
}

/**
 * 入力したdatをスレッド配列に変換します
 * @param dat
 * @return スレッド配列
 */
export const convertDatToThreadArray = function (dat: string): ThreadRes[] {
    if (!dat) return [];
    const lines = dat.trim().split('\n');

    return lines.map(line => {
        const parts = line.split('<>');
        return {
            name: parts[0] || '',
            mail: parts[1] || '',
            date: parts[2] || '',
            message: parts[3] || '',
            title: parts[4] || ''
        };
    }).filter(res => res.name || res.mail || res.date || res.message || res.title); // 空の行を除外
}

/**
 * 入力したスレッド配列をdat形式のテキストに変換します
 * @param threadArray スレッド配列
 * @return dat
 */
export const convertThreadArrayToDat = function (threadArray: ThreadRes[]): string {
  if (!threadArray || threadArray.length === 0) return "";

  return threadArray.map(res =>
      [res.name, res.mail, res.date, res.message, res.title].join('<>')
  ).join('\n') + '\n';
}