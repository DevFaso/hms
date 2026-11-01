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
 *  escape  — role="dialog" with no `(keydown.escape)` on the dialog or an
 *            ancestor, in a component with no `document:keydown.escape`
 *            HostListener and no CDK/Material dialog (which closes on Escape
 *            itself). QA D1004 (round 19): a handler on a DESCENDANT does not
 *            count — a typeahead's `(keydown.escape)` clears its list, it
 *            does not close the dialog.
 *  focus   — the dialog (or an ancestor) carries no focus-managing directive.
 *            QA D1004 (round 19): a NESTED dialog's directive does not count
 *            for the dialog around it (the outer form still kept focus on its
 *            opener, so its own Escape never fired).
 *
 * A native `<dialog>` has the role, the modality and Escape built in: only
 * its name is checked.
 *
 * The anchor of a finding is the element's class (or role) plus the text of
 * the first heading inside it, so a pin survives the lines around it moving
 * and stops matching when the dialog is fixed or removed.
 */
import { tags, innerHtml, attrValue, classTokens, hasAttr, lineOf } from './html-tags.mjs';
import { balanced } from './js-scan.mjs';

const CONTAINER_TOKEN =
  /^(?:[a-z0-9]+-)*(?:modal|dialog|popover|flyout|offcanvas)(?:-(?:container|panel|card|box|window|wrapper|sheet|surface))?$/;

/**
 * QA D1125 / D1004 (round 20): a hand-built overlay is not always called a
 * modal. The shell's notification panel and profile menu, the reception
 * snapshot drawer and the dashboard drawer each sat behind a page-covering
 * `*-backdrop` / `*-overlay` element with no dialog semantics, so the scan
 * keyed on `role="dialog"` never saw them. A COVER is an element whose class
 * ends in backdrop / overlay / scrim; what it covers the page for must be a
 * dialog: the cover itself, a dialog inside it, or the element right after
 * it (the sibling-panel shape).
 *
 * A `*-overlay` with no `(click)` is not a cover the user opened and dismisses
 * (a loading spinner's veil, a camera icon over an avatar, the idle lock
 * screen): only a `*-backdrop` / `*-scrim` or a dismissable `*-overlay` is read.
 */
const COVER_TOKEN = /^(?:[a-z0-9]+-)*(?:backdrop|overlay|scrim)$/;
const ALWAYS_COVER_TOKEN = /^(?:[a-z0-9]+-)*(?:backdrop|scrim)$/;

export function isCover(tag) {
  const tokens = [...classTokens(tag)];
  if (tokens.some((t) => ALWAYS_COVER_TOKEN.test(t))) return true;
  return tokens.some((t) => COVER_TOKEN.test(t)) && tag.attrs.has('(click)');
}

/** A page layout container is never the panel of an overlay, whatever dialogs it holds. */
export const LAYOUT_MARKUP = /<(?:router-outlet|main)\b/;

/**
 * A panel that is a landmark on a wide screen and a modal over the page on a
 * phone (the shell's navigation drawer) may stand behind a cover only when it
 * carries `appDrawerDialog` with `(drawerClose)` bound: role and aria-modal
 * while open, focus moved in, Tab kept inside, Escape from anywhere, focus
 * back to the opener (shared/a11y/drawer-dialog.directive.ts).
 *
 * QA D1153 / D1125 (round 21): until round 20 an element holding a `<nav>`
 * with its own `(keydown.escape)` passed here. That handler fired only once
 * focus had been put inside by hand: focus stayed on Menu, Escape did nothing
 * and Tab walked to the page under the backdrop. An Escape binding is no
 * longer enough for a drawer.
 */
export function isDrawerDialog(tag) {
  return tag.attrs.has('[appDrawerDialog]') && (tag.attrs.get('(drawerClose)') ?? '').trim() !== '';
}

