/**
 * Glued colons in what the reader sees (QA D1052, D1002, D1079).
 *
 * French typography writes a (non-breaking) space before a colon:
 * "Statut : Confirmé", never "Statut: Confirmé". The colon therefore belongs
 * to the language — `{{ 'COMMON.COLON' | translate }}` (en ":", fr "\u00a0:",
 * es ":") or a translation that carries it — and never to the markup.
 *
 * Round 17 found it after translated labels on the pharmacy screens; rounds 18
 * and 19 found it in literal text (`MRN: CHW8973`, `>Statut:<`) and after
 * interpolated labels, which the first scan (`| translate }}:` only) could not
 * see. This module reads the DISPLAY TEXT of a template — tags, attribute
 * values, comments, `{{ … }}` expressions and control-flow headers removed —
 * so `[x]="a ? b : c"`, `@if (a) {`, `translate: { n: 1 }`, `slice:0:8`,
 * `style="color: red"` and `href="https://…"` are never read, and flags:
 *
 *  label  — a letter (or `)` `]` `.` `'`) directly followed by `:`, where the
 *           colon ends the text or is followed by whitespace or a value
 *           (`MRN: {{ mrn }}`, `<dt>Statut:</dt>`, `MRN:{{ mrn }}`). A colon
 *           followed by more letters or `//` (`https://`, `a:b`) is not a
 *           label; a colon after a digit (`10:30`, `1:2`) is a time or ratio.
 *  value  — a colon after an interpolation, with or without spaces
 *           (`{{ 'X' | translate }}:`, `{{ label }} :`), except between two
 *           interpolations with nothing around it (`{{ h }}:{{ m }}`,
 *           `{{ host }}:{{ port }}`: a time or an address, not a label).
 *           An interpolation that is a translation always counts.
 *
 * `.ts` label strings (`'MRN: ' + mrn`, `` `Statut: ${s}` ``) and locale values
 * are read by {@link gluedTsLabels} and {@link gluedFrenchValue}.
 */

const VALUE = '\u0002';
const BREAK = '\u0001';
const LETTER = /[\p{L})\].'\u2019]/u;

/**
 * The display text of a template: tags, comments and control-flow syntax
 * become BREAK, each `{{ … }}` becomes VALUE. `values[i]` is the expression of
 * the i-th VALUE, `offsets[k]` the template offset of display character k.
 */
export function displayText(html) {
  let out = '';
  const offsets = [];
  const values = [];
  const exprs = [];
  const attrs = [];
  const push = (s, at) => {
    for (const ch of s) {
      out += ch;
      offsets.push(at);
    }
  };
  let i = 0;
  const n = html.length;
  while (i < n) {
    const c = html[i];
    if (html.startsWith('<!--', i)) {
      const end = html.indexOf('-->', i + 4);
      push(BREAK, i);
      i = end < 0 ? n : end + 3;
    } else if (c === '<' && /[A-Za-z/!]/.test(html[i + 1] ?? '')) {
      const nameMatch = /^<([A-Za-z][\w-]*)/.exec(html.slice(i, i + 40));
      let j = i + 1;
      let quote = null;
      while (j < n) {
        const d = html[j];
        if (quote) {
          if (d === quote) quote = null;
        } else if (d === '"' || d === "'") quote = d;
        else if (d === '>') break;
        j++;
      }
      push(BREAK, i);
      for (const m of html.slice(i, j).matchAll(ATTRIBUTE)) {
        const value = m[3] ?? m[4];
        const at = i + m.index + m[0].length - value.length - 1;
        if (/^\[|^bind-/.test(m[1])) exprs.push({ expr: value, offset: at });
        else if (TEXT_ATTRIBUTES.has(m[1].toLowerCase())) attrs.push({ text: value, offset: at });
      }
      i = j + 1;
      const name = nameMatch?.[1]?.toLowerCase();
      if (name === 'style' || name === 'script') {
        const close = html.toLowerCase().indexOf(`</${name}`, i);
        i = close < 0 ? n : close;
      }
    } else if (html.startsWith('{{', i)) {
      const end = html.indexOf('}}', i + 2);
      values.push(html.slice(i + 2, end < 0 ? n : end));
      exprs.push({ expr: values.at(-1), offset: i + 2 });
      push(VALUE, i);
      i = end < 0 ? n : end + 2;
    } else if (c === '@' && /[a-z]/.test(html[i + 1] ?? '')) {
      // @if (…) {  @else if (…) {  @for (…) {  @switch (…) {  @case (…) {
      // @let x = …;  @else {  @default {  @empty {  @defer (…) {
      const word = /^@([a-z]+)(\s+if)?/.exec(html.slice(i, i + 20));
      let j = i + word[0].length;
      if (word[1] === 'let') {
        const semi = html.indexOf(';', j);
        j = semi < 0 ? n : semi + 1;
      } else {
        while (j < n && /\s/.test(html[j])) j++;
        if (html[j] === '(') {
          let depth = 0;
          let quote = null;
          for (; j < n; j++) {
            const d = html[j];
            if (quote) {
              if (d === quote) quote = null;
            } else if (d === '"' || d === "'" || d === '`') quote = d;
            else if (d === '(') depth++;
            else if (d === ')' && --depth === 0) {
              j++;
              break;
            }
          }
        }
      }
      push(BREAK, i);
      i = j;
    } else if (c === '{' || c === '}') {
      push(BREAK, i);
      i++;
    } else {
      push(c, i);
      i++;
    }
  }
  return { text: out, offsets, values, exprs, attrs };
}

// name, then a quoted value (an Angular binding may hold the other quote)
const ATTRIBUTE = /\s([^\s=>/]+)\s*=\s*("([^"]*)"|'([^']*)')/g;
// plain attributes whose value the reader sees or hears
const TEXT_ATTRIBUTES = new Set(['title', 'placeholder', 'aria-label', 'alt', 'label']);

