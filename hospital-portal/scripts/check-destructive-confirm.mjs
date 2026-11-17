#!/usr/bin/env node
/**
 * Destructive-action gate (QA CX-010, CX-001, round 33; D1197 / D1229 class).
 *
 * A destructive call reachable from a template action with no confirmation on
 * its path fails the build. Rules: lib/destructive-confirm-scan.mjs. What may
 * legitimately end something on one click is pinned, with why, in
 * scripts/destructive-confirm-baseline.json.
 *
 * Usage:
 *   node scripts/check-destructive-confirm.mjs
 *   node scripts/check-destructive-confirm.mjs --report-only
 *   node scripts/check-destructive-confirm.mjs --write-baseline   # READ THE DIFF
 */
import { resolve } from 'node:path';

import {
  destructiveConfirmFindings,
  destructiveServiceMethods,
} from './lib/destructive-confirm-scan.mjs';
import { PORTAL_DIR, scanTemplates } from './lib/template-gate.mjs';
import { componentSources } from './lib/component-sources.mjs';
import { runGate, isMain } from './lib/baseline.mjs';

export const gate = {
  name: 'destructive confirmation',
  baselinePath: resolve(PORTAL_DIR, 'scripts', 'destructive-confirm-baseline.json'),
  findings: (ctx = {}) => {
    const components = ctx.components ?? componentSources();
    const services = destructiveServiceMethods(components);
    const byFile = new Map(components.map((c) => [c.file, c.text]));
    return scanTemplates(
      (html, _source, rel) => {
        // The class alone: an external template's quotes are not TypeScript literals.
        const ts = rel.endsWith('.html')
          ? byFile.get(rel.replace(/\.html$/, '.ts'))
          : byFile.get(rel);
        return ts === undefined ? [] : destructiveConfirmFindings(html, ts, services);
      },
      {},
      ctx,
    );
  },
  comment: [
    'Destructive calls a template reaches with no confirmation on the path, as they stand today (QA CX-010).',
    'Each key is "file::unconfirmed::handler->Service.method"; "why" says why one click is right, or its defect id.',
    'A site not listed fails the build: ask with ConfirmService.ask(), or send it from <app-confirm-dialog> / <app-reason-dialog>, then delete its pin.',
  ],
  hint: 'Ask first: `if (!(await this.confirm.ask({ titleKey, confirmKey }))) return;` (shared/confirm-dialog/confirm.service.ts).',
};

if (isMain(import.meta.url)) runGate(gate);
