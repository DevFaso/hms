// QA D1219 guard (round 23): two buttons with the same label in one dialog.
//
// The bed board's transfer-cancel reason dialog showed "Annuler" twice: the
// shared dialog's dismiss button (COMMON.CANCEL) and its confirm button
// (BED_BOARD.TRANSFER_CANCEL, "Annuler" / "Cancelar" / "Cancel"). A nurse
// could not tell which one cancels the transfer and which one only closes.
//
// Rule, in every template of the portal and in each of the three locales:
//  - two buttons shown together in one dialog (both directly in it, not one
//    of them in an `@if` / `@for` / `@switch` branch of their own) never
//    carry the same label (the static text, the translated key, or the
//    aria-label that names an icon button) while doing different things
//    (the header's close icon and the footer's "Fermer" may share a name);
//  - every `<app-reason-dialog>` / `<app-confirm-dialog>`: the confirm label
//    (`confirmKey`, default COMMON.CONFIRM) differs from the dismiss label
//    (`dismissKey`, default COMMON.CANCEL).
// Allowlist: none.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { tags, innerHtml } from './html-tags.mjs';
import { closeIndexOf, DIALOG_MARKUP } from './dialog-scan.mjs';
import { balanced } from './js-scan.mjs';
import { loadTemplates } from './template-gate.mjs';

const I18N = resolve(import.meta.dirname, '..', '..', 'src', 'assets', 'i18n');
const LOCALES = Object.fromEntries(
  ['en', 'fr', 'es'].map((l) => [l, JSON.parse(readFileSync(resolve(I18N, `${l}.json`), 'utf8'))]),
);

function translate(locale, key) {
  let node = LOCALES[locale];
  for (const part of key.split('.')) node = node?.[part];
  return typeof node === 'string' ? node : key;
}

const SHARED = new Map([
  ['app-reason-dialog', { confirm: 'COMMON.CONFIRM', dismiss: 'COMMON.CANCEL' }],
  ['app-confirm-dialog', { confirm: 'COMMON.CONFIRM', dismiss: 'COMMON.CANCEL' }],
]);

