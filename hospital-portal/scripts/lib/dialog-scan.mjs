/**
 * Dialog semantics (QA D976, D988, D1004, D968; earlier D173).
 *
 * Round after round a new modal ships as a `<div class="modal">` that a screen
 * reader reads as part of the page behind it, that Escape does not close, and
 * that has no name. Four rules, all on whole tags (lib/html-tags.mjs):
 *
 *  role    — an element whose class names it a dialog container (`modal`,
 *            `dialog`, `x-modal`, `x-dialog-panel`, ...) carries no
 *            role="dialog" / "alertdialog", and neither an ancestor nor a
 *            descendant does. Backdrops, headers, footers and buttons are
 *            not containers and are not matched.
 *  modal   — role="dialog" without aria-modal="true".
 *  label   — role="dialog" without aria-labelledby or aria-label.
 *  escape  — role="dialog" in a component with no Escape handler anywhere
 *            (template `(keydown.escape)`, a `document:keydown.escape`
 *            HostListener, an `'Escape'` key check, or the CDK dialog, which
 *            closes on Escape itself).
 *
 * The anchor of a finding is the element's class (or role) plus the text of
 * the first heading inside it, so a pin survives the lines around it moving
 * and stops matching when the dialog is fixed or removed.
 */
import { tags, innerHtml, hasAttr, attrValue, classTokens } from './html-tags.mjs';

const CONTAINER_TOKEN =
  /^(?:[a-z0-9]+-)*(?:modal|dialog)(?:-(?:container|panel|card|box|window|wrapper|sheet|surface))?$/;

const DIALOG_ROLES = new Set(['dialog', 'alertdialog']);

export const ESCAPE_HANDLER =
  /keydown\.escape|keydown\.esc\b|['"]Escape['"]|cdkTrapFocus|MatDialog|@angular\/cdk\/dialog|\bDialogRef\b/;

function headingOf(inner) {
  if (inner == null) return '';
  const m = /<h[1-6]\b[^>]*>([\s\S]*?)<\/h[1-6]>/i.exec(inner);
  if (!m) return '';
  return m[1]
    .replace(/<[^>]*>/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 60);
}

function anchor(tag, inner) {
  const base = tag.attrs.get('class') ?? attrValue(tag, 'role') ?? tag.name;
  const heading = headingOf(inner);
  return heading ? `${base} <${heading}>` : base;
}

/**
 * @param {string} html the template
 * @param {string} componentSource the template plus its component's `.ts`
 *   (or the `.ts` alone for an inline template) — where an Escape handler
 *   may live
 * @param {Set<string>} dialogSelectors selectors of child components whose own
 *   template is a dialog; a container that wraps one of them delegates the
 *   semantics and is not reported (lib/component-sources.mjs)
 * @returns {{ line: number, rule: string, anchor: string, message: string }[]}
 */
export function dialogFindings(html, componentSource = html, dialogSelectors = new Set()) {
  const tokens = tags(html);
  const out = [];
  const stack = [];
  const hasEscape = ESCAPE_HANDLER.test(componentSource);
  const childDialog =
    dialogSelectors.size > 0
      ? new RegExp(
          String.raw`<(?:${[...dialogSelectors].map((s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|')})\b`,
        )
      : null;
  tokens.forEach((tag, index) => {
    if (tag.type === 'close') {
      for (let k = stack.length - 1; k >= 0; k--) {
        if (stack[k].name === tag.name) {
          stack.length = k;
          break;
        }
      }
      return;
    }
    const role = attrValue(tag, 'role');
    const isDialog = role !== undefined && DIALOG_ROLES.has(role.replace(/['"]/g, '').trim());
    const inner = innerHtml(html, tokens, index);
    if (isDialog) {
      const a = anchor(tag, inner);
      if (!hasAttr(tag, 'aria-modal')) {
        out.push({
          line: tag.line,
          rule: 'modal',
          anchor: a,
          message: `role="${role}" without aria-modal="true": the page behind stays in the reading order`,
        });
      }
      if (!hasAttr(tag, 'aria-labelledby') && !hasAttr(tag, 'aria-label')) {
        out.push({
          line: tag.line,
          rule: 'label',
          anchor: a,
          message: `role="${role}" with no name: add aria-labelledby pointing at its title`,
        });
      }
      if (!hasEscape) {
        out.push({
          line: tag.line,
          rule: 'escape',
          anchor: a,
          message:
            'dialog in a component with no Escape handler ((keydown.escape) or a document:keydown.escape HostListener)',
        });
      }
    } else if ([...classTokens(tag)].some((t) => CONTAINER_TOKEN.test(t))) {
      const insideDialog = stack.some((s) => s.isDialog);
      const wrapsDialog =
        inner != null &&
        (/\brole\s*=\s*["'](?:dialog|alertdialog)["']/.test(inner) ||
          (childDialog !== null && childDialog.test(inner)));
      if (!insideDialog && !wrapsDialog) {
        out.push({
          line: tag.line,
          rule: 'role',
          anchor: anchor(tag, inner),
          message: `class="${tag.attrs.get('class') ?? ''}" looks like a dialog container but has no role="dialog"`,
        });
      }
    }
    if (!tag.selfClosing) stack.push({ name: tag.name, isDialog });
  });
  return out;
}
