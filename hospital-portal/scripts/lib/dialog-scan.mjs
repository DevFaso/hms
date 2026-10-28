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
import { balanced } from './js-scan.mjs';

const CONTAINER_TOKEN =
  /^(?:[a-z0-9]+-)*(?:modal|dialog)(?:-(?:container|panel|card|box|window|wrapper|sheet|surface))?$/;

const DIALOG_ROLES = new Set(['dialog', 'alertdialog']);

/**
 * Round 16: the shared dialog directives set role="dialog" and
 * aria-modal="true" from their host bindings, trap focus, and emit on Escape
 * (shared/a11y/dialog-a11y.directive.ts emits `dialogClose`,
 * shared/a11y/modal-dialog.directive.ts emits `escaped`). An element carrying
 * one IS a dialog to assistive technology; its Escape counts only when that
 * output is bound to something, and its name is still checked.
 */
const DIRECTIVE_DIALOGS = new Map([
  ['appDialogA11y', '(dialogClose)'],
  ['appModalDialog', '(escaped)'],
]);

/**
 * A keyboard-contract directive that does NOT make its host a dialog (no host
 * role/aria-modal), but does close it on Escape from a host keydown listener:
 * shared/a11y/dialog-focus.directive.ts (`appDialogFocus`) emits
 * `dialogDismiss`. Its host still needs role="dialog", aria-modal and a name;
 * only its Escape counts, and only when that output is bound.
 */
const ESCAPE_DIRECTIVES = new Map([['appDialogFocus', '(dialogDismiss)']]);

function directiveEscape(tag) {
  for (const [directive, output] of ESCAPE_DIRECTIVES) {
    if (tag.attrs.has(directive) && (tag.attrs.get(output) ?? '').trim() !== '') return true;
  }
  return false;
}

function directiveDialog(tag) {
  for (const [directive, output] of DIRECTIVE_DIALOGS) {
    if (tag.attrs.has(directive)) return { escape: (tag.attrs.get(output) ?? '').trim() !== '' };
  }
  return null;
}

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

/**
 * A `(keydown)="onKey($event)"` whose OWN handler reads `'Escape'` — in the
 * binding itself, or in the body of the method it calls. An `'Escape'` check
 * elsewhere in the component (a typeahead's key handler) does not close this dialog.
 */
function hasKeydownEscape(tag, componentSource) {
  const key = [...tag.attrs.keys()].find((k) => KEYDOWN_ATTR.test(k));
  if (key === undefined) return false;
  const binding = tag.attrs.get(key) ?? '';
  if (ESCAPE_KEY.test(binding)) return true;
  const handler = /^\s*['"]?\s*(?:this\.)?([A-Za-z_$][\w$]*)\s*\(/.exec(binding)?.[1];
  if (!handler) return false;
  const declaration = new RegExp(
    String.raw`(?:^|[\s;}])${handler.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\s*\([^)]*\)\s*(?::[^{;]+)?\{`,
    'gm',
  );
  for (const m of componentSource.matchAll(declaration)) {
    const body = balanced(componentSource, m.index + m[0].length - 1, '{', '}');
    if (ESCAPE_KEY.test(body)) return true;
  }
  return false;
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
    const directive = directiveDialog(tag);
    const role = attrValue(tag, 'role') ?? (directive ? 'dialog' : undefined);
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
      const ariaModal = directive ? 'true' : attrValue(tag, 'aria-modal');
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
        directive?.escape === true ||
        directiveEscape(tag) ||
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
        (/\bappDialogA11y\b|\bappModalDialog\b|\brole\s*=\s*["'](?:dialog|alertdialog)["']|\[(?:attr\.)?role\]\s*=\s*["']'(?:dialog|alertdialog)'["']|<dialog\b/.test(
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
