import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

test('every optional remote font stylesheet is nonblocking and the root keeps loading feedback', () => {
  const html = readFileSync('src/index.html', 'utf8');
  const fonts = [...html.matchAll(/<link\b[^>]*fonts\.googleapis\.com[^>]*>/gs)].map(
    (match) => match[0],
  );
  const styles = fonts.filter((tag) => tag.includes('rel="stylesheet"'));
  assert.equal(styles.length, 3);
  for (const tag of styles) assert.match(tag, /media="print"[^>]*data-optional-font/);
  assert.match(html, /id="boot-screen"/);
  assert.match(html, /setTimeout\([\s\S]+20000\)/);
  assert.match(readFileSync('src/app/app.component.ts', 'utf8'), /<app-route-loading\s*\/>/);
});

const shared = readFileSync('../scripts/perf/go-live-journey.js', 'utf8')
  .replace(/^import .*;\r?\n/gm, '')
  .replace(/export /g, '');
function run(env, csrfAvailable = true) {
  const calls = [];
  const sandbox = {
    __ENV: env,
    __VU: 1,
    __ITER: 0,
    sleep() {},
    check() {},
    http: {
      get(...args) {
        calls.push(['GET', ...args]);
        if (args[0].endsWith('/auth/csrf-token'))
          return {
            status: 204,
            cookies: csrfAvailable ? { 'XSRF-TOKEN': [{ value: 'cdx-xsrf' }] } : {},
          };
        return { status: 200 };
      },
      post(...args) {
        calls.push(['POST', ...args]);
        return { status: 201 };
      },
    },
  };
  vm.createContext(sandbox);
  vm.runInContext(shared, sandbox);
  return { sandbox, calls };
}
const safe = {
  BASE_URL: 'http://localhost:8081/api',
  AUTH_TOKEN: 'cdx-not-a-secret',
  HOSPITAL_ID: '00000000-0000-0000-0000-00000000cd12',
};
test('every load lane is explicit, read-only by default and rejects production or accidental writes', () => {
  for (const lane of ['registration', 'consultation', 'billing']) {
    const source = readFileSync(`../scripts/perf/${lane}-k6.js`, 'utf8');
    assert.match(source, /configuration\(/);
    assert.match(source, /journey\(/);
    const { sandbox, calls } = run(safe);
    sandbox.journey(lane, '/read', '/write', null);
    assert.equal(calls.length, 1);
    assert.equal(calls[0][0], 'GET');
  }
  for (const target of [
    'https://api.e-keneya.com/api',
    'https://dev.e-keneya.com.evil.test/api',
    'https://user:password@dev.e-keneya.com/api',
  ]) {
    const { sandbox, calls } = run({ ...safe, BASE_URL: target });
    assert.throws(() => sandbox.journey('billing', '/read', '/write', null));
    assert.equal(calls.length, 0);
  }
  const { sandbox, calls } = run({ ...safe, ALLOW_SYNTHETIC_WRITES: 'yes' });
  assert.throws(() => sandbox.journey('billing', '/read', '/write', '{}'));
  assert.equal(calls.length, 0);
});
test('opted-in billing load cannot issue invoices or post money', () => {
  const { sandbox, calls } = run({
    ...safe,
    ALLOW_SYNTHETIC_WRITES: 'yes',
    MOCK_GATEWAYS_CONFIRMED: 'yes',
    RUN_ID: 'cdx-run',
  });
  assert.throws(() => sandbox.journey('billing', '/read', '/write', '{"status":"SENT"}'));
  assert.equal(calls.filter((call) => call[0] === 'POST').length, 0);
  sandbox.journey('billing', '/read', '/write', '{"status":"DRAFT"}');
  assert.equal(calls.filter((call) => call[0] === 'POST').length, 1);
  assert.match(
    readFileSync('../scripts/perf/billing-k6.js', 'utf8'),
    /\/billing-invoices\/hospital\//,
  );
});

test('each opted-in write bootstraps its virtual-user CSRF cookie and echoes it, refusing a missing cookie', () => {
  const env = {
    ...safe,
    ALLOW_SYNTHETIC_WRITES: 'yes',
    MOCK_GATEWAYS_CONFIRMED: 'yes',
    RUN_ID: 'cdx-run',
  };
  for (const lane of ['registration', 'consultation', 'billing']) {
    const { sandbox, calls } = run(env);
    sandbox.journey(lane, '/read', '/write', '{"status":"DRAFT"}');
    assert.equal(calls[1][0], 'GET');
    assert.equal(calls[1][1], `${safe.BASE_URL}/auth/csrf-token`);
    assert.equal(calls[2][0], 'POST');
    assert.equal(calls[2][3].headers['X-XSRF-TOKEN'], 'cdx-xsrf');
    assert.equal(calls[2][3].redirects, 0);
    const refused = run(env, false);
    assert.throws(() => refused.sandbox.journey(lane, '/read', '/write', '{"status":"DRAFT"}'));
    assert.equal(refused.calls.filter((call) => call[0] === 'POST').length, 0);
  }
});
