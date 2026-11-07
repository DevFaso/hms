/**
 * QA D1451 (round 35). A template event expression that evaluates to `false`
 * cancels the DOM event: Angular's event plugin calls `preventDefault()`.
 *
 *   <div class="backdrop" (click)="$event.target === $event.currentTarget && close()">
 *     <input type="checkbox" [(ngModel)]="x" />
 *
 * A click inside the dialog bubbles to the backdrop, the comparison is false,
 * the whole expression is false, and the browser puts the checkbox back. The
 * box reads as dead by mouse, label, keyboard and `element.click()`, and a spec
 * that sets the field directly never sees it.
 *
 * The rule reads the expression Angular would return, on every cancelable
 * event of an element that holds (or is) something whose default action
 * matters: a field, a label, a link, a button, a component that may render one.
 * The backdrop has one sanctioned form: `appBackdropClose` (a void handler).
 */
import { classMethods } from './destructive-confirm-scan.mjs';
import { tags, innerHtml } from './html-tags.mjs';
import { balanced, endOfLiteralOrComment } from './js-scan.mjs';

/** The DOM events whose default action a `false` cancels and a reader would miss. */
const CANCELABLE_EVENT =
  /^\((?:click|dblclick|auxclick|contextmenu|mousedown|mouseup|pointerdown|pointerup|touchstart|touchend|keydown|keyup|keypress|paste|cut)(?:\.[\w.]+)?\)$/;

/** An element whose own default action matters. */
const CONTROL_TAG = /^(?:input|select|textarea|label|a|summary|details|option)$/;

/** Something inside the element whose default action a bubbling event would lose. */
const HOLDS_CONTROL =
  /<(?:input|select|textarea|label|a|button|summary|details|[a-z]+-[a-z0-9-]+)[\s/>]/;

/** Offsets of `expr` that are at depth 0 and outside a string literal. */
function topLevel(expr) {
  const out = [];
  let depth = 0;
  for (let i = 0; i < expr.length; i++) {
    const skipped = endOfLiteralOrComment(expr, i);
    if (skipped >= 0) {
      i = skipped;
      continue;
    }
    const ch = expr[i];
    if (ch === '(' || ch === '[' || ch === '{') depth++;
    else if (ch === ')' || ch === ']' || ch === '}') depth--;
    else if (depth === 0) out.push(i);
  }
  return out;
}

function findTop(expr, token, offsets = topLevel(expr)) {
  return offsets.filter((i) => expr.startsWith(token, i));
}

/** The statement Angular returns: the last one of `a(); b()`. */
function lastStatement(expr) {
  const semis = findTop(expr, ';');
  const parts = [];
  let from = 0;
  for (const s of semis) {
    parts.push(expr.slice(from, s));
    from = s + 1;
  }
  parts.push(expr.slice(from));
  return parts.map((p) => p.trim()).findLast((p) => p !== '') ?? '';
}

function unwrap(expr) {
  let e = expr.trim();
  while (e.startsWith('(') && balanced(e, 0).length === e.length) e = e.slice(1, -1).trim();
  return e;
}

function ternaryParts(expr, offsets) {
  const question = offsets.find(
    (i) => expr[i] === '?' && expr[i + 1] !== '.' && expr[i + 1] !== '?' && expr[i - 1] !== '?',
  );
  if (question === undefined) return null;
  // The `:` of this `?`: the first top-level one once nested ternaries are counted.
  let nested = 0;
  for (const i of offsets) {
    if (i <= question) continue;
    if (expr[i] === '?' && expr[i + 1] !== '.' && expr[i + 1] !== '?' && expr[i - 1] !== '?') {
      nested++;
    } else if (expr[i] === ':') {
      if (nested === 0) return [expr.slice(question + 1, i), expr.slice(i + 1)];
      nested--;
    }
  }
  return null;
}

