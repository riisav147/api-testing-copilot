import React, { useEffect, useRef, useState } from 'react';
import { ACTIVE_BATCH_STATES, batchSummary, failedSelection } from './batchState.js';

async function request(path, body) {
  const response = await fetch(`/api${path}`, { ...(body === undefined ? {} : { method: 'POST', body: JSON.stringify(body) }), headers: { 'Content-Type': 'application/json' } });
  const data = await response.json();
  if (!response.ok) { const error = new Error(data.detail || `Request failed (${response.status})`); error.status = response.status; throw error; }
  return data;
}
export default function BatchPanel({ projectId, plan, target, locked, executions, onResults }) {
  const [selected, setSelected] = useState([]), [parallel, setParallel] = useState(false), [independent, setIndependent] = useState(false), [approved, setApproved] = useState(false);
  const [jobs, setJobs] = useState([]), [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('');
  const storageKey = `copilot-batch:${projectId}`;
  const [pending, setPending] = useState(() => { try { return JSON.parse(localStorage.getItem(storageKey)); } catch { return null; } });
  const callback = useRef(onResults), observed = useRef(new Set()); callback.current = onResults;
  const revisions = plan?.scenarioRevisions.join(',');
  useEffect(() => { setSelected([]); setApproved(false); setParallel(false); setIndependent(false); }, [plan?.id, revisions, target?.id, target?.revision]);
  useEffect(() => {
    let stopped = false, timer;
    async function poll() {
      try {
        const data = await request(`/projects/${projectId}/batches`);
        if (stopped) return;
        setJobs(data);
        const ids = data.flatMap(j => j.cases.map(c => c.executionId).filter(Boolean));
        if (ids.some(id => !observed.current.has(id))) { await callback.current(); ids.forEach(id => observed.current.add(id)); }
      } catch (e) { if (!stopped) setError(e.message); }
      finally { if (!stopped) timer = setTimeout(poll, 1500); }
    }
    poll(); return () => { stopped = true; clearTimeout(timer); };
  }, [projectId]);
  const readOnly = selected.every(i => ['GET', 'HEAD'].includes(plan?.scenarios[i]?.method));
  function changeSelection(next) { setSelected(next); setApproved(false); setParallel(false); setIndependent(false); setNotice(''); }
  async function submit(payload) {
    setBusy(true); setError('');
    try {
      // Persist the ID before POST; a lost response must never create a second job.
      localStorage.setItem(storageKey, JSON.stringify(payload)); setPending(payload);
      const job = await request('/batches', payload);
      localStorage.removeItem(storageKey); setPending(null); setApproved(false);
      setJobs(previous => [job, ...previous.filter(j => j.id !== job.id)]);
      setNotice('Batch saved. You can continue reviewing tests while it runs.');
    } catch (e) {
      if ([400,404,422].includes(e.status)) { localStorage.removeItem(storageKey); setPending(null); setApproved(false); setError(e.message); }
      else setError(`${e.message} The submission ID is retained. Check its status or retry the same submission.`);
    }
    finally { setBusy(false); }
  }
  async function checkPending() {
    setBusy(true); setError('');
    try {
      const job = await request(`/batches/${pending.id}`);
      setJobs(previous => [job, ...previous.filter(j => j.id !== job.id)]);
      localStorage.removeItem(storageKey); setPending(null); setApproved(false); setNotice('Saved submission found. No new execution was requested.');
    } catch (e) { setError(`${e.message}. You may retry the same submission; the server reuses its ID.`); }
    finally { setBusy(false); }
  }
  async function cancel(id) {
    setBusy(true); setError('');
    try { const job = await request(`/batches/${id}/cancel`, {}); setJobs(previous => previous.map(j => j.id === id ? job : j)); }
    catch (e) { setError(e.message); } finally { setBusy(false); }
  }
  return <section className="panel batch-panel" aria-label="Batch execution">
    <div className="section-heading"><div><h2>Run selected tests</h2><p>Approve saved revisions together, then follow each result. Sequential execution is the default.</p></div><span className="badge">BATCH RUNNER</span></div>
    {plan && <>
      <div className="batch-toolbar"><button className="button subtle" disabled={locked || busy} onClick={() => changeSelection(plan.scenarios.map((_, i) => i))}>Select all cases</button><button className="button subtle" disabled={locked || busy} onClick={() => changeSelection([])}>Clear selection</button><span>{selected.length} selected · target: {target ? `${target.name} · ${target.baseUrl} · revision ${target.revision}` : 'bundled localhost sandbox'}</span></div>
      <div className="batch-selection">{plan.scenarios.map((scenario, i) => <article key={i}>
        <label className="approval"><input type="checkbox" aria-label={`Select batch case ${i + 1}`} disabled={locked || busy} checked={selected.includes(i)} onChange={e => changeSelection(e.target.checked ? [...selected, i].sort((a, b) => a - b) : selected.filter(x => x !== i))}/><span>Case {i + 1} · {scenario.title} · revision {plan.scenarioRevisions[i]}</span></label>
        <details><summary>{scenario.method} {scenario.path} · expected HTTP {scenario.expectedStatus} · review request and checks</summary><pre>{JSON.stringify({ query: scenario.query, body: scenario.body, assertions: scenario.assertions }, null, 2)}</pre></details>
      </article>)}</div>
      <label className="approval"><input type="checkbox" checked={parallel} disabled={locked || busy || !readOnly || !selected.length} onChange={e => { setParallel(e.target.checked); setIndependent(false); setApproved(false); }}/><span>Run up to 2 independent GET/HEAD cases in parallel</span></label>
      {!readOnly && <p>Selections containing writes run sequentially.</p>}
      {parallel && readOnly && <label className="approval"><input type="checkbox" checked={independent} disabled={locked || busy} onChange={e => { setIndependent(e.target.checked); setApproved(false); }}/><span>These cases do not depend on each other's results or shared state.</span></label>}
      <label className="approval"><input type="checkbox" aria-label="Approve selected batch" checked={approved} disabled={locked || busy || !selected.length || !!pending} onChange={e => setApproved(e.target.checked)}/><span>I reviewed the selected saved requests, assertions and displayed target, and approve this batch.</span></label>
      <button className="button primary" disabled={locked || busy || !!pending || !approved || !selected.length || (parallel && readOnly && !independent)} onClick={() => submit({ id: crypto.randomUUID(), planId: plan.id, environmentId: target?.id ?? null, cases: selected.map(i => ({ scenarioIndex: i, expectedRevision: plan.scenarioRevisions[i] })), parallelism: parallel && readOnly ? 2 : 1, independent: parallel && readOnly && independent, approved: true, expectedEnvironmentRevision: target?.revision ?? 0, expectedTarget: target?.baseUrl ?? 'bundled localhost sandbox' })}>{busy ? 'Saving…' : 'Run approved batch'}</button>
    </>}
    {pending && <div className="batch-pending"><p>Unconfirmed submission {pending.id}. Its original selection and target are retained; retries reuse this ID.</p><details><summary>Review retained submission</summary><pre>{JSON.stringify(pending, null, 2)}</pre></details><button disabled={busy} className="button subtle" onClick={checkPending}>Check submission status</button><button disabled={busy || locked} className="button subtle" onClick={() => submit(pending)}>Retry same submission</button></div>}
    {error && <p className="editor-error" role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}
    <h3>Batch history</h3><p>Cancel stops queued cases; claimed requests finish. Uncertain outcomes need target inspection and are excluded from failed-case selection.</p>
    {!jobs.length && <p>No batches yet. Select and review cases above.</p>}
    {jobs.map(job => {
      const summary = batchSummary(job), failed = failedSelection(job, plan), allFailed = job.cases.filter(c => c.state === 'FAILED').length;
      const sameTarget = (job.environmentId ?? null) === (target?.id ?? null);
      return <article className="batch-job" key={job.id}>
        <div className="batch-toolbar"><strong>{job.state} · {summary.finished}/{summary.total} finished</strong><span>{new Date(job.createdAt).toLocaleString()} · {job.parallelism === 1 ? 'sequential' : 'up to 2 parallel'}</span>{ACTIVE_BATCH_STATES.has(job.state) && <button disabled={busy} className="button subtle" onClick={() => cancel(job.id)}>Cancel queued cases</button>}</div>
        <p>Target: {job.target}{job.environmentSnapshot && ` · ${job.environmentSnapshot.name} · revision ${job.environmentSnapshot.revision}`} · plan {job.planId}</p><progress aria-label={`Batch ${job.id} progress`} max={summary.total} value={summary.finished}/>
        <div className="batch-toolbar"><a className="button subtle" href={`/api/batches/${job.id}/export?format=json`} download>Download results JSON</a><a className="button subtle" href={`/api/batches/${job.id}/export?format=junit`} download>Download JUnit XML</a></div>
        <p>{Object.entries(summary.counts).map(([state, count]) => `${count} ${state.toLowerCase().replaceAll('_', ' ')}`).join(' · ')}</p>
        <details><summary>Review {job.cases.length} case outcomes and approved snapshots</summary>{job.cases.map(c => { const result = executions.find(e => e.id === c.executionId); return <div className="batch-case" key={c.scenarioIndex}><b>Case {c.scenarioIndex + 1} · revision {c.scenarioRevision} · {c.state}</b><p>{c.detail}</p>{result && <p>HTTP {result.actualStatus} · expected {result.expectedStatus} · {result.durationMs} ms</p>}<details><summary>Saved request, assertions and recorded response</summary><pre>{JSON.stringify({ request: c.snapshot, execution: result ?? null }, null, 2)}</pre></details></div>; })}</details>
        {allFailed > 0 && <><button className="button subtle" disabled={locked || busy || ACTIVE_BATCH_STATES.has(job.state) || !sameTarget || failed.length !== allFailed} onClick={() => { changeSelection(failed); setNotice('Failed cases selected. Review the current target and requests, then approve a new batch.'); }}>Select failed cases for review</button>{(!sameTarget || failed.length !== allFailed) && <p>Open this plan with its original case revisions and select its environment to prepare failed cases.</p>}</>}
      </article>;
    })}
  </section>;
}
