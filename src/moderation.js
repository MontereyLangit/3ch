export function parseRules(text) {
  return text.split(/\r?\n/).flatMap((line, i) => {
    if (!line.trim() || line.trim().startsWith('#')) return [];
    const parts = line.split('<>');
    if (parts.length !== 4) throw new Error(`${i + 1}行目: 板<>対象<>NGワード<>動作 の形式で入力してください。`);
    const [board, target, word, action] = parts;
    if (!['', 'deny', 'mark', 'TateDame', 'md5'].includes(action)) throw new Error(`${i + 1}行目: 動作が不正です。`);
    try { return [{ board: new RegExp(board || '.*'), target: new RegExp(target || '.*'), word: new RegExp(word || '.*'), action, isWord: !!word }]; }
    catch { throw new Error(`${i + 1}行目: 正規表現が不正です。`); }
  });
}

export async function checkModeration(gb, env) {
  if (gb.cap || gb.form.bbs === 'saku') return 1;
  const deny = message => { gb.error = message; return 0; };
  if (gb.country === 'T1') return deny('変なホスト規制中！！ Tor is not allowed.');
  const content = `FROM=${gb.form.FROM} mail=${gb.form.mail} MESSAGE=${gb.form.MESSAGE} subject=${gb.form.subject}`;
  const decodedContent = content.replace(/&#(x[0-9a-f]+|\d+);/gi, (entity, digits) => {
    const code = digits[0].toLowerCase() === 'x' ? parseInt(digits.slice(1), 16) : Number(digits);
    return code > 0 && code <= 0x10ffff ? String.fromCodePoint(code) : entity;
  }).replace(/&(?:amp|lt|gt|quot|apos);/g, entity => ({ '&amp;': '&', '&lt;': '<', '&gt;': '>', '&quot;': '"', '&apos;': "'" })[entity]);
  for (const file of ['deny.cgi', 'ngwords.cgi']) {
    const object = await env.ch3www.get(file);
    if (!object) continue;
    let rules;
    try { rules = parseRules(await object.text()); }
    catch { return deny('規制設定に問題があります。管理者にお問い合わせください。'); }
    for (const rule of rules) {
      if (!rule.board.test(gb.form.bbs) || !rule.target.test(gb.userInfo) || !(rule.word.test(content) || rule.word.test(decodedContent))) continue;
      if (rule.isWord && gb.settings.BBS_NGWORD_PASS === 'checked') continue;
      if (rule.action === 'TateDame' && gb.number !== 1) continue;
      if (rule.action === 'mark' || gb.settings.BBS_NO_MADAKANA === 'checked') {
        if (!rule.isWord && !gb.form.FROM.startsWith('</b>[´・ω・｀]<b> ')) gb.form.FROM = '</b>[´・ω・｀]<b> ' + gb.form.FROM;
        gb.form.MESSAGE += ' <small>👀 Madakana: Caution</small>';
        continue;
      }
      return deny(rule.isWord ? 'NGワード禁止！' : rule.action === 'TateDame' ? 'このホストではスレッドを立てられません。' : 'アクセス規制中です！！');
    }
  }
  return 1;
}
