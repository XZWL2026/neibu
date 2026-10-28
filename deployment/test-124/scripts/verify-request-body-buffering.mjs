// Anonymous requests must reach authentication even when Nginx buffers their body.
// No price writes: no credentials, an invalid catalog version, and no price rows.
import assert from 'node:assert/strict';

const origin = process.argv[2] ?? 'http://127.0.0.1:28080';
const identity = await fetch(new URL('/test-environment.json', origin), { signal: AbortSignal.timeout(10000) });
assert.equal(identity.status, 200);
const environment = await identity.json();
assert.equal(environment.environment, 'TEST ONLY - xzwl-test-124');
assert.equal(environment.production_data, false);

for (const size of [100, 24000, 100000]) {
  const body = JSON.stringify({ expectedVersion: -1, prices: [], diagnosticPadding: 'x'.repeat(size) });
  const response = await fetch(new URL('/admin/api/v1/admin/billing/media-prices', origin), {
    method: 'PUT', headers: { 'Content-Type': 'application/json' }, body,
    signal: AbortSignal.timeout(15000),
  });
  assert.equal(response.status, 401, `${body.length} bytes must reach authentication`);
  assert.match(response.headers.get('content-type') ?? '', /application\/json/);
  await response.body?.cancel();
  console.log(`PASS ${body.length} bytes: authenticated route rejects anonymous request with JSON 401`);
}
