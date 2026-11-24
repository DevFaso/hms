import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadTemplates, PORTAL_DIR } from './lib/template-gate.mjs';
import { controlStyleSources } from './lib/form-control-styles.mjs';
import { bareControlFindings } from './lib/form-control-design.mjs';
import { componentSources } from './lib/component-sources.mjs';

export function legacyMountFindings(templates, components) {
  const owner = 'src/app/billing/cash-closing/cash-closing.component.ts';
  const mounted =
    templates.some(
      (template) => template.rel !== owner && /<app-cash-closing(?:\s|>)/.test(template.text),
    ) ||
    components.some(
      (component) =>
        component.file !== owner &&
        !component.file.startsWith('src/app/testing/') &&
        /\bCashClosingComponent\b/.test(component.text),
    );
  return mounted
    ? [
        'CashClosingComponent is now mounted/imported: retire its unmounted-control exception and review its live form.',
      ]
    : [];
}

// Complements the administration census in the independently reviewable C-UI PR.
export const inScope = (file) =>
  !/^src\/app\/(admin\/|admin-governance\/|super-admin\/)/.test(file);
export function checkClinicalFormControls(ctx = {}) {
  const exceptions = JSON.parse(
    readFileSync(resolve(PORTAL_DIR, 'scripts/clinical-form-control-exceptions.json'), 'utf8'),
  );
  const templates = ctx.templates ?? loadTemplates();
  const findings = bareControlFindings(
    templates,
    exceptions,
    inScope,
    ctx.controlStyles ?? controlStyleSources(templates),
  );
  findings.push(...legacyMountFindings(templates, ctx.components ?? componentSources()));
  for (const finding of findings) console.error(finding);
  return findings.length === 0;
}
if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  process.exitCode = checkClinicalFormControls() ? 0 : 1;
}
