/**
 * QA D1245 / D1089 / D1124 (NOT_FIXED rounds 18–25): a required input that the
 * submit path never checks.
 *
 * The round-22 rule (`required-form-submit.test.mjs`) read only a `<form>`
 * holding a control that carries `required`. Round 25 found the sibling it
 * could not see: the prescription dialog is not a `<form>` (a `.modal` whose
 * "Créer" is a `(click)` button), and its "Nom du patient *" search input
 * carried no `required` — the star was the only sign — so `patientId: ''` was
 * sent and the server answered with a generic 400.
 *
 * Two rules, over every template:
 *
 * - `star-undeclared`: a control whose `<label>` shows the required star
 *   (`Nom du patient *`) declares it — `required`, `[required]`,
 *   `aria-required` / `[attr.aria-required]`, or `required` on
 *   `<app-patient-picker>` (which puts it on its input). The star is what the
 *   reader sees; the attribute is what the submit guard (and a screen reader)
 *   reads.
 * - `unchecked`: every declared-required control is checked by its submit
 *   scope — the nearest enclosing `<form>`, else the nearest dialog
 *   (`role="dialog"`, `<dialog>`, `appDialogA11y`, `appModalDialog`, a `modal`
 *   class), else the template. Checked means a submit button of the scope
 *   carries `submitGuardRequired` (the guard reads every rendered required
 *   control at click time), or a guarded button lists the control's id in
 *   `submitGuardFields` (the guard refuses a listed required field that is
 *   blank, whatever its expression says), or the submit handler refuses field
 *   by field itself (`refuseFields`, `focusFirstInvalid`, `blankFields`).
 */
import { tags } from './html-tags.mjs';
import { closeIndexOf } from './dialog-scan.mjs';
import { memberBody } from './disabled-control-scan.mjs';

