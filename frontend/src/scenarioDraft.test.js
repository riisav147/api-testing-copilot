import test from 'node:test';
import assert from 'node:assert/strict';
import { fromFields, toFields, parseRequest, formatRequest, readContractFile } from './scenarioDraft.js';

test('form and JSON preserve nested values, numeric strings, null and query text', () => {
  const original = { body: { name: '12', price: 0, enabled: false, tags: ['new'], metadata: { id: 1 }, deleted: null }, query: { limit: '10' } };
  const roundtrip = fromFields(toFields(original.body), [{ name: 'limit', value: '10' }], true);
  assert.deepEqual(parseRequest(formatRequest(roundtrip)), original);
  assert.deepEqual(fromFields([], [], false), { body: null, query: {} });
  assert.deepEqual(fromFields([], [], true), { body: {}, query: {} });
});
test('rejects duplicate fields, malformed values and invalid query types without coercion', () => {
  assert.throws(() => fromFields([{name:'price',type:'number',value:'"5"'}], [], true), /valid number/);
  assert.throws(() => fromFields([{name:'a',type:'null'}, {name:'a',type:'null'}], [], true), /Duplicate/);
  assert.throws(() => fromFields([], [{name:'',value:'1'}], false), /needs a name/);
  assert.throws(() => parseRequest('{"body":null,"query":{"id":1}}'), /text values/);
  assert.throws(() => parseRequest('{"body":null,"query":{},"method":"DELETE"}'), /Method and path/);
  assert.throws(() => parseRequest('{'), /invalid/);
  assert.throws(() => toFields([1, 2]), /JSON view/);
});
test('keeps special property names as data', () => {
  const body = JSON.parse('{"__proto__":{"safe":true},"constructor":"value"}');
  assert.deepEqual(fromFields(toFields(body), [], true).body, body);
  assert.equal({}.safe, undefined);
});
test('file import validates extension, byte limit, character limit and emptiness', async () => {
  const file = (name, content, size = content.length) => ({ name, size, text: async () => content });
  assert.equal(await readContractFile(file('api.YAML', 'openapi: 3.0.3')), 'openapi: 3.0.3');
  await assert.rejects(readContractFile(file('api.txt', '{}')), /Choose one/);
  await assert.rejects(readContractFile(file('api.json', '')), /empty/);
  await assert.rejects(readContractFile(file('api.json', '{}', 240001)), /too large/);
  await assert.rejects(readContractFile(file('api.yml', 'x'.repeat(60001))), /60,000/);
});