/** Index of the element that ends right before `tokens[index]` opens, or -1. */
function previousSiblingIndex(tokens, index) {
  const prev = tokens[index - 1];
  if (prev?.type !== 'close') return -1;
  for (let k = index - 2; k >= 0; k--) {
    if (
      tokens[k].type === 'open' &&
      tokens[k].name === prev.name &&
      closeIndexOf(tokens, k) === index - 1
    )
      return k;
  }
  return -1;
}

/** Markup that can take keyboard focus, so a keydown can be fired inside it. */
const FOCUSABLE_MARKUP =
  /<(?:button|a|input|select|textarea|ng-content|app-[\w-]+)\b|\btabindex\s*=|\[(?:attr\.)?tabindex\]|\bcontenteditable\b|\bappModalDialog\b|\bappDialogA11y\b|\bappDialogFocus\b|\brole\s*=\s*["'](?:dialog|alertdialog)["']|<dialog\b/;
const FOCUSABLE_TAGS = new Set(['button', 'a', 'input', 'select', 'textarea']);

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

/**
 * QA D1030 (D968's class): `(keydown.escape)="$event.stopPropagation()"` on
 * the dialog does not close it, it SWALLOWS the key, so a close bound on the
 * backdrop around it never fires. Such a binding is not an Escape handler,
 * and it hides every handler above it. (QA D1004 class, the same rule as
 * fix/r17-clinical-workflow wrote it: a binding made only of
 * `$event.stopPropagation()` and/or `$event.preventDefault()` closes nothing,
 * so it is never an Escape handler; it swallows the key only when it stops
 * propagation.)
 */
// Split on `;` and test each statement whole: one anchored, linear regex per
// statement (a repeated `\s*;?\s*` group backtracks exponentially, CodeQL js/redos).
const STOP_STATEMENT = /^\$event\.(?:stopPropagation|preventDefault)\(\)$/;
const SWALLOW_ONLY = {
  test(binding) {
    const statements = binding
      .split(';')
      .map((part) => part.trim())
      .filter((part) => part !== '');
    return statements.length > 0 && statements.every((part) => STOP_STATEMENT.test(part));
  },
};
const STOPS_PROPAGATION = /\$event\.stopPropagation\(\)/;

function escapeBindings(tag) {
  return [...tag.attrs.keys()]
    .filter((k) => ESCAPE_ATTR.test(k))
    .map((k) => tag.attrs.get(k) ?? '');
}

function hasEscapeAttr(tag) {
  return escapeBindings(tag).some((binding) => !SWALLOW_ONLY.test(binding));
}

function swallowsEscape(tag) {
  const bindings = escapeBindings(tag);
  if (
    bindings.length > 0 &&
    bindings.every((binding) => SWALLOW_ONLY.test(binding)) &&
    bindings.some((binding) => STOPS_PROPAGATION.test(binding))
  )
    return true;
  // `(keydown)="$event.stopPropagation()"` swallows every key, Escape included.
  return [...tag.attrs.keys()].some(
    (k) =>
      KEYDOWN_ATTR.test(k) &&
      SWALLOW_ONLY.test(tag.attrs.get(k) ?? '') &&
      STOPS_PROPAGATION.test(tag.attrs.get(k) ?? ''),
  );
}

/**
 * QA D1030 / D968: a hand-built dialog must move focus into itself, keep Tab
 * inside and give focus back - the shared directives do (appModalDialog,
 * appDialogA11y, appDialogFocus), as do a CDK focus trap and the CDK/Material
 * dialog service. A native <dialog> opened with showModal() does it itself.
 */
const SHOW_MODAL = /\.showModal\(\)/;
const FOCUS_DIRECTIVE = /^(?:appModalDialog|appDialogA11y|appDialogFocus|cdkTrapFocus)$/;
const COMPONENT_FOCUS = /MatDialog|@angular\/cdk\/dialog|\bDialogRef\b|\bFocusTrapFactory\b/;

function hasFocusDirective(tag) {
  return [...tag.attrs.keys()].some((k) => FOCUS_DIRECTIVE.test(k));
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

/** Index of the token that closes `tokens[openIndex]` (itself for a void/self-closing tag). */
export function closeIndexOf(tokens, openIndex) {
  const open = tokens[openIndex];
  if (open.selfClosing || open.type !== 'open') return openIndex;
  let depth = 0;
  for (let k = openIndex + 1; k < tokens.length; k++) {
    const t = tokens[k];
    if (t.name !== open.name) continue;
    if (t.type === 'open' && !t.selfClosing) depth++;
    else if (t.type === 'close') {
      if (depth === 0) return k;
      depth--;
    }
  }
  return openIndex;
}

export const DIALOG_MARKUP =
  /\bappDialogA11y\b|\bappModalDialog\b|\brole\s*=\s*["'](?:dialog|alertdialog)["']|\[(?:attr\.)?role\]\s*=\s*["']'(?:dialog|alertdialog)'["']|<dialog\b/;

/** Is this tag (with its inner markup) a dialog, or a wrapper around one? */
function isOrWrapsDialog(html, tokens, index, childDialog) {
  const tag = tokens[index];
  if (directiveDialog(tag) || tag.name === 'dialog') return true;
  const role = attrValue(tag, 'role');
  if (role !== undefined && DIALOG_ROLES.has(role.replace(/['"]/g, '').trim())) return true;
  if (childDialog !== null && childDialog.test(html.slice(tag.start, tag.end))) return true;
  // A `.modal`-named container is the role rule's to report: not reported twice.
  if ([...classTokens(tag)].some((t) => CONTAINER_TOKEN.test(t))) return true;
  const inner = innerHtml(html, tokens, index);
  return (
    inner != null &&
    (DIALOG_MARKUP.test(inner) ||
      containsContainerClass(inner) ||
      (childDialog !== null && childDialog.test(inner)))
  );
}

/** A dialog-container class anywhere inside (the role rule reports it if it is not a dialog). */
function containsContainerClass(inner) {
  for (const m of inner.matchAll(/\bclass="([^"]*)"/g)) {
    if (m[1].split(/\s+/).some((t) => CONTAINER_TOKEN.test(t))) return true;
  }
  return false;
}

/**
 * QA D1125 (round 20): the cover rule. A page-covering backdrop whose panel is
 * not a dialog (role, modality, focus in, Escape, focus back) — neither the
 * cover, nor a dialog inside it, nor the element right after it.
 */
function coverFinding(html, tokens, index, childDialog) {
  if (isOrWrapsDialog(html, tokens, index, childDialog)) return null;
  const nextIndex = closeIndexOf(tokens, index) + 1;
  const next = tokens[nextIndex];
  const hasPanel = next?.type === 'open';
  if (
    hasPanel &&
    !LAYOUT_MARKUP.test(innerHtml(html, tokens, nextIndex) ?? '') &&
    isOrWrapsDialog(html, tokens, nextIndex, childDialog)
  )
    return null;
  const prevIndex = previousSiblingIndex(tokens, index);
  if ([prevIndex, hasPanel ? nextIndex : -1].some((k) => k >= 0 && isDrawerDialog(tokens[k])))
    return null;
  const tag = tokens[index];
  const panel = hasPanel ? next : tag;
  const panelName = hasPanel ? (panel.attrs.get('class') ?? panel.name) : '-';
  return {
    line: panel.line,
    rule: 'overlay',
    anchor: `${tag.attrs.get('class') ?? tag.name} + ${panelName}`,
    message:
      'a page-covering overlay whose panel is not a dialog: put appModalDialog (role, aria-modal, focus in, Escape, focus back) and a name on the panel',
  };
}

/**
 * QA D1153 / D1125 / D1004 (round 21): the cover rule saw a drawer or a menu
 * only when a backdrop sat next to it. A panel NAMED a drawer, a menu, a side
 * nav, an off-canvas or a sheet (a page column called a "sidebar" is layout,
 * not an overlay; the whole class token, optionally with a
 * -panel / -container / -box / -card / -wrapper suffix) pops over the page,
 * with or without a backdrop: it must be a dialog (role, aria-modal, focus in,
 * Escape, focus back), a drawer dialog (`appDrawerDialog` + `(drawerClose)`),
 * or wrap one. An inline disclosure region (`<section>`, `<details>`) is part
 * of the page, not an overlay.
 */
const PANEL_TOKEN =
  /^(?:[a-z0-9]+-)*(?:drawer|menu|sidenav|offcanvas|sheet)(?:-(?:panel|container|box|card|wrapper))?$/;
const INLINE_REGION_TAGS = new Set(['section', 'details']);

function panelFinding(html, tokens, index, childDialog) {
  const tag = tokens[index];
  if (tag.type !== 'open' || INLINE_REGION_TAGS.has(tag.name)) return null;
  if (![...classTokens(tag)].some((t) => PANEL_TOKEN.test(t))) return null;
  if (isDrawerDialog(tag) || isOrWrapsDialog(html, tokens, index, childDialog)) return null;
  // A panel right after a cover (or a cover itself) is the cover rule's to report: not twice.
  const prev = previousSiblingIndex(tokens, index);
  if (isCover(tag) || (prev >= 0 && isCover(tokens[prev]))) return null;
  return {
    line: tag.line,
    rule: 'panel',
    anchor: anchor(tag, innerHtml(html, tokens, index)),
    message:
      'a drawer/menu panel that is not a dialog: put appModalDialog (or appDrawerDialog for a panel that is a modal only on a phone) and a name on it',
  };
}

/**
 * QA D1125 (round 20): `(keydown.escape)` bound on an element that can never
 * hold focus (the shell's `aria-hidden` backdrop, an empty cover) never fires:
 * keyboard events go to the focused element and bubble UP, never sideways.
 */
function unreachableEscape(html, tokens, index) {
  const tag = tokens[index];
  if (!hasEscapeAttr(tag)) return null;
  if (FOCUSABLE_TAGS.has(tag.name) || hasAttr(tag, 'tabindex') || hasAttr(tag, 'contenteditable'))
    return null;
  // A dialog element is read by the focus rule (it must take focus itself).
  if (directiveDialog(tag) || hasFocusDirective(tag)) return null;
  const role = (attrValue(tag, 'role') ?? '').replace(/['"]/g, '').trim();
  if (tag.name === 'dialog' || DIALOG_ROLES.has(role)) return null;
  const hidden = (attrValue(tag, 'aria-hidden') ?? '').replace(/['"]/g, '').trim() === 'true';
  const inner = innerHtml(html, tokens, index) ?? '';
  if (!hidden && FOCUSABLE_MARKUP.test(inner)) return null;
  return {
    line: tag.line,
    rule: 'escape-target',
    anchor: `${tag.attrs.get('class') ?? tag.name} (keydown.escape)`,
    message:
      '(keydown.escape) on an element that can never hold focus (no focusable content, or aria-hidden): the key never reaches it',
  };
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
    const unreachable = unreachableEscape(html, tokens, index);
    if (unreachable) out.push(unreachable);
    if (!stack.some((s) => s.isDialog) && isCover(tag)) {
      const cover = coverFinding(html, tokens, index, childDialog);
      if (cover) out.push(cover);
    }
    if (!stack.some((s) => s.isDialog)) {
      const panel = panelFinding(html, tokens, index, childDialog);
      if (panel) out.push(panel);
    }
    const directive = directiveDialog(tag);
    const role = attrValue(tag, 'role') ?? (directive ? 'dialog' : undefined);
    // A native <dialog> has the implicit role, is modal when opened with
    // showModal() and closes on Escape by itself: only its NAME is checked.
    // QA D1030: only when the component opens it with showModal(). A
    // `<dialog [open]="...">` is NOT modal: no inert page behind, no Escape,
    // no focus moved in. It is checked like a hand-built dialog. (Coarse on
    // purpose: one showModal() call in the component exempts its <dialog>s.)
    const nativeDialog = tag.name === 'dialog' && SHOW_MODAL.test(componentSource);
    const isDialog =
      tag.name === 'dialog' ||
      (role !== undefined && DIALOG_ROLES.has(role.replace(/['"]/g, '').trim()));
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
          message: `${tag.name === 'dialog' ? '<dialog> not opened with showModal()' : `role="${role}"`} without aria-modal="true": the page behind stays in the reading order`,
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
        // QA D1030: a dialog that swallows Escape never lets the backdrop's close fire.
        (!swallowsEscape(tag) && stack.some((s) => s.escape));
      if (!hasEscape) {
        out.push({
          line: tag.line,
          rule: 'escape',
          anchor: a,
          message:
            'dialog in a component with no Escape handler ((keydown.escape) or a document:keydown.escape HostListener)',
        });
      }
      const managesFocus =
        nativeDialog ||
        directive !== null ||
        hasFocusDirective(tag) ||
        stack.some((s) => s.focus) ||
        COMPONENT_FOCUS.test(componentSource);
      if (!managesFocus) {
        out.push({
          line: tag.line,
          rule: 'focus',
          anchor: a,
          message:
            'hand-built dialog with no focus management: add appModalDialog (focus in, Tab kept inside, focus back on close)',
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
        focus: hasFocusDirective(tag),
      });
    }
  });
  out.push(...openerFindings(html, componentSource, childDialog));
  return out;
}

/**
 * QA D1146 (round 21, a regression from the D1124 fix): the portal booking
 * opener was wrapped in `@if (!showBookingForm())`, so the button was
 * DESTROYED while its dialog was open. The dialog directive remembered it,
 * found it disconnected on close, and focus fell to BODY (Escape, the close
 * button and a keyboard open all failed). A re-created button is a new
 * element: focus can never come back to it.
 *
 * Rule `opener`: a clickable element (a `(click)` binding) rendered only while
 * a dialog's condition is FALSE — inside `@if (!cond)`, the `@else` of
 * `@if (cond) { <dialog> }`, or `*ngIf="!cond"` — whose click reads that
 * condition (in the binding or in the body of the method it calls). Keep the
 * opener rendered; refuse a second open in the handler instead.
 */
const COMMENT = /<!--[\s\S]*?-->/g;

function blankComments(html) {
  return html.replace(COMMENT, (m) => m.replace(/[^\n]/g, ' '));
}

/** The `{ … }` group that opens at `open` (comments already blanked); braces only. */
function braceGroupEnd(html, open) {
  let depth = 0;
  for (let i = open; i < html.length; i++) {
    if (html[i] === '{') depth++;
    else if (html[i] === '}' && --depth === 0) return i;
  }
  return html.length - 1;
}

/** The identifier a condition reads: `showForm()` / `this.showForm` / `state.open` → its last name. */
function conditionName(cond) {
  const m = /([A-Za-z_$][\w$]*)\s*(?:\(\s*\))?\s*$/.exec(cond.trim());
  return m ? m[1] : null;
}

/** Every `@if (cond) { … } [@else { … }]` block: condition, then-body, else-body. */
function ifBlocks(html) {
  const out = [];
  for (const m of html.matchAll(/@if\s*\(/g)) {
    const condGroup = balanced(html, m.index + m[0].length - 1);
    const cond = condGroup.slice(1, -1).split(';')[0].trim();
    const afterCond = m.index + m[0].length - 1 + condGroup.length;
    const open = html.indexOf('{', afterCond);
    if (open < 0 || html.slice(afterCond, open).trim() !== '') continue;
    const close = braceGroupEnd(html, open);
    const block = { cond, start: open, body: html.slice(open + 1, close), elseBody: null };
    const elseMatch = /^\s*@else\s*\{/.exec(html.slice(close + 1));
    if (elseMatch) {
      const elseOpen = close + elseMatch[0].length;
      block.elseBody = html.slice(elseOpen + 1, braceGroupEnd(html, elseOpen));
    }
    out.push(block);
  }
  return out;
}

const NEGATED = /^!\s*(?!=)([\s\S]+)$/;

function clickReads(tag, name, componentSource) {
  const binding = tag.attrs.get('(click)') ?? '';
  const escaped = (id) => id.replace(/\$/g, String.raw`\$`);
  const reads = new RegExp(String.raw`\b${escaped(name)}\b`);
  if (reads.test(binding)) return true;
  const handler = /^\s*(?:this\.)?([A-Za-z_$][\w$]*)\s*\(/.exec(binding)?.[1];
  if (!handler) return false;
  const declaration = new RegExp(
    String.raw`(?:^|[\s;}])${escaped(handler)}\s*\([^)]*\)\s*(?::[^{;]+)?\{`,
    'gm',
  );
  for (const d of componentSource.matchAll(declaration)) {
    if (reads.test(balanced(componentSource, d.index + d[0].length - 1, '{', '}'))) return true;
  }
  return false;
}

function openerFindings(html, componentSource, childDialog) {
  const clean = blankComments(html);
  const blocks = ifBlocks(clean);
  const opensDialog = (body) =>
    DIALOG_MARKUP.test(body) || (childDialog !== null && childDialog.test(body));
  const dialogConditions = new Set(
    blocks
      .filter((b) => opensDialog(b.body))
      .map((b) => conditionName(b.cond))
      .filter(Boolean),
  );
  // Regions rendered only while a dialog condition is false.
  const hiding = [];
  for (const b of blocks) {
    const negated = NEGATED.exec(b.cond);
    const name = negated ? conditionName(negated[1]) : null;
    if (name && dialogConditions.has(name) && !/[&|?]/.test(b.cond))
      hiding.push({ name, body: b.body });
    const own = conditionName(b.cond);
    if (b.elseBody !== null && opensDialog(b.body) && own)
      hiding.push({ name: own, body: b.elseBody });
  }
  for (const tag of tags(clean)) {
    const ngIf = NEGATED.exec((attrValue(tag, '*ngIf') ?? '').replace(/['"]/g, '').trim());
    const name = ngIf ? conditionName(ngIf[1]) : null;
    if (name && dialogConditions.has(name)) {
      hiding.push({ name, body: clean.slice(tag.start, closeTagEnd(clean, tag)) });
    }
  }
  const out = [];
  const seen = new Set();
  for (const { name, body } of hiding) {
    for (const tag of tags(body)) {
      if (tag.type !== 'open' || !tag.attrs.has('(click)')) continue;
      if (!clickReads(tag, name, componentSource)) continue;
      const key = `${name}::${tag.attrs.get('(click)')}`;
      if (seen.has(key)) continue;
      seen.add(key);
      out.push({
        line: lineOf(clean, clean.indexOf(body)) + tag.line - 1,
        rule: 'opener',
        anchor: `${tag.attrs.get('class') ?? tag.name} (click)="${(tag.attrs.get('(click)') ?? '').trim()}" hidden while ${name}`,
        message: `the opener is not rendered while its dialog (${name}) is open, so focus cannot come back to it on close: keep it rendered and refuse a second open in its handler`,
      });
    }
  }
  return out;
}

/** End offset of the element `tag` opens (its close tag), in `html`. */
function closeTagEnd(html, tag) {
  const tokens = tags(html);
  const index = tokens.findIndex((t) => t.start === tag.start);
  if (index < 0) return tag.end;
  return tokens[closeIndexOf(tokens, index)].end;
}
