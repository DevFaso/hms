/**
 * Which parts of an Angular template expression reach the screen WITHOUT
 * passing through a resolving pipe.
 *
 * The raw-enum scan used to ask one question of a whole `{{ … }}`: does a
 * resolving pipe appear anywhere in it? That excused far too much:
 *
 *     {{ 'ONBOARDING.ROLE_ACTIVE_BODY' | translate: { role: a.roleName } }}
 *     {{ s.jobTitle ?? s.roleName ?? ('SCHEDULING.STAFF_FALLBACK' | translate) }}
 *
 * In the first, `translate` resolves the KEY, and `a.roleName` is spliced
 * into the French sentence as `ROLE_DOCTOR`. In the second, `translate`
 * resolves only the parenthesised fallback, and both fields ahead of it render
 * raw. The five onboarding role-welcome screens and the scheduling staff picker
 * showed wire tokens for exactly this reason while the gate reported them
 * clean.
 *
 * So an expression is now read the way Angular parses it:
 *
 *  - a parenthesised group holding its own top-level pipe is a separate
 *    expression, judged on its own and removed from the one around it;
 *  - what is left splits at its top-level pipes: the HEAD (before the first
 *    pipe) is resolved when that first pipe is a resolving one, and is
 *    otherwise on screen as it is;
 *  - a `translate` pipe's ARGUMENTS are interpolation parameters, spliced into
 *    the translated text verbatim, so they are on screen too. Other pipes'
 *    arguments (`date: 'short'`, `enumLabel: 'x'`) are configuration.
 *
 * String literals are blanked before any of this, so a `|` or `(` inside a
 * key cannot mislead the split and a key like `'A.status'` is never a field.
 */

/** Pipes whose output is not a bare enum token. */
export const RESOLVING_PIPES = new Set([
  'enumLabel',
  'roleLabel',
  'translate',
  'date',
  'number',
  'currency',
  'percent',
]);

/** Pipes whose arguments are spliced into what is displayed. */
const ARGS_RENDERED = new Set(['translate']);

const blankRange = (s, from, to) =>
  s.slice(0, from) + s.slice(from, to).replace(/[^\n]/g, ' ') + s.slice(to);
const keepRange = (s, from, to) => blankRange(blankRange(s, to, s.length), 0, from);

/** String CONTENTS blanked, quotes kept, same length. */
export function blankStrings(expr) {
  let out = '';
  let i = 0;
  while (i < expr.length) {
    const c = expr[i];
    if (c === "'" || c === '"' || c === '`') {
      let k = i + 1;
      while (k < expr.length && expr[k] !== c) k += expr[k] === '\\' ? 2 : 1;
      out += c + expr.slice(i + 1, Math.min(k, expr.length)).replace(/[^\n]/g, ' ');
      if (k < expr.length) out += c;
      i = k + 1;
    } else {
      out += c;
      i += 1;
    }
  }
  return out;
}

/** Offsets of the pipes at bracket depth 0 in [from, to) — `|`, never `||`. */
function topLevelPipes(t, from, to) {
  const pipes = [];
  let depth = 0;
  for (let i = from; i < to; i += 1) {
    const c = t[i];
    if (c === '(' || c === '[' || c === '{') depth += 1;
    else if (c === ')' || c === ']' || c === '}') depth -= 1;
    else if (c === '|' && depth === 0 && t[i - 1] !== '|' && t[i + 1] !== '|') pipes.push(i);
  }
  return pipes;
}

/** Content ranges of the parenthesised groups directly inside [from, to). */
function parenGroups(t, from, to) {
  const groups = [];
  let depth = 0;
  let open = -1;
  for (let i = from; i < to; i += 1) {
    if (t[i] === '(') {
      if (depth === 0) open = i;
      depth += 1;
    } else if (t[i] === ')' && depth > 0) {
      depth -= 1;
      if (depth === 0) groups.push([open + 1, i]);
    }
  }
  return groups;
}

/**
 * Lift every piped group out of [from, to), at any depth: returns the ranges
 * to blank from the enclosing expression and the on-screen parts found inside
 * the lifted groups.
 */
function lift(t, from, to) {
  const blanked = [];
  const found = [];
  for (const [a, b] of parenGroups(t, from, to)) {
    if (topLevelPipes(t, a, b).length) {
      found.push(...onScreen(t, a, b));
      blanked.push([a - 1, b + 1]);
    } else {
      const inner = lift(t, a, b);
      blanked.push(...inner.blanked);
      found.push(...inner.found);
    }
  }
  return { blanked, found };
}

/**
 * The CONDITION ranges of the top-level ternaries in [from, to), nested ones
 * included: `a.status ? (a.status | enumLabel: 'x') : '—'` tests the field and
 * renders only the piped branch, so the test is not a render. A `?` is a
 * ternary unless it is part of `?.` or `??`.
 */
function ternaryConditions(t, from, to) {
  const isTernary = (i) => t[i] === '?' && t[i + 1] !== '.' && t[i + 1] !== '?' && t[i - 1] !== '?';
  let depth = 0;
  for (let i = from; i < to; i += 1) {
    const c = t[i];
    if (c === '(' || c === '[' || c === '{') depth += 1;
    else if (c === ')' || c === ']' || c === '}') depth -= 1;
    else if (depth === 0 && isTernary(i)) {
      // The `:` that closes this `?`, counting nested ternaries in between.
      let nested = 0;
      let d = 0;
      for (let k = i + 1; k < to; k += 1) {
        const ch = t[k];
        if (ch === '(' || ch === '[' || ch === '{') d += 1;
        else if (ch === ')' || ch === ']' || ch === '}') d -= 1;
        else if (d === 0 && isTernary(k)) nested += 1;
        else if (d === 0 && ch === ':') {
          if (nested === 0) {
            return [
              [from, i],
              ...ternaryConditions(t, i + 1, k),
              ...ternaryConditions(t, k + 1, to),
            ];
          }
          nested -= 1;
        }
      }
      return [[from, i]];
    }
  }
  return [];
}

function onScreen(t, from, to) {
  const { blanked, found } = lift(t, from, to);
  let outer = keepRange(t, from, to);
  for (const [a, b] of blanked) outer = blankRange(outer, a, b);

  const pipes = topLevelPipes(outer, from, to);
  const nameAt = (p) => /^\s*([A-Za-z_$][\w$]*)/.exec(outer.slice(p + 1, to))?.[1] ?? '';
  if (!pipes.length || !RESOLVING_PIPES.has(nameAt(pipes[0]))) {
    const head = pipes[0] ?? to;
    let shown = keepRange(outer, from, head);
    for (const [a, b] of ternaryConditions(outer, from, head)) shown = blankRange(shown, a, b);
    found.push(shown);
  }
  pipes.forEach((p, n) => {
    if (!ARGS_RENDERED.has(nameAt(p))) return;
    const end = pipes[n + 1] ?? to;
    const colon = outer.indexOf(':', p);
    if (colon !== -1 && colon < end) found.push(keepRange(outer, colon + 1, end));
  });
  return found;
}

/**
 * The parts of `expr` that are rendered as they are, each as a string the
 * same length as `expr` with everything else blanked — so a match's index in
 * any of them is its index in `expr`.
 */
export function unresolvedParts(expr) {
  const t = blankStrings(expr);
  return onScreen(t, 0, t.length);
}
