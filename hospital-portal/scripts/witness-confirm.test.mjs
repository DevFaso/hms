import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { test } from 'node:test';
import assert from 'node:assert/strict';

/**
 * QA D2679 (r65 → r77): the witness of a controlled-drug movement was a name picked in a list, and never
 * confirmed. The class: a witness picker of the pharmacy with nothing for the witness to do. Every `<select>`
 * of the pharmacy screens that lists `witnessCandidates()` must be followed, in the same field, by
 * `<app-witness-confirm>` bound to the same choice; a new witness picker cannot ship without it.
 *
 * The server is the gate (ControlledStockWitness refuses a movement whose witness has not confirmed, and
 * ControlledStockWitnessTest pins it); this keeps the place where the witness acts on every form.
 */
const PHARMACY = fileURLToPath(new URL('../src/app/pharmacy/', import.meta.url));
const read = (path) => readFileSync(path, 'utf8');

const templates = readdirSync(PHARMACY)
  .filter((name) => name.endsWith('.html'))
  .map((name) => ({ name, html: read(join(PHARMACY, name)) }));

/** Each witness `<select>…</select>` of a template, with what follows it. */
function witnessPickers(html) {
  const pickers = [];
  const select = /<select\b[^>]*>[\s\S]*?<\/select>/g;
  for (const match of html.matchAll(select)) {
    if (!match[0].includes('witnessCandidates()')) continue;
    const model = /\[\(ngModel\)\]="([^"]+)"/.exec(match[0])?.[1];
    pickers.push({
      model,
      after: html.slice(match.index + match[0].length, match.index + match[0].length + 500),
    });
  }
  return pickers;
}

test('the pharmacy has witness pickers to guard (not vacuous)', () => {
  const count = templates.reduce((n, t) => n + witnessPickers(t.html).length, 0);
  assert.ok(count >= 6, `only ${count} witness picker(s) found`);
});

for (const { name, html } of templates) {
  for (const picker of witnessPickers(html)) {
    test(`${name}: the witness picked in ${picker.model} confirms`, () => {
      assert.ok(picker.model, `${name} has a witness picker with no model`);
      const confirm = /^\s*(?:<!--[\s\S]*?-->\s*)?<app-witness-confirm\b([^>]*)\/>/.exec(
        picker.after,
      );
      assert.ok(confirm, `${name}: nothing for the witness of ${picker.model} to confirm with`);
      assert.ok(
        confirm[1].includes(`[witnessId]="${picker.model}"`),
        `${name}: the confirmation is not bound to ${picker.model}`,
      );
      assert.match(confirm[1], /\[candidates\]="witnessCandidates\(\)"/);
      assert.match(confirm[1], /fieldId="\w+Secret"/);
    });
  }
}

test('the secret is posted in the body of the one confirmation call, never put in a URL or kept', () => {
  const service = read(
    fileURLToPath(new URL('../src/app/services/pharmacy.service.ts', import.meta.url)),
  );
  assert.match(
    service,
    /confirmWitness\([\s\S]{0,300}'\/pharmacy\/stock-transactions\/witness-confirmations',[\s\S]{0,80}\{ witnessedBy, secret \}/,
  );
  const component = read(join(PHARMACY, 'witness-confirm', 'witness-confirm.component.ts'));
  assert.doesNotMatch(component, /localStorage|sessionStorage/);
  // Cleared on success and on refusal.
  assert.ok((component.match(/this\.secret\.set\(''\);/g) ?? []).length >= 2);
});
