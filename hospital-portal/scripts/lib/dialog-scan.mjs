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
 *  modal   — role="dialog" with no aria-modal, or aria-modal="false".
 *  label   — role="dialog" without a non-empty aria-labelledby or aria-label.
 *  escape  — role="dialog" with no `(keydown.escape)` on the dialog, an
 *            ancestor or a descendant, in a component with no
 *            `document:keydown.escape` HostListener and no CDK/Material
 *            dialog (which closes on Escape itself).
 *
 * A native `<dialog>` has the role, the modality and Escape built in: only
 * its name is checked.
 *
 * The anchor of a finding is the element's class (or role) plus the text of
 * the first heading inside it, so a pin survives the lines around it moving
 * and stops matching when the dialog is fixed or removed.
 */
import { tags, innerHtml, attrValue, classTokens } from './html-tags.mjs';

const CONTAINER_TOKEN =
  /^(?:[a-z0-9]+-)*(?:modal|dialog)(?:-(?:container|panel|card|box|window|wrapper|sheet|surface))?$/;

const DIALOG_ROLES = new Set(['dialog', 'alertdialog']);

/**
 * An Escape handler that belongs to the whole component: a document/window
 * HostListener, or the CDK/Material dialog service, which closes on Escape
 * itself. A `(keydown.escape)` must sit on the dialog element, an ancestor or
 * a descendant (checked per dialog); a focus trap or an `'Escape'` string
 * elsewhere in the file (a typeahead's key handler) does not close a dialog.
 */
export const COMPONENT_ESCAPE =
  /HostListener\(\s*['"](?:document:|window:)?keydown\.esc(?:ape)?['"]|MatDialog|@angular\/cdk\/dialog|\bDialogRef\b/;
const ESCAPE_ATTR = /^\(keydown\.esc(?:ape)?\)$/;
const KEYDOWN_ATTR = /^\(keydown\)$/;
const ESCAPE_KEY = /['"]Escape['"]/;
const ESCAPE_IN_MARKUP = /\(keydown\.esc(?:ape)?\)=/;

function hasEscapeAttr(tag) {
  return [...tag.attrs.keys()].some((k) => ESCAPE_ATTR.test(k));
}

/** A `(keydown)="onKey($event)"` whose component reads `event.key === 'Escape'`. */
function hasKeydownEscape(tag, componentSource) {
  return (
    [...tag.attrs.keys()].some((k) => KEYDOWN_ATTR.test(k)) && ESCAPE_KEY.test(componentSource)
  );
}

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
  const componentEscape = COMPONENT_ESCAPE.test(componentSource);
  const childDialog =
    dialogSelectors.size > 0
      ? new RegExp(
          // `(?=[\s/>])`, not `\b`: `<app-order-set-picker-row>` is not `<app-order-set-picker>`.
          String.raw`<(?:${[...dialogSelectors].map((s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|')})(?=[\s/>])`,
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
    // A native <dialog> has the implicit role, is modal when opened with
    // showModal() and closes on Escape by itself: only its NAME is checked.
    const nativeDialog = tag.name === 'dialog';
    const isDialog =
      nativeDialog || (role !== undefined && DIALOG_ROLES.has(role.replace(/['"]/g, '').trim()));
    // innerHtml walks forward to the matching close tag; only a dialog or a
    // container needs it, not every <td> of a worklist.
    if (isDialog) {
      const inner = innerHtml(html, tokens, index);
      const a = anchor(tag, inner);
      // `aria-modal="false"` is not modal and `aria-label=""` names nothing: the VALUE counts.
      const ariaModal = attrValue(tag, 'aria-modal');
      if (
        !nativeDialog &&
        (ariaModal === undefined || ariaModal.replace(/['"]/g, '').trim() === 'false')
      ) {
        out.push({
          line: tag.line,
          rule: 'modal',
          anchor: a,
          message: `role="${role}" without aria-modal="true": the page behind stays in the reading order`,
        });
      }
      const named = ['aria-labelledby', 'aria-label'].some((n) => {
        const v = attrValue(tag, n);
        return v !== undefined && v.trim() !== '';
      });
      if (!named) {
        out.push({
          line: tag.line,
          rule: 'label',
          anchor: a,
          message: `${nativeDialog ? '<dialog>' : `role="${role}"`} with no name: add aria-labelledby pointing at its title`,
        });
      }
      const hasEscape =
        nativeDialog ||
        componentEscape ||
        hasEscapeAttr(tag) ||
        hasKeydownEscape(tag, componentSource) ||
        stack.some((s) => s.escape) ||
        (inner != null && ESCAPE_IN_MARKUP.test(inner));
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
      const inner = innerHtml(html, tokens, index);
      const insideDialog = stack.some((s) => s.isDialog);
      // A plain role, a BOUND role (`[attr.role]="'dialog'"`), a native <dialog> or a
      // child component that is itself a dialog all make the wrapper a wrapper.
      const wrapsDialog =
        inner != null &&
        (/\brole\s*=\s*["'](?:dialog|alertdialog)["']|\[(?:attr\.)?role\]\s*=\s*["']'(?:dialog|alertdialog)'["']|<dialog\b/.test(
          inner,
        ) ||
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
    if (!tag.selfClosing) {
      stack.push({
        name: tag.name,
        isDialog,
        escape: hasEscapeAttr(tag) || hasKeydownEscape(tag, componentSource),
      });
    }
  });
  return out;
}
