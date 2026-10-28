/**
 * Every non-spec `.ts` source under src/app, portal-relative, for the gates
 * that read component code (money helpers, English literals, id fragments).
 * The same `file`/`line`/`id` shape as lib/template-gate.mjs.
 */
import { readFileSync } from 'node:fs';
import { dirname, relative, resolve } from 'node:path';

import { walk } from './walk.mjs';
import { PORTAL_DIR, SRC_DIR } from './template-gate.mjs';

export function componentSources() {
  const out = [];
  for (const file of walk(SRC_DIR, ['.ts'])) {
    if (file.endsWith('.spec.ts')) continue;
    out.push({
      file: relative(PORTAL_DIR, file).replaceAll('\\', '/'),
      text: readFileSync(file, 'utf8'),
    });
  }
  return out;
}

/**
 * @param {(source: string, relFile: string) => {line:number, rule:string, anchor:string, message:string}[]} scan
 * @param {{ components?: ReturnType<typeof componentSources> }} [ctx] a shared context; read from disk when absent
 */
export function scanComponents(scan, ctx = {}) {
  const findings = [];
  for (const { file, text } of ctx.components ?? componentSources()) {
    for (const hit of scan(text, file)) {
      findings.push({ ...hit, file, id: `${file}::${hit.rule}::${hit.anchor}` });
    }
  }
  return findings;
}

/**
 * The selectors of the components whose own template is a dialog
 * (`role="dialog"` or `role="alertdialog"` anywhere in it). A page that wraps
 * `<app-order-set-picker>` in a `<div class="modal">` delegates the dialog
 * semantics to the child; the dialog gate must not report that wrapper.
 *
 * @param {{ components?: ReturnType<typeof componentSources>, templates?: { file: string, text: string }[] }} [ctx]
 *   the shared context: a `templateUrl` is looked up among the templates already
 *   loaded, and read from disk only when the caller loaded none
 */
export function dialogComponentSelectors(ctx = {}) {
  const selectors = new Set();
  const loaded = new Map((ctx.templates ?? []).map((t) => [resolve(t.file), t.text]));
  for (const { file, text } of ctx.components ?? componentSources()) {
    const selector = /selector:\s*['"]([^'"]+)['"]/.exec(text);
    if (!selector) continue;
    let template;
    const url = /templateUrl:\s*['"]([^'"]+)['"]/.exec(text);
    if (url) {
      const path = resolve(PORTAL_DIR, dirname(file), url[1]);
      template = loaded.get(path);
      if (template === undefined) {
        try {
          template = readFileSync(path, 'utf8');
        } catch {
          template = '';
        }
      }
    } else {
      template = text;
    }
    if (/\brole\s*=\s*["'](?:dialog|alertdialog)["']/.test(template)) {
      selectors.add(selector[1].trim());
    }
  }
  return selectors;
}