const isTranslation = (expr) => /\|\s*translate\b/.test(expr);

/**
 * Every glued colon of a template.
 *
 * @param {string} html
 * @returns {{ offset: number, rule: 'label' | 'value' | 'expr', excerpt: string }[]}
 */
export function gluedColonSites(html) {
  const { exprs, attrs } = displayText(html);
  const sites = textSites(html);
  for (const { expr, offset } of exprs) {
    for (const lit of gluedTsLabels(expr)) {
      const at = offset + lit.offset;
      sites.push({ offset: at, rule: 'expr', excerpt: excerptAt(html, at) });
    }
  }
  for (const { text, offset } of attrs) {
    for (const site of textSites(text)) {
      const at = offset + site.offset;
      sites.push({ offset: at, rule: site.rule, excerpt: excerptAt(html, at) });
    }
  }
  return sites.sort((a, b) => a.offset - b.offset);
}

const excerptAt = (html, at) => html.slice(Math.max(0, at - 30), at + 10).replace(/\s+/g, ' ');

/** Glued colons in the display text proper (rules label and value). */
function textSites(html) {
  const { text, offsets, values } = displayText(html);
  const sites = [];
  let valueIndex = -1;
  const valueAt = new Map();
  for (let k = 0; k < text.length; k++) {
    if (text[k] === VALUE) valueAt.set(k, values[++valueIndex]);
  }
  for (let k = 0; k < text.length; k++) {
    if (text[k] !== ':') continue;
    const next = text[k + 1];
    const prev = text[k - 1];
    // the character before the colon, spaces skipped (for the value rule)
    let b = k - 1;
    while (b >= 0 && /\s/.test(text[b])) b--;
    const site = (rule) =>
      sites.push({
        offset: offsets[k],
        rule,
        excerpt: excerptAt(html, offsets[k]),
      });
    if (text[b] === VALUE) {
      const expr = valueAt.get(b);
      const between = b === k - 1 && next === VALUE;
      if (isTranslation(expr) || !between) site('value');
      continue;
    }
    if (prev === undefined || !LETTER.test(prev)) continue;
    const endsLabel = next === undefined || next === BREAK || next === VALUE || /\s/.test(next);
    if (endsLabel) site('label');
  }
  return sites;
}

/** How many glued colons one template text holds. */
export function gluedColons(html) {
  return gluedColonSites(html).length;
}

/**
 * QA D1138 (round 20): the same class for `?`, `!` and `;`. French writes a
 * (narrow no-break) space before them too ("Annuler la demande pour Zongo,
 * Fatimata ?"); English and Spanish do not. The imaging cancel prompt typed
 * the `?` in the markup after the patient's name (`</strong>?`), so French
 * read "Fatimata?". The mark belongs to the translation (a sentence with a
 * `{{name}}` placeholder), never to the markup. Rule `punct`: in the display
 * text, a `?` `!` `;` typed with no space after a letter, a closing bracket or
 * an interpolation — tag boundaries skipped (`</strong>?`). An HTML entity's
 * own `;` (`&nbsp;`) is not punctuation.
 *
 * @returns {{ offset: number, rule: 'punct', excerpt: string }[]}
 */
