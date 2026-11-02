import React, { useEffect, useState } from 'react';

async function request(path,body) {
  const response=await fetch(`/api${path}`,body?{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}:{});
  const data=await response.json();if(!response.ok)throw new Error(data.detail||'Contract review failed');return data;
}
export default function ContractChanges({workspace,locked,onCreated,onPlan,onBusy}) {
  const versions=workspace.specifications;
  const [from,setFrom]=useState(versions.at(-2)?.specification.id||''),[to,setTo]=useState(versions.at(-1)?.specification.id||'');
  const [source,setSource]=useState(()=>workspace.plans.filter(p=>p.specificationId===from).at(-1)?.id||''),[comparison,setReport]=useState(null),[choices,setChoices]=useState({}),[reviewed,setReviewed]=useState(false);
  const [busy,setBusy]=useState(false),[error,setError]=useState(''),[id,setId]=useState(()=>crypto.randomUUID());
  const versionKey=versions.map(v=>v.specification.id).join(',');
  useEffect(()=>{if(versions.length>=2&&!from)setFrom(versions.at(-2).specification.id);setTo(versions.at(-1)?.specification.id||'');},[versionKey]);
  const plans=workspace.plans.filter(p=>p.specificationId===from);
  const sourcePlan=plans.find(p=>p.id===source);
  const revisions=sourcePlan?.scenarioRevisions.join(',');
  const report=comparison&&comparison.fromSpecificationId===from&&comparison.toSpecificationId===to&&(comparison.planId||'')===source&&(!source||comparison.expectedRevisions.join(',')===revisions)?comparison:null;
  useEffect(()=>{setReport(null);setChoices({});setReviewed(false);setId(crypto.randomUUID());setError('');},[from,to,source,revisions]);
  useEffect(()=>{setSource(workspace.plans.filter(p=>p.specificationId===from).at(-1)?.id||'');},[from]);
  function edit(index,value){setChoices(previous=>({...previous,[index]:value}));setReviewed(false);setId(crypto.randomUUID());}
  async function compare(){setBusy(true);onBusy(true);setError('');setReviewed(false);setId(crypto.randomUUID());try{setReport(await request(`/projects/${workspace.project.id}/contract-diff?from=${from}&to=${to}${source?`&planId=${source}`:''}`));setChoices({});}catch(e){setError(e.message);}finally{setBusy(false);onBusy(false);}}
  async function create(){setBusy(true);onBusy(true);setError('');try{
    const selected=Object.entries(choices).filter(([,v])=>v.selected).map(([index,v])=>({scenarioIndex:Number(index),action:v.ruleId?'ADOPT_INPUT':'KEEP',ruleId:v.ruleId||null}));
    const plan=await request(`/plans/${source}/contract-update`,{id,targetSpecificationId:to,expectedRevisions:report.expectedRevisions,choices:selected,reviewed});
    await onCreated(plan);setReviewed(false);setId(crypto.randomUUID());
  }catch(e){setError(e.message);}finally{setBusy(false);onBusy(false);}}
  const selectedCount=Object.values(choices).filter(v=>v.selected).length;
  const removed=report?.changes.some(c=>c.endpointKey===sourcePlan?.endpointKey&&c.kind==='OPERATION_REMOVED');
  return <section className="panel contract-changes" aria-label="Contract version comparison"><h2>Review contract changes</h2>
    <p>Compare an older contract with a newer version. Review input proposals and create a separate plan; saved expectations and historical results are preserved.</p>
    {versions.length<2?<p>Import another version into this project to compare contracts.</p>:<>
      <div className="change-controls"><label>Older version<select aria-label="Older contract version" disabled={locked||busy} value={from} onChange={e=>setFrom(e.target.value)}><option value="">Choose version</option>{versions.map(s=><option key={s.specification.id} value={s.specification.id}>Version {s.version} · {s.source.name}</option>)}</select></label>
      <label>Newer version<select aria-label="Newer contract version" disabled={locked||busy} value={to} onChange={e=>setTo(e.target.value)}>{versions.map(s=><option key={s.specification.id} value={s.specification.id}>Version {s.version} · {s.source.name}</option>)}</select></label>
      <label>Saved plan to assess<select aria-label="Plan to assess" disabled={locked||busy} value={source} onChange={e=>setSource(e.target.value)}><option value="">Changes only</option>{plans.map(p=><option key={p.id} value={p.id}>{p.endpointKey} · {new Date(p.createdAt).toLocaleString()}</option>)}</select></label></div>
      <button className="button subtle" disabled={locked||busy||!from||!to||from===to} onClick={compare}>{busy?'Working…':'Compare contracts'}</button>
    </>}
    {error&&<p role="alert" className="editor-error">{error}</p>}
    {report&&<><p>Version {report.fromVersion} → {report.toVersion} · {report.changes.length} detected changes{report.limited?' · limit reached, impact unknown':''}</p><p>{report.scope}</p>
      {!report.changes.length&&<p>No changes detected in the normalized operations and compared context. This is not a compatibility guarantee.</p>}
      <div className="change-list">{report.changes.map(c=><details key={c.id}><summary>{c.certainty} · {c.endpointKey} · {c.kind} · {c.pointer||'operation/context'}</summary><div className="change-snapshots"><div><h4>OLDER</h4><code>{c.beforeSource||'No exact original pointer available'}</code><pre>{JSON.stringify(c.before,null,2)}</pre></div><div><h4>NEWER</h4><code>{c.afterSource||'No exact original pointer available'}</code><pre>{JSON.stringify(c.after,null,2)}</pre></div></div></details>)}</div>
      {sourcePlan&&<><h3>Saved tests that need review</h3><p>{sourcePlan.endpointKey}. AFFECTED means a represented rule changed; REVIEW means operation changes need manual assessment; UNKNOWN means unsupported or incomplete analysis. Every selected case still needs review.</p>
        <button className="button subtle" disabled={locked||busy} onClick={()=>onPlan(sourcePlan)}>Open original plan</button>
        {report.impacts.map(impact=>{const old=sourcePlan.scenarios[impact.scenarioIndex],choice=choices[impact.scenarioIndex]||{},suggestion=impact.suggestions.find(s=>s.ruleId===choice.ruleId);return <article className="change-case" key={impact.scenarioIndex}>
          <h3>Case {impact.scenarioIndex+1} · revision {impact.revision} · {impact.state}</h3><p>{old.title} · {impact.detail}</p>
          <details><summary>Source rule links and detected changes</summary><pre>{JSON.stringify({ruleIds:impact.ruleIds,changeIds:impact.changeIds},null,2)}</pre></details>
          <label className="approval"><input type="checkbox" aria-label={`Include contract case ${impact.scenarioIndex+1}`} disabled={locked||busy||removed} checked={!!choice.selected} onChange={e=>edit(impact.scenarioIndex,{...choice,selected:e.target.checked})}/><span>Include this case in the new plan</span></label>
          <label>Input decision<select aria-label={`Input decision for case ${impact.scenarioIndex+1}`} disabled={locked||busy||removed} value={choice.ruleId||''} onChange={e=>edit(impact.scenarioIndex,{...choice,ruleId:e.target.value})}><option value="">Keep original input and checks after manual review</option>{impact.suggestions.map(s=><option key={s.ruleId} value={s.ruleId}>Propose {s.field} · {s.probe} from the newer contract</option>)}</select></label>
          <div className="change-snapshots"><div><h4>ORIGINAL REQUEST</h4><pre>{JSON.stringify({query:old.query,body:old.body,expectedStatus:old.expectedStatus,assertions:old.assertions},null,2)}</pre></div><div><h4>REVIEWED NEW REQUEST</h4><pre>{JSON.stringify({query:(suggestion?.request||old).query,body:(suggestion?.request||old).body,expectedStatus:old.expectedStatus,assertions:old.assertions},null,2)}</pre>{suggestion&&<code>New rule: {suggestion.sourcePointer}</code>}</div></div>
        </article>;})}
        {removed&&<p className="editor-error">This operation was removed. A plan cannot be created for it in the newer contract.</p>}
        <p>One input proposal can be applied per case. Other fixture or assertion changes need manual editing after creation. Removed statuses or query parameters can prevent copying; expectations are never changed to make a result pass.</p>
        <label className="approval"><input type="checkbox" aria-label="Confirm contract update review" disabled={locked||busy||!selectedCount||removed} checked={reviewed} onChange={e=>setReviewed(e.target.checked)}/><span>I reviewed each selected input, its unchanged expected status and response checks against the newer contract. Create a separate plan; execution needs a new approval.</span></label>
        <button className="button primary" disabled={locked||busy||!reviewed||!selectedCount||removed} onClick={create}>Create reviewed plan for version {report.toVersion}</button>
      </>}
    </>}
  </section>;
}

