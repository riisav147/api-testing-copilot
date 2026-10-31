import React, { useEffect, useState } from 'react';
export default function RuleCoverage({ spec, operation, plan, runKey, locked, onPlan, onSelect }) {
  const [report,setReport]=useState(null),[error,setError]=useState(''),[busy,setBusy]=useState(false),[decisions,setDecisions]=useState({});
  const url=spec ? `/api/specifications/${spec.id}/coverage?endpointKey=${encodeURIComponent(operation)}${plan?`&planId=${plan.id}`:''}` : '';
  async function jsonRequest(url,body) {
    const response=await fetch(url,body?{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}:{});
    const data=await response.json();if(!response.ok)throw new Error(data.detail || 'Could not load rule coverage');return data;
  }
  useEffect(()=>{let cancelled=false;setReport(null);setError('');if(spec&&operation)jsonRequest(url).then(data=>{if(!cancelled)setReport(data);}).catch(e=>{if(!cancelled)setError(e.message);});return()=>{cancelled=true;};},[url,JSON.stringify(plan?.scenarioRevisions),runKey]);
  async function act(fn) { setBusy(true);setError('');try{await fn();setReport(await jsonRequest(url));}catch(e){setError(e.message);}finally{setBusy(false);} }
  if(!spec)return null;
  return <section className="panel rule-coverage" aria-label="Contract rule coverage"><h2>Contract rules and missing tests</h2>
    <p>{report?.scope || 'Loading supported contract rules…'}</p>{error&&<p role="alert" className="editor-error">{error}</p>}
    {report && <><p>Specification version {report.version} · {report.rules.filter(r=>r.state!=='MISSING').length} of {report.rules.length} supported rule probes represented in this plan. Passing means its configured assertions passed.</p>
      {report.conflicts.map(c=><article className="rule-conflict" key={c.id}><h3>Possible numeric requirement conflict</h3><p>Contract: <b>{c.contractStatement}</b></p><p>Documentation excerpt: <mark>{c.documentationStatement}</mark></p><p>Saved decision: {c.decision}{c.reason&&` — ${c.reason}`}</p>
        <label>Interpretation<select aria-label="Conflict interpretation" disabled={locked||busy} value={decisions[c.id]?.choice||'USE_CONTRACT'} onChange={e=>setDecisions({...decisions,[c.id]:{...decisions[c.id],choice:e.target.value}})}><option value="USE_CONTRACT">Use the contract for this version</option><option value="REVISE_CONTRACT">Contract needs revision before testing</option></select></label>
        <label>Reason<input aria-label="Decision reason" maxLength={1000} disabled={locked||busy} value={decisions[c.id]?.reason||''} onChange={e=>setDecisions({...decisions,[c.id]:{...decisions[c.id],reason:e.target.value}})}/></label>
        <button className="button subtle" disabled={locked||busy||!decisions[c.id]?.reason?.trim()} onClick={()=>act(()=>jsonRequest(`/api/specifications/${spec.id}/conflicts?endpointKey=${encodeURIComponent(operation)}`,{conflictId:c.id,choice:decisions[c.id]?.choice||'USE_CONTRACT',reason:decisions[c.id].reason}))}>Save interpretation</button><p>Unresolved conflicts and requests to revise the contract block generation and execution. Saving a decision does not rewrite the contract or its expected statuses.</p>
      </article>)}
      {plan&&<button className="button primary" disabled={locked||busy} onClick={()=>act(async()=>onPlan(await jsonRequest(`/api/plans/${plan.id}/missing-tests`,{expectedRevisions:plan.scenarioRevisions})))}>{busy?'Working…':'Generate missing tests'}</button>}
      <p className="field-help">Additional cases are deterministic. Existing cases and edits are preserved. Plans hold at most 12 cases; remaining gaps stay visible. Candidate fixtures and expected results still need review.</p>
      {!report.rules.length&&<p>No supported probes for this operation. This does not mean the API is fully tested. Numeric and string probes cover supported top-level JSON body fields; negative probes require a documented 400.</p>}
      <div className="rule-grid">{report.rules.map(row=><article className="rule-row" key={row.rule.id}><div><b>{row.rule.explanation}</b><span className={`badge ${row.state==='FAILED'?'negative':row.state==='PASSED'?'positive':''}`}>{row.state}</span></div><p>Field {row.rule.field} · {row.rule.probe} · expected HTTP {row.rule.expectedStatus}</p><details><summary>View exact contract rule</summary><code>{row.rule.sourcePointer}</code><pre><mark>{JSON.stringify(row.rule.sourceValue,null,2)}</mark></pre><p>This pointer identifies the rule in the original specification version {report.version}. For local references, the component definition is linked.</p></details><div className="editor-actions">{row.scenarioIndexes.map(index=><button key={index} className="button subtle" disabled={locked||busy} onClick={()=>onSelect(index)}>Review case {index+1}</button>)}</div></article>)}</div>
    </>}
  </section>;
}