const FIELD = new Set(['input', 'select', 'textarea']);
const PICKERS = new Set(['app-patient-picker']);
const SELF_REFUSAL = /\brefuseFields\(|\bfocusFirstInvalid\(|\bblankFields\(/;

const NONE = new Set();

const unquote = (v) => (v ?? '').replace(/^["']|["']$/g, '').trim();

function staticId(tag) {
  if (PICKERS.has(tag.name)) return unquote(tag.attrs.get('inputId'));
  const id = unquote(tag.attrs.get('id'));
  if (id) return id;
  const bound = /^'([^']+)'$/.exec(unquote(tag.attrs.get('[id]')));
  return bound ? bound[1] : '';
}

/** Whether a control (or a picker) declares itself required. */
export function declaresRequired(tag, subForms = NONE) {
  // A field-group component (`<app-provider-evidence-form>`) holds required
  // fields of its own: wherever it is used, its host's submit must read them.
  if (subForms.has(tag.name)) return true;
  const a = tag.attrs;
  if (PICKERS.has(tag.name)) return a.has('required') || a.has('[required]');
  return (
    a.has('required') ||
    a.has('[required]') ||
    a.has('aria-required') ||
    a.has('[attr.aria-required]')
  );
}

function isControl(tag, subForms = NONE) {
  if (PICKERS.has(tag.name) || subForms.has(tag.name)) return true;
  if (!FIELD.has(tag.name)) return false;
  return unquote(tag.attrs.get('type')) !== 'hidden';
}

/**
 * The element the submit guard reads its fields in: the closest `<form>`,
 * `role="dialog"` / `"alertdialog"`, `<dialog>`, `.modal` or `[data-submit-scope]` — the same list as
 * `root()` in shared/forms/submit-guard.directive.ts, so what the scan calls
 * checked is what the guard reads at click time.
 */
function isScope(tag) {
  if (tag.name === 'form' || tag.name === 'dialog' || tag.attrs.has('data-submit-scope'))
    return true;
  // `appDialogA11y` and `appModalDialog` put `role="dialog"` on their host.
  if (tag.attrs.has('appDialogA11y') || tag.attrs.has('appModalDialog')) return true;
  const role = unquote(tag.attrs.get('role'));
  if (role === 'dialog' || role === 'alertdialog') return true;
  return /(?:^|\s)modal(?:\s|$)/.test(unquote(tag.attrs.get('class')));
}

/** The index of each token's parent element (-1 at the top). */
function parents(tokens) {
  const parent = new Array(tokens.length).fill(-1);
  const stack = [];
  tokens.forEach((t, i) => {
    if (t.type === 'close') {
      const at = stack.map((k) => tokens[k].name).lastIndexOf(t.name);
      if (at >= 0) stack.length = at;
      return;
    }
    parent[i] = stack.length > 0 ? stack[stack.length - 1] : -1;
    if (t.type === 'open' && !t.selfClosing && closeIndexOf(tokens, i) !== i) stack.push(i);
  });
  return parent;
}

function scopeOf(tokens, parent, index) {
  for (let k = parent[index]; k >= 0; k = parent[k]) {
    if (isScope(tokens[k])) return k;
  }
  return -1;
}

/** The text of a label without its tags and interpolations. */
function labelText(html, tokens, i) {
  const close = closeIndexOf(tokens, i);
  if (close === i) return '';
  return html
    .slice(tokens[i].end, tokens[close].start)
    .replace(/<[^>]*>/g, ' ')
    .replace(/\{\{[\s\S]*?\}\}/g, ' ');
}

function starredControls(html, tokens, byId) {
  const out = [];
  tokens.forEach((t, i) => {
    if (t.type !== 'open' || t.name !== 'label') return;
    if (!labelText(html, tokens, i).includes('*')) return;
    const forId = unquote(t.attrs.get('for'));
    if (forId && byId.has(forId)) {
      out.push(byId.get(forId));
      return;
    }
    const close = closeIndexOf(tokens, i);
    for (let k = i + 1; k < close; k++) {
      if (tokens[k].type === 'open' && isControl(tokens[k])) {
        out.push(k);
        return;
      }
    }
  });
  return out;
}

function handlerName(binding) {
  return /^\s*(?:this\.)?([A-Za-z_$][\w$]*)\s*\(/.exec(binding ?? '')?.[1] ?? null;
}

function listedFields(button) {
  const raw = button.attrs.get('[submitGuardFields]') ?? '';
  return new Set([...raw.matchAll(/'([^']+)'/g)].map((m) => m[1]));
}

function isSubmitButton(tag, inForm) {
  if (tag.name === 'input') return /^(?:submit|button)$/.test(unquote(tag.attrs.get('type')));
  if (tag.name !== 'button') return false;
  return !inForm || unquote(tag.attrs.get('type') || 'submit') === 'submit';
}

function checks(tokens, scope, componentTs) {
  // Outside any form or dialog the guard has no element to read the fields
  // in (it would fall back to the button's grandparent): only a handler
  // that refuses field by field counts there.
  const from = scope < 0 ? 0 : scope;
  const to = scope < 0 ? tokens.length : closeIndexOf(tokens, scope);
  const inForm = scope >= 0 && tokens[scope].name === 'form';
  const buttons = tokens
    .slice(from, to)
    .filter((t) => t.type === 'open' && isSubmitButton(t, inForm));
  const all = scope >= 0 && buttons.some((b) => b.attrs.has('submitGuardRequired'));
  const listed = new Set();
  for (const b of buttons) {
    if (scope >= 0 && (b.attrs.has('[appSubmitGuard]') || b.attrs.has('appSubmitGuard')))
      for (const id of listedFields(b)) listed.add(id);
  }
  const handlers = [
    inForm ? tokens[scope].attrs.get('(ngSubmit)') : null,
    inForm ? tokens[scope].attrs.get('(submit)') : null,
    ...buttons.map((b) => b.attrs.get('(click)')),
  ]
    .map(handlerName)
    .filter(Boolean);
  const selfRefusal = handlers.some((h) => SELF_REFUSAL.test(memberBody(componentTs, h)));
  return { all, listed, selfRefusal };
}

/**
 * The required controls of a template that no submit path checks.
 *
 * @returns {{ line: number, rule: 'star-undeclared' | 'unchecked', id: string }[]}
 */
export function requiredFieldFindings(html, componentTs = '', subForms = NONE) {
  const tokens = tags(html);
  const byId = new Map();
  tokens.forEach((t, i) => {
    if (t.type === 'open' && isControl(t, subForms)) {
      const id = staticId(t);
      if (id) byId.set(id, i);
    }
  });
  const out = [];
  const required = new Set();
  for (const k of starredControls(html, tokens, byId)) {
    if (declaresRequired(tokens[k])) required.add(k);
    else out.push({ line: tokens[k].line, rule: 'star-undeclared', id: staticId(tokens[k]) });
  }
  tokens.forEach((t, i) => {
    if (t.type === 'open' && isControl(t, subForms) && declaresRequired(t, subForms))
      required.add(i);
  });
  const parent = parents(tokens);
  const cache = new Map();
  for (const k of [...required].sort((a, b) => a - b)) {
    const scope = scopeOf(tokens, parent, k);
    if (!cache.has(scope)) cache.set(scope, checks(tokens, scope, componentTs));
    const c = cache.get(scope);
    const id = staticId(tokens[k]);
    if (c.all || c.selfRefusal || (id && c.listed.has(id))) continue;
    out.push({ line: tokens[k].line, rule: 'unchecked', id });
  }
  return out.sort((a, b) => a.line - b.line);
}

/**
 * Whether a template is a field group another form embeds: it declares
 * required fields, has no button of its own and no form or dialog around
 * them. Its fields are checked by the submit of every template that uses it.
 */
export function isFieldGroup(html) {
  const tokens = tags(html).filter((t) => t.type === 'open');
  if (tokens.some((t) => t.name === 'button' || isScope(t))) return false;
  return tokens.some((t) => isControl(t) && declaresRequired(t));
}