export function gluedPunctuationSites(html) {
  const { text, offsets } = displayText(html);
  const sites = [];
  for (let k = 0; k < text.length; k++) {
    const ch = text[k];
    if (ch !== '?' && ch !== '!' && ch !== ';') continue;
    if (
      ch === ';' &&
      /&(?:[a-z][a-z0-9]*|#x?[0-9a-f]+)$/i.test(text.slice(Math.max(0, k - 12), k))
    ) {
      continue;
    }
    // Only a mark that ends a word counts (`?.`, `!=` are not display text).
    const next = text[k + 1];
    if (next !== undefined && next !== BREAK && next !== VALUE && !/\s/.test(next)) continue;
    let b = k - 1;
    while (b >= 0 && text[b] === BREAK) b--;
    if (b < 0) continue;
    const prev = text[b];
    // A space typed before the mark is already the French gap.
    if (prev === VALUE || LETTER.test(prev)) {
      sites.push({ offset: offsets[k], rule: 'punct', excerpt: excerptAt(html, offsets[k]) });
    }
  }
  return sites;
}

/**
 * A French translation with a colon glued to the word (or placeholder) before
 * it ("Date de naissance:", "{{label}}: {{value}}"). `{{host}}:{{port}}`
 * (between two placeholders), `https://` and `10:30` are not labels.
 */
export function gluedFrenchValue(value) {
  const stripped = value.replace(/\}\}:\{\{/g, '}} {{');
  return /[\p{L})}\].'\u2019]:(?![/\p{L}\p{N}])/u.test(stripped);
}

/**
 * QA D1138: a French translation with `?`, `!` or `;` glued to the word (or
 * placeholder) before it ("Supprimer {{name}}?"). A URL's `?` and an entity's
 * `;` are not punctuation.
 */
export function gluedFrenchPunctuation(value) {
  const stripped = value
    .replace(/https?:\/\/\S+/g, '')
    .replace(/&(?:[a-z][a-z0-9]*|#x?[0-9a-f]+);/gi, '');
  return /[\p{L})}\].'\u2019\u00bb][?!;](?![\p{L}\p{N}=])/u.test(stripped);
}

/**
 * String literals in a `.ts` source that COMPOSE a glued label with a value
 * for display: `'MRN: ' + mrn`, `label + ': ' + value`, `` `Statut: ${s}` ``,
 * `` `${label}: ${value}` ``. A sentence that merely contains a colon (an
 * English fallback table, a console message) is the hard-coded-English class
 * other gates own; what this reads is the label-colon-value shape, which is
 * wrong in French whatever the label's language. `${h}:${m}` (a time or an
 * address), URLs, CSS, comments and console.* arguments are not labels.
 *
 * @returns {{ offset: number, literal: string }[]}
 */
export function gluedTsLabels(source) {
  const out = [];
  const n = source.length;
  let i = 0;
  while (i < n) {
    const c = source[i];
    if (c === '/' && source[i + 1] === '/') {
      const end = source.indexOf('\n', i);
      i = end < 0 ? n : end;
      continue;
    }
    if (c === '/' && source[i + 1] === '*') {
      const end = source.indexOf('*/', i + 2);
      i = end < 0 ? n : end + 2;
      continue;
    }
    if (c === "'" || c === '"' || c === '`') {
      let j = i + 1;
      let body = '';
      while (j < n && source[j] !== c) {
        if (source[j] === '\\') {
          body += source[j + 1] ?? '';
          j += 2;
          continue;
        }
        if (c === '`' && source[j] === '$' && source[j + 1] === '{') {
          let depth = 0;
          for (; j < n; j++) {
            if (source[j] === '{') depth++;
            else if (source[j] === '}' && --depth === 0) break;
          }
          body += VALUE;
          j++;
          continue;
        }
        if (c !== '`' && source[j] === '\n') break;
        body += source[j];
        j++;
      }
      const before = source.slice(Math.max(0, i - 40), i);
      let a = j + 1;
      while (a < n && /\s/.test(source[a])) a++;
      const concatenated = /\+\s*$/.test(before) || source[a] === '+';
      const logged = /console\.\w+\(\s*$/.test(before);
      if (!logged && composesLabel(body, concatenated)) out.push({ offset: i, literal: body });
      i = j + 1;
      continue;
    }
    i++;
  }
  return out;
}

const SCHEME = /^(?:https?|mailto|tel|sms|data|blob|urn|file):/i;

function composesLabel(body, concatenated) {
  if (/[\n<{};]/.test(body) || SCHEME.test(body) || /:\/\//.test(body)) return false;
  // A display colon is followed by a space (or ends the text); `a:b`,
  // `staff:${id}`, `${h}:${m}` and `phrase:${string}` are keys, times and types.
  for (let k = body.indexOf(':'); k >= 0; k = body.indexOf(':', k + 1)) {
    const prev = body[k - 1];
    const next = body[k + 1];
    const spaced = next === ' ' || next === undefined;
    // `${label}: ${value}`, `${label}:` — a colon after a substitution
    if (prev === VALUE && spaced) return true;
    // `Statut: ${s}` — a label colon before a substitution
    if (prev && LETTER.test(prev) && next === ' ' && body[k + 2] === VALUE) return true;
  }
  if (!concatenated) return false;
  // `label + ': ' + value`, `'MRN: ' + mrn`, `x + ' MRN:'` (not `a + ':' + b`, a key)
  return /^\s*:\s+$/.test(body) || /[\p{L})\].'’]:(?:\s+|$)$/u.test(body);
}
