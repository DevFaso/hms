// QA D1089 / D1069 guard (round 19): a button is never disabled because an
// amount (or, in billing and pharmacy, a cancel / void reason) is empty.
//
// PR #981 promised "Saisissez un montant supérieur à zéro" in the patient's pay
// dialog, and the component did show it, but "Payer" was bound to
// `[disabled]="payProcessing() || !payAmount || payAmount <= 0"`, so the
// handler that shows the message could never run (round 19, NOT_FIXED). The
// same dead button sat on the desk's payment dialog, the invoice line forms
// and the payment-reversal and invoice-cancel reasons. The rule: the button
// stays clickable (only a request in flight disables it) and the handler
// refuses with the message, through `refuseFields` / `cfaAmountProblem`.
//
// Scan: every `[disabled]` binding on a `<button>` in every template (`.html`
// and inline `template:`); the identifiers it reads, and the bodies of the
// component methods it calls, must not name an amount (amount, price, copay,
// fee); in billing and pharmacy screens, nor a reason. Pins: none.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { templatesIn } from './inline-templates.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');

/** An identifier that holds money typed by the user. */
const AMOUNT = /^[a-z]\w*(?:Amount|Price|Copay|Fee)\b|^(?:amount|price|copay|fee)\b/;
/** An identifier that holds a typed reason. */
const REASON = /^[a-z]\w*Reason\b|^reason\b/;
/** Screens where a void without a reason moves money or stock (QA D1069). */
const MONEY_SCREENS =
  /^(billing|pharmacy|reception\/payment-pending-panel|patient-portal\/my-billing)\//;

/** The `[disabled]="…"` values of the `<button>` tags of one template. */
export function disabledBindings(html) {
  const out = [];
  for (const tag of html.matchAll(/<button\b[^>]*>/gs)) {
    const m = /\[disabled\]\s*=\s*"([^"]*)"/s.exec(tag[0]);
    if (m) out.push(m[1]);
  }
  return out;
}

/** The body of member `name` in a component's source ('' when absent). */
function memberBody(ts, name) {
  const header = new RegExp(
    `^ {2}(?:(?:public|private|protected|readonly|get)\\s+)*${name}\\s*\\([^)]*\\)[^{;]*\\{`,
    'm',
  );
  const m = header.exec(ts);
  if (!m) return '';
  let depth = 0;
  for (let i = m.index + m[0].length - 1; i < ts.length; i++) {
    if (ts[i] === '{') depth++;
    else if (ts[i] === '}' && --depth === 0) return ts.slice(m.index, i + 1);
  }
  return '';
}

/** The identifiers an expression reads (`this.` dropped, member access split). */
function identifiers(expr) {
  return [...expr.matchAll(/[A-Za-z_$][\w$]*/g)].map((m) => m[0]);
}

/** Whether a `[disabled]` expression waits on a typed amount (or reason). */
export function deadOnEmpty(expr, componentTs = '', moneyScreen = false) {
  const words = new Set(identifiers(expr));
  for (const call of expr.matchAll(/([A-Za-z_$][\w$]*)\s*\(/g)) {
    const body = memberBody(componentTs, call[1]);
    for (const w of identifiers(body)) words.add(w);
  }
  for (const w of words) {
    if (AMOUNT.test(w)) return w;
    if (moneyScreen && REASON.test(w)) return w;
  }
  return null;
}

test('QA D1089 / D1069: no button is disabled by an empty amount or a missing void reason', () => {
  const offenders = [];
  let bindings = 0;
  for (const { file, text } of templatesIn(appDir)) {
    const rel = relative(appDir, file).replaceAll('\\', '/');
    const tsFile = file.endsWith('.html') ? file.replace(/\.html$/, '.ts') : file;
    const componentTs = existsSync(tsFile) ? readFileSync(tsFile, 'utf8') : '';
    for (const expr of disabledBindings(text)) {
      bindings++;
      const word = deadOnEmpty(expr, componentTs, MONEY_SCREENS.test(rel));
      if (word)
        offenders.push(`${rel}: [disabled]="${expr.replace(/\s+/g, ' ').trim()}" waits on ${word}`);
    }
  }
  assert.ok(bindings > 100, `scanned ${bindings} [disabled] bindings`);
  assert.deepEqual(
    offenders,
    [],
    'a dead button hides the message its handler would show: disable only while a request runs, and refuse in the handler',
  );
});

test('QA D1089 guard: the scan sees the shapes the defect had', () => {
  assert.equal(deadOnEmpty('payProcessing() || !payAmount || payAmount <= 0'), 'payAmount');
  assert.equal(
    deadOnEmpty(
      '!canAddItem()',
      '  canAddItem(): boolean {\n    return !!this.newItemUnitPrice();\n  }\n',
    ),
    'newItemUnitPrice',
  );
  assert.equal(
    deadOnEmpty('paymentActionBusy() || !reversalReason().trim()', '', true),
    'reversalReason',
  );
  assert.equal(deadOnEmpty('paymentActionBusy() || !reversalReason().trim()', '', false), null);
  assert.equal(deadOnEmpty('payProcessing()', '  payProcessing = signal(false);\n'), null);
  assert.deepEqual(disabledBindings('<button class="x" [disabled]="saving()">'), ['saving()']);
});
