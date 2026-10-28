/**
 * Runs a template scanner over every Angular template of the portal (each
 * `.html`, and each inline `template:` — lib/inline-templates.mjs) and shapes
 * its hits into the findings lib/baseline.mjs compares: a portal-relative
 * `file`, a 1-based `line` of that file, and a stable `id` of
 * `file::rule::anchor`.
 *
 * `componentSource` handed to the scanner is the template plus the `.ts`
 * beside it (`x.component.html` → `x.component.ts`, `x.html` → `x.ts`), so a
 * rule may look for a handler the component declares rather than the template.
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve, relative } from 'node:path';

import { templatesIn } from './inline-templates.mjs';

export const PORTAL_DIR = resolve(import.meta.dirname, '..', '..');
export const SRC_DIR = resolve(PORTAL_DIR, 'src', 'app');

export function componentSourceFor(file, text) {
  // An inline template's `text` is the markup alone; the Escape handler of such
  // a component lives in the class around it, so hand back the whole `.ts`.
  if (!file.endsWith('.html')) return readFileSync(file, 'utf8');
  const ts = file.replace(/\.html$/, '.ts');
  return existsSync(ts) ? `${text}\n${readFileSync(ts, 'utf8')}` : text;
}

/**
 * @param {(html: string, componentSource: string, file: string) => {line:number, rule:string, anchor:string, message:string}[]} scan
 * @param {{ skip?: (relFile: string) => boolean }} [options]
 */
export function scanTemplates(scan, options = {}) {
  const findings = [];
  for (const { file, text, lineOffset } of templatesIn(SRC_DIR)) {
    const rel = relative(PORTAL_DIR, file).replaceAll('\\', '/');
    if (options.skip?.(rel)) continue;
    for (const hit of scan(text, componentSourceFor(file, text), rel)) {
      findings.push({
        ...hit,
        file: rel,
        line: hit.line + lineOffset,
        id: `${rel}::${hit.rule}::${hit.anchor}`,
      });
    }
  }
  return findings;
}
