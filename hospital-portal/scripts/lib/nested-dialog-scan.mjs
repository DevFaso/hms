/**
 * QA D1209 / D1201 (round 23, regression of the D1201 fix; same class as
 * D1122): a dialog OPENED FROM INSIDE ANOTHER DIALOG rendered under it.
 * "Lever" in the Précautions dialog opened the shared reason dialog while the
 * Précautions dialog stayed open; the bed-board stylesheet gives its `.modal`
 * z-index 1001 and the shared reason dialog's backdrop 1000, so the reason
 * dialog's buttons sat under the Précautions box and a mouse could not lift a
 * precaution. Static z-index numbers chosen per stylesheet cannot order two
 * dialogs from different components; the order is set at run time by
 * `shared/a11y/overlay-stack.ts` (every dialog directive goes through it).
 *
 * This scan finds every such pair by construction, so the guard can list
 * them and name the click-level spec that covers each:
 *
 *  - a dialog written INSIDE another dialog's markup (DOM nesting), and
 *  - a dialog whose open condition (the innermost `@if (x())` around it, or a
 *    `*ngIf`) is a signal or field that a handler bound INSIDE another dialog
 *    sets to a non-empty value when clicked (its synchronous part: what a
 *    `.subscribe(…)` callback does after the server answered is a refresh),
 *    while that handler does not also close the dialog it was clicked in (a
 *    hand-off from one dialog to the next is not a stack).
 *
 * A dialog is an element carrying a dialog directive or role, a native
 * `<dialog>`, or a child component that is itself a dialog (the shared
 * `<app-reason-dialog>` / `<app-confirm-dialog>`, and any component
 * `dialogSelectorsWithDirectives` finds).
 */
import { tags, attrValue } from './html-tags.mjs';
import { balanced } from './js-scan.mjs';
import { closeIndexOf } from './dialog-scan.mjs';

