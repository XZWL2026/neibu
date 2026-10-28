// Run against TEST 124 via its SSH tunnel. Makes only anonymous GET requests.
// Usage: node deployment/test-124/scripts/verify-portal-routing.mjs [origin]
import assert from 'node:assert/strict';

const origin = process.argv[2] ?? 'http://127.0.0.1:28080';
const metadata = await fetch(new URL('/test-environment.json', origin), { signal: AbortSignal.timeout(10000) });
assert.equal(metadata.status, 200);
const environment = await metadata.json();
assert.equal(environment.environment, 'TEST ONLY - xzwl-test-124');
assert.equal(environment.production_data, false);

for (const route of ['/home', '/api/portal-entry', '/api/login', '/api/dashboard', '/api/keys', '/admin/login', '/admin/overview']) {
  const response = await fetch(new URL(route, origin), { signal: AbortSignal.timeout(10000) });
  assert.equal(response.status, 200, `${route}: HTTP status`);
  const scripts = [...(await response.text()).matchAll(/<script\b[^>]*\bsrc=["']([^"']+)["']/g)].map(match => match[1]);
  const prefix = route === '/home' ? '/assets/' : '/admin/assets/';
  assert.ok(scripts.length > 0 && scripts.every(src => src.startsWith(prefix)), `${route}: expected ${prefix}, got ${JSON.stringify(scripts)}`);
  for (const src of scripts) {
    const asset = await fetch(new URL(src, origin), { headers: { Range: 'bytes=0-255' }, signal: AbortSignal.timeout(10000) });
    assert.ok([200, 206].includes(asset.status), `${src}: missing script`);
    assert.match(asset.headers.get('content-type') ?? '', /javascript/, `${src}: wrong content type`);
    await asset.body?.cancel();
  }
  console.log(`PASS ${route}: ${prefix}`);
}

for (const route of ['/api/v1/auth/session', '/admin/api/v1/auth/session', '/api/session/api/v1/auth/session']) {
  const response = await fetch(new URL(route, origin), { signal: AbortSignal.timeout(10000) });
  assert.equal(response.status, 200, `${route}: HTTP status`);
  assert.match(response.headers.get('content-type') ?? '', /application\/json/, `${route}: API must not return HTML`);
  const session = await response.json();
  assert.equal(session.authenticated, false, `${route}: anonymous session`);
  console.log(`PASS ${route}: anonymous JSON session`);
}
