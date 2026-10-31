import React, { useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import './style.css';
import { CHECK_LABELS } from './AssertionEditor.jsx';
import EnvironmentPanel from './EnvironmentPanel.jsx';
import RuleCoverage from './RuleCoverage.jsx';
import ProjectHistory from './ProjectHistory.jsx';
import ScenarioEditor from './ScenarioEditor.jsx';
import { readContractFile } from './scenarioDraft.js';

async function api(path, body, method = 'POST') {
  const response = await fetch(`/api${path}`, {
    ...(body === undefined ? {} : { method, ...(method === 'DELETE' ? {} : {body: JSON.stringify(body)}) }),
    headers: { 'Content-Type': 'application/json' },
  });
  const data = await response.json();
  if (!response.ok) { const error = new Error(data.detail || `Request failed (${response.status})`); error.status = response.status; throw error; }
  return data;
}

function App() {
  const [projects,setProjects]=useState([]);
  const [workspace,setWorkspace]=useState(null);
  const [projectName,setProjectName]=useState('');
  const [deleteConfirm,setDeleteConfirm]=useState(false);
  const [target,setTarget]=useState(null);
  const [configBusy,setConfigBusy]=useState(false);
  const [attemptMessage,setAttemptMessage]=useState('');
  const [info, setInfo] = useState(null);
  const [name, setName] = useState('Catalog API');
  const [content, setContent] = useState('');
  const [documentation, setDocumentation] = useState('');
  const [spec, setSpec] = useState(null);
  const [operation, setOperation] = useState('');
  const [plan, setPlan] = useState(null);
  const [selected, setSelected] = useState(0);
  const [approved, setApproved] = useState(false);
  const [results, setResults] = useState({});
  const [analyses, setAnalyses] = useState({});
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [view, setView] = useState('scenarios');
  const [editing, setEditing] = useState(false);
  const [conflict, setConflict] = useState(false);
  const [notice, setNotice] = useState('');
  const [dragging, setDragging] = useState(false);
  const locked = !!busy || editing || configBusy;


  useEffect(() => { refreshProjects().then(async () => {
    const savedId=localStorage.getItem('copilot-project');
    if(savedId) { try { await openProject(savedId,localStorage.getItem('copilot-plan')); } catch { localStorage.removeItem('copilot-project'); localStorage.removeItem('copilot-plan'); } }
  }).catch(e=>setError(e.message)); }, []);
  useEffect(() => { api('/info').then(setInfo).catch(() => setError('Backend unavailable. Start Spring Boot on port 8080.')); }, []);
  useEffect(() => { setApproved(false); }, [selected, plan, editing, target]);
  const scenario = plan?.scenarios[selected];
  const result = results[selected];
  const analysis = analyses[selected];
  const revision = plan?.scenarioRevisions[selected];
  const historicalResult = result && result.scenarioRevision !== revision;
  const endpoint = spec?.endpoints.find(e => e.key === plan?.endpointKey);
  const queryNames = [...new Set((endpoint?.contract.parameters || []).filter(p => p.in === 'query').map(p => p.name))];


  async function refreshProjects() { const list=await api('/projects'); setProjects(list); return list; }
  async function openProject(id,preferredPlan) {
    const data=await api(`/projects/${id}`); setWorkspace(data); setProjectName(data.project.name); setDeleteConfirm(false);
    localStorage.setItem('copilot-project',id);
    const chosen=data.plans.find(p=>p.id===preferredPlan) || data.plans.at(-1);
    const saved=data.specifications.find(s=>s.specification.id===chosen?.specificationId) || data.specifications.at(-1);
    if(saved) { setSpec(saved.specification); setName(saved.source.name); setContent(saved.source.content); setDocumentation(saved.source.documentation || ''); setOperation(chosen?.endpointKey || saved.specification.endpoints[0].key); }
    setPlan(null); setResults({}); setAnalyses({}); setApproved(false); setEditing(false); setConflict(false);
    if(chosen) showPlan(chosen,data); else localStorage.removeItem('copilot-plan');
  }
  function showPlan(chosen,data=workspace) {
    const saved=data.specifications.find(s=>s.specification.id===chosen.specificationId);
    setSpec(saved.specification); setName(saved.source.name); setContent(saved.source.content); setDocumentation(saved.source.documentation || '');
    setOperation(chosen.endpointKey); setPlan(chosen); setSelected(0); setApproved(false); setView('scenarios');
    localStorage.setItem('copilot-plan',chosen.id);
    const resultMap={},analysisMap={};
    data.executions.filter(e=>e.planId===chosen.id).forEach(e=>{resultMap[e.scenarioIndex]=e;analysisMap[e.scenarioIndex]=data.analyses[e.id];});
    setResults(resultMap); setAnalyses(analysisMap);
  }
  function showSpecification(id) {
    const saved=workspace.specifications.find(s=>s.specification.id===id); if(!saved)return;
    setSpec(saved.specification);setName(saved.source.name);setContent(saved.source.content);setDocumentation(saved.source.documentation || '');setOperation(saved.specification.endpoints[0].key);
    setPlan(null);setResults({});setAnalyses({});setApproved(false);setConflict(false);localStorage.removeItem('copilot-plan');
  }
  function newProject() { setWorkspace(null);setProjectName('');setName('New API');setContent('');setDocumentation('');setDeleteConfirm(false);invalidateContract();localStorage.removeItem('copilot-project');localStorage.removeItem('copilot-plan'); }
  async function refreshHistory() { if(workspace) setWorkspace(await api(`/projects/${workspace.project.id}`)); }
  async function task(label, action) {
    setBusy(label); setError(''); setNotice('');
    try { await action(); } catch (e) { setError(e.message); if (e.status === 409 && plan) setConflict(true); } finally { setBusy(''); }
  }
  function invalidateContract() { setSpec(null); setPlan(null); setResults({}); setAnalyses({}); setConflict(false); setNotice(''); }
  async function importFile(files) {
    if (locked) return;
    await task('Reading specification', async () => {
      if (files.length !== 1) throw new Error('Choose one specification file at a time.');
      const text = await readContractFile(files[0]);
      setContent(text); invalidateContract();
      setNotice(`${files[0].name} loaded. Review the API name and supporting documentation, then import the contract.`);
    });
  }
  async function saveScenario(changes) {
    setBusy('Saving scenario'); setError(''); setNotice('');
    try {
      const updated = await api(`/plans/${plan.id}/scenarios/${selected}`, changes, 'PUT');
      await refreshHistory(); setPlan(updated); setEditing(false); setApproved(false); setConflict(false);
      setNotice('Changes saved. Review this revision and approve it before running. Earlier results retain their original requests.');
    } catch (e) { if (e.status === 409 && plan) setConflict(true); throw e; }
    finally { setBusy(''); }
  }
  async function reloadPlan() {
    await task('Reloading plan', async () => {
      setPlan(await api(`/plans/${plan.id}`)); setEditing(false); setApproved(false); setConflict(false);
      setNotice('Latest plan loaded. Review the request again before approving.');
    });
  }

  async function loadExample() {
    await task('Loading example', async () => {
      const response = await fetch('/examples/catalog.openapi.yaml', { cache: 'no-store' });
      if (!response.ok) throw new Error('Could not load example. Check the backend.');
      setContent(await response.text()); setName('Catalog API'); invalidateContract();
      setDocumentation('Product 1 exists. Product 999999 does not exist. Free products with price=0 are permitted by the contract.');
    });
  }
  async function importContract() {
    await task('Importing contract', async () => {
      const imported = await api('/specifications', { name, content, documentation, projectId: workspace?.project.id ?? null });
      setSpec(imported); setOperation(imported.endpoints.find(e => e.method === 'POST')?.key || imported.endpoints[0].key);
      setPlan(null); setResults({}); setAnalyses({});
      await refreshProjects();
      const data=await api(`/specifications/${imported.id}/workspace`); const owner=data.project.id; setWorkspace(data);setProjectName(data.project.name);localStorage.setItem('copilot-project',owner);localStorage.removeItem('copilot-plan');
    });
  }
  async function generate() {
    await task('Generating scenarios', async () => {
      const generated = await api('/plans', { specificationId: spec.id, endpointKey: operation });
      setPlan(generated); setConflict(false); setSelected(0); setResults({}); setAnalyses({}); setView('scenarios'); localStorage.setItem('copilot-plan',generated.id); await refreshHistory();
    });
  }
  async function execute() {
    await task('Executing approved scenario', async () => {
      const payload={planId:plan.id,scenarioIndex:selected,expectedRevision:revision,approved,environmentId:target?.id??null};
      const key=`copilot-approval:${plan.id}:${selected}:${revision}:${target?.id||'sandbox'}:${target?.revision||0}`;
      let approvalId=localStorage.getItem(key);
      if(!approvalId) {const granted=await api('/approvals',payload);approvalId=granted.id;localStorage.setItem(key,approvalId);}
      let observed;
      try {observed=await api('/executions',{...payload,approvalId});localStorage.removeItem(key);setAttemptMessage('');}
      catch(error) {try {const attempt=await api(`/approvals/${approvalId}/attempt`);setAttemptMessage(`Approval ${approvalId}: ${attempt.state}. Repeating this submission reuses the same approval. If the outcome is unknown, inspect the target before editing and reviewing a new request.`);}catch{} throw error;}
      setResults(previous => ({ ...previous, [selected]: observed }));
      setAnalyses(previous => { const next = { ...previous }; delete next[selected]; return next; });
      setApproved(false); await refreshHistory();
    });
  }
  function downloadPlan() {
    const url = URL.createObjectURL(new Blob([JSON.stringify(plan, null, 2)], { type: 'application/json' }));
    const a = document.createElement('a'); a.href = url; a.download = 'test-plan.json'; a.click(); URL.revokeObjectURL(url);
  }

  return <div className="app">
    <aside className="sidebar">
      <a className="brand" href="/" aria-label="API Copilot home"><span className="brand-mark">⌘</span><span>api<span className="brand-light">copilot</span><small>TESTING WORKSPACE</small></span></a>
      <div className="nav-label">WORKSPACE</div>
      <div className="nav-item active"><span>▦</span> Test studio <span className="nav-dot" /></div>
      <div className="sidebar-project"><span className="project-icon">{spec ? '✓' : '{ }'}</span><div>{spec?.name || 'Your first API'}<small>{spec ? `${spec.endpoints.length} operations imported` : 'Start with an OpenAPI contract'}</small></div></div>
      <div className="sidebar-footer"><span className="avatar">RR</span><div>Rishav’s workspace<small>Local development</small></div></div>
    </aside>
    <div className="main-shell">
      <header className="topbar"><span>Workspace <span className="slash">/</span> <b>Test studio</b></span><span className="connection"><i className={info ? 'online' : ''} />{info ? 'Backend connected' : 'Connecting…'}</span></header>
      <main>
        <div className="page-heading"><div><div className="eyebrow">FROM CONTRACT TO CONFIDENCE</div><h1>Give your API a second opinion.</h1><p>Generate grounded scenarios. Review every request. Find the gaps.</p></div><span className={`mode ${info?.mode === 'ai' ? 'ai' : ''}`}>{info?.mode === 'ai' ? '✦ AI mode' : '◈ Demo mode'}</span></div>
        <div className="mode-note">{info?.mode === 'ai' ? 'AI mode uses OpenAI and pgvector. Review generated requests before execution.' : 'Demo mode uses deterministic generation and exact endpoint lookup. No LLM or embeddings are used.'}</div>
        {error && <div role="alert" className="error"><b>Something needs attention.</b> {error}<button aria-label="Dismiss error" onClick={() => setError('')}>×</button></div>}
        {notice && <p className="notice" role="status">{notice}</p>}
        {conflict && <div className="conflict"><p>The saved scenario changed. Reloading discards unsaved edits and clears approval.</p><button className="button subtle" disabled={!!busy} onClick={reloadPlan}>Discard edits and reload latest plan</button></div>}
        <section className="panel project-manager"><h2>Projects</h2><div className="project-controls"><label>Saved project<select aria-label="Saved project" disabled={locked} value={workspace?.project.id || ''} onChange={e=>{if(e.target.value) task('Opening project',()=>openProject(e.target.value));}}><option value="">New project</option>{projects.map(p=><option key={p.id} value={p.id}>{p.name}{p.deleting?' · deletion pending':''}</option>)}</select></label><button className="button subtle" disabled={locked} onClick={newProject}>New project</button></div>
        {workspace && <><label>Specification version<select aria-label="Specification version" disabled={locked} value={spec?.id || workspace.specifications.at(-1)?.specification.id || ''} onChange={e=>showSpecification(e.target.value)}>{workspace.specifications.map(s=><option key={s.specification.id} value={s.specification.id}>Version {s.version} · {s.source.name}</option>)}</select></label><label>Project name<input aria-label="Project name" disabled={locked} maxLength={100} value={projectName} onChange={e=>setProjectName(e.target.value)}/></label><div className="editor-actions"><button className="button subtle" disabled={locked || !projectName.trim()} onClick={()=>task('Renaming project',async()=>{await api(`/projects/${workspace.project.id}`,{name:projectName},'PUT');await refreshProjects();setWorkspace({...workspace,project:{...workspace.project,name:projectName}});})}>Rename project</button><button className="button subtle" disabled={locked} onClick={()=>setDeleteConfirm(true)}>Delete project</button></div><p>Importing another contract here saves a new specification version. {workspace.specifications.length} version(s) saved.</p></>}
        {deleteConfirm && <div role="alert"><p>Delete this project, all its versions, plans, runs and indexed evidence? This cannot be undone.</p><button className="button subtle" disabled={locked} onClick={()=>setDeleteConfirm(false)}>Keep project</button><button className="button primary" disabled={locked} onClick={()=>task('Deleting project',async()=>{await api(`/projects/${workspace.project.id}`,{},'DELETE');newProject();await refreshProjects();})}>Confirm deletion</button></div>}
        {projects.filter(p=>p.deleting).map(p=><button key={p.id} className="button subtle" disabled={locked} onClick={()=>task('Finishing deletion',async()=>{await api(`/projects/${p.id}`,{},'DELETE');await refreshProjects();})}>Retry deletion of {p.name}</button>)}
        </section>
        <div className="steps"><span className={spec ? 'complete' : 'current'}><i>{spec ? '✓' : '1'}</i>Import contract</span><div/><span className={plan ? 'complete' : spec ? 'current' : ''}><i>{plan ? '✓' : '2'}</i>Choose & generate</span><div/><span className={Object.keys(results).length ? 'complete' : plan ? 'current' : ''}><i>3</i>Review, run & understand</span></div>
        <section className="panel import-panel"><div className="section-heading"><div><h2><span className="section-icon">{ }</span> Your API contract</h2><p>Bring the source of truth. Suggestions stay grounded in this contract.</p></div><button className="button subtle" disabled={locked} onClick={loadExample}>↗ Load sample API</button></div>
          <div className={`file-drop ${dragging ? 'dragging' : ''}`} aria-label="Specification file import" onDragOver={e => { e.preventDefault(); if (!locked) setDragging(true); }} onDragLeave={() => setDragging(false)} onDrop={e => { e.preventDefault(); setDragging(false); importFile(Array.from(e.dataTransfer.files)); }}>
            <div><b>Drop an OpenAPI file here</b><p>JSON or YAML · up to 60,000 characters. You can also paste below.</p></div>
            <label className="file-picker">Choose file<input type="file" aria-label="Choose specification file" accept=".json,.yaml,.yml" disabled={locked} onChange={e => { const files = Array.from(e.target.files); e.target.value = ''; if (files.length) importFile(files); }}/></label>
          </div>
          <div className="import-grid"><div><label htmlFor="api-name">API name</label><input id="api-name" value={name} maxLength={100} disabled={locked} onChange={e => { setName(e.target.value); invalidateContract(); }}/><label htmlFor="contract">OpenAPI specification <span>JSON or YAML · 60,000 characters max</span></label><textarea id="contract" className="code-editor" value={content} maxLength={60000} disabled={locked} onChange={e => { setContent(e.target.value); invalidateContract(); }} placeholder={'openapi: 3.0.3\ninfo:\n  title: Your API\npaths:\n  ...'} spellCheck="false" /></div><div className="context-column"><label htmlFor="documentation">Supporting documentation <span>Optional</span></label><textarea id="documentation" value={documentation} maxLength={30000} disabled={locked} onChange={e => { setDocumentation(e.target.value); invalidateContract(); }} placeholder="Add business rules, fixture IDs, or endpoint behaviour…"/><div className="hint"><span>⌁</span><div><b>Better context, better tests.</b><p>Include valid IDs and business rules. Remote references are blocked; local schema references are resolved.</p></div></div><button className="button primary import-button" disabled={locked || !name.trim() || !content.trim()} onClick={importContract}>{busy === 'Importing contract' ? 'Importing…' : spec ? '✓ Contract imported' : 'Import contract →'}</button></div></div>
        </section>
        <div className="section-heading workbench-heading"><div><h2>Scenario workbench</h2><p>{plan ? `${plan.scenarios.length} scenarios grounded in ${plan.evidence.length} evidence chunks.` : 'Select an operation to explore its test coverage.'}</p></div>{plan && <button className="button subtle" onClick={downloadPlan}>↓ Export JSON</button>}</div>
        <div className="operation-bar"><div><span className="operation-label">OPERATION</span><select aria-label="API operation" value={operation} disabled={!spec || locked} onChange={e => { setOperation(e.target.value); setPlan(null); setResults({}); setAnalyses({}); }}>{!spec && <option value="">Import a contract first</option>}{spec?.endpoints.map(e => <option key={e.key}>{e.key}</option>)}</select></div><button className="button primary" disabled={!spec || locked} onClick={generate}>{busy === 'Generating scenarios' ? 'Generating…' : '✦ Generate scenarios'}</button></div>
        <EnvironmentPanel projectId={workspace?.project.id} locked={locked} onTarget={e=>{setTarget(e);setApproved(false);setAttemptMessage('');}} onBusy={setConfigBusy}/>
        <RuleCoverage spec={spec} operation={operation} plan={plan} runKey={Object.values(results).map(r=>r.id).join(',')} locked={locked} onPlan={async updated=>{setPlan(updated);setApproved(false);await refreshHistory();}} onSelect={index=>{setSelected(index);setView('scenarios');}}/>
        {!plan ? <section className="panel empty"><div className="empty-symbol">⌁</div><h3>Your next bug is hiding in an edge case.</h3><p>Load the sample, import its contract, then generate scenarios<br/>for <code>POST /products</code> to find the boundary mismatch.</p><div className="category-legend"><span>● Positive</span><span>● Negative</span><span>● Edge cases</span></div></section> : <>
          <div className="metrics"><div><small>SCENARIOS</small><strong>{plan.evaluation.total}<span>ready to review</span></strong></div><div><small title="How many of positive, negative and edge categories are present. This is not code coverage.">CATEGORY COVERAGE</small><strong>{Math.round(plan.evaluation.categoryCoverage * 100)}%<span>of 3 categories</span></strong></div><div><small title="Citations identify the source context; they do not prove that a test is correct.">SOURCE CITATIONS</small><strong>{Math.round(plan.evaluation.sourceCitationCoverage * 100)}%<span>provenance, not correctness</span></strong></div><div><small>EXECUTED</small><strong>{Object.entries(results).filter(([index, value]) => value.scenarioRevision === plan.scenarioRevisions[index]).length}<span>of {plan.scenarios.length} current revisions</span></strong></div></div>
          <div className="tabs" role="tablist" aria-label="Plan view"><button disabled={locked} role="tab" aria-selected={view === 'scenarios'} onClick={() => setView('scenarios')}>Scenarios <span>{plan.scenarios.length}</span></button><button disabled={locked} role="tab" aria-selected={view === 'evidence'} onClick={() => setView('evidence')}>Retrieved evidence <span>{plan.evidence.length}</span></button></div>
          {view === 'evidence' ? <section className="evidence-list">{plan.evidence.map(e => <article key={e.id} className="panel evidence"><div><b>Source</b><code>{e.id}</code><span>Score {e.score.toFixed(3)}</span></div><pre>{e.content}</pre></article>)}</section> : <div className="workbench"><section className="scenario-list">{plan.scenarios.map((s, i) => <button key={i} disabled={locked} className={`scenario-card ${selected === i ? 'selected' : ''}`} onClick={() => setSelected(i)}><div><span className={`badge ${s.category.toLowerCase()}`}>{s.category}</span><span className="case-id">TC-{String(i + 1).padStart(2, '0')}</span></div><h3>{s.title}</h3><p><b>{s.method}</b> {s.path}</p><footer><span>Expected <b>{s.expectedStatus}</b></span><span className={results[i]?.scenarioRevision === plan.scenarioRevisions[i] ? results[i].passed ? 'pass' : 'fail' : ''}>{results[i] ? results[i].scenarioRevision !== plan.scenarioRevisions[i] ? '○ Previous revision' : results[i].passed ? '● Passed' : '● Failed' : '○ Not run'}</span></footer></button>)}</section>
            <section className="panel inspector"><div className="inspector-title"><h3>Request inspector</h3><span className="case-id">TC-{String(selected + 1).padStart(2, '0')}</span></div><div className="request-line"><b>{scenario.method}</b><code>{target?target.baseUrl:'/sandbox'}{scenario.path}</code></div><p className="rationale">{scenario.rationale}</p><p className="revision">Saved revision {revision}{revision > 1 ? ' · Edited request; the original generated rationale is retained for context.' : ''}</p>
              {editing ? <ScenarioEditor key={`${plan.id}-${selected}-${revision}`} scenario={scenario} revision={revision} statuses={[...(endpoint?.statuses || [])].sort((a,b) => a-b)} queryNames={queryNames} endpoint={endpoint} busy={!!busy} onSave={saveScenario} onCancel={() => setEditing(false)}/> : <><div className="request-heading"><h4>REQUEST BODY & QUERY</h4><button className="button subtle" disabled={!!busy} onClick={() => { setEditing(true); setNotice(''); }}>Edit request</button></div><pre>{JSON.stringify({ query: scenario.query, body: scenario.body }, null, 2)}</pre></>}
              {!editing && <section className="saved-checks"><h4>RESPONSE CHECKS</h4><p>HTTP status equals {scenario.expectedStatus}</p>{scenario.assertions?.map((check,i) => <p key={i}>{CHECK_LABELS[check.kind]} · {check.target || 'whole body'}{check.kind !== 'JSON_EXISTS' && <> · {JSON.stringify(check.expected)}</>}</p>)}</section>}
              <details><summary>Supporting sources ({scenario.sourceIds.length})</summary>{scenario.sourceIds.map(id => <code className="source-id" key={id}>{id}</code>)}</details><div className="execution-box"><div className="target"><span>◎ {target ? `${target.name} · ${target.baseUrl} · revision ${target.revision}` : 'BUNDLED LOCAL SANDBOX'}</span><b>Expected HTTP {scenario.expectedStatus}</b></div><label className="approval"><input type="checkbox" checked={approved} disabled={locked} onChange={e => setApproved(e.target.checked)}/><span>I reviewed revision {revision} and approve running it against {target ? target.baseUrl : 'the bundled sandbox'}.</span></label><button className="button primary run" disabled={!approved || locked} onClick={execute}>{busy === 'Executing approved scenario' ? 'Executing…' : '▷ Execute approved scenario'}</button></div>
              {attemptMessage&&<p role="status" className="editor-error">{attemptMessage}</p>}
              {result && <div className={`result ${result.passed ? 'passed' : 'failed'}`} aria-live="polite"><div><b>{historicalResult ? `Previous result · revision ${result.scenarioRevision}` : result.passed ? '✓ All configured checks passed' : '× Response checks did not all pass'}</b><span>{result.durationMs} ms</span></div><p>{historicalResult && <>This result belongs to an earlier request. The current revision has not run.<br/></>}Expected {result.expectedStatus} · Observed {result.actualStatus}</p>{result.bodyTruncated && <p className="editor-error" role="status">Response truncated at 8,192 bytes. JSON checks could not be evaluated.</p>}
              <div className="assertion-results" aria-label="Assertion results">{result.assertionResults?.map((check,i) => <article className={`assertion-result ${check.outcome.toLowerCase()}`} key={i}><b>{check.outcome} · {CHECK_LABELS[check.check] || 'HTTP status'} · {check.target || 'whole body'}</b><p>{check.detail}</p><dl><dt>Expected</dt><dd>{JSON.stringify(check.expected)}</dd><dt>Actual</dt><dd>{check.actual === null ? 'null or unavailable — see detail' : JSON.stringify(check.actual)}</dd></dl></article>)}</div>
              <p>Executed target: {result.target || 'bundled localhost sandbox'}</p><h4>RESPONSE BODY</h4><pre>{result.responseBody}</pre><details><summary>Response headers</summary><pre>{JSON.stringify(result.responseHeaders,null,2)}</pre></details><details><summary>Request executed at revision {result.scenarioRevision}</summary><pre>{JSON.stringify(result.requestSnapshot, null, 2)}</pre></details><button className="button subtle" disabled={locked} onClick={() => task('Analysing result', async () => { const a = await api(`/executions/${result.id}/analysis`, {}); setAnalyses(previous => ({ ...previous, [selected]: a })); await refreshHistory(); })}>{busy === 'Analysing result' ? 'Analysing…' : 'Explain this result →'}</button>{analysis && <p className="analysis"><span className="badge">{analysis.mode} analysis</span><br/>{analysis.explanation}</p>}</div>}
            </section></div>}
          <div className="evaluation-notes">{plan.evaluation.warnings.map((warning, i) => <p key={i}>ⓘ {warning}</p>)}</div>
        </>}
        {workspace && <ProjectHistory key={workspace.project.id} workspace={workspace} busy={locked} onPlan={p=>showPlan(p)}/>}
        <footer className="page-footer"><span>API Copilot <span className="footer-dot">·</span> Grounded in your contract.</span><span>Projects and execution history are saved locally.</span></footer>
      </main>
    </div>
  </div>;
}

createRoot(document.getElementById('root')).render(<App/>);
