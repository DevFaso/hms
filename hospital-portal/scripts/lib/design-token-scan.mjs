/**
 * QA r65 (D2790 / D2793 / D2804): the design-token gate's scanners.
 *
 * The portal had 8 primary-button colours, secondary text at 4.34:1 and the
 * pre-rebrand indigo, purple and blue in 100 stylesheets. Three classes, each
 * read from every stylesheet, template and component:
 *
 *  - `legacy-colour`      one of the pre-rebrand hexes (or the old blue / indigo
 *                         halo) anywhere: the brand is `var(--primary)`;
 *  - `low-contrast-text`  a text `color:` lighter than --text-muted (slate-400,
 *                         slate-500, gray-400, gray-500, #777…#aaa): below 4.5:1
 *                         on the page background. Use var(--text-secondary) or
 *                         var(--text-muted);
 *  - `local-button`       a stylesheet restyling .btn-primary / .btn-secondary /
 *                         .btn-danger (colour, border, radius, size, type). They
 *                         are defined once, in shared/styles/_page-common.scss.
 *                         A page may place one (margin, width, flex) and may own
 *                         a variant (`.btn-primary.small`, `[aria-pressed]`);
 *  - `btn-base-class`     a template giving a shared button a page's own
 *                         button base as well (`btn`, `btn-icon`; a static
 *                         class or a `[class.x]` binding): the page's scoped
 *                         rule for that base would outrank the shared look.
 *
 * SCSS is read with a small brace walker (no parser dependency): comments are
 * blanked, blocks are nested by braces, `&` and descendant nesting are
 * resolved, at-rules are transparent.
 */
import { tags } from './html-tags.mjs';

export const LEGACY_HEX = ['#6366f1', '#4f46e5', '#7c3aed', '#1976d2', '#0284c7', '#0891b2'];
const LEGACY = new RegExp(
  `(${LEGACY_HEX.join('|')})\\b|rgba?\\(\\s*(?:37,\\s*99,\\s*235|99,\\s*102,\\s*241)\\b`,
  'gi',
);
/** Greys that are below 4.5:1 on the #f1f5f9 page background. */
export const LOW_CONTRAST_HEX = [
  '#64748b',
  '#6b7280',
  '#94a3b8',
  '#9ca3af',
  '#777',
  '#777777',
  '#888',
  '#888888',
  '#999',
  '#999999',
  '#aaa',
  '#aaaaaa',
  // status colours that are fills, not text: 3.8:1 or less on white
  // (#dc2626 is 4.41:1 on the page background)
  '#059669',
  '#10b981',
  '#16a34a',
  '#22c55e',
  '#d97706',
  '#f59e0b',
  '#ea580c',
  '#f97316',
  '#ef4444',
  '#dc2626',
  '#0ea5e9',
  '#3b82f6',
];
const LOW = new RegExp(`(${LOW_CONTRAST_HEX.join('|')})\\b`, 'gi');

export const SHARED_BUTTONS = ['btn-primary', 'btn-secondary', 'btn-danger'];
const NEUTRAL = new Set(['btn', ...SHARED_BUTTONS]);

/** What a page may not restate on a shared button. Placement stays its own. */
export const BUTTON_IDENTITY =
  /^(display|align-items|gap|column-gap|padding(-[\w-]+)?|background(-color|-image)?|color|border(-(top|right|bottom|left))?(-(color|width|style))?|border-radius|font(-size|-weight|-family)?|cursor|transition|text-decoration|line-height|min-height|height|box-shadow|opacity|transform|outline(-offset)?|letter-spacing|text-transform|filter)$/;

const BACKSLASH = String.fromCharCode(92);

