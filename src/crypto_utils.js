import { crypt as unixCrypt } from 'unix-crypt-td-js';
/**
 * 16進文字列をバイナリ文字列に変換します。
 * @param {string} hex 
 * @returns {string}
 */
export function hexToBinary(hex) {
    return Array.from({ length: hex.length / 2 }, (_, i) =>
        String.fromCharCode(parseInt(hex.substr(i * 2, 2), 16))
    ).join('');
}

/**
 * 文字列のSHA-1ハッシュを計算し、Base64エンコードして返します。
 * @param {string} str 
 * @returns {Promise<string>}
 */
export async function sha1Base64(str) {
    const encoder = new TextEncoder();
    const data = encoder.encode(str);
    const buffer = await crypto.subtle.digest('SHA-1', data);
    const bytes = new Uint8Array(buffer);
    const result = btoa(String.fromCharCode(...bytes));
    return result.slice(0, 27);
}

/**
 * 指定されたアルゴリズムで文字列のハッシュを計算します。
 * @param {string} algo 
 * @param {string} str 
 * @returns {Promise<string>}
 */
export async function hash(algo, str) {
    const encoder = new TextEncoder();
    const data = encoder.encode(str);
    const hashBuffer = await crypto.subtle.digest(algo, data);
    return [...new Uint8Array(hashBuffer)]
        .map(b => b.toString(16).padStart(2, '0'))
        .join('');
}

/**
 * MD5ハッシュを計算します。
 * @param {string} str 
 * @returns {string}
 */
export function md5(str) {
  // ... (md5関数の実装は長いため省略)
  // common.jsからそのままコピーしてください
}

/**
 * Unix crypt(3) を実装します。
 * @param {string} pw 
 * @param {string} salt 
 * @returns {string}
 */
export const crypt = (pw, salt) => unixCrypt(pw, salt);

export function translateSaltChar(c) {
    const code = c.charCodeAt(0);
    if ((code >= 0x3A && code <= 0x40) ||
        (code >= 0x5B && code <= 0x60) ||
        (code >= 0x00 && code <= 0x2D) ||
        (code >= 0x7B && code <= 0xFF)) {
        const table = "A-Ga-f./";
        return table[(code - (code >= 0x7B ? 0x7B : (code >= 0x5B ? 0x5B : (code >= 0x3A ? 0x3A : 0x00))) % table.length)];
    }
    return c;
}


// MD5 and Crypt implementations from common.js

/*
 * md5
 */
