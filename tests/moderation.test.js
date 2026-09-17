import { test } from 'node:test';
import assert from 'node:assert/strict';
import { checkModeration, parseRules } from '../src/moderation.js';

const context = () => ({ form: { bbs: 'sample', FROM: 'name', mail: '', MESSAGE: 'hello', subject: 'title' }, userInfo: 'IP:192.0.2.1 AS1234-JP', country: 'JP', settings: {}, number: 2 });
const env = (deny = '', ng = '') => ({ ch3www: { get: async key => ({ text: async () => key === 'deny.cgi' ? deny : ng }) } });
test('ルール検証: コメント、空欄、空白、不正な正規表現・動作', () => {
  assert.equal(parseRules('# comment\n\n^sample$<>IP:192\\.0\\.2\\.1 <>word<>deny').length, 1);
  for (const rule of ['[<><><>deny', 'bad format', '<><><>unknown']) assert.throws(() => parseRules(rule));
});
test('アクセス拒否・非対象・Tor・スレ立てのみ', async () => {
  const gb = context();
  assert.equal(await checkModeration(gb, env('^sample$<>AS1234-<><>deny')), 0);
  assert.match(gb.error, /アクセス規制/);
  assert.equal(await checkModeration(context(), env('^elsewhere$<><><>deny')), 1);
  assert.equal(await checkModeration({ ...context(), country: 'T1' }, env()), 0);
  assert.equal(await checkModeration(context(), env('<><><>TateDame')), 1);
  assert.equal(await checkModeration({ ...context(), number: 1 }, env('<><><>TateDame')), 0);
});
test('NGはタイトル・名前・メール・本文を検査する', async () => {
  for (const field of ['subject', 'FROM', 'mail', 'MESSAGE']) {
    const gb = context(); gb.form[field] = '禁止語';
    assert.equal(await checkModeration(gb, env('', '<><>禁止語<>deny')), 0);
  }
  const encoded = context(); encoded.form.MESSAGE = '&#x66;orbidden';
  assert.equal(await checkModeration(encoded, env('', '<><>forbidden<>deny')), 0);
});
test('markの後も拒否ルールを評価し、既存の例外設定を維持する', async () => {
  assert.equal(await checkModeration(context(), env('<><><>mark\n<><><>deny')), 0);
  const gb = context(); gb.settings.BBS_NO_MADAKANA = 'checked';
  assert.equal(await checkModeration(gb, env('<><><>deny')), 1);
  assert.match(gb.form.MESSAGE, /Caution/);
  assert.match(gb.form.FROM, /´・ω・｀/);
  assert.equal(await checkModeration({ ...context(), cap: 1 }, env('<><><>deny')), 1);
  const pass = context(); pass.settings.BBS_NGWORD_PASS = 'checked';
  assert.equal(await checkModeration(pass, env('', '<><>hello<>deny')), 1);
  assert.equal(await checkModeration(context(), env('invalid')), 0);
});
