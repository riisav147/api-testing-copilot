import React from 'react';
export const CHECK_LABELS = {
  HEADER_EQUALS: 'Header equals', JSON_EXISTS: 'JSON field exists', JSON_TYPE: 'JSON field type',
  JSON_EQUALS: 'JSON value equals', JSON_GTE: 'Number is at least', JSON_LTE: 'Number is at most', JSON_SCHEMA: 'JSON matches schema',
};
export default function AssertionEditor({ rows, onChange, responseSchema }) {
  const update = (index, patch) => onChange(rows.map((row, i) => i === index ? { ...row, ...patch } : row));
  return <section className="assertion-editor" aria-label="Response assertions">
    <h4>RESPONSE CHECKS</h4>
    <p className="field-help">HTTP status is always checked. Add up to 20 response checks. JSON fields use pointers: /price, /items/0/id, or blank for the whole body.</p>
    {rows.map((row, i) => <div className="assertion-edit" key={i}>
      <label>Check<select aria-label={`Check ${i+1} kind`} value={row.kind} onChange={e => update(i, {kind:e.target.value, value:e.target.value==='JSON_TYPE'?'string':e.target.value==='HEADER_EQUALS'?'': 'null'})}>{Object.entries(CHECK_LABELS).map(([kind,label]) => <option key={kind} value={kind}>{label}</option>)}</select></label>
      <label>{row.kind === 'HEADER_EQUALS' ? 'Header name' : 'JSON Pointer'}<input aria-label={`Check ${i+1} target`} value={row.target} placeholder={row.kind==='HEADER_EQUALS'?'content-type':'/price'} onChange={e => update(i,{target:e.target.value})}/></label>
      {row.kind !== 'JSON_EXISTS' && <label className="assertion-value">Expected {row.kind==='HEADER_EQUALS'?'text':row.kind==='JSON_TYPE'?'type':'JSON value'}
        {row.kind === 'JSON_TYPE' ? <select aria-label={`Check ${i+1} expected`} value={row.value} onChange={e => update(i,{value:e.target.value})}>{['object','array','string','number','integer','boolean','null'].map(t => <option key={t}>{t}</option>)}</select>
          : row.kind === 'JSON_SCHEMA' ? <textarea aria-label={`Check ${i+1} expected`} value={row.value} onChange={e => update(i,{value:e.target.value})} spellCheck="false"/>
          : <input aria-label={`Check ${i+1} expected`} value={row.value} onChange={e => update(i,{value:e.target.value})}/>}
      </label>}
      <button type="button" className="button subtle" aria-label={`Remove check ${i+1}`} onClick={() => onChange(rows.filter((_,index) => index!==i))}>Remove check</button>
    </div>)}
    <div className="editor-actions"><button type="button" className="button subtle" disabled={rows.length>=20} onClick={() => onChange([...rows,{kind:'JSON_EXISTS',target:'',value:'null'}])}>Add response check</button>
      {responseSchema && <button type="button" className="button subtle" disabled={rows.length>=20} onClick={() => onChange([...rows,{kind:'JSON_SCHEMA',target:'',value:JSON.stringify(responseSchema,null,2)}])}>Use documented response schema</button>}
    </div>
    <p className="field-help">Schema checks support type, properties, required, items, enum, minimum, maximum, nullable and boolean additionalProperties. Other keywords make the check unevaluable. Header comparison is exact, including content-type parameters.</p>
  </section>;
}
