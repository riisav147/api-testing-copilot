import React, { useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import './style.css';
import ScenarioEditor from './ScenarioEditor.jsx';
import { readContractFile } from './scenarioDraft.js';

async function api(path, body, method = 'POST') {
  const response = await fetch(`/api${path}`, {
    ...(body === undefined ? {} : { method, body: JSON.stringify(body) }),
    headers: { 'Content-Type': 'application/json' },
  });
  const data = await response.json();
  if (!response.ok) { const error = new Error(data.detail || `Request failed (${response.status})`); error.status = response.status; throw error; }
  return data;
}

function App() {
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
  const locked = !!busy || editing;


  useEffect(() => { api('/info').then(setInfo).catch(() => setError('Backend unavailable. Start Spring Boot on port 8080.')); }, []);
  useEffect(() => { setApproved(false); }, [selected, plan, editing]);
  const scenario = plan?.scenarios[selected];
  const result = results[selected];
  const analysis = analyses[selected];
  const revision = plan?.scenarioRevisions[selected];
  const historicalResult = result && result.scenarioRevision !== revision;
  const endpoint = spec?.endpoints.find(e => e.key === plan?.endpointKey);
  const queryNames = [...new Set((endpoint?.contract.parameters || []).filter(p => p.in === 'query').map(p => p.name))];


  async function task(label, action) {
    setBusy(label); setError(''); setNotice('');
    try { await action(); } catch (e) { setError(e.message); if (e.status === 409) setConflict(true); } finally { setBusy(''); }
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
      setPlan(updated); setEditing(false); setApproved(false); setConflict(false);
      setNotice('Changes saved. Review this revision and approve it before running. Earlier results retain their original requests.');
    } catch (e) { if (e.status === 409) setConflict(true); throw e; }
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
      const response = await fetch('/examples/catalog.openapi.yaml');
      if (!response.ok) throw new Error('Could not load example. Check the backend.');
      setContent(await response.text()); setName('Catalog API'); invalidateContract();
      setDocumentation('Product 1 exists. Product 999999 does not exist. Free products with price=0 are permitted by the contract.');
    });
  }
  async function importContract() {
    await task('Importing contract', async () => {
      const imported = await api('/specifications', { name, content, documentation });
      setSpec(imported); setOperation(imported.endpoints.find(e => e.method === 'POST')?.key || imported.endpoints[0].key);
      setPlan(null); setResults({}); setAnalyses({});
    });
  }
  async function generate() {
    await task('Generating scenarios', async () => {
      const generated = await api('/plans', { specificationId: spec.id, endpointKey: operation });
      setPlan(generated); setConflict(false); setSelected(0); setResults({}); setAnalyses({}); setView('scenarios');
    });
  }
  async function execute() {
    await task('Executing approved scenario', async () => {
      const observed = await api('/executions', { planId: plan.id, scenarioIndex: selected, expectedRevision: revision, approved });
      setResults(previous => ({ ...previous, [selected]: observed }));
      setAnalyses(previous => { const next = { ...previous }; delete next[selected]; return next; });
      setApproved(false);
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
        {!plan ? <section className="panel empty"><div className="empty-symbol">⌁</div><h3>Your next bug is hiding in an edge case.</h3><p>Load the sample, import its contract, then generate scenarios<br/>for <code>POST /products</code> to find the boundary mismatch.</p><div className="category-legend"><span>● Positive</span><span>● Negative</span><span>● Edge cases</span></div></section> : <>
          <div className="metrics"><div><small>SCENARIOS</small><strong>{plan.evaluation.total}<span>ready to review</span></strong></div><div><small title="How many of positive, negative and edge categories are present. This is not code coverage.">CATEGORY COVERAGE</small><strong>{Math.round(plan.evaluation.categoryCoverage * 100)}%<span>of 3 categories</span></strong></div><div><small title="Citations identify the source context; they do not prove that a test is correct.">SOURCE CITATIONS</small><strong>{Math.round(plan.evaluation.sourceCitationCoverage * 100)}%<span>provenance, not correctness</span></strong></div><div><small>EXECUTED</small><strong>{Object.entries(results).filter(([index, value]) => value.scenarioRevision === plan.scenarioRevisions[index]).length}<span>of {plan.scenarios.length} current revisions</span></strong></div></div>
          <div className="tabs" role="tablist" aria-label="Plan view"><button disabled={locked} role="tab" aria-selected={view === 'scenarios'} onClick={() => setView('scenarios')}>Scenarios <span>{plan.scenarios.length}</span></button><button disabled={locked} role="tab" aria-selected={view === 'evidence'} onClick={() => setView('evidence')}>Retrieved evidence <span>{plan.evidence.length}</span></button></div>
          {view === 'evidence' ? <section className="evidence-list">{plan.evidence.map(e => <article key={e.id} className="panel evidence"><div><b>Source</b><code>{e.id}</code><span>Score {e.score.toFixed(3)}</span></div><pre>{e.content}</pre></article>)}</section> : <div className="workbench"><section className="scenario-list">{plan.scenarios.map((s, i) => <button key={i} disabled={locked} className={`scenario-card ${selected === i ? 'selected' : ''}`} onClick={() => setSelected(i)}><div><span className={`badge ${s.category.toLowerCase()}`}>{s.category}</span><span className="case-id">TC-{String(i + 1).padStart(2, '0')}</span></div><h3>{s.title}</h3><p><b>{s.method}</b> {s.path}</p><footer><span>Expected <b>{s.expectedStatus}</b></span><span className={results[i]?.scenarioRevision === plan.scenarioRevisions[i] ? results[i].passed ? 'pass' : 'fail' : ''}>{results[i] ? results[i].scenarioRevision !== plan.scenarioRevisions[i] ? '○ Previous revision' : results[i].passed ? '● Passed' : '● Failed' : '○ Not run'}</span></footer></button>)}</section>
            <section className="panel inspector"><div className="inspector-title"><h3>Request inspector</h3><span className="case-id">TC-{String(selected + 1).padStart(2, '0')}</span></div><div className="request-line"><b>{scenario.method}</b><code>/sandbox{scenario.path}</code></div><p className="rationale">{scenario.rationale}</p><p className="revision">Saved revision {revision}{revision > 1 ? ' · Edited request; the original generated rationale is retained for context.' : ''}</p>
              {editing ? <ScenarioEditor key={`${plan.id}-${selected}-${revision}`} scenario={scenario} revision={revision} statuses={[...(endpoint?.statuses || [])].sort((a,b) => a-b)} queryNames={queryNames} busy={!!busy} onSave={saveScenario} onCancel={() => setEditing(false)}/> : <><div className="request-heading"><h4>REQUEST BODY & QUERY</h4><button className="button subtle" disabled={!!busy} onClick={() => { setEditing(true); setNotice(''); }}>Edit request</button></div><pre>{JSON.stringify({ query: scenario.query, body: scenario.body }, null, 2)}</pre></>}
              <details><summary>Supporting sources ({scenario.sourceIds.length})</summary>{scenario.sourceIds.map(id => <code className="source-id" key={id}>{id}</code>)}</details><div className="execution-box"><div className="target"><span>◎ LOCAL SANDBOX ONLY</span><b>Expected HTTP {scenario.expectedStatus}</b></div><label className="approval"><input type="checkbox" checked={approved} disabled={locked} onChange={e => setApproved(e.target.checked)}/><span>I reviewed revision {revision} and approve running it against the bundled sandbox.</span></label><button className="button primary run" disabled={!approved || locked} onClick={execute}>{busy === 'Executing approved scenario' ? 'Executing…' : '▷ Execute approved scenario'}</button></div>
              {result && <div className={`result ${result.passed ? 'passed' : 'failed'}`} aria-live="polite"><div><b>{historicalResult ? `Previous result · revision ${result.scenarioRevision}` : result.passed ? '✓ Status assertion passed' : '× Status assertion failed'}</b><span>{result.durationMs} ms</span></div><p>{historicalResult && <>This result belongs to an earlier request. The current revision has not run.<br/></>}Expected {result.expectedStatus} · Observed {result.actualStatus}</p><pre>{result.responseBody}</pre><details><summary>Request executed at revision {result.scenarioRevision}</summary><pre>{JSON.stringify(result.requestSnapshot, null, 2)}</pre></details><button className="button subtle" disabled={locked} onClick={() => task('Analysing result', async () => { const a = await api(`/executions/${result.id}/analysis`, {}); setAnalyses(previous => ({ ...previous, [selected]: a })); })}>{busy === 'Analysing result' ? 'Analysing…' : 'Explain this result →'}</button>{analysis && <p className="analysis"><span className="badge">{analysis.mode} analysis</span><br/>{analysis.explanation}</p>}</div>}
            </section></div>}
          <div className="evaluation-notes">{plan.evaluation.warnings.map((warning, i) => <p key={i}>ⓘ {warning}</p>)}</div>
        </>}
        <footer className="page-footer"><span>API Copilot <span className="footer-dot">·</span> Grounded in your contract.</span><span>Session data clears on backend restart.</span></footer>
      </main>
    </div>
  </div>;
}

createRoot(document.getElementById('root')).render(<App/>);