export function md5(str) {
  function cmn(q, a, b, x, s, t) {
    a = (((a + q) | 0) + ((x + t) | 0)) | 0;
    return (((a << s) | (a >>> (32 - s))) + b) | 0;
  }

  function ff(a, b, c, d, x, s, t) {
    return cmn((b & c) | (~b & d), a, b, x, s, t);
  }

  function gg(a, b, c, d, x, s, t) {
    return cmn((b & d) | (c & ~d), a, b, x, s, t);
  }

  function hh(a, b, c, d, x, s, t) {
    return cmn(b ^ c ^ d, a, b, x, s, t);
  }

  function ii(a, b, c, d, x, s, t) {
    return cmn(c ^ (b | ~d), a, b, x, s, t);
  }

  function md5cycle(x, k) {
    let [a, b, c, d] = x;

    a = ff(a, b, c, d, k[0], 7, -680876936);
    d = ff(d, a, b, c, k[1], 12, -389564586);
    c = ff(c, d, a, b, k[2], 17, 606105819);
    b = ff(b, c, d, a, k[3], 22, -1044525330);
    a = ff(a, b, c, d, k[4], 7, -176418897);
    d = ff(d, a, b, c, k[5], 12, 1200080426);
    c = ff(c, d, a, b, k[6], 17, -1473231341);
    b = ff(b, c, d, a, k[7], 22, -45705983);
    a = ff(a, b, c, d, k[8], 7, 1770035416);
    d = ff(d, a, b, c, k[9], 12, -1958414417);
    c = ff(c, d, a, b, k[10], 17, -42063);
    b = ff(b, c, d, a, k[11], 22, -1990404162);
    a = ff(a, b, c, d, k[12], 7, 1804603682);
    d = ff(d, a, b, c, k[13], 12, -40341101);
    c = ff(c, d, a, b, k[14], 17, -1502002290);
    b = ff(b, c, d, a, k[15], 22, 1236535329);

    a = gg(a, b, c, d, k[1], 5, -165796510);
    d = gg(d, a, b, c, k[6], 9, -1069501632);
    c = gg(c, d, a, b, k[11], 14, 643717713);
    b = gg(b, c, d, a, k[0], 20, -373897302);
    a = gg(a, b, c, d, k[5], 5, -701558691);
    d = gg(d, a, b, c, k[10], 9, 38016083);
    c = gg(c, d, a, b, k[15], 14, -660478335);
    b = gg(b, c, d, a, k[4], 20, -405537848);
    a = gg(a, b, c, d, k[9], 5, 568446438);
    d = gg(d, a, b, c, k[14], 9, -1019803690);
    c = gg(c, d, a, b, k[3], 14, -187363961);
    b = gg(b, c, d, a, k[8], 20, 1163531501);
    a = gg(a, b, c, d, k[13], 5, -1444681467);
    d = gg(d, a, b, c, k[2], 9, -51403784);
    c = gg(c, d, a, b, k[7], 14, 1735328473);
    b = gg(b, c, d, a, k[12], 20, -1926607734);

    a = hh(a, b, c, d, k[5], 4, -378558);
    d = hh(d, a, b, c, k[8], 11, -2022574463);
    c = hh(c, d, a, b, k[11], 16, 1839030562);
    b = hh(b, c, d, a, k[14], 23, -35309556);
    a = hh(a, b, c, d, k[1], 4, -1530992060);
    d = hh(d, a, b, c, k[4], 11, 1272893353);
    c = hh(c, d, a, b, k[7], 16, -155497632);
    b = hh(b, c, d, a, k[10], 23, -1094730640);
    a = hh(a, b, c, d, k[13], 4, 681279174);
    d = hh(d, a, b, c, k[0], 11, -358537222);
    c = hh(c, d, a, b, k[3], 16, -722521979);
    b = hh(b, c, d, a, k[6], 23, 76029189);
    a = hh(a, b, c, d, k[9], 4, -640364487);
    d = hh(d, a, b, c, k[12], 11, -421815835);
    c = hh(c, d, a, b, k[15], 16, 530742520);
    b = hh(b, c, d, a, k[2], 23, -995338651);

    a = ii(a, b, c, d, k[0], 6, -198630844);
    d = ii(d, a, b, c, k[7], 10, 1126891415);
    c = ii(c, d, a, b, k[14], 15, -1416354905);
    b = ii(b, c, d, a, k[5], 21, -57434055);
    a = ii(a, b, c, d, k[12], 6, 1700485571);
    d = ii(d, a, b, c, k[3], 10, -1894986606);
    c = ii(c, d, a, b, k[10], 15, -1051523);
    b = ii(b, c, d, a, k[1], 21, -2054922799);
    a = ii(a, b, c, d, k[8], 6, 1873313359);
    d = ii(d, a, b, c, k[15], 10, -30611744);
    c = ii(c, d, a, b, k[6], 15, -1560198380);
    b = ii(b, c, d, a, k[13], 21, 1309151649);
    a = ii(a, b, c, d, k[4], 6, -145523070);
    d = ii(d, a, b, c, k[11], 10, -1120210379);
    c = ii(c, d, a, b, k[2], 15, 718787259);
    b = ii(b, c, d, a, k[9], 21, -343485551);

    x[0] = (x[0] + a) | 0;
    x[1] = (x[1] + b) | 0;
    x[2] = (x[2] + c) | 0;
    x[3] = (x[3] + d) | 0;
  }

  function md51(s) {
    const utf8 = new TextEncoder().encode(s); // UTF-8エンコードされたUint8Array
    const n = utf8.length;
    const state = [1732584193, -271733879, -1732584194, 271733878];

    let i;
    for (i = 64; i <= n; i += 64) {
      md5cycle(state, md5blk(utf8.subarray(i - 64, i)));
    }

    const tail = new Uint8Array(64);
    tail.set(utf8.subarray(i - 64, n));
    tail[n % 64] = 0x80;

    if (n % 64 >= 56) {
      md5cycle(state, md5blk(tail));
      tail.fill(0);
    }

    const bitLen = n * 8;
    tail[56] = bitLen & 0xff;
    tail[57] = (bitLen >>> 8) & 0xff;
    tail[58] = (bitLen >>> 16) & 0xff;
    tail[59] = (bitLen >>> 24) & 0xff;

    md5cycle(state, md5blk(tail));

    return state;
  }

  function md5blk(arr) {
    const blks = new Array(16);
    for (let i = 0; i < 64; i += 4) {
      blks[i >> 2] =
        arr[i] +
        (arr[i + 1] << 8) +
        (arr[i + 2] << 16) +
        (arr[i + 3] << 24);
    }
    return blks;
  }

  function rhex(n) {
    const s = [];
    for (let j = 0; j < 4; j++) {
      s.push(((n >> (j * 8)) & 0xff).toString(16).padStart(2, "0"));
    }
    return s.join("");
  }

  return md51(str).map(rhex).join("");
}
