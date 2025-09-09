export const sjis = class {
  constructor() {
    this.e = {}; this.d = {};
    ((td = new TextDecoder('sjis'), f = w =>
      (_ => _.length == 1 &&
        (this.e[_] = w).reduce((a, x, i, { length: l }) =>
          (i == l - 1 ? a[x] = _ : a[x] ||= {}), this.d)
      )(td.decode(new Uint8Array(w)))
    ) => [...Array(238)].forEach((_, i) =>
      i < 191
        ? f([i + (i < 128 ? 0 : 0xa1 - 128)])
        : [...Array(188)].forEach((_, j) =>
          f([i - 191 + (i - 191 < 31 ? 0x81 : 0xe0 - 31),
            j + 0x40 + (62 < j ? 1 : 0)]))
    ))();
  }
  encode(w) { return [...w].flatMap(x => this.e[x]); }
  decode(w) {
    return w.reduce((a, x) =>
      (x = a.x[x], x.length ? (a.a += x, a.x = this.d) : a.x = x, a),
      { a: '', x: this.d }).a;
  }
};

/* 
 * テキストデータの出力処理（UTF-8 or Shift_JIS）
 * text/${type}
 */
export function respondPlainText(text, type, encoding = "UTF-8", rangeHeader = "", lastModified = new Date(), ifModifiedSinceHeader = "", addHeader = {}) {
  let response = null;

  if (ifModifiedSinceHeader && lastModified <= new Date(ifModifiedSinceHeader)) {
    return new Response(null, {
      status: 304,
      headers: {
        "Last-Modified": lastModified.toUTCString()
      }
    });
  }
  /* Shift_JIS */
  else if(encoding === "Shift_JIS") {
    /* UTF-8 text から SJIS bytes に変換 */
    const sjisEncoder = new sjis();
    const sjisBytes = Uint8Array.from(sjisEncoder.encode(text));
    const totalLength = sjisBytes.length;

    /* 差分取得の処理 */
    if (rangeHeader) {
      /* 書式 bytes=start-end */
      const match = rangeHeader.match(/^bytes=(\d*)-(\d*)$/);
      if(!match) {
        return new Response("Bad Range Header", { status: 400 });
      }
      
      let start = parseInt(match[1] || "0", 10);
      let end = match[2] ? parseInt(match[2], 10) : totalLength - 1;

      if(start >= totalLength || start > end) {
        return new Response("Requested Range Not Satisfiable", {
          status: 416,
          headers: {
            "Content-Range": `bytes */${totalLength}`
          }
        });
      }

      end = Math.min(end, totalLength - 1);
      const chunk = sjisBytes.slice(start, end + 1);

      response ??= new Response(chunk, {
        status: 206,
        headers: {
          "Content-Type": `text/${type};charset=Shift_JIS`,
          "Last-Modified": lastModified.toUTCString(),
          "Content-Length": chunk.length.toString(),
          "Content-Range": `bytes ${start}-${end}/${totalLength}`,
          "Accept-Ranges": "bytes"
        }
      });
    }else {
      /* 通常出力 */
      response ??= new Response(sjisBytes, {
        headers: {
          "content-type": `text/${type};charset=Shift_JIS`,
          "Content-Length": totalLength.toString()
        }
      });

      /* text/plain形式の場合のみ最終更新時刻と差分取得ヘッダを出力 */
      if(type === "plain") {
        response.headers.set("Last-Modified", lastModified.toUTCString());
        response.headers.set("Accept-Ranges", "bytes");
      }
    }
  }
  /* UTF-8 */
  else {
    /* 差分取得の処理 */
    const encoder = new TextEncoder();
    const bytes = encoder.encode(text);
    const totalLength = bytes.length;

    if(rangeHeader) {
      const match = rangeHeader.match(/^bytes=(\d*)-(\d*)$/);
      if (!match) {
        return new Response("Bad Range Header", { status: 400 });
      }

      let start = parseInt(match[1] || "0", 10);
      let end = match[2] ? parseInt(match[2], 10) : totalLength - 1;

      if(start >= totalLength || start > end) {
        return new Response("Requested Range Not Satisfiable", {
          status: 416,
          headers: {
            "Content-Range": `bytes */${totalLength}`
          }
        });
      }

      end = Math.min(end, totalLength - 1);
      const chunk = bytes.slice(start, end + 1);

      response ??= new Response(chunk, {
        status: 206,
        headers: {
          "Content-Type": `text/${type}; charset=UTF-8`,
          "Last-Modified": lastModified.toUTCString(),
          "Content-Length": chunk.length.toString(),
          "Content-Range": `bytes ${start}-${end}/${totalLength}`,
          "Accept-Ranges": "bytes"
        }
      });
    }else {
      /* 通常出力 */
      response ??= new Response(text, {
        headers: {
          "content-type": `text/${type};charset=UTF-8`,
          "Content-Length": totalLength.toString()
        }
      });
      
      /* text/plain形式の場合のみ最終更新時刻と差分取得ヘッダを出力
       * Last-Modified, Content-Length, Accept-Rangesあたりは正しくセットされないようなので諦める。差分取得には影響ない模様
       */
      if(type === "plain") {
        response.headers.set("Last-Modified", lastModified.toUTCString());
        response.headers.set("Accept-Ranges", "bytes");
      }
    }
  }

  /* 追加ヘッダの処理 */
  Object.entries(addHeader).forEach(([name,value]) => {
    response.headers.set(name, value);
  });

  return response;
}

