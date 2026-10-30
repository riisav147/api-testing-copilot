import React, { useState } from 'react';
export default function ProjectHistory({ workspace, onPlan, busy }) {
  const [left,setLeft]=useState(''),[right,setRight]=useState('');
  const runs=workspace?.executions || [];
  return <section className="panel project-history"><h2>Saved plans and execution history</h2>
    <p>Every run retains its request revision and assertions. Reopening a plan loads its latest saved revision and clears approval.</p>
    <div className="history-plans">{workspace?.plans.map(plan => <button disabled={busy} className="button subtle" key={plan.id} onClick={() => onPlan(plan)}>{plan.endpointKey} · {new Date(plan.createdAt).toLocaleString()}</button>)}</div>
    {!runs.length ? <p>No executions saved yet.</p> : <><label>First run<select aria-label="First run" value={left} onChange={e => setLeft(e.target.value)}><option value="">Choose a run</option>{runs.map(run => <option key={run.id} value={run.id}>{new Date(run.createdAt).toLocaleString()} · case {run.scenarioIndex+1} revision {run.scenarioRevision} · {run.passed?'Passed':'Not passed'}</option>)}</select></label>
    <label>Compare with<select aria-label="Compare with" value={right} onChange={e => setRight(e.target.value)}><option value="">Choose another run</option>{runs.map(run => <option key={run.id} value={run.id}>{new Date(run.createdAt).toLocaleString()} · case {run.scenarioIndex+1} revision {run.scenarioRevision} · {run.passed?'Passed':'Not passed'}</option>)}</select></label>
    <div className="history-comparison">{[left,right].filter(Boolean).map((id,i) => {const run=runs.find(r=>r.id===id); if(!run)return null; return <article key={i}><h3>Case {run.scenarioIndex+1} · Revision {run.scenarioRevision}</h3><p>Plan {run.planId} · {run.passed?'Passed':'Not passed'} · HTTP {run.actualStatus} · {run.durationMs} ms</p><h4>EXACT REQUEST AND ASSERTIONS</h4><pre>{JSON.stringify(run.requestSnapshot,null,2)}</pre><h4>RECORDED CHECKS</h4><pre>{JSON.stringify(run.assertionResults,null,2)}</pre>{workspace.analyses[run.id] && <p>{workspace.analyses[run.id].explanation}</p>}</article>;})}</div></>}
  </section>;
}
