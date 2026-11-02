/**
 * Round 29 (D1256 / D1272 / D1245 class, NOT_FIXED in rounds 26, 27, 28 and
 * 29): the enumerating half of the BEHAVIOURAL form guard.
 *
 * Every wave fixed what a pattern-based scan could see, and QA found the rest
 * by using the screens: a form with a star, a `required` and a guard on its
 * button that still refused by a toast alone (the nurse station's vitals,
 * D1330), a guard that named a filled field and marked none (the eMAR five
 * rights, D1329), a 409 that named the field under `fieldErrors` while the
 * screen showed a toast (the lab test code, D1338). Only driving the screen
 * catches those, so each submit must be driven by a Karma spec through
 * `src/app/testing/form-refusal-behaviour.ts`. This scan finds the submits
 * from the code, so a NEW form is held to it without anyone adding it to a list:
 *
 * - rule `empty-submit-untested`: a submit control — a `<button>` or
 *   `<input>` carrying `appSubmitGuard` (any binding) or `type="submit"` —
 *   whose anchor (its static `data-testid`, else its static `id`) no spec in
 *   the template's directory names next to an `expectEmptySubmitRefusal(`
 *   call. A submit with neither anchor cannot be named by a spec: its anchor
 *   is `(click)=<handler>` / `type=submit`, and the fix is to give it a
 *   `data-testid` and drive it.
 * - rule `server-field-errors-unmapped`: a component with a guarded submit
 *   whose code never maps a refusal's `fieldErrors` onto its controls
 *   (`refusedFieldReasons(` of shared/refusal-reason.ts, or a component's own
 *   `fieldErrorsOf(`): a 400 naming a field is then a toast with nothing
 *   marked (D1341, D1338). Driven by `expectFieldsMarked(` in the spec.
 *
 * Today's sites are pinned in scripts/form-behaviour-baseline.json with the
 * defect id; the pins only shrink.
 */
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';

import { tags } from './html-tags.mjs';
import { balanced } from './js-scan.mjs';
import { PORTAL_DIR } from './template-gate.mjs';

const unquote = (v) => (v ?? '').replace(/^["']|["']$/g, '').trim();

/** A static attribute value (no interpolation), or ''. */
function staticAttr(attrs, name) {
  const v = attrs.get(name);
  if (v === undefined || v === null) return '';
  const value = unquote(v);
  return value.includes('{{') ? '' : value;
}

/** Whether a tag is a submit control: guarded by the submit guard, or `type="submit"`. */
export function isSubmitControl(tag) {
  if (tag.type !== 'open' || (tag.name !== 'button' && tag.name !== 'input')) return false;
  const guarded = tag.attrs.has('appSubmitGuard') || tag.attrs.has('[appSubmitGuard]');
  const submit = staticAttr(tag.attrs, 'type') === 'submit';
  return guarded || submit;
}

/** Whether the submit control is guarded (a required-field refusal the guard owns). */
function isGuarded(tag) {
  return tag.attrs.has('appSubmitGuard') || tag.attrs.has('[appSubmitGuard]');
}

/**
 * The name a spec gives the control: `data-testid=<v>` or `id=<v>` (both
 * static), else the handler it calls, which no selector can name.
 */
export function anchorOf(tag) {
  const testid = staticAttr(tag.attrs, 'data-testid');
  if (testid) return { anchor: `data-testid=${testid}`, name: testid, nameable: true };
  const id = staticAttr(tag.attrs, 'id');
  if (id) return { anchor: `id=${id}`, name: id, nameable: true };
  const click = unquote(tag.attrs.get('(click)') ?? '');
  const handler = /^([\w$.]+)\s*\(/.exec(click)?.[1];
  if (handler) return { anchor: `(click)=${handler}`, name: '', nameable: false };
  return { anchor: 'type=submit', name: '', nameable: false };
}

const specCache = new Map();

/** The text of every spec in `dir` (the template's own directory). */
function specsIn(dir) {
  if (!specCache.has(dir)) {
    const texts = existsSync(dir)
      ? readdirSync(dir)
          .filter((f) => f.endsWith('.spec.ts'))
          .map((f) => readFileSync(join(dir, f), 'utf8'))
      : [];
    specCache.set(dir, texts);
  }
  return specCache.get(dir);
}

/** The argument text of every `expectEmptySubmitRefusal(` call in a spec. */
export function drivenSelectors(specText) {
  const out = [];
  for (const m of specText.matchAll(/\bexpectEmptySubmitRefusal\(/g)) {
    out.push(balanced(specText, m.index + m[0].length - 1));
  }
  return out;
}

/**
 * Whether a spec drives the control named `name` through
 * `expectEmptySubmitRefusal(`: the call's own arguments name it
 * (`[data-testid="x"]`, `#x`). A selector used elsewhere in the spec (a
 * `querySelector` of the same button) does not count.
 */
export function specDrives(specTexts, name) {
  const esc = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const selector = new RegExp(`(?:data-testid=\\\\?["']${esc}\\\\?["']|#${esc}(?![\\w-]))`);
  return specTexts.some((t) => drivenSelectors(t).some((args) => selector.test(args)));
}

/** Whether a component maps a refusal's `fieldErrors` onto its controls. */
export function mapsFieldErrors(componentSource) {
  return /\b(?:refusedFieldReasons|fieldErrorsOf)\(/.test(componentSource);
}

/**
 * @param {string} html the template
 * @param {string} componentSource the template plus its `.ts`
 * @param {string} relFile the portal-relative template path
 * @param {(dir: string) => string[]} [specs] the spec texts of a directory (tests inject one)
 */
export function formBehaviourFindings(html, componentSource, relFile, specs = specsIn) {
  const out = [];
  const specTexts = specs(dirname(resolve(PORTAL_DIR, relFile)));
  let guardedLine = 0;
  for (const tag of tags(html)) {
    if (!isSubmitControl(tag)) continue;
    if (isGuarded(tag) && guardedLine === 0) guardedLine = tag.line;
    const { anchor, name, nameable } = anchorOf(tag);
    if (nameable && specDrives(specTexts, name)) continue;
    out.push({
      line: tag.line,
      rule: 'empty-submit-untested',
      anchor,
      message: nameable
        ? `submit ${anchor} is not driven empty by a spec (expectEmptySubmitRefusal in this directory)`
        : `submit ${anchor} has no data-testid a spec can name; give it one and drive it with expectEmptySubmitRefusal`,
    });
  }
  if (guardedLine > 0 && !mapsFieldErrors(componentSource)) {
    out.push({
      line: guardedLine,
      rule: 'server-field-errors-unmapped',
      anchor: 'component',
      message:
        'a guarded form whose component never marks the fields a 400 names under fieldErrors ' +
        '(refusedFieldReasons + refuseFields, shared/refusal-reason.ts)',
    });
  }
  return out;
}