export function respond404(pathname) {
  return new Response(
`<!DOCTYPE html>
<html>
<head>
<title>Error 404</title>
<meta http-equiv="Content-type" content="text/html; charset=UTF-8" />
</head>
<body>
<h1>Error 404</h1>
<div id="content">
The page you requested &quot;${pathname}&quot; is not available.
</div>
<hr>
<div id="footer"><a href="https://www.3chbbs.com/">３ちゃんねる</a>(<a href="https://wiki.3chbbs.com/index.php/Project_edge">edge</a>)</div>
</body>
</html>`,
  {
    headers: {
      "content-type": "text/html;charset=UTF-8",
    },
    status: 404
  });
}

export function respond500(errorMsg) {
  return new Response(
`<!DOCTYPE html>
<html>
<head>
<title>500 Internal Server Error</title>
<meta http-equiv="Content-type" content="text/html; charset=UTF-8" />
</head>
<body>
<h1>Internal Server Error</h1>
<div id="content">
The server was unable to complete your request. Please try again later.<br>
If this problem persists, please post the notice on <a href="https://delightly.3chbbs.com/operate3/">the operate3 board</a>.<br>
不明なエラーが発生しました。運用情報板で報告していただけるとありがたいです。<br>
Error Message: ${errorMsg}
</div>
<hr>
<div id="footer"><a href="https://www.3chbbs.com/">３ちゃんねる</a>(<a href="https://wiki.3chbbs.com/index.php/Project_edge">edge</a>)</div>
</body>
</html>`,
  {
    headers: {
      "content-type": "text/html;charset=UTF-8",
    },
    status: 500
  });
}

// 16進文字列 → バイナリ文字列
export function hexToBinary(hex)
{
  return Array.from({ length: hex.length / 2 }, (_, i) =>
    String.fromCharCode(parseInt(hex.substr(i * 2, 2), 16))
  ).join('');
}
// SHA1 → Base64
export async function sha1Base64(str)
{
  const encoder = new TextEncoder();
  const data = encoder.encode(str);
  const buffer = await crypto.subtle.digest('SHA-1', data);
  const bytes = new Uint8Array(buffer);
  const result = btoa(String.fromCharCode(...bytes));
  return result.slice(0,27);
}
export async function hash(algo, str) {
  const encoder = new TextEncoder();
  const data = encoder.encode(str);
  const hashBuffer = await crypto.subtle.digest(algo, data);
  return [...new Uint8Array(hashBuffer)]
    .map(b => b.toString(16).padStart(2, '0'))
    .join('');
}
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


