import AssertionEditor from './AssertionEditor.jsx';
import { assertionDrafts, parseAssertions } from './assertionDraft.js';
import React, { useId, useState } from 'react';
import { formatRequest, fromFields, parseRequest, toFields } from './scenarioDraft.js';

export default function ScenarioEditor({ scenario, revision, statuses, queryNames, endpoint, busy, onSave, onCancel }) {
  const id = useId();
  const canForm = scenario.body == null || (typeof scenario.body === 'object' && !Array.isArray(scenario.body));
  const [mode, setMode] = useState(canForm ? 'form' : 'json');
  const [fields, setFields] = useState(() => canForm ? toFields(scenario.body ?? null) : []);
  const [queries, setQueries] = useState(() => Object.entries(scenario.query).map(([name, value]) => ({ name, value })));
  const [bodyEnabled, setBodyEnabled] = useState(scenario.body != null);
  const [json, setJson] = useState(() => formatRequest(scenario));
  const [status, setStatus] = useState(scenario.expectedStatus);
  const [checks, setChecks] = useState(() => assertionDrafts(scenario.assertions));
  const responseSchema = endpoint?.contract.responses?.[status]?.content?.['application/json']?.schema;
  const [error, setError] = useState('');
  function request() { return mode === 'json' ? parseRequest(json) : fromFields(fields, queries, bodyEnabled); }
  function switchMode(next) {
    if (next === mode) return;
    try {
      const value = request();
      if (next === 'json') setJson(JSON.stringify(value, null, 2));
      else {
        setFields(toFields(value.body)); setBodyEnabled(value.body !== null);
        setQueries(Object.entries(value.query).map(([name, value]) => ({ name, value })));
      }
      setError(''); setMode(next);
    } catch (e) { setError(e.message); }
  }
  function reset() {
    setMode(canForm ? 'form' : 'json'); setFields(canForm ? toFields(scenario.body ?? null) : []);
    setQueries(Object.entries(scenario.query).map(([name, value]) => ({ name, value })));
    setBodyEnabled(scenario.body != null); setJson(formatRequest(scenario)); setStatus(scenario.expectedStatus); setChecks(assertionDrafts(scenario.assertions)); setError('');
  }
  async function save(e) {
    e.preventDefault();
    try { setError(''); await onSave({ ...request(), expectedStatus: Number(status), expectedRevision: revision, assertions: parseAssertions(checks) }); }
    catch (e) { setError(e.message); }
  }
  function update(setter, index, patch) { setter(rows => rows.map((row, i) => i === index ? { ...row, ...patch } : row)); }
  return <form className="scenario-editor" onSubmit={save} aria-label="Edit scenario">
    <p className="editor-note">Editing revision {revision}. Method, path and sources remain tied to this operation. Saving requires a new review and approval.</p>
    <fieldset disabled={busy}>
      <legend className="sr-only">Request editor</legend>
      <div className="editor-toolbar"><div className="view-toggle" role="group" aria-label="Request editor view">
        <button type="button" aria-pressed={mode === 'form'} onClick={() => switchMode('form')}>Form</button>
        <button type="button" aria-pressed={mode === 'json'} onClick={() => switchMode('json')}>JSON</button>
      </div><button type="button" className="button subtle" onClick={reset}>Reset edits</button></div>
      {mode === 'json' ? <><label htmlFor={`${id}-json`}>Request JSON</label><textarea id={`${id}-json`} className="code-editor request-json" value={json} onChange={e => setJson(e.target.value)} spellCheck="false"/><p className="field-help">Use body and query only. Query values must be strings. You can use null for no body.</p></> : <>
        <label className="body-toggle"><input type="checkbox" checked={bodyEnabled} onChange={e => setBodyEnabled(e.target.checked)}/> Include a JSON object body</label>
        {bodyEnabled && <><p className="field-help">Choose the value type explicitly. Use object or array for nested JSON. Remove a field to test missing input.</p>
          {fields.map((field, i) => <div className="body-field" key={i}>
            <label>Field name<input aria-label={`Body field ${i + 1} name`} value={field.name} onChange={e => update(setFields, i, { name: e.target.value })}/></label>
            <label>Type<select aria-label={`Body field ${i + 1} type`} value={field.type} onChange={e => update(setFields, i, { type: e.target.value, value: e.target.value === 'boolean' ? 'true' : e.target.value === 'null' ? 'null' : field.value })}>{['string','number','boolean','null','object','array'].map(type => <option key={type}>{type}</option>)}</select></label>
            <label className="field-value">Value{field.type === 'boolean' ? <select aria-label={`Body field ${i + 1} value`} value={field.value} onChange={e => update(setFields, i, { value: e.target.value })}><option>true</option><option>false</option></select> : <input aria-label={`Body field ${i + 1} value`} disabled={field.type === 'null'} value={field.type === 'null' ? 'null' : field.value} onChange={e => update(setFields, i, { value: e.target.value })}/>}</label>
            <button type="button" className="button subtle remove-field" aria-label={`Remove body field ${i + 1}`} onClick={() => setFields(rows => rows.filter((_, index) => index !== i))}>Remove</button>
          </div>)}<button type="button" className="button subtle" onClick={() => setFields(rows => [...rows, { name: '', type: 'string', value: '' }])}>Add body field</button></>}
        <h4>QUERY PARAMETERS</h4>
        {queries.map((field, i) => <div className="query-field" key={i}><label>Parameter<select aria-label={`Query parameter ${i + 1} name`} value={field.name} onChange={e => update(setQueries, i, { name: e.target.value })}><option value="">Choose parameter</option>{queryNames.map(name => <option key={name}>{name}</option>)}</select></label><label>Text value<input aria-label={`Query parameter ${i + 1} value`} value={field.value} onChange={e => update(setQueries, i, { value: e.target.value })}/></label><button type="button" className="button subtle remove-field" onClick={() => setQueries(rows => rows.filter((_, index) => index !== i))}>Remove</button></div>)}
        {queryNames.length ? <button type="button" className="button subtle" onClick={() => setQueries(rows => [...rows, { name: '', value: '' }])}>Add query parameter</button> : <p className="field-help">No query parameters are documented for this operation.</p>}
      </>}
      <label htmlFor={`${id}-status`} className="status-label">Expected HTTP status</label>
      <select id={`${id}-status`} value={status} onChange={e => setStatus(e.target.value)}>{statuses.map(code => <option key={code} value={code}>{code}</option>)}</select>
      <p className="field-help">Choose a documented response status. Changing an expectation does not establish that the API is correct.</p>
      <AssertionEditor rows={checks} onChange={setChecks} responseSchema={responseSchema}/>
      {error && <p className="editor-error" role="alert">{error}</p>}
      <div className="editor-actions"><button className="button primary" type="submit">{busy ? 'Saving…' : 'Save changes'}</button><button className="button subtle" type="button" onClick={onCancel}>Cancel</button></div>
    </fieldset>
  </form>;
}