const DIALOG_ATTRS = ['appModalDialog', 'appDialogA11y', 'appDialogFocus', 'appDrawerDialog'];
const CALL = /(?:^|[^\w$.])([A-Za-z_$][\w$]*)\s*\(/g;
/** A write that can open something: `x.set(value)` / `x.update(…)` / `this.x = value`, never a falsy value. */
const FALSY_VALUE = String.raw`(?:null|false|undefined|''|""|\[\])`;
const SIGNAL_WRITE = new RegExp(
  String.raw`\b(?:this\.)?([A-Za-z_$][\w$]*)\s*\.\s*(?:set\s*\((?!\s*${FALSY_VALUE}\s*\))|update\s*\()`,
  'g',
);
const FIELD_WRITE = new RegExp(
  String.raw`\bthis\.([A-Za-z_$][\w$]*)\s*=(?![=>])(?!\s*${FALSY_VALUE}\s*[;,)\n])`,
  'g',
);
const STATEMENT_WRITE = new RegExp(
  String.raw`(?:^|[;\s])([A-Za-z_$][\w$]*)\s*=(?![=>])(?!\s*${FALSY_VALUE}\s*(?:;|$))`,
  'g',
);
const FALSY_SET = (name) =>
  new RegExp(
    String.raw`\b(?:this\.)?${name}\s*\.\s*set\s*\(\s*${FALSY_VALUE}\s*\)|\bthis\.${name}\s*=\s*(?:null|false|undefined)\b`,
  );
const KEYWORDS = new Set([
  'if',
  'for',
  'while',
  'switch',
  'return',
  'function',
  'translate',
  'set',
  'update',
  'emit',
  'stopPropagation',
  'preventDefault',
]);

function isDialogTag(tag, dialogSelectors) {
  if (tag.type !== 'open') return false;
  if (DIALOG_ATTRS.some((a) => tag.attrs.has(a))) return true;
  if (tag.name === 'dialog') return true;
  const role = (attrValue(tag, 'role') ?? '').replace(/['"]/g, '').trim();
  if (role === 'dialog' || role === 'alertdialog') return true;
  return dialogSelectors.has(tag.name);
}

/**
 * The dialogs of a template, in document order, with the identifiers of the
 * condition that renders each one.
 */
function dialogsOf(html, tokens, dialogSelectors) {
  const out = [];
  tokens.forEach((tag, index) => {
    if (!isDialogTag(tag, dialogSelectors)) return;
    const end = tokens[closeIndexOf(tokens, index)].end ?? tag.end;
    out.push({
      tag,
      start: tag.start,
      end,
      line: tag.line,
      name: tag.name,
      conditions: conditionsAround(html, tag.start, tokens, index),
    });
  });
  return out;
}

/** Identifiers of the innermost `@if (…)` block, and of every `*ngIf`, that encloses `offset`. */
function conditionsAround(html, offset, tokens, index) {
  const ids = new Set();
  // The innermost `@if` whose body holds the dialog is what opens it; the
  // blocks around it (a tab, a loaded flag) are the page, not the dialog.
  let innermost = null;
  for (const m of html.matchAll(/@(?:if|else if)\s*\(/g)) {
    if (m.index > offset) break;
    const cond = balanced(html, m.index + m[0].length - 1, '(', ')');
    const braceAt = html.indexOf('{', m.index + m[0].length - 1 + cond.length);
    if (braceAt < 0 || braceAt > offset) continue;
    const body = balanced(html, braceAt, '{', '}');
    if (braceAt + body.length < offset) continue;
    innermost = cond;
  }
  if (innermost !== null) {
    // `@if (x(); as y)`: `y` is the template's alias, not a field.
    const expr = innermost.replace(/;\s*as\s+[A-Za-z_$][\w$]*/g, '');
    for (const id of expr.matchAll(/[A-Za-z_$][\w$]*/g)) ids.add(id[0]);
  }
  // *ngIf on the tag itself or an ancestor element.
  for (let k = index; k >= 0; k--) {
    const t = tokens[k];
    if (t.type !== 'open') continue;
    if (k !== index && (tokens[closeIndexOf(tokens, k)].end ?? t.end) < offset) continue;
    const ngIf = t.attrs.get('*ngIf') ?? t.attrs.get('[ngIf]');
    if (ngIf) for (const id of String(ngIf).matchAll(/[A-Za-z_$][\w$]*/g)) ids.add(id[0]);
  }
  for (const k of ['as', 'null', 'true', 'false', 'undefined', 'length', 'let']) ids.delete(k);
  return ids;
}

function methodBody(source, name) {
  const decl = new RegExp(
    String.raw`(?:^|\n)[ \t]*(?:(?:protected|private|public|async|readonly|override)[ \t]+)*${name}[ \t]*(?:<[^>\n]*>)?\(`,
    'g',
  );
  for (const m of source.matchAll(decl)) {
    const params = balanced(source, m.index + m[0].length - 1, '(', ')');
    const after = m.index + m[0].length - 1 + params.length;
    const brace = source.indexOf('{', after);
    if (brace < 0) continue;
    const between = source.slice(after, brace);
    if (/[;=]/.test(between) && !between.includes('=>')) continue;
    return balanced(source, brace, '{', '}');
  }
  // An arrow-function field: `name = (…) => { … }`.
  const arrow = new RegExp(String.raw`\b${name}\s*=\s*(?:async\s*)?\([^)]*\)\s*(?::[^=]+)?=>\s*\{`);
  const a = arrow.exec(source);
  return a ? balanced(source, a.index + a[0].length - 1, '{', '}') : '';
}

/**
 * A method's synchronous part: what runs in a `.subscribe(…)` / `.then(…)`
 * callback happens after the server answered (a refresh of the record the
 * dialog shows), not when the user clicked.
 */
function withoutCallbacks(body) {
  let out = body;
  for (;;) {
    const m = /\.\s*(?:subscribe|then|pipe)\s*\(/.exec(out);
    if (!m) return out;
    const args = balanced(out, m.index + m[0].length - 1, '(', ')');
    out = out.slice(0, m.index) + out.slice(m.index + m[0].length - 1 + args.length);
  }
}

/** What a handler expression (and the methods it calls directly) writes when clicked. */
function writesOf(expression, source) {
  const writes = new Set();
  let text = expression;
  for (const m of expression.matchAll(SIGNAL_WRITE)) writes.add(m[1]);
  for (const m of expression.matchAll(FIELD_WRITE)) writes.add(m[1]);
  // A template statement's own assignment (`x = true`).
  for (const m of expression.matchAll(STATEMENT_WRITE)) writes.add(m[1]);
  const seen = new Set();
  for (const m of expression.matchAll(CALL)) {
    const name = m[1];
    if (KEYWORDS.has(name) || seen.has(name)) continue;
    seen.add(name);
    const body = withoutCallbacks(methodBody(source, name));
    if (!body) continue;
    for (const w of body.matchAll(SIGNAL_WRITE)) writes.add(w[1]);
    for (const w of body.matchAll(FIELD_WRITE)) writes.add(w[1]);
    text += `\n${body}`;
  }
  return { writes, text };
}

function handlersIn(tokens, from, to) {
  const out = [];
  for (const t of tokens) {
    if (t.type !== 'open' || t.start < from || t.start >= to) continue;
    for (const [attr, value] of t.attrs) {
      if (/^\([\w.-]+\)$/.test(attr) && typeof value === 'string') out.push(value);
    }
  }
  return out;
}

function opensFrom(parent, child, handlers, componentSource) {
  return handlers.some((h) => {
    const { writes, text } = writesOf(h, componentSource);
    if (![...child.conditions].some((c) => writes.has(c))) return false;
    // A hand-off closes the dialog it was clicked in: no stack.
    return ![...parent.conditions].some((c) => !child.conditions.has(c) && FALSY_SET(c).test(text));
  });
}

/**
 * Every dialog that can be opened while another dialog of the same template
 * stays open.
 *
 * @param {string} html the template
 * @param {string} componentSource the template plus the component class
 * @param {Set<string>} dialogSelectors selectors of child components that are dialogs
 * @returns {{ parentLine: number, childLine: number, parent: string, child: string, parentStacked: boolean|null, childStacked: boolean|null, how: 'nested'|'opened' }[]}
 */
export function nestedDialogPairs(html, componentSource, dialogSelectors = new Set()) {
  const tokens = tags(html);
  const dialogs = dialogsOf(html, tokens, dialogSelectors);
  const pairs = [];
  for (const parent of dialogs) {
    const handlers = handlersIn(tokens, parent.start, parent.end);
    for (const child of dialogs) {
      if (child === parent) continue;
      if (child.start > parent.start && child.end <= parent.end) {
        pairs.push(pair(parent, child, 'nested'));
      } else if (
        !(parent.start > child.start && parent.end <= child.end) &&
        opensFrom(parent, child, handlers, componentSource)
      ) {
        pairs.push(pair(parent, child, 'opened'));
      }
    }
  }
  return pairs;
}

function pair(parent, child, how) {
  return {
    parentLine: parent.line,
    childLine: child.line,
    parent: label(parent),
    child: label(child),
    parentStacked: stacked(parent.tag),
    childStacked: stacked(child.tag),
    how,
  };
}

/**
 * Is this end lifted by shared/a11y/overlay-stack.ts? An element with a
 * dialog directive is; a native `<dialog>` opened with showModal() is in the
 * browser's top layer. A child component (`null`) is decided by its own
 * template, which the caller reads.
 */
function stacked(tag) {
  if (['appModalDialog', 'appDialogA11y', 'appDialogFocus'].some((a) => tag.attrs.has(a)))
    return true;
  if (tag.name === 'dialog') return true;
  return tag.name.includes('-') ? null : false;
}

function label(dialog) {
  const cls = dialog.tag.attrs.get('class');
  const key =
    dialog.tag.attrs.get('titleKey') ??
    dialog.tag.attrs.get('[attr.aria-label]') ??
    dialog.tag.attrs.get('aria-labelledby');
  const name = typeof key === 'string' ? key.replace(/\s+/g, ' ').trim().slice(0, 60) : '';
  const first = typeof cls === 'string' ? `.${cls.trim().split(/\s+/)[0]}` : '';
  return `${dialog.name}${first}${name ? `[${name}]` : ''}`;
}

/**
 * Selectors of the components that ARE a dialog: the first element of the
 * template is a dialog (directive, role, `<dialog>`) or the cover that holds
 * one (`*-backdrop` / `*-overlay` / `*-scrim`) — the shared
 * `<app-reason-dialog>` / `<app-confirm-dialog>`, the check-out and
 * close-unseen dialogs, the snapshot drawer. A component that only HOLDS a
 * dialog somewhere in its page (a reception panel) is not one: a click in it
 * is a click on the page.
 *
 * @param {{ file: string, text: string }[]} components the `.ts` sources (portal-relative `file`)
 * @param {{ rel: string, text: string }[]} templates every template, read once
 */
export function wholeDialogSelectors(components, templates) {
  const out = new Set();
  const byComponent = new Map();
  for (const t of templates) {
    const ts = t.rel.endsWith('.html') ? t.rel.replace(/\.html$/, '.ts') : t.rel;
    if (!byComponent.has(ts)) byComponent.set(ts, t.text);
  }
  for (const { file, text } of components) {
    const selector = /selector:\s*['"]([a-z][\w-]*)['"]/.exec(text);
    const template = byComponent.get(file);
    if (!selector || template === undefined) continue;
    const first = tags(template).find((t) => t.type === 'open');
    if (first === undefined) continue;
    const cls = first.attrs.get('class');
    const cover =
      typeof cls === 'string' && /(?:^|[\s-])(?:backdrop|overlay|scrim)(?:\s|$)/.test(cls);
    if (cover || isDialogTag(first, new Set())) out.add(selector[1]);
  }
  return out;
}