/** A method of the component that is declared to return a boolean, or says `return false`. */
export function booleanMethods(source) {
  const out = new Set();
  const declared =
    /(?:^|\n)[ \t]*(?:(?:private|public|protected|static|override|readonly)\s+)*([A-Za-z_$][\w$]*)\s*(?:=\s*)?\([^()]*(?:\([^()]*\)[^()]*)*\)\s*:\s*boolean\b/g;
  for (const m of source.matchAll(declared)) out.add(m[1]);
  // No return type written: `return false` in its body says the same.
  for (const [name, body] of classMethods(source)) {
    const untyped = new RegExp(String.raw`\b${name}\s*\([^()]*\)\s*\{`).test(source);
    if (untyped && /\breturn false\b/.test(body)) out.add(name);
  }
  return out;
}

/**
 * Can this expression evaluate to exactly `false`? Unknown is "no": a call on a
 * service or a signal's `.set(…)` returns nothing a reader could tell from here.
 */
export function canBeFalse(rawExpr, methods = new Set()) {
  const expr = unwrap(lastStatement(rawExpr));
  if (expr === '') return false;
  const offsets = topLevel(expr);

  const ternary = ternaryParts(expr, offsets);
  if (ternary) return ternary.some((branch) => canBeFalse(branch, methods));

  const ors = findTop(expr, '||', offsets);
  // `a || b` is never `a` when `a` is falsy, so only the right side can answer false.
  if (ors.length > 0) return canBeFalse(expr.slice(ors.at(-1) + 2), methods);
  const nullish = findTop(expr, '??', offsets);
  if (nullish.length > 0) {
    return (
      canBeFalse(expr.slice(0, nullish[0]), methods) ||
      canBeFalse(expr.slice(nullish[0] + 2), methods)
    );
  }
  // `a && b` answers `a` when `a` is falsy, which is `false` for any condition.
  if (findTop(expr, '&&', offsets).length > 0) return true;

  const assign = offsets.find(
    (i) =>
      expr[i] === '=' &&
      expr[i + 1] !== '=' &&
      expr[i - 1] !== '=' &&
      !['!', '<', '>'].includes(expr[i - 1]),
  );
  if (assign !== undefined) return canBeFalse(expr.slice(assign + 1), methods);

  if (offsets.some((i) => '<>'.includes(expr[i]))) return true;
  if (['==', '!='].some((op) => findTop(expr, op, offsets).length > 0)) return true;
  if (expr.startsWith('!') || expr === 'false') return true;

  const own = /^(?:this\.)?([A-Za-z_$][\w$]*)\s*\(/.exec(expr);
  return (
    own !== null &&
    methods.has(own[1]) &&
    balanced(expr, expr.indexOf('(')).length === expr.length - expr.indexOf('(')
  );
}

/**
 * @param {string} html the template
 * @param {string} source the component's class (the template plus its `.ts`)
 */
export function eventCancelFindings(html, source = '') {
  const methods = booleanMethods(source);
  const tokens = tags(html);
  const out = [];
  tokens.forEach((tag, index) => {
    if (tag.type !== 'open') return;
    for (const [attr, value] of tag.attrs) {
      if (!CANCELABLE_EVENT.test(attr) || !value || !canBeFalse(value, methods)) continue;
      const inner = innerHtml(html, tokens, index) ?? '';
      const submits = tag.name === 'button' && tag.attrs.get('type') !== 'button';
      if (!submits && !CONTROL_TAG.test(tag.name) && !tag.name.includes('-') && !HOLDS_CONTROL.test(inner)) {
        continue;
      }
      out.push({
        line: tag.line,
        rule: 'event-cancelled-by-false',
        anchor: `${attr}=${value.replace(/\s+/g, ' ').trim()}`,
        message: `${attr} on <${tag.name}> can evaluate to false, and Angular then cancels the event: a field, link or button inside stops working (QA D1451)`,
      });
    }
  });
  return out;
}
