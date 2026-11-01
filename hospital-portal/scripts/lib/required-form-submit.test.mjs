// QA D1193 / D1089 / D1124 guard (round 22): a form with required fields that
// posts empty.
//
// The lab instrument and lab inventory "Créer" on an empty form sent the
// request: the fields carried `required`, but Angular's forms switch the
// browser's own check off (`novalidate`) and the submit button checked
// nothing. The reader got the server's raw "ne doit pas être vide" or a
// generic toast. 26 forms of the portal had the same shape.
//
// Rule, over every template (each `.html` and each inline `template:`): a
// `<form>` holding a `required` / `[required]` control has its submit button
// (`type="submit"`, or a button with no type) carry `[appSubmitGuard]`
// (a reason the component computes) or `submitGuardRequired` (the guard reads
// the form's required fields at click time and names the blank ones;
// shared/forms/submit-guard.directive.ts). Allowlist: none.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { relative, join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

import { templatesIn } from './inline-templates.mjs';
import { tags } from './html-tags.mjs';
import { closeIndexOf } from './dialog-scan.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');

const FIELD = new Set(['input', 'select', 'textarea']);

/** The forms of a template that hold a required field and whose submit is unguarded: `{ line }`. */
export function unguardedRequiredForms(html) {
  const tokens = tags(html);
  const out = [];
  tokens.forEach((t, i) => {
    if (t.type !== 'open' || t.name !== 'form') return;
    const inner = tokens.slice(i + 1, closeIndexOf(tokens, i)).filter((x) => x.type === 'open');
    const required = inner.some(
      (x) => FIELD.has(x.name) && (x.attrs.has('required') || x.attrs.has('[required]')),
    );
    if (!required) return;
    const submits = inner.filter(
      (x) =>
        x.name === 'button' && (x.attrs.get('type') ?? 'submit').replace(/["']/g, '') === 'submit',
    );
    const guarded = submits.some(
      (b) => b.attrs.has('[appSubmitGuard]') || b.attrs.has('submitGuardRequired'),
    );
    if (!guarded) out.push({ line: t.line });
  });
  return out;
}

test('QA D1193: no form with required fields can be sent empty', () => {
  const found = [];
  let forms = 0;
  for (const { file, text, lineOffset } of templatesIn(appDir)) {
    forms += tags(text).filter((t) => t.type === 'open' && t.name === 'form').length;
    const rel = relative(appDir, file).replaceAll('\\', '/');
    for (const f of unguardedRequiredForms(text))
      found.push(`${rel}:${f.line + (lineOffset ?? 0)}`);
  }
  assert.ok(forms >= 50, `every form must be read, saw ${forms}`);
  assert.deepEqual(
    found,
    [],
    'put appSubmitGuard submitGuardRequired (or [appSubmitGuard]="reason") on the submit button',
  );
});

test('QA D1193 guard: the scan sees the lab instrument form and accepts the guarded one', () => {
  const broken = `<form (ngSubmit)="save()">
      <label>Nom * <input type="text" name="name" required [(ngModel)]="form.name" /></label>
      <button type="submit" class="btn-primary" [disabled]="saving()">Créer</button>
    </form>`;
  assert.equal(unguardedRequiredForms(broken).length, 1);
  const fixed = broken.replace(
    '[disabled]="saving()"',
    '[disabled]="saving()" appSubmitGuard submitGuardRequired',
  );
  assert.deepEqual(unguardedRequiredForms(fixed), []);
  const reasoned = broken.replace('[disabled]="saving()"', '[appSubmitGuard]="!form.name"');
  assert.deepEqual(unguardedRequiredForms(reasoned), []);
  const noType = broken.replace('type="submit" ', '');
  assert.equal(unguardedRequiredForms(noType).length, 1);
  const optional = '<form><input name="q" /><button>Go</button></form>';
  assert.deepEqual(unguardedRequiredForms(optional), []);
});
