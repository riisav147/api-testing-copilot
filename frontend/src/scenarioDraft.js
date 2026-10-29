// Shared by the form and JSON editors so switching views never changes the request.
export function parseRequest(text) {
  let request;
  try { request = JSON.parse(text); } catch { throw new Error('Request JSON is invalid. Check quotes, commas and brackets.'); }
  if (!request || Array.isArray(request) || typeof request !== 'object'
      || !Object.hasOwn(request, 'body') || !Object.hasOwn(request, 'query')
      || Object.keys(request).some(key => !['body', 'query'].includes(key))) {
    throw new Error('Use an object containing only "body" and "query". Method and path stay fixed.');
  }
  if (!request.query || Array.isArray(request.query) || typeof request.query !== 'object'
      || Object.values(request.query).some(value => typeof value !== 'string')) {
    throw new Error('Query must be an object with text values, such as {"limit":"10"}.');
  }
  return request;
}
export function toFields(body) {
  if (body === null) return [];
  if (Array.isArray(body) || typeof body !== 'object') throw new Error('Use JSON view to edit a body that is not an object or null.');
  return Object.entries(body).map(([name, value]) => ({ name, type: value === null ? 'null' : Array.isArray(value) ? 'array' : typeof value,
    value: typeof value === 'string' ? value : JSON.stringify(value) }));
}
function entries(rows, valueFor) {
  const seen = new Set();
  return rows.map(row => {
    if (!row.name.trim()) throw new Error('Every field needs a name. Remove unused rows.');
    if (seen.has(row.name)) throw new Error(`Duplicate field: ${row.name}`);
    seen.add(row.name);
    return [row.name, valueFor(row)];
  });
}
export function fromFields(fields, queries, bodyEnabled) {
  const body = bodyEnabled ? Object.fromEntries(entries(fields, field => {
    if (field.type === 'string') return field.value;
    if (field.type === 'null') return null;
    let value;
    try { value = JSON.parse(field.value); } catch { throw new Error(`Enter a valid ${field.type} for ${field.name}.`); }
    const type = value === null ? 'null' : Array.isArray(value) ? 'array' : typeof value;
    if (type !== field.type || (type === 'number' && !Number.isFinite(value))) throw new Error(`Enter a valid ${field.type} for ${field.name}.`);
    return value;
  })) : null;
  return { body, query: Object.fromEntries(entries(queries, row => row.value)) };
}
export function formatRequest(scenario) { return JSON.stringify({ query: scenario.query, body: scenario.body ?? null }, null, 2); }
export async function readContractFile(file) {
  if (!file || !/\.(json|ya?ml)$/i.test(file.name)) throw new Error('Choose one .json, .yaml or .yml specification.');
  if (file.size > 240000) throw new Error('File is too large. Specifications may contain up to 60,000 characters.');
  const content = await file.text();
  if (!content.trim()) throw new Error('The selected file is empty.');
  if (content.length > 60000) throw new Error('Specifications may contain up to 60,000 characters.');
  return content;
}