/**
 * Unix crypt(3) Javascript Implementation
 * 
 * Straightforward implementaiton of the DES-based Unix crypt(3) hash, based largely
 * on crypt.c in the Seventh Edition Unix distribution released by Caldera Systems 
 * under a BSD-style license.
 *
 * @author <a href="mailto:tim@timdumol.com">Tim Joseph Dumol</a>
 */

/*
Legalese:

Copyright(C) Tim Joseph F. Dumol 2011. All rights reserved.
Derived from crypt.c in the Seventh Edition Unix distribution by
Caldera International, which is Copyright(C) Caldera International
Inc. 2001-2002. All rights reserved.

Redistribution and use in source and binary forms,
with or without modification, are permitted provided that the
following conditions are met:

Redistributions of source code and documentation must retain the above
copyright notice, this list of conditions and the following
disclaimer.

* Redistributions in binary form must reproduce the above copyright
  notice, this list of conditions and the following disclaimer in the
  documentation and/or other materials provided with the distribution.

* All advertising materials mentioning features or use of this software
  must display the following acknowledgement: This product includes
  software developed or owned by Caldera International, Inc.

* Neither the name of Caldera International, Inc. nor the names of
  other contributors may be used to endorse or promote products derived
  from this software without specific prior written permission.

USE OF THE SOFTWARE PROVIDED FOR UNDER THIS LICENSE BY CALDERA
INTERNATIONAL, INC. AND CONTRIBUTORS ``AS IS'' AND ANY EXPRESS OR
IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL CALDERA INTERNATIONAL, INC. BE LIABLE
FOR ANY DIRECT, INDIRECT INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.  */

/**
 * Implements the Unix crypt(3) DES-based hash.
 *
 * @param {Array.<number>|string} pw The string to hash
 * @param {Array.<number>|string} salt The salt to use (two character string from [a-zA-Z0-9./]).
 * @param {boolean=} returnBytes (optional) If true, return an array of bytes;
 *                                      otherwise, return a string.
 */
