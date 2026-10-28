/**
 * A small tag tokenizer for Angular templates, shared by the accessibility
 * gates (dialog semantics, icon-only buttons, unlabelled fields).
 *
 * Prettier writes a tag with more than a few attributes one attribute per
 * line, so a line-oriented grep sees `role="dialog"` and never the
 * `aria-modal="true"` three lines below it: the first measurement of the
 * dialog gap counted 114 "dialogs without aria-modal" where a tag-level read
 * finds a fraction of that. Everything here works on whole tags.
 *
 * It is not an HTML parser. It knows comments, opening and closing tags,
 * self-closing tags, quoted attribute values that may contain `>` (an Angular
 * `(click)="a > b"` or `[class.x]="n > 0"`), and the void elements that never
 * close. That is all the gates need.
 */

export const VOID_ELEMENTS = new Set([
  'area',
  'base',
  'br',
  'col',
  'embed',
  'hr',
  'img',
  'input',
  'link',
  'meta',
  'param',
  'source',
  'track',
  'wbr',
]);

const NAME_START = /[A-Za-z]/;
const ATTR_END = /[\s=>]/;

/**
 * Every tag of `html` in document order.
 *
 * @param {string} html
 * @returns {{
 *   type: 'open' | 'close',
 *   name: string,
 *   attrs: Map<string, string | null>,
 *   start: number,
 *   end: number,
 *   selfClosing: boolean,
 *   line: number,
 * }[]} `start`/`end` are offsets of the tag text (`end` exclusive);
 *   `attrs` keeps the attribute name exactly as written (`[attr.aria-label]`,
 *   `(keydown.escape)`, `*ngIf`), value `null` when the attribute has none;
 *   `line` is 1-based.
 */
export function tags(html) {
  const out = [];
  let i = 0;
  while (i < html.length) {
    const lt = html.indexOf('<', i);
    if (lt < 0) break;
    if (html.startsWith('<!--', lt)) {
      const close = html.indexOf('-->', lt + 4);
      i = close < 0 ? html.length : close + 3;
      continue;
    }
    let j = lt + 1;
    const isClose = html[j] === '/';
    if (isClose) j++;
    if (!NAME_START.test(html[j] ?? '')) {
      i = lt + 1;
      continue;
    }
    const nameStart = j;
    while (j < html.length && /[\w:.-]/.test(html[j])) j++;
    const name = html.slice(nameStart, j).toLowerCase();
    const attrs = new Map();
    let selfClosing = false;
    let closed = false;
    while (j < html.length) {
      while (j < html.length && /\s/.test(html[j])) j++;
      if (html[j] === '>') {
        j++;
        closed = true;
        break;
      }
      if (html[j] === '/' && html[j + 1] === '>') {
        selfClosing = true;
        j += 2;
        closed = true;
        break;
      }
      if (html[j] === '/') {
        j++;
        continue;
      }
      const attrStart = j;
      while (
        j < html.length &&
        !ATTR_END.test(html[j]) &&
        !(html[j] === '/' && html[j + 1] === '>')
      ) {
        j++;
      }
      const attrName = html.slice(attrStart, j);
      if (attrName.length === 0) {
        j++;
        continue;
      }
      let value = null;
      let k = j;
      while (k < html.length && /\s/.test(html[k])) k++;
      if (html[k] === '=') {
        k++;
        while (k < html.length && /\s/.test(html[k])) k++;
        const quote = html[k];
        if (quote === '"' || quote === "'") {
          const endQuote = html.indexOf(quote, k + 1);
          value = endQuote < 0 ? html.slice(k + 1) : html.slice(k + 1, endQuote);
          j = endQuote < 0 ? html.length : endQuote + 1;
        } else {
          const valueStart = k;
          while (k < html.length && !/[\s>]/.test(html[k])) k++;
          value = html.slice(valueStart, k);
          j = k;
        }
      }
      attrs.set(attrName, value);
    }
    if (!closed) break;
    out.push({
      type: isClose ? 'close' : 'open',
      name,
      attrs,
      start: lt,
      end: j,
      selfClosing: selfClosing || (!isClose && VOID_ELEMENTS.has(name)),
      line: lineOf(html, lt),
    });
    i = j;
  }
  return out;
}

/** 1-based line of offset `index` in `text`. */
export function lineOf(text, index) {
  let line = 1;
  for (let i = 0; i < index && i < text.length; i++) {
    if (text[i] === '\n') line++;
  }
  return line;
}

/**
 * The inner markup of the element opened by `tokens[openIndex]`: the text
 * between its `>` and its matching close tag (same name, same depth), or
 * `null` for a void or self-closing tag or when no close tag follows.
 */
export function innerHtml(html, tokens, openIndex) {
  const open = tokens[openIndex];
  if (open.type !== 'open' || open.selfClosing) return null;
  let depth = 0;
  for (let k = openIndex + 1; k < tokens.length; k++) {
    const t = tokens[k];
    if (t.name !== open.name) continue;
    if (t.type === 'open' && !t.selfClosing) depth++;
    else if (t.type === 'close') {
      if (depth === 0) return html.slice(open.end, t.start);
      depth--;
    }
  }
  return null;
}

/**
 * Does the tag carry `name`, written plainly or as an Angular binding
 * (`[attr.name]`, `[name]`, `attr.name`)?
 */
export function hasAttr(tag, name) {
  return (
    tag.attrs.has(name) ||
    tag.attrs.has(`[attr.${name}]`) ||
    tag.attrs.has(`[${name}]`) ||
    tag.attrs.has(`attr.${name}`)
  );
}

/** The plain or bound value of `name` on the tag, or `undefined`. */
export function attrValue(tag, name) {
  for (const key of [name, `[attr.${name}]`, `[${name}]`, `attr.${name}`]) {
    if (tag.attrs.has(key)) return tag.attrs.get(key) ?? '';
  }
  return undefined;
}

/** The class tokens of the tag: the static `class` plus `[class.x]` keys. */
export function classTokens(tag) {
  const out = new Set();
  for (const token of (tag.attrs.get('class') ?? '').split(/\s+/)) {
    if (token) out.add(token);
  }
  for (const key of tag.attrs.keys()) {
    const m = /^\[class\.([^\]]+)\]$/.exec(key);
    if (m) out.add(m[1]);
  }
  return out;
}
