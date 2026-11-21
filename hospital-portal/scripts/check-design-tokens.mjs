#!/usr/bin/env node
/**
 * Design-token gate (QA r65: D2790 one primary button, D2793 contrast, D2804
 * pre-rebrand colours). Reads every stylesheet, template and component of the
 * portal; rules in lib/design-token-scan.mjs.
 *
 * Usage:
 *   node scripts/check-design-tokens.mjs
 *   node scripts/check-design-tokens.mjs --report-only
 *   node scripts/check-design-tokens.mjs --write-baseline   # READ THE DIFF
 */
import { readFileSync } from 'node:fs';
import { relative, resolve } from 'node:path';

import { scssFindings, sourceFindings, templateFindings } from './lib/design-token-scan.mjs';
import { componentSources } from './lib/component-sources.mjs';
import { PORTAL_DIR } from './lib/template-gate.mjs';
import { walk } from './lib/walk.mjs';
import { runGate, isMain } from './lib/baseline.mjs';

const SRC = resolve(PORTAL_DIR, 'src');
/** The sheet that defines the shared buttons, and the global sheet (its print rule hides them). */
const BUTTON_HOME = new Set(['src/app/shared/styles/_page-common.scss', 'src/styles.scss']);

const rel = (file) => relative(PORTAL_DIR, file).replaceAll('\\', '/');
const shape = (file, hits) =>
  hits.map((h) => ({ ...h, file, id: `${file}::${h.rule}::${h.anchor}` }));

export function designTokenFindings(ctx = {}) {
  const findings = [];
  for (const path of walk(SRC, ['.scss'])) {
    const file = rel(path);
    findings.push(
      ...shape(file, scssFindings(readFileSync(path, 'utf8'), { buttons: !BUTTON_HOME.has(file) })),
    );
  }
  for (const path of walk(resolve(SRC, 'app'), ['.html'])) {
    findings.push(...shape(rel(path), templateFindings(readFileSync(path, 'utf8'))));
  }
  for (const { file, text } of ctx.components ?? componentSources()) {
    // The component's own text: inline `styles:` and `template:` included.
    findings.push(...shape(file, [...sourceFindings(text), ...templateFindings(text)]));
  }
  return findings;
}

export const gate = {
  name: 'design tokens',
  baselinePath: resolve(PORTAL_DIR, 'scripts', 'design-tokens-baseline.json'),
  findings: designTokenFindings,
  comment: [
    'Pre-rebrand colours, low-contrast text colours and local restyling of the shared buttons (QA r65: D2790 / D2793 / D2804).',
    'A pin is a deliberate exception with its reason. A new site is never pinned to pass.',
  ],
  hint:
    'Brand: var(--primary) / --primary-dark / --primary-tint / --primary-soft. Text: var(--text-secondary) / var(--text-muted). ' +
    'Buttons: .btn-primary / .btn-secondary / .btn-danger are defined once in shared/styles/_page-common.scss; ' +
    'a page places them (margin, width) or owns a variant class, it does not restyle them.',
};

if (isMain(import.meta.url)) runGate(gate);
