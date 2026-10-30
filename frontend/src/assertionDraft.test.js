import test from 'node:test';
import assert from 'node:assert/strict';
import { assertionDrafts, parseAssertions } from './assertionDraft.js';
test('assertion editing preserves typed JSON and plain header text', () => {
  const checks = [
    {kind:'JSON_EQUALS',target:'/price',expected:5},
    {kind:'JSON_EQUALS',target:'/name',expected:'5'},
    {kind:'JSON_TYPE',target:'/price',expected:'number'},
    {kind:'HEADER_EQUALS',target:'content-type',expected:'application/json'},
    {kind:'JSON_SCHEMA',target:'',expected:{type:'object',required:['price']}},
    {kind:'JSON_EXISTS',target:'/name',expected:null},
  ];
  assert.deepEqual(parseAssertions(assertionDrafts(checks)), checks);
});
test('bad expected values cannot be saved', () => {
  assert.throws(() => parseAssertions([{kind:'JSON_EQUALS',target:'/name',value:'unquoted'}]), /valid JSON/);
  assert.throws(() => parseAssertions([{kind:'JSON_GTE',target:'/price',value:'"5"'}]), /number/);
});
