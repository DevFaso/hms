#!/usr/bin/env node
/**
 * Every component declares its change-detection strategy (QA D2030).
 *
 * Angular 22 made OnPush the default. A component that omits
 * `changeDetection` and sets plain fields in an HTTP callback or a timer never
 * re-renders: the national IU « Afficher le numéro complet » called the API,
 * wrote the audit row and showed nothing, with its button stuck disabled.
 * Declaring the strategy makes the choice explicit: `Eager` for components
 * with plain mutable fields, `OnPush` only for signal-driven ones.
 */
import { resolve } from 'node:path';

import { scanComponents } from './lib/component-sources.mjs';
import { PORTAL_DIR } from './lib/template-gate.mjs';
import { runGate, isMain } from './lib/baseline.mjs';

/** @param {string} source */
export function changeDetectionFindings(source) {
  const hits = [];
  const re = /@Component\(\s*\{/g;
  let m;
  while ((m = re.exec(source))) {
    // The decorator's object literal: balance braces from the opening one.
    let depth = 0;
    let end = m.index + m[0].length - 1;
    for (; end < source.length; end++) {
      const c = source[end];
      if (c === '{') depth++;
      else if (c === '}' && --depth === 0) break;
    }
    const body = source.slice(m.index, end + 1);
    if (/\bchangeDetection\s*:/.test(body)) continue;
    const cls = /export\s+class\s+(\w+)/.exec(source.slice(end))?.[1] ?? 'anonymous';
    hits.push({
      line: source.slice(0, m.index).split('\n').length,
      rule: 'missing-change-detection',
      anchor: cls,
      message: `${cls} declares no changeDetection; Angular 22 defaults to OnPush`,
    });
  }
  return hits;
}

export const gate = {
  name: 'change detection',
  baselinePath: resolve(PORTAL_DIR, 'scripts', 'change-detection-baseline.json'),
  findings: (ctx = {}) => scanComponents((text) => changeDetectionFindings(text), ctx),
  comment: ['Components with no explicit changeDetection (QA D2030). It must stay empty.'],
  hint: 'Add `changeDetection: ChangeDetectionStrategy.Eager` (plain fields) or `.OnPush` (signals only).',
};

if (isMain(import.meta.url)) runGate(gate);
