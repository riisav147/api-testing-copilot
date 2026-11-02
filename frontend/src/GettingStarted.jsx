import React from 'react';
export default function GettingStarted({spec,plan,hasResults,mode}) {
  const next=!spec?'Import a contract':!plan?'Choose an operation and generate tests':!hasResults?'Review and approve one request':'Inspect the recorded checks and report';
  return <section className="panel getting-started" aria-label="Getting started">
    <h2>Start with one request</h2><p role="status">Next: {next}.</p>
    <details open={!spec}><summary>Your first test, step by step</summary><ol>
      <li><a href="#contract-import">Load the sample API</a>, review its contract and select Import contract.</li>
      <li><a href="#test-workbench">Choose POST /products</a> and generate scenarios.</li>
      <li>Choose the minimum-price case. Inspect the body, expected status, response checks and target.</li>
      <li>Approve that saved revision and execute it. The sample deliberately returns HTTP 400 for price zero, while its contract expects 201.</li>
      <li>Inspect the failing check, then preview or download its report. A cURL reproduction sends a new request.</li>
    </ol><p>{mode==='ai'?'AI mode sends contract/context to the configured provider and may incur usage charges.':'Demo mode uses templates and makes no model-provider calls.'} Passing checks do not prove the whole API is correct. Saved work is local to this backend workspace.</p></details>
  </section>;
}