/** Same length as `src`: comments become spaces, braces and semicolons inside strings become `_`. */
export function blankScss(src) {
  let out = '';
  let i = 0;
  while (i < src.length) {
    const c = src[i];
    const n = src[i + 1];
    if (c === '/' && n === '*') {
      const e = src.indexOf('*/', i + 2);
      const end = e < 0 ? src.length : e + 2;
      out += src.slice(i, end).replace(/[^\n]/g, ' ');
      i = end;
    } else if (c === '/' && n === '/' && src[i - 1] !== ':') {
      const e = src.indexOf('\n', i);
      const end = e < 0 ? src.length : e;
      out += ' '.repeat(end - i);
      i = end;
    } else if (c === '"' || c === "'") {
      let j = i + 1;
      while (j < src.length && src[j] !== c && src[j] !== '\n') j += src[j] === BACKSLASH ? 2 : 1;
      if (j > src.length) j = src.length;
      const closed = src[j] === c;
      out += c + src.slice(i + 1, j).replace(/[{};]/g, '_') + (closed ? c : '');
      i = j + (closed ? 1 : 0);
    } else {
      out += c;
      i++;
    }
  }
  return out;
}

/** Every `{ … }` block of a blanked stylesheet, with its selector, depth and parent. */
export function scssBlocks(blanked) {
  const res = [];
  const stack = [];
  let last = 0;
  let interpolation = 0;
  for (let i = 0; i < blanked.length; i++) {
    const c = blanked[i];
    if (c === '#' && blanked[i + 1] === '{') {
      interpolation++;
      i++;
      continue;
    }
    if (interpolation && c === '}') {
      interpolation--;
      continue;
    }
    if (c === ';') last = i + 1;
    else if (c === '{') {
      const raw = blanked.slice(last, i);
      const block = {
        selector: raw.trim().replace(/\s+/g, ' '),
        start: last + (raw.length - raw.trimStart().length),
        open: i,
        close: -1,
        parent: stack[stack.length - 1] ?? null,
      };
      res.push(block);
      stack.push(block);
      last = i + 1;
    } else if (c === '}') {
      const block = stack.pop();
      if (block) block.close = i;
      last = i + 1;
    }
  }
  return res;
}

function splitTop(list) {
  const out = [];
  let depth = 0;
  let cur = '';
  for (const ch of list) {
    if (ch === '(' || ch === '[') depth++;
    else if (ch === ')' || ch === ']') depth--;
    if (ch === ',' && depth === 0) {
      out.push(cur.trim());
      cur = '';
    } else cur += ch;
  }
  if (cur.trim()) out.push(cur.trim());
  return out;
}

/** The full selectors a block resolves to; null inside @keyframes and the like. */
export function resolveSelectors(block) {
  if (!block) return [''];
  const sel = block.selector;
  if (/^@(keyframes|font-face|property|function)/.test(sel)) return null;
  const parents = resolveSelectors(block.parent);
  if (parents === null) return null;
  if (sel.startsWith('@')) return parents;
  const out = [];
  for (const parent of parents) {
    for (const own of splitTop(sel)) {
      if (own.includes('&')) out.push(own.replaceAll('&', parent));
      else out.push(parent ? `${parent} ${own}` : own);
    }
  }
  return out;
}

function lastCompound(sel) {
  let depth = 0;
  let start = 0;
  for (let i = 0; i < sel.length; i++) {
    const ch = sel[i];
    if (ch === '(' || ch === '[') depth++;
    else if (ch === ')' || ch === ']') depth--;
    else if (depth === 0 && (ch === ' ' || ch === '>' || ch === '+' || ch === '~')) start = i + 1;
  }
  return sel.slice(start);
}

/**
 * `exact`: the selector's subject is a shared button itself (with at most a
 * state pseudo-class or `[disabled]`). `modified`: a variant the page owns
 * (another class, an attribute state). `other`: not a shared button.
 */