/** Every control-flow block body: `{ start, end }` offsets of its braces. */
export function blocksOf(html) {
  const out = [];
  for (const m of html.matchAll(/@(?:if|else\s+if|for|case|switch)\s*\(|@(?:else|default|empty)\s*\{/g)) {
    let brace = m.index + m[0].length - 1;
    if (html[brace] === '(') {
      const cond = balanced(html, brace, '(', ')');
      brace = html.indexOf('{', brace + cond.length);
      if (brace < 0) continue;
    }
    out.push({ start: brace, end: brace + balanced(html, brace, '{', '}').length });
  }
  return out;
}

function innermost(blocks, offset) {
  let best = null;
  for (const b of blocks) {
    if (b.start < offset && offset < b.end && (best === null || b.start > best.start)) best = b;
  }
  return best;
}

/** A label source: a translate key (`{ key }`) or a literal (`{ text }`), or null when bound. */
function labelOf(tag, inner) {
  const aria = tag.attrs.get('aria-label') ?? tag.attrs.get('[attr.aria-label]');
  const source = typeof aria === 'string' ? aria : inner;
  const key = /^\s*(?:\{\{\s*)?'([A-Z0-9_.]+)'\s*\|\s*translate\s*(?:\}\})?\s*$/.exec(
    source.replace(/<[^>]*>[^<]*<\/[^>]*>/g, ' ').replace(/<[^>]*>/g, ' '),
  );
  if (key) return { key: key[1] };
  const text = source
    .replace(/<span[^>]*material-symbols[^>]*>[^<]*<\/span>/g, ' ')
    .replace(/<[^>]*>/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  if (!text || /\{\{|\}\}/.test(text)) return null;
  return { text };
}

function resolved(label, locale) {
  return (label.key ? translate(locale, label.key) : label.text).trim().toLocaleLowerCase(locale);
}

/**
 * @returns {{ line: number, message: string }[]}
 */
export function duplicateLabelFindings(html) {
  const tokens = tags(html);
  const blocks = blocksOf(html);
  const out = [];
  tokens.forEach((tag, index) => {
    if (tag.type !== 'open') return;
    const shared = SHARED.get(tag.name);
    if (shared) {
      const confirm = tag.attrs.get('confirmKey') ?? shared.confirm;
      const dismiss = tag.attrs.get('dismissKey') ?? shared.dismiss;
      for (const locale of Object.keys(LOCALES)) {
        if (translate(locale, confirm).trim() === translate(locale, dismiss).trim()) {
          out.push({
            line: tag.line,
            message: `<${tag.name}> confirm ${confirm} and dismiss ${dismiss} both read "${translate(locale, confirm)}" (${locale}): set dismissKey="COMMON.BACK" or a confirm key that names the action`,
          });
          break;
        }
      }
      return;
    }
    const open = html.slice(tag.start, tag.end);
    if (!DIALOG_MARKUP.test(open)) return;
    const scope = innermost(blocks, tag.start);
    const end = tokens[closeIndexOf(tokens, index)].end;
    const buttons = [];
    for (let k = index + 1; k < tokens.length && tokens[k].start < end; k++) {
      const b = tokens[k];
      if (b.type !== 'open' || b.name !== 'button') continue;
      if (innermost(blocks, b.start) !== scope) continue;
      const label = labelOf(b, innerHtml(html, tokens, k) ?? '');
      const click = String(b.attrs.get('(click)') ?? b.attrs.get('type') ?? '').replace(/\s+/g, '');
      if (label) buttons.push({ line: b.line, label, click });
    }
    for (const locale of Object.keys(LOCALES)) {
      const seen = new Map();
      for (const b of buttons) {
        const text = resolved(b.label, locale);
        const first = seen.get(text);
        // Two buttons that do the same thing (the header's close icon and
        // the footer's Close) may share a name; two different actions may not.
        if (first !== undefined && first.click !== b.click) {
          out.push({
            line: b.line,
            message: `two buttons of one dialog read "${text}" and do different things (${locale}, lines ${first.line} and ${b.line})`,
          });
        } else if (first === undefined) {
          seen.set(text, b);
        }
      }
    }
  });
  return out;
}

test('D1219: the transfer-cancel shape — confirm and dismiss both "Annuler" — is found', () => {
  const html = `<app-reason-dialog titleKey="BED_BOARD.TRANSFER_CANCEL_PROMPT" confirmKey="BED_BOARD.TRANSFER_CANCEL" />`;
  assert.equal(duplicateLabelFindings(html).length, 1);
  const fixed = `<app-reason-dialog confirmKey="BED_BOARD.TRANSFER_CANCEL_CONFIRM" dismissKey="COMMON.BACK" />`;
  assert.deepEqual(duplicateLabelFindings(fixed), []);
});

test('D1219: two literal buttons with one label in a dialog are found; exclusive branches are not', () => {
  const twice = `<div appModalDialog aria-label="x"><button (click)="a()">OK</button><button (click)="b()"> ok </button></div>`;
  assert.equal(duplicateLabelFindings(twice).length, 3);
  const sameAction = `<div appModalDialog aria-label="x"><button aria-label="Close" (click)="c()">x</button><button (click)="c()">Close</button></div>`;
  assert.deepEqual(duplicateLabelFindings(sameAction), []);
  const branches = `<div appModalDialog aria-label="x">@if (a) { <button>OK</button> } @else { <button>OK</button> }</div>`;
  assert.deepEqual(duplicateLabelFindings(branches), []);
});

test('every template: no dialog shows two buttons with the same label (allowlist: none)', () => {
  const offenders = [];
  let shared = 0;
  for (const { rel, text, lineOffset } of loadTemplates()) {
    shared += (text.match(/<app-(?:reason|confirm)-dialog\b/g) ?? []).length;
    for (const f of duplicateLabelFindings(text)) offenders.push(`${rel}:${f.line + lineOffset} ${f.message}`);
  }
  assert.ok(shared >= 10, `only ${shared} shared reason/confirm dialogs found: the scan lost its shape`);
  assert.deepEqual(offenders, []);
});
