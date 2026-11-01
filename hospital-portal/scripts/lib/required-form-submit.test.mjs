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
// shared/forms/submit-guard.directive.ts) — or its submit handler refuses by
// itself, field by field (`refuseFields`, `focusFirstInvalid`, `blankFields`:
// the patient registration form and five others name their own fields; the
// guard would replace their wording with the generic one). Allowlist: none.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { relative, join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

import { templatesIn } from './inline-templates.mjs';
import { tags } from './html-tags.mjs';
import { closeIndexOf } from './dialog-scan.mjs';
import { memberBody } from './disabled-control-scan.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');

const FIELD = new Set(['input', 'select', 'textarea']);

/** A submit handler that refuses blank required fields itself, naming each one. */
const SELF_REFUSAL = /\brefuseFields\(|\bfocusFirstInvalid\(|\bblankFields\(/;

/** The method a binding calls (`save($event)` → `save`). */
function handlerName(binding) {
  return /^\s*(?:this\.)?([A-Za-z_$][\w$]*)\s*\(/.exec(binding ?? '')?.[1] ?? null;
}

/** The forms of a template that hold a required field and whose submit is unguarded: `{ line }`. */
export function unguardedRequiredForms(html, componentTs = '') {
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
    if (guarded) return;
    const handlers = [t.attrs.get('(ngSubmit)'), t.attrs.get('(submit)')]
      .concat(submits.map((b) => b.attrs.get('(click)')))
      .map(handlerName)
      .filter(Boolean);
    if (handlers.some((h) => SELF_REFUSAL.test(memberBody(componentTs, h)))) return;
    out.push({ line: t.line });
  });
  return out;
}

test('QA D1193: no form with required fields can be sent empty', () => {
  const found = [];
  let forms = 0;
  for (const { file, text, lineOffset } of templatesIn(appDir)) {
    forms += tags(text).filter((t) => t.type === 'open' && t.name === 'form').length;
    const rel = relative(appDir, file).replaceAll('\\', '/');
    const tsFile = file.endsWith('.html') ? file.replace(/\.html$/, '.ts') : file;
    const componentTs = existsSync(tsFile) ? readFileSync(tsFile, 'utf8') : '';
    for (const f of unguardedRequiredForms(text, componentTs))
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
  // A handler that refuses field by field itself is not this class.
  const selfChecked = broken.replace('(ngSubmit)="save()"', '(ngSubmit)="onSubmit()"');
  const ts =
    '  onSubmit(): void {\n    if (refuseFields(this.host, blankFields(v, ids), ids, msg)) return;\n  }\n';
  assert.deepEqual(unguardedRequiredForms(selfChecked, ts), []);
  const passThrough = '  onSubmit(): void {\n    this.save();\n  }\n';
  assert.equal(unguardedRequiredForms(selfChecked, passThrough).length, 1);
  const optional = '<form><input name="q" /><button>Go</button></form>';
  assert.deepEqual(unguardedRequiredForms(optional), []);
});
