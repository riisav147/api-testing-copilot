import React, { useEffect, useState } from 'react';
const empty={name:'Development API',baseUrl:'http://127.0.0.1:8081',authType:'NONE',authHeader:'X-API-Key',secretRef:'',headerRefs:{},revision:0};
export default function EnvironmentPanel({ projectId, locked, onTarget, onBusy }) {
  const [items,setItems]=useState([]),[selected,setSelected]=useState(''),[draft,setDraft]=useState(empty),[headerText,setHeaderText]=useState('{}'),[authorized,setAuthorized]=useState(false),[message,setMessage]=useState('');
  useEffect(()=>{let cancelled=false;setSelected('');onTarget(null);setDraft(empty);setAuthorized(false);setHeaderText('{}');setMessage('');setItems([]);
    if(projectId)fetch(`/api/projects/${projectId}/environments`).then(r=>r.json()).then(data=>{if(!cancelled&&Array.isArray(data))setItems(data);});return()=>{cancelled=true;};},[projectId]);
  async function request(path,body,method='POST') {const r=await fetch(path,{method,headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});const data=await r.json();if(!r.ok)throw new Error(data.detail||'Environment request failed');return data;}
  function choose(id) {setSelected(id);const e=items.find(e=>e.id===id);onTarget(e||null);setDraft(e||empty);setHeaderText(JSON.stringify(e?.headerRefs||{},null,2));setAuthorized(false);setMessage('');}
  async function save(e) {e.preventDefault();onBusy(true);setMessage('');try {
    const refs=JSON.parse(headerText);if(!refs||Array.isArray(refs)||typeof refs!=='object'||Object.values(refs).some(v=>typeof v!=='string'))throw new Error('Header references must map header names to environment-variable names.');
    const saved=await request(`/api/projects/${projectId}/environments${draft.id?`/${draft.id}`:''}`,{name:draft.name,baseUrl:draft.baseUrl,authType:draft.authType,authHeader:draft.authHeader,secretRef:draft.secretRef,headerRefs:refs,authorized,expectedRevision:draft.revision},draft.id?'PUT':'POST');
    setItems(previous=>[...previous.filter(e=>e.id!==saved.id),saved]);setDraft(saved);setSelected(saved.id);onTarget(saved);setAuthorized(false);setMessage('Environment saved. Review this target before approving a request.');
  }catch(e){setMessage(e.message);}finally{onBusy(false);}}
  const field=(key,value)=>setDraft({...draft,[key]:value});
  return <section className="panel environment-panel"><h2>Execution environment</h2><p>The sandbox is the default. Development targets must use an explicitly allowlisted http://127.0.0.1:port origin. Credentials stay in the backend environment.</p>
    <label>Target<select aria-label="Execution environment" disabled={locked} value={selected} onChange={e=>choose(e.target.value)}><option value="">Bundled sandbox</option>{items.map(e=><option key={e.id} value={e.id}>{e.name} · {e.baseUrl} · revision {e.revision}</option>)}</select></label>
    {projectId&&<details><summary>Add or update a development environment</summary><form onSubmit={save}><fieldset disabled={locked}><legend className="sr-only">Development environment configuration</legend>
      <button type="button" className="button subtle" onClick={()=>{setDraft(empty);setAuthorized(false);setHeaderText('{}');}}>New environment configuration</button>
      <label>Name<input aria-label="Environment name" required maxLength={100} value={draft.name} onChange={e=>field('name',e.target.value)}/></label>
      <label>Base URL<input aria-label="Environment base URL" required value={draft.baseUrl} onChange={e=>field('baseUrl',e.target.value)}/></label>
      <label>Authentication<select aria-label="Environment authentication" value={draft.authType} onChange={e=>field('authType',e.target.value)}><option value="NONE">None</option><option value="BEARER">Bearer token</option><option value="API_KEY">API key header</option></select></label>
      {draft.authType==='API_KEY'&&<label>API key header name<input aria-label="API key header name" value={draft.authHeader||''} onChange={e=>field('authHeader',e.target.value)}/></label>}
      {draft.authType!=='NONE'&&<label>Server environment-variable name<input aria-label="Credential reference" placeholder="MY_DEV_API_TOKEN" value={draft.secretRef||''} onChange={e=>field('secretRef',e.target.value)}/></label>}
      <label>Additional headers as environment-variable references<textarea aria-label="Header references" value={headerText} onChange={e=>setHeaderText(e.target.value)}/></label><p>Example: {`{"X-Test-Header":"MY_TEST_HEADER"}`}. Enter variable names only. Set their values on the backend and restart; never paste credentials into request bodies or this form.</p>
      <label className="approval"><input type="checkbox" checked={authorized} onChange={e=>setAuthorized(e.target.checked)}/>I own or am authorized to test this development target.</label>
      <button className="button primary" disabled={!authorized} type="submit">Save environment</button></fieldset></form></details>}
    {selected&&<button className="button subtle" disabled={locked} onClick={async()=>{onBusy(true);try{const r=await request(`/api/projects/${projectId}/environments/${selected}/check`,{});setMessage(`HEAD returned HTTP ${r.status}. ${r.detail}`);}catch(e){setMessage(e.message);}finally{onBusy(false);}}}>Check connection with HEAD</button>}
    {message&&<p role="status" className="notice">{message}</p>}
  </section>;
}