export const crypt = (function() {
    /*
     * Initial permutation,
     */
    var IP = [
        58,50,42,34,26,18,10, 2,
        60,52,44,36,28,20,12, 4,
        62,54,46,38,30,22,14, 6,
        64,56,48,40,32,24,16, 8,
        57,49,41,33,25,17, 9, 1,
        59,51,43,35,27,19,11, 3,
        61,53,45,37,29,21,13, 5,
        63,55,47,39,31,23,15, 7
    ];

    /*
     * Final permutation, FP = IP^(-1)
     */
    var FP =[
        40, 8,48,16,56,24,64,32,
        39, 7,47,15,55,23,63,31,
        38, 6,46,14,54,22,62,30,
        37, 5,45,13,53,21,61,29,
        36, 4,44,12,52,20,60,28,
        35, 3,43,11,51,19,59,27,
        34, 2,42,10,50,18,58,26,
        33, 1,41, 9,49,17,57,25
    ];

    /*
     * Permuted-choice 1 from the key bits
     * to yield C and D.
     * Note that bits 8,16... are left out:
     * They are intended for a parity check.
     */
    var PC1_C = [
        57,49,41,33,25,17, 9,
        1,58,50,42,34,26,18,
        10, 2,59,51,43,35,27,
        19,11, 3,60,52,44,36
    ];

    var PC1_D = [
        63,55,47,39,31,23,15,
        7,62,54,46,38,30,22,
        14, 6,61,53,45,37,29,
        21,13, 5,28,20,12, 4
    ];

    /*
     * Sequence of shifts used for the key schedule.
     */
    var shifts =[
        1,1,2,2,2,2,2,2,1,2,2,2,2,2,2,1
    ];

    /*
     * Permuted-choice 2, to pick out the bits from
     * the CD array that generate the key schedule.
     */
    var PC2_C = [
        14,17,11,24, 1, 5,
        3,28,15, 6,21,10,
        23,19,12, 4,26, 8,
        16, 7,27,20,13, 2
    ];

    var PC2_D = [
        41,52,31,37,47,55,
        30,40,51,45,33,48,
        44,49,39,56,34,53,
        46,42,50,36,29,32
    ];

    /*
     * The C and D arrays used to calculate the key schedule.
     */

    var C = [];
    var D = [];
    /*
     * The key schedule.
     * Generated from the key.
     */
    var KS = [];
    for (var i = 0; i < 16; ++i) {
        KS[i] = [];
    }

    /*
     * Set up the key schedule from the key.
     */

    function setkey(key) {
        var i, j, k, t;

        /*
         * First, generate C and D by permuting
         * the key.  The low order bit of each
         * 8-bit char is not used, so C and D are only 28
         * bits apiece.
         */
        for (i=0; i<28; i++) {
            C[i] = key[PC1_C[i]-1];
            D[i] = key[PC1_D[i]-1];
        }
        /*
         * To generate Ki, rotate C and D according
         * to schedule and pick up a permutation
         * using PC2.
         */
        for (i=0; i<16; i++) {
            /*
             * rotate.
             */
            for (k=0; k<shifts[i]; k++) {
                t = C[0];
                for (j=0; j<28-1; j++)
                    C[j] = C[j+1];
                C[27] = t;
                t = D[0];
                for (j=0; j<28-1; j++)
                    D[j] = D[j+1];
                D[27] = t;
            }
            /*
             * get Ki. Note C and D are concatenated.
             */
            for (j=0; j<24; j++) {
                KS[i][j] = C[PC2_C[j]-1];
                KS[i][j+24] = D[PC2_D[j]-28-1];
            }
        }
    }

    /*
     * The E bit-selection table.
     */
    var E = [];
    var e = [
        32, 1, 2, 3, 4, 5,
        4, 5, 6, 7, 8, 9,
        8, 9,10,11,12,13,
        12,13,14,15,16,17,
        16,17,18,19,20,21,
        20,21,22,23,24,25,
        24,25,26,27,28,29,
        28,29,30,31,32, 1
    ];

    /*
     * The 8 selection functions.
     * For some reason, they give a 0-origin
     * index, unlike everything else.
     */
    var S = [
        [14, 4,13, 1, 2,15,11, 8, 3,10, 6,12, 5, 9, 0, 7,
         0,15, 7, 4,14, 2,13, 1,10, 6,12,11, 9, 5, 3, 8,
         4, 1,14, 8,13, 6, 2,11,15,12, 9, 7, 3,10, 5, 0,
         15,12, 8, 2, 4, 9, 1, 7, 5,11, 3,14,10, 0, 6,13],

        [15, 1, 8,14, 6,11, 3, 4, 9, 7, 2,13,12, 0, 5,10,
         3,13, 4, 7,15, 2, 8,14,12, 0, 1,10, 6, 9,11, 5,
         0,14, 7,11,10, 4,13, 1, 5, 8,12, 6, 9, 3, 2,15,
         13, 8,10, 1, 3,15, 4, 2,11, 6, 7,12, 0, 5,14, 9],

        [10, 0, 9,14, 6, 3,15, 5, 1,13,12, 7,11, 4, 2, 8,
         13, 7, 0, 9, 3, 4, 6,10, 2, 8, 5,14,12,11,15, 1,
         13, 6, 4, 9, 8,15, 3, 0,11, 1, 2,12, 5,10,14, 7,
         1,10,13, 0, 6, 9, 8, 7, 4,15,14, 3,11, 5, 2,12],

        [7,13,14, 3, 0, 6, 9,10, 1, 2, 8, 5,11,12, 4,15,
         13, 8,11, 5, 6,15, 0, 3, 4, 7, 2,12, 1,10,14, 9,
         10, 6, 9, 0,12,11, 7,13,15, 1, 3,14, 5, 2, 8, 4,
         3,15, 0, 6,10, 1,13, 8, 9, 4, 5,11,12, 7, 2,14],

        [2,12, 4, 1, 7,10,11, 6, 8, 5, 3,15,13, 0,14, 9,
         14,11, 2,12, 4, 7,13, 1, 5, 0,15,10, 3, 9, 8, 6,
         4, 2, 1,11,10,13, 7, 8,15, 9,12, 5, 6, 3, 0,14,
         11, 8,12, 7, 1,14, 2,13, 6,15, 0, 9,10, 4, 5, 3],

        [12, 1,10,15, 9, 2, 6, 8, 0,13, 3, 4,14, 7, 5,11,
         10,15, 4, 2, 7,12, 9, 5, 6, 1,13,14, 0,11, 3, 8,
         9,14,15, 5, 2, 8,12, 3, 7, 0, 4,10, 1,13,11, 6,
         4, 3, 2,12, 9, 5,15,10,11,14, 1, 7, 6, 0, 8,13],

        [4,11, 2,14,15, 0, 8,13, 3,12, 9, 7, 5,10, 6, 1,
         13, 0,11, 7, 4, 9, 1,10,14, 3, 5,12, 2,15, 8, 6,
         1, 4,11,13,12, 3, 7,14,10,15, 6, 8, 0, 5, 9, 2,
         6,11,13, 8, 1, 4,10, 7, 9, 5, 0,15,14, 2, 3,12],

        [13, 2, 8, 4, 6,15,11, 1,10, 9, 3,14, 5, 0,12, 7,
         1,15,13, 8,10, 3, 7, 4,12, 5, 6,11, 0,14, 9, 2,
         7,11, 4, 1, 9,12,14, 2, 0, 6,10,13,15, 3, 5, 8,
         2, 1,14, 7, 4,10, 8,13,15,12, 9, 0, 3, 5, 6,11]
    ]

    /*
     * P is a permutation on the selected combination
     * of the current L and key.
     */
    var P = [
        16, 7,20,21,
        29,12,28,17,
        1,15,23,26,
        5,18,31,10,
        2, 8,24,14,
        32,27, 3, 9,
        19,13,30, 6,
        22,11, 4,25
    ];

    /*
     * The current block, divided into 2 halves.
     */
    var L = [], R = [];
    var tempL = [[]];
    var f =[];

    /*
     * The combination of the key and the input, before selection.
     */
    var preS = [];

    /*
     * The payoff: encrypt a block.
     */

    function encrypt(block, edflag) {
        var i, ii, j, k, t;

        /*
         * First, permute the bits in the input
         */
        var perm = [];
        for (j=0; j<64; j++) {
            perm[j] = block[IP[j]-1];
        }
        for (j=0; j<32; ++j) {
            L[j] = perm[j];
            R[j] = perm[j+32];
        }
        /*
         * Perform an encryption operation 16 times.
         */
        for (ii=0; ii<16; ii++) {
            /*
             * Set direction
             */
            if (edflag)
                i = 15-ii;
            else
                i = ii;
            /*
             * Save the R array,
             * which will be the new L.
             */
            for (j=0; j<32; j++)
                tempL[j] = R[j];
            /*
             * Expand R to 48 bits using the E selector;
             * exclusive-or with the current key bits.
             */
            for (j=0; j<48; j++)
                preS[j] = R[E[j]-1] ^ KS[i][j];
            /*
             * The pre-select bits are now considered
             * in 8 groups of 6 bits each.
             * The 8 selection functions map these
             * 6-bit quantities into 4-bit quantities
             * and the results permuted
             * to make an f(R, K).
             * The indexing into the selection functions
             * is peculiar; it could be simplified by
             * rewriting the tables.
             */
            for (j=0; j<8; j++) {
                t = 6*j;
                k = S[j][(preS[t+0]<<5)+
                         (preS[t+1]<<3)+
                         (preS[t+2]<<2)+
                         (preS[t+3]<<1)+
                         (preS[t+4]<<0)+
                         (preS[t+5]<<4)];
                t = 4*j;
                f[t+0] = (k>>3)&1;
                f[t+1] = (k>>2)&1;
                f[t+2] = (k>>1)&1;
                f[t+3] = (k>>0)&1;
            }
            /*
             * The new R is L ^ f(R, K).
             * The f here has to be permuted first, though.
             */
            for (j=0; j<32; j++)
                R[j] = L[j] ^ f[P[j]-1];
            /*
             * Finally, the new L (the original R)
             * is copied back.
             */
            for (j=0; j<32; j++)
                L[j] = tempL[j];
        }
        /*
         * The output L and R are reversed.
         */
        for (j=0; j<32; j++) {
            t = L[j];
            L[j] = R[j];
            R[j] = t;
        }
        /*
         * The final output
         * gets the inverse permutation of the very original.
         */
        for (j=0; j<32; ++j) {
            perm[j] = L[j];
            perm[j+32] = R[j];
        }
        for (j=0; j<64; j++) {
            block[j] = perm[FP[j]-1];
        }
    }

    /**
     * Transform a string to an array of bytes
     */
    var strToBytes = function(str) {
        var i, x = [];
        for (i = 0; i < str.length; ++i) {
            x[i] = str.charCodeAt(i);
        }
        return x
    };

    var bytesToStr = function(bytes) {
        return String.fromCharCode.apply(String, bytes);
    }
    
    return function crypt(pw, salt, returnBytes) {
        if (typeof(pw) === 'string') pw = strToBytes(pw);
        if (typeof(salt) === 'string') salt = strToBytes(salt);

        var i, j, k, c, temp;
        var block = [], iobuf = [];
        for(i=0; i<66; i++)
            block[i] = 0;
        for(i=0, k=0; (c= pw[k]) && i<64; ++k){
            for(j=0; j<7; j++, i++)
                block[i] = (c>>(6-j)) & 1;
            i++;
        }

        setkey(block);

        for(i=0; i<66; i++)
            block[i] = 0;

        for(i=0;i<48;i++)
            E[i] = e[i];

        for(i=0, k=0;i<2;i++, ++k){
            c = salt[k];
            iobuf[i] = c;
            if(c>'Z'.charCodeAt(0)) c -= 6;
            if(c>'9'.charCodeAt(0)) c -= 7;
            c -= '.'.charCodeAt(0);
            for(j=0;j<6;j++){
                if((c>>j) & 1){
                    temp = E[6*i+j];
                    E[6*i+j] = E[6*i+j+24];
                    E[6*i+j+24] = temp;
                }
            }
        }

        for(i=0; i<25; i++)
            encrypt(block,0);

        for(i=0; i<11; i++){
            c = 0;
            for(j=0; j<6; j++){
                c <<= 1;
                c |= block[6*i+j];
            }
            c += '.'.charCodeAt(0);
            if(c>'9'.charCodeAt(0)) c += 7;
            if(c>'Z'.charCodeAt(0)) c += 6;
            iobuf[i+2] = c;
        }
        if(iobuf[1]==0)
            iobuf[1] = iobuf[0];

        if (returnBytes) return(iobuf);
        else return bytesToStr(iobuf);
    }
})();

/*
if (typeof(module) !== "undefined" && module != null) {
  module["exports"] = unixCryptTD;
}
if (typeof(window) !== "undefined" && window != null) {
  window["unixCryptTD"] = unixCryptTD;
} 
*/