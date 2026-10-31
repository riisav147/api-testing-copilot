import test from 'node:test';
import assert from 'node:assert/strict';
import { batchSummary, failedSelection } from './batchState.js';

test('progress distinguishes pending, in-flight and terminal case outcomes', () => {
  const summary = batchSummary({cases: ['PASSED','FAILED','UNKNOWN','CANCELLED','NOT_RUN','BLOCKED','RUNNING','PENDING'].map(state => ({state}))});
  assert.equal(summary.finished, 6);
  assert.equal(summary.total, 8);
  assert.equal(summary.counts.UNKNOWN, 1);
});
test('failed selection excludes uncertain, stale and other-plan cases', () => {
  const job = {planId:'p',cases:[{scenarioIndex:0,scenarioRevision:1,state:'FAILED'},{scenarioIndex:1,scenarioRevision:1,state:'UNKNOWN'},{scenarioIndex:2,scenarioRevision:1,state:'FAILED'}]};
  assert.deepEqual(failedSelection(job,{id:'p',scenarioRevisions:[1,1,2]}),[0]);
  assert.deepEqual(failedSelection(job,{id:'other',scenarioRevisions:[1,1,1]}),[]);
});
