// Guards the portal's HTTP security headers (hospital-portal/nginx.conf).
//
// nginx forgets every inherited add_header in a location that sets its own,
// which is how index.html once shipped with no security headers at all. So
// the headers live in nginx/security-headers.conf and every location that
// serves the portal itself must include it; the /api/ proxies must NOT (the
// API sends its own, and a second copy of X-Frame-Options or Referrer-Policy
// is at best noise, at worst a conflicting value).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  applyScriptHashes,
  HASHES_PLACEHOLDER,
  inlineScriptHashes,
} from './write-csp-script-hashes.mjs';

const INCLUDE = 'include /etc/nginx/security-headers.conf;';
const nginx = readFileSync('nginx.conf', 'utf8').replace(/#.*$/gm, '');
const headers = readFileSync('nginx/security-headers.conf', 'utf8').replace(/#.*$/gm, '');

/** Every `location … { … }` block of the config: its matcher and its body. */
function locations(config) {
  const blocks = [];
  const opener = /\blocation\b([^{]*)\{/g;
  let match;
  while ((match = opener.exec(config))) {
    let depth = 1;
    let i = opener.lastIndex;
    while (depth > 0 && i < config.length) {
      if (config[i] === '{') depth++;
      else if (config[i] === '}') depth--;
      i++;
    }
    blocks.push({ matcher: match[1].trim(), body: config.slice(opener.lastIndex, i - 1) });
  }
  return blocks;
}

/** The value of one add_header in security-headers.conf. */
function header(name) {
  const match = headers.match(new RegExp(`add_header\\s+${name}\\s+"([^"]*)"\\s+always;`));
  assert.ok(match, `${name} must be set with \`always\` in nginx/security-headers.conf`);
  return match[1];
}

function directive(policy, name) {
  const found = policy
    .split(';')
    .map((part) => part.trim())
    .find((part) => part.split(/\s+/)[0] === name);
  assert.ok(found, `the CSP must declare ${name}`);
  return found.split(/\s+/).slice(1);
}

const csp = header('Content-Security-Policy(?:-Report-Only)?');

test('every location that serves the portal includes the security headers', () => {
  const blocks = locations(nginx);
  assert.ok(blocks.length >= 8, `expected the portal's locations, found ${blocks.length}`);
  for (const { matcher, body } of blocks) {
    if (/\bproxy_pass\b/.test(body)) {
      assert.ok(
        !body.includes(INCLUDE) && !/\badd_header\b/.test(body),
        `location ${matcher} proxies to the API, which sets its own headers: add none here`,
      );
    } else {
      assert.ok(body.includes(INCLUDE), `location ${matcher} must \`${INCLUDE}\``);
    }
  }
});

test('no add_header outside a location (it would leak onto the API proxies twice)', () => {
  let outside = nginx;
  for (const { body } of locations(nginx)) outside = outside.replace(body, '');
  assert.doesNotMatch(outside, /\badd_header\b/);
});

test('the shared headers carry the hardened values', () => {
  assert.equal(header('Strict-Transport-Security'), 'max-age=31536000; includeSubDomains');
  assert.equal(header('X-Frame-Options'), 'DENY');
  assert.equal(header('X-Content-Type-Options'), 'nosniff');
  assert.equal(header('Referrer-Policy'), 'strict-origin-when-cross-origin');
  const permissions = header('Permissions-Policy');
  // The portal captures patient photos and eMAR barcodes (camera) and records
  // chat voice notes (microphone); nothing reads the location.
  assert.match(permissions, /camera=\(self\)/);
  assert.match(permissions, /microphone=\(self\)/);
  assert.match(permissions, /geolocation=\(\)/);
});

test('the CSP is strict: no inline or eval script, no plugins, no framing, no base hijack', () => {
  const scripts = directive(csp, 'script-src');
  assert.ok(scripts.includes(HASHES_PLACEHOLDER), 'inline scripts are allowed by build-time hash');
  for (const loose of ["'unsafe-inline'", "'unsafe-eval'", '*', 'https:', 'data:']) {
    assert.ok(!scripts.includes(loose), `script-src must not allow ${loose}`);
  }
  assert.deepEqual(directive(csp, 'object-src'), ["'none'"]);
  assert.deepEqual(directive(csp, 'frame-ancestors'), ["'none'"]);
  assert.deepEqual(directive(csp, 'base-uri'), ["'self'"]);
  assert.deepEqual(directive(csp, 'form-action'), ["'self'"]);
  assert.deepEqual(directive(csp, 'default-src'), ["'self'"]);
});

test('every external origin the portal talks to is allowed by the CSP', () => {
  const connect = directive(csp, 'connect-src');
  const frames = directive(csp, 'frame-src');
  for (const file of ['environment.ts', 'environment.dev.ts', 'environment.prod.ts']) {
    const source = readFileSync(`src/environments/${file}`, 'utf8');
    const faro = source.match(/faroCollectorUrl:\s*'([^']*)'/s)?.[1];
    if (faro) assert.ok(connect.includes(new URL(faro).origin), `${file}: Faro collector`);
    const issuer = source.match(/issuer:\s*'([^']*)'/)?.[1];
    if (issuer && issuer.startsWith('https://')) {
      const origin = new URL(issuer).origin;
      assert.ok(connect.includes(origin), `${file}: Keycloak issuer in connect-src`);
      assert.ok(frames.includes(origin), `${file}: Keycloak issuer in frame-src (silent refresh)`);
    }
  }
  // The font stylesheets in index.html.
  assert.ok(directive(csp, 'style-src').includes('https://fonts.googleapis.com'));
  assert.ok(directive(csp, 'font-src').includes('https://fonts.gstatic.com'));
  // Blob previews: PDF in an iframe, photos and scans as images, voice notes.
  assert.ok(frames.includes('blob:'));
  assert.ok(directive(csp, 'img-src').includes('blob:'));
  assert.ok(directive(csp, 'media-src').includes('blob:'));
});

test('inline scripts are hashed exactly as the browser hashes them', () => {
  const html =
    '<script>a()</script><script src="main.js" type="module"></script>' +
    '<script type="text/javascript">\r\n  b();\r\n</script><script>a()</script>';
  assert.deepEqual(inlineScriptHashes(html), [
    // printf 'a()' | openssl dgst -sha256 -binary | base64
    "'sha256-qVpDBgj7bpq5hMAcGp3AOc79J3Y1Z4HvySTwKrWDoy4='",
    // The HTML parser hands the script LF, not the CRLF a Windows checkout has:
    // printf '\n  b();\n' | openssl dgst -sha256 -binary | base64
    "'sha256-vJZYjneEF8LRio5tFk2+HT+ejJJK2TwIJsCCMksNLhE='",
  ]);
  assert.equal(
    applyScriptHashes(`script-src 'self' ${HASHES_PLACEHOLDER};`, ["'sha256-x'"]),
    "script-src 'self' 'sha256-x';",
  );
  assert.throws(() => applyScriptHashes('script-src', []), /exactly one/);
});

test('the image build fills the hashes from the built index.html', () => {
  const dockerfile = readFileSync('Dockerfile', 'utf8');
  assert.match(
    dockerfile,
    /node scripts\/write-csp-script-hashes\.mjs dist\/hospital-portal\/browser\/index\.html/,
  );
  assert.match(
    dockerfile,
    /COPY --from=build \/app\/security-headers\.conf \/etc\/nginx\/security-headers\.conf/,
  );
});
