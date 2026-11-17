/**
 * QA D1122 (round 20, fix-induced by D1100): a dialog rendered UNDER its
 * backdrop. The "Libérer le lit" confirmation was written as a SIBLING of its
 * `.modal-backdrop`; the admissions stylesheet makes the backdrop a fixed,
 * z-index 1000 flex box that centres the dialog INSIDE it, and gives `.modal`
 * no position of its own. So the dialog sat in the page flow below the table,
 * under the fixed backdrop, and every mouse click landed on the backdrop.
 * Keyboard users could still release a bed, so no keyboard spec saw it.
 *
 * Rule: wherever a cover (lib/dialog-scan.mjs `isCover`: a `*-backdrop` /
 * `*-scrim`, or a dismissable `*-overlay`) is followed by its dialog as a
 * SIBLING, the dialog must be lifted above the cover by the styles that apply
 * to it (global first, then the component's own): `position: fixed` or
 * `absolute` and a `z-index` greater than the cover's. A dialog nested INSIDE
 * its cover (the house shape, the #442 lesson) is above it by construction
 * and is not read.
 */
import { tags, innerHtml, attrValue, classTokens } from './html-tags.mjs';
import { balanced } from './js-scan.mjs';
import { isCover, closeIndexOf, DIALOG_MARKUP, LAYOUT_MARKUP } from './dialog-scan.mjs';

function isDialogPanel(html, tokens, index) {
  const tag = tokens[index];
  const role = (attrValue(tag, 'role') ?? '').replace(/['"]/g, '').trim();
  if (tag.attrs.has('appModalDialog') || tag.attrs.has('appDialogA11y')) return true;
  if (role === 'dialog' || role === 'alertdialog' || tag.name === 'dialog') return true;
  const inner = innerHtml(html, tokens, index) ?? '';
  return !LAYOUT_MARKUP.test(inner) && DIALOG_MARKUP.test(inner);
}

/**
 * Every cover whose dialog is its next SIBLING (not its child).
 *
 * @returns {{ cover: object, panel: object }[]}
 */
export function siblingCoverPanels(html) {
  const tokens = tags(html);
  const out = [];
  tokens.forEach((tag, index) => {
    if (tag.type !== 'open' || !isCover(tag)) return;
    if (DIALOG_MARKUP.test(innerHtml(html, tokens, index) ?? '')) return;
    const nextIndex = closeIndexOf(tokens, index) + 1;
    const next = tokens[nextIndex];
    if (next?.type !== 'open' || !isDialogPanel(html, tokens, nextIndex)) return;
    out.push({ cover: tag, panel: next });
  });
  return out;
}

/**
 * The top-level declarations of every rule in `css` with a selector whose
 * subject (last compound) is `.token` — `.token`, `.a .token`, `.token.x`,
 * `.token:hover`, either side of a selector list — nested blocks removed.
 */
export function declarationsFor(css, token) {
  const escaped = token.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const rule = new RegExp(
    String.raw`(?:^|[\s,}>~+])\.${escaped}(?![\w-])[^\s{};,]*\s*(?:,[^{};]*)?\{`,
    'g',
  );
  const out = [];
  for (const m of css.matchAll(rule)) {
    const body = balanced(css, m.index + m[0].length - 1, '{', '}');
    let flat = '';
    let depth = 0;
    for (const ch of body.slice(1, -1)) {
      if (ch === '{') depth++;
      else if (ch === '}') depth--;
      else if (depth === 0) flat += ch;
    }
    out.push(flat);
  }
  return out;
}

function property(declarations, name) {
  let value;
  const pattern = new RegExp(String.raw`(?:^|[;\s])${name}\s*:\s*([^;]+)`, 'g');
  for (const block of declarations) {
    for (const m of block.matchAll(pattern)) value = m[1].trim();
  }
  return value;
}

function stacking(css, tag) {
  const decls = [...classTokens(tag)].flatMap((t) => declarationsFor(css, t));
  const z = Number.parseInt(property(decls, 'z-index') ?? '', 10);
  return { position: property(decls, 'position'), z: Number.isNaN(z) ? null : z };
}

/**
 * @param {string} html the template
 * @param {string} css every style that applies to it, global first, the component's own last
 * @returns {{ line: number, anchor: string, message: string }[]}
 */
export function stackingFindings(html, css) {
  const out = [];
  for (const { cover, panel } of siblingCoverPanels(html)) {
    const below = stacking(css, cover);
    const above = stacking(css, panel);
    const lifted = above.position === 'fixed' || above.position === 'absolute';
    const higher = above.z !== null && (below.z === null || above.z > below.z);
    if (lifted && higher) continue;
    out.push({
      line: panel.line,
      anchor: `${cover.attrs.get('class') ?? cover.name} + ${panel.attrs.get('class') ?? panel.name}`,
      message:
        `dialog rendered as a SIBLING of its cover without being lifted above it ` +
        `(panel position: ${above.position ?? 'static'}, z-index ${above.z ?? 'auto'}; cover z-index ${below.z ?? 'auto'}): ` +
        'nest the dialog INSIDE the backdrop, or give it position: fixed and a higher z-index',
    });
  }
  return out;
}