export function classifyButtonSelector(sel) {
  const compound = lastCompound(sel);
  if (compound.includes('::')) return 'other';
  let depth = 0;
  let flat = '';
  for (const ch of compound) {
    if (ch === '(') depth++;
    else if (ch === ')') depth--;
    else if (depth === 0) flat += ch;
  }
  const classes = [...flat.matchAll(/\.([\w-]+)/g)].map((m) => m[1]);
  if (!classes.some((c) => SHARED_BUTTONS.includes(c))) return 'other';
  const attrs = [...flat.matchAll(/\[([a-zA-Z-]+)/g)].map((m) => m[1]);
  if (attrs.some((a) => a !== 'disabled' && a !== 'aria-disabled')) return 'modified';
  return classes.every((c) => NEUTRAL.has(c)) ? 'exact' : 'modified';
}

function lineOf(text, index) {
  let line = 1;
  for (let i = 0; i < index; i++) if (text.charCodeAt(i) === 10) line++;
  return line;
}

/** A block's own declarations (nested blocks left out) as `[property, offset]`. */
function ownDeclarations(blanked, block) {
  const out = [];
  let depth = 0;
  let start = block.open + 1;
  for (let i = block.open + 1; i < block.close; i++) {
    const ch = blanked[i];
    if (ch === '{') depth++;
    else if (ch === '}') {
      depth--;
      start = i + 1;
    } else if (ch === ';') {
      if (depth === 0) {
        const stmt = blanked.slice(start, i);
        const m = /^\s*([\w-]+)\s*:/.exec(stmt);
        if (m) out.push([m[1], start + stmt.length - stmt.trimStart().length]);
      }
      start = i + 1;
    }
  }
  return out;
}

/** Is the hex at `index` the value of a text `color:` declaration? */
function isTextColour(blanked, index) {
  // The declaration the hex belongs to starts after the last `;`, `{` or `}`
  // before it (a value may sit on the line after its property).
  const stmtStart =
    Math.max(
      blanked.lastIndexOf(';', index),
      blanked.lastIndexOf('{', index),
      blanked.lastIndexOf('}', index),
    ) + 1;
  return /^\s*color\s*:/.test(blanked.slice(stmtStart, index));
}

/**
 * @param {string} scss a stylesheet (or the CSS of an inline `styles:` block)
 * @param {{ buttons?: boolean }} [options] `buttons: false` for the one sheet
 *   that DEFINES the shared buttons
 * @returns {{ line: number, rule: string, anchor: string, message: string }[]}
 */
export function scssFindings(scss, options = {}) {
  const text = scss.replaceAll('\r\n', '\n');
  const blanked = blankScss(text);
  const hits = [];
  let m;
  LEGACY.lastIndex = 0;
  while ((m = LEGACY.exec(blanked))) {
    const anchor = m[1] ? m[1].toLowerCase() : 'legacy-halo';
    hits.push({
      line: lineOf(text, m.index),
      rule: 'legacy-colour',
      anchor,
      message: `${m[0]} is a pre-rebrand colour; use var(--primary) (or its -dark / -tint / -soft)`,
    });
  }
  LOW.lastIndex = 0;
  while ((m = LOW.exec(blanked))) {
    if (!isTextColour(blanked, m.index)) continue;
    hits.push({
      line: lineOf(text, m.index),
      rule: 'low-contrast-text',
      anchor: m[1].toLowerCase(),
      message: `text colour ${m[1]} is below 4.5:1 on the page background; use var(--text-secondary) / var(--text-muted), or a status text token (--success-text, --warning-text, --danger, --info-text)`,
    });
  }
  if (options.buttons !== false) {
    for (const block of scssBlocks(blanked)) {
      if (block.close < 0 || block.selector.startsWith('@')) continue;
      const resolved = resolveSelectors(block);
      if (!resolved) continue;
      const exact = resolved.filter((sel) => classifyButtonSelector(sel) === 'exact');
      if (!exact.length) continue;
      for (const [property, offset] of ownDeclarations(blanked, block)) {
        if (!BUTTON_IDENTITY.test(property)) continue;
        hits.push({
          line: lineOf(text, offset),
          rule: 'local-button',
          anchor: exact[0],
          message: `\`${exact[0]}\` restates \`${property}\`: the shared buttons are defined once, in shared/styles/_page-common.scss`,
        });
      }
    }
  }
  return hits;
}

const STYLE_ATTR = /\bstyle="([^"]*)"/g;
/**
 * Classes of a page's OWN button family. On the same element as a shared
 * button class, the page's scoped rule for it outranks the shared look: a
 * local `.btn { background: #fff }` under `.btn-danger` printed white on
 * white, and `.btn-icon.btn-danger` made an icon button 40 px tall.
 */
const OWN_BUTTON_BASES = ['btn', 'btn-icon'];

/**
 * A template: inline `style=""` colours, and a shared button class on an
 * element that also carries a page's own button base. Read per tag, static
 * `class=""` and `[class.x]` bindings together.
 */