export function AdoptionHistory({plan,workspace,locked,onPlan}) {
  const [data,setData]=useState(null),[error,setError]=useState('');
  const runKey=workspace.executions.filter(e=>e.planId===plan.id).map(e=>e.id).join(',');
  useEffect(()=>{let cancelled=false;setData(null);setError('');fetch(`/api/plans/${plan.id}/contract-update`).then(async response=>{if(response.status===404)return null;const value=await response.json();if(!response.ok)throw new Error(value.detail||'Could not load contract review history');return value;}).then(value=>{if(!cancelled)setData(value);}).catch(e=>{if(!cancelled)setError(e.message);});return()=>{cancelled=true;};},[plan.id,plan.scenarioRevisions.join(','),runKey]);
  if(error)return <p role="alert" className="editor-error">{error}</p>;if(!data)return null;
  const source=workspace.plans.find(p=>p.id===data.adoption.sourcePlanId);
  return <section className="panel contract-changes" aria-label="Contract update history"><h2>Contract update history</h2><p>Original snapshots and latest recorded results of the identified revisions. Results may use different targets or fixture states; a change in pass/fail does not establish a cause.</p>
    {source&&<button className="button subtle" disabled={locked} onClick={()=>onPlan(source)}>Open source plan</button>}
    {data.adoption.cases.map(c=>{const before=data.before.find(e=>e.scenarioIndex===c.sourceIndex),after=data.after.find(e=>e.scenarioIndex===c.targetIndex);return <article className="change-case" key={c.targetIndex}><h3>Original case {c.sourceIndex+1} revision {c.sourceRevision} → new case {c.targetIndex+1} · {c.action}</h3><p>Current new revision {data.currentRevisions[c.targetIndex]}; initial reviewed revision 1 retained below.</p><div className="change-snapshots">{[['BEFORE',c.sourceSnapshot,before],['AFTER (INITIAL REVIEWED INPUT)',c.targetSnapshot,after]].map(([label,snapshot,run])=><div key={label}><h4>{label}</h4><pre>{JSON.stringify(snapshot,null,2)}</pre>{run?<><p>{run.passed?'PASSED':'FAILED'} · expected {run.expectedStatus}, observed {run.actualStatus} · revision {run.scenarioRevision} · target {run.target}</p><details><summary>Exact request and checks for this recorded result</summary><pre>{JSON.stringify({request:run.requestSnapshot,checks:run.assertionResults},null,2)}</pre></details><a className="button subtle" href={`/api/executions/${run.id}/report?format=markdown`} download>Download recorded report</a></>:<p>No execution recorded for the identified revision.</p>}</div>)}</div></article>;})}
  </section>;
}
