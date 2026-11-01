import React, { useEffect, useState } from 'react';
export default function ReportPanel({ executionId }) {
  const [report,setReport]=useState(null),[error,setError]=useState(''),[notice,setNotice]=useState('');
  useEffect(()=>{setReport(null);setError('');setNotice('');},[executionId]);
  async function load() {
    if(report)return report;
    const response=await fetch(`/api/executions/${executionId}/report`),data=await response.json();
    if(!response.ok)throw new Error(data.detail||'Could not build report');setReport(data);return data;
  }
  async function action(kind) {
    setError('');setNotice('');
    try {const data=await load();if(kind==='copy'){await navigator.clipboard.writeText(data.markdown);setNotice('Report copied. Review its contents before sharing.');}}
    catch(e){setError(e.message);}
  }
  return <section className="report-panel" aria-label="Reproduction and exports"><h4>REPRODUCE & SHARE</h4><p>Reports use this recorded revision. Review the target and fixture state before running cURL; reproduction sends a new request.</p>
    <div className="batch-toolbar"><button className="button subtle" onClick={()=>action('preview')}>Preview report</button><button className="button subtle" onClick={()=>action('copy')}>Copy bug report</button>
      {[['markdown','Report .md'],['curl','cURL .sh'],['json','Results JSON'],['junit','JUnit XML']].map(([format,label])=><a key={format} className="button subtle" href={`/api/executions/${executionId}/report?format=${format}`} download>{label}</a>)}</div>
    {error&&<p className="editor-error" role="alert">{error}</p>}{notice&&<p role="status">{notice}</p>}
    {report&&<details open><summary>Recorded facts and reproduction script</summary><pre>{report.markdown}</pre></details>}
  </section>;
}
