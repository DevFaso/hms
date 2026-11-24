import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { loadTemplates, PORTAL_DIR } from './lib/template-gate.mjs';
import {
  consentScopeFindings,
  deliveryTranslationFindings,
  presentationFindings,
} from './lib/presentation-regressions.mjs';

export function checkPresentation(ctx = {}) {
  const findings = presentationFindings(ctx.templates ?? loadTemplates());
  const catalog = (locale) =>
    JSON.parse(readFileSync(resolve(PORTAL_DIR, `src/assets/i18n/${locale}.json`), 'utf8'));
  const en = catalog('en');
  const es = catalog('es');
  findings.push(...deliveryTranslationFindings(en, es));
  for (const [language, values] of [
    ['en', en],
    ['fr', catalog('fr')],
    ['es', es],
  ]) {
    findings.push(...consentScopeFindings(values, language));
  }
  for (const finding of findings) console.error(finding);
  return findings.length === 0;
}
