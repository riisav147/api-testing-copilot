export const ACTIVE_BATCH_STATES = new Set(['QUEUED', 'RUNNING', 'CANCELLING']);
export function batchSummary(job) {
  const counts = {};
  for (const item of job.cases) counts[item.state] = (counts[item.state] || 0) + 1;
  return { counts, finished: job.cases.filter(c => !['PENDING', 'RUNNING'].includes(c.state)).length, total: job.cases.length };
}
export function failedSelection(job, plan) {
  if (!plan || job.planId !== plan.id) return [];
  return job.cases.filter(c => c.state === 'FAILED' && plan.scenarioRevisions[c.scenarioIndex] === c.scenarioRevision).map(c => c.scenarioIndex);
}
