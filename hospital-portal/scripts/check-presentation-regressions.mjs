import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { loadTemplates, PORTAL_DIR } from './lib/template-gate.mjs';
import { copiedTranslations, presentationFindings } from './lib/presentation-regressions.mjs';

export function checkPresentation(ctx = {}) {
  const findings = presentationFindings(ctx.templates ?? loadTemplates());
  const catalog = (locale) =>
    JSON.parse(readFileSync(resolve(PORTAL_DIR, `src/assets/i18n/${locale}.json`), 'utf8'));
  const en = catalog('en');
  const es = catalog('es');
  findings.push(
    ...copiedTranslations(en.PATIENT_NOTIFICATIONS, es.PATIENT_NOTIFICATIONS).map(
      (key) => `es.PATIENT_NOTIFICATIONS.${key}: missing translation or copied English`,
    ),
  );
  for (const finding of findings) console.error(finding);
  return findings.length === 0;
}
