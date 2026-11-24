import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadTemplates, PORTAL_DIR } from './lib/template-gate.mjs';
import { bareControlFindings } from './lib/form-control-design.mjs';

// First module of C-UI. The clinical/operations module extends this census in
// its own PR; it is not silently grandfathered in this module's allowlist.
export const inScope = (file) => /^src\/app\/(admin\/|admin-governance\/|super-admin\/)/.test(file);
export function checkFormControls(ctx = {}) {
  const exceptions = JSON.parse(
    readFileSync(resolve(PORTAL_DIR, 'scripts/form-control-exceptions.json'), 'utf8'),
  );
  const findings = bareControlFindings(ctx.templates ?? loadTemplates(), exceptions, inScope);
  for (const finding of findings) console.error(finding);
  return findings.length === 0;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  process.exitCode = checkFormControls() ? 0 : 1;
}