export function templateFindings(html) {
  const hits = [];
  let m;
  for (const tag of tags(html)) {
    if (tag.type !== 'open') continue;
    const classes = new Set((tag.attrs.get('class') ?? '').split(/\s+/).filter(Boolean));
    for (const name of tag.attrs.keys()) {
      const bound = /^\[class\.([\w-]+)\]$/.exec(name);
      if (bound) classes.add(bound[1]);
    }
    const shared = SHARED_BUTTONS.find((c) => classes.has(c));
    const base = OWN_BUTTON_BASES.find((c) => classes.has(c));
    if (shared && base) {
      hits.push({
        line: tag.line,
        rule: 'btn-base-class',
        anchor: shared,
        message:
          base === 'btn'
            ? `\`${base}\` with \`${shared}\`: drop the bare \`btn\`; \`${shared}\` alone carries the shared look`
            : `\`${base}\` with \`${shared}\`: an icon button is not a shared button; mark it \`${base} danger\` and style that`,
      });
    }
  }
  STYLE_ATTR.lastIndex = 0;
  while ((m = STYLE_ATTR.exec(html))) {
    for (const hit of scssFindings(`x{${m[1]}}`, { buttons: false })) {
      hits.push({ ...hit, line: lineOf(html, m.index) });
    }
  }
  return hits;
}

/** Where a component's inline `styles:` begin: up to the first template literal's backtick. */
const INLINE_STYLES_START = /\bstyles\s*:\s*\[?\s*`/g;

/**
 * The CSS of a component's inline `styles:` as `[css, offset]` pairs: one
 * template literal, or the several of a `styles: [` array. Read by hand: a
 * literal ends at the next backtick, and the next one may follow a comma.
 */
function inlineStyles(ts) {
  const out = [];
  let m;
  INLINE_STYLES_START.lastIndex = 0;
  while ((m = INLINE_STYLES_START.exec(ts))) {
    let open = m.index + m[0].length - 1;
    while (open >= 0 && ts[open] === '`') {
      const close = ts.indexOf('`', open + 1);
      if (close < 0) break;
      out.push([ts.slice(open + 1, close), open + 1]);
      let next = close + 1;
      while (next < ts.length && (ts[next] === ',' || ts[next].trim() === '')) next++;
      open = ts[next] === '`' ? next : -1;
      INLINE_STYLES_START.lastIndex = close + 1;
    }
  }
  return out;
}

/**
 * A component `.ts`: the legacy hexes anywhere in it (inline styles, chart
 * palettes), and its inline `styles:` read as a stylesheet (text colours, the
 * shared buttons).
 */
export function sourceFindings(ts) {
  const hits = [];
  let m;
  LEGACY.lastIndex = 0;
  while ((m = LEGACY.exec(ts))) {
    hits.push({
      line: lineOf(ts, m.index),
      rule: 'legacy-colour',
      anchor: m[1] ? m[1].toLowerCase() : 'legacy-halo',
      message: `${m[0]} is a pre-rebrand colour; use var(--primary), or pin a chart series with its reason`,
    });
  }
  for (const [css, start] of inlineStyles(ts)) {
    const offset = lineOf(ts, start) - 1;
    for (const hit of scssFindings(css)) {
      // a legacy hex is already reported above, from the whole file
      if (hit.rule !== 'legacy-colour') hits.push({ ...hit, line: hit.line + offset });
    }
  }
  return hits;
}

/* ── Token contrast ─────────────────────────────────────────────────────── */

export function luminance(hex) {
  let h = hex.replace('#', '');
  if (h.length === 3) h = [...h].map((c) => c + c).join('');
  const [r, g, b] = [0, 2, 4]
    .map((i) => Number.parseInt(h.slice(i, i + 2), 16) / 255)
    .map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

export function contrast(a, b) {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

/** The `--name: #hex;` custom properties of the first `:root { … }` block. */
export function rootTokens(scss) {
  const text = blankScss(scss.replaceAll('\r\n', '\n'));
  const root = scssBlocks(text).find((b) => b.selector === ':root');
  const tokens = {};
  if (!root) return tokens;
  for (const m of text.slice(root.open + 1, root.close).matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)) {
    tokens[m[1]] = m[2].trim();
  }
  return tokens;
}
