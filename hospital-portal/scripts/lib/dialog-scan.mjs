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
 *  subform — QA D1155 / D1144 / D1131 (round 21): an inline action form
 *            INSIDE a dialog (a block opened from the dialog — `@if (…) { … }`
 *            or an `<ng-template>` — whose element holds a text field and a
 *            button closing that form, not the dialog) without
 *            `appInlineSubForm` + `(dismissed)` (or its own `(keydown.escape)`
 *            handler that stops the key). Escape there bubbled to the dialog,
 *            which closed and dropped the typed reason.
 *  label-icon — QA D1174 (round 21): the element a dialog's aria-labelledby
 *            names holds an icon-font span without aria-hidden="true", so the
 *            ligature ("monitor_heart") is read as the first word of the name.
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
import { tags, innerHtml, attrValue, classTokens, hasAttr } from './html-tags.mjs';
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
 * The one non-dialog panel a cover may stand for: a navigation drawer (the
 * shell's phone sidebar, an element holding a `<nav>`), which closes on its
 * own reachable `(keydown.escape)`. A landmark is not made a dialog because it
 * slides over the page on a phone.
 */
function isNavDrawer(html, tokens, index) {
  const inner = innerHtml(html, tokens, index) ?? '';
  return /<nav\b/.test(inner) && hasEscapeAttr(tokens[index]);
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

/** A control that can hold focus, written as a tag. */
const FOCUSABLE_CONTROL =
  /<(?:button|select|textarea)\b|<input\b(?![^>]*\btype\s*=\s*["']hidden["'])|<a\b[^>]*\b(?:href|routerLink)\b|\btabindex\s*=\s*["'](?!-1)\d/;

/** `inner` without the bodies of its `@if` / `@else` / `@for` / `@switch` blocks. */
function withoutBlocks(inner) {
  let out = '';
  let i = 0;
  while (i < inner.length) {
    const at = inner.indexOf('@', i);
    if (at < 0) {
      out += inner.slice(i);
      break;
    }
    out += inner.slice(i, at);
    const block = /^@(?:if|else|for|switch|case|default|defer|placeholder|loading|empty)\b/.exec(
      inner.slice(at),
    );
    const brace = block ? inner.indexOf('{', at) : -1;
    if (brace < 0) {
      out += '@';
      i = at + 1;
      continue;
    }
    let depth = 0;
    let k = brace;
    for (; k < inner.length; k++) {
      if (inner[k] === '{') depth++;
      else if (inner[k] === '}' && --depth === 0) break;
    }
    i = k + 1;
  }
  return out;
}

/**
 * QA D1154 / D1004 / D1125 (round 21): the lab outbox's message dialog
 * trusted `cdkTrapFocus` + auto-capture, which focuses the first control
 * present when the dialog opens. The card opened while the message was still
 * loading, every control behind an `@if`: nothing was captured, focus stayed
 * on "Voir le message", and the card's Escape never fired. The shared
 * directives focus the dialog itself when it has nothing focusable.
 */
function capturesNothing(tag, inner, stack, directive, nativeDialog, componentSource) {
  if (nativeDialog || directive !== null || COMPONENT_FOCUS.test(componentSource)) return false;
  const keys = [...tag.attrs.keys()];
  if (keys.some((k) => /^(?:appDialogFocus)$/.test(k)) || stack.some((s) => s.focus)) return false;
  if (!keys.includes('cdkTrapFocus')) return false;
  return !FOCUSABLE_CONTROL.test(withoutBlocks(inner ?? ''));
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
  if ([prevIndex, hasPanel ? nextIndex : -1].some((k) => k >= 0 && isNavDrawer(html, tokens, k)))
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
      } else if (capturesNothing(tag, inner, stack, directive, nativeDialog, componentSource)) {
        out.push({
          line: tag.line,
          rule: 'focus',
          anchor: a,
          message:
            'cdkTrapFocus captures nothing when this dialog opens (every control is behind an @if, e.g. while it loads): focus stays on the opener and Escape never reaches it; use appModalDialog',
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
  return out.concat(
    subFormFindings(html, tokens, componentSource),
    labelIconFindings(html, tokens),
  );
}

/** A field that holds what the reader types (a checkbox or a file is not text to lose). */
const NON_TEXT_INPUTS = new Set(['checkbox', 'radio', 'hidden', 'file', 'button', 'submit']);
/** A button handler that closes a form: a method so named, or a flag switched off. */
const CLOSE_METHOD = /cancel|close|dismiss|hide|back|discard/i;
const FLAG_OFF = /\.set\(\s*(?:false|null|'')\s*\)|=\s*(?:false|null)\b/;
const TRANSPARENT_TAGS = new Set(['ng-container', 'ng-template']);
const DIALOG_CLOSE_OUTPUTS = ['(escaped)', '(keydown.escape)', '(dialogClose)', '(dialogDismiss)'];

function calledMethod(binding) {
  return /^\s*(?:this\.)?([A-Za-z_$][\w$]*)\s*\(/.exec(binding ?? '')?.[1];
}

function isDialogTag(tag) {
  const role = (attrValue(tag, 'role') ?? '').replace(/['"]/g, '').trim();
  return tag.name === 'dialog' || DIALOG_ROLES.has(role) || directiveDialog(tag) !== null;
}

/** The method names that close a dialog: its Escape outputs, and a backdrop's click. */
function dialogClosers(tag) {
  const out = new Set();
  for (const output of DIALOG_CLOSE_OUTPUTS) {
    const name = calledMethod(tag.attrs.get(output));
    if (name) out.add(name);
  }
  if (isCover(tag)) {
    const name = calledMethod(tag.attrs.get('(click)'));
    if (name) out.add(name);
  }
  return out;
}

/** `(keydown.escape)` on the form itself whose handler stops the key (the encounter addendum's shape). */
function ownStoppingEscape(tag, componentSource) {
  return escapeBindings(tag).some((binding) => {
    if (STOPS_PROPAGATION.test(binding)) return !SWALLOW_ONLY.test(binding);
    const handler = calledMethod(binding);
    if (!handler) return false;
    const declaration = new RegExp(
      String.raw`(?:^|[\s;}])${handler.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\s*\([^)]*\)\s*(?::[^{;]+)?\{`,
      'gm',
    );
    for (const m of componentSource.matchAll(declaration)) {
      const body = balanced(componentSource, m.index + m[0].length - 1, '{', '}');
      if (/\.stopPropagation\(\)/.test(body)) return true;
    }
    return false;
  });
}

function hasSubFormContract(tag, componentSource) {
  if (tag.attrs.has('appInlineSubForm')) {
    return (tag.attrs.get('(dismissed)') ?? '').trim() !== '';
  }
  return ownStoppingEscape(tag, componentSource);
}

/**
 * Index just past the bracket that closes the one at `open`. Plain counting:
 * Angular's block syntax makes a literal `{` / `}` in template text an error
 * (it is written `&#123;`), and attribute values and interpolations nest
 * their own brackets in pairs, while quote-aware matching misreads an
 * apostrophe in French text as an open string.
 */
function closeOfBracket(html, open, opener, closer) {
  let depth = 0;
  for (let i = open; i < html.length; i++) {
    if (html[i] === opener) depth++;
    else if (html[i] === closer && --depth === 0) return i + 1;
  }
  return html.length;
}

/**
 * Each `@if (…) { … }` / `@else if (…) { … }` / `@else { … }` block body and
 * `<ng-template>` body: `{ start, end, chain }` (`end` is the closing `}`).
 * The bodies of one `@if … @else if … @else` chain share a `chain` number:
 * two of them are never on screen together.
 */
function blockBodies(html, tokens) {
  const out = [];
  let chains = 0;
  for (const m of html.matchAll(/@(?:if\s*\(|else\b)/g)) {
    let from = m.index + m[0].length;
    let chain;
    if (m[0].startsWith('@if')) {
      from = closeOfBracket(html, from - 1, '(', ')');
      chain = chains++;
    } else {
      const elseIf = /^\s*if\s*\(/.exec(html.slice(from));
      if (elseIf) from = closeOfBracket(html, from + elseIf[0].length - 1, '(', ')');
      // The body this `@else` continues ends right before it.
      const closing = html.slice(0, m.index).trimEnd().length - 1;
      chain = out.find((b) => b.end === closing)?.chain ?? chains++;
    }
    const brace = html.indexOf('{', from);
    if (brace < 0) continue;
    out.push({ start: brace + 1, end: closeOfBracket(html, brace, '{', '}') - 1, chain });
  }
  tokens.forEach((tag, index) => {
    if (tag.type !== 'open' || tag.name !== 'ng-template') return;
    const close = closeIndexOf(tokens, index);
    out.push({ start: tag.end, end: tokens[close].start, chain: chains++ });
  });
  return out;
}

function isTextField(tag) {
  if (tag.name === 'textarea' || tag.name === 'select') return true;
  if (tag.name !== 'input') return false;
  const type = (attrValue(tag, 'type') ?? 'text').replace(/['"]/g, '').trim();
  return !NON_TEXT_INPUTS.has(type);
}

function closesForm(tag, dialogClosers) {
  if (tag.name !== 'button') return false;
  const binding = tag.attrs.get('(click)');
  if (!binding) return false;
  const name = calledMethod(binding);
  if (name) return CLOSE_METHOD.test(name) && !dialogClosers.has(name);
  return FLAG_OFF.test(binding);
}

/**
 * Whether the element at `index` holds a text field and a button that closes
 * the form (not the dialog) that can be on screen together. A field in an
 * `@if` and a "Close" in its `@else` are two states of a panel, not a form
 * with its own Cancel (the prescription dialog's CDS advisories); a field and
 * a Cancel in two separate `@if (editing)` cells of one row are one form
 * (an invoice line edited in place).
 */
function closesItselfWithAField(tokens, index, blocks, dialogClosers) {
  const from = tokens[index].end;
  const to = tokens[closeIndexOf(tokens, index)].start;
  const inner = blocks.filter((b) => b.start >= from && b.end <= to);
  const branchesAt = (pos) => inner.filter((b) => pos >= b.start && pos < b.end);
  const fields = [];
  const closes = [];
  for (let k = index + 1; k < tokens.length && tokens[k].start < to; k++) {
    const t = tokens[k];
    if (t.type !== 'open') continue;
    if (isTextField(t)) fields.push(branchesAt(t.start));
    else if (closesForm(t, dialogClosers)) closes.push(branchesAt(t.start));
  }
  const exclusive = (a, b) => a.some((x) => b.some((y) => x.chain === y.chain && x !== y));
  return fields.some((f) => closes.some((c) => !exclusive(f, c)));
}

/**
 * QA D1155 / D1144 / D1131 (round 21): an inline sub-form inside a dialog
 * must keep Escape to itself (appInlineSubForm). See the header.
 */
export function subFormFindings(html, tokens = tags(html), componentSource = html) {
  const dialogs = [];
  const stack = [];
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
    const closers = dialogClosers(tag);
    if (isDialogTag(tag)) {
      const all = new Set(closers);
      for (const s of stack) for (const c of s.closers) all.add(c);
      dialogs.push({
        start: tag.end,
        end: tokens[closeIndexOf(tokens, index)].start,
        closers: all,
      });
    }
    if (!tag.selfClosing) stack.push({ name: tag.name, closers });
  });
  if (dialogs.length === 0) return [];
  const out = [];
  const seen = new Set();
  const blocks = blockBodies(html, tokens);
  for (const { start, end } of blocks) {
    const dialog = dialogs.filter((d) => start > d.start && start < d.end).at(-1);
    if (!dialog) continue;
    let depth = 0;
    tokens.forEach((tag, index) => {
      if (tag.start < start || tag.start >= end) return;
      // `<ng-container>` / `<ng-template>` render no element: their children stand at their level.
      if (TRANSPARENT_TAGS.has(tag.name)) return;
      if (tag.type === 'close') {
        depth--;
        return;
      }
      const topLevel = depth === 0;
      if (!tag.selfClosing) depth++;
      if (!topLevel || seen.has(index) || isDialogTag(tag)) return;
      const inner = innerHtml(html, tokens, index) ?? '';
      if (DIALOG_MARKUP.test(inner)) return;
      if (!closesItselfWithAField(tokens, index, blocks, dialog.closers)) return;
      seen.add(index);
      if (hasSubFormContract(tag, componentSource)) return;
      out.push({
        line: tag.line,
        rule: 'subform',
        anchor: anchor(tag, inner),
        message:
          'an inline form inside a dialog without appInlineSubForm + (dismissed): Escape in it closes the whole dialog and drops the typed text',
      });
    });
  }
  return out;
}

/** An icon-font element: its text is a ligature name, never words to read. */
const ICON_CLASS = /\bmaterial-(?:symbols|icons)\b/;

/**
 * QA D1174 (round 21): the vitals dialog was announced as "monitor_heart
 * Saisir les signes vitaux": the icon in its title had no aria-hidden.
 */
export function labelIconFindings(html, tokens = tags(html)) {
  const out = [];
  const named = new Set();
  for (const tag of tokens) {
    if (tag.type !== 'open') continue;
    const ids = attrValue(tag, 'aria-labelledby');
    if (ids) for (const id of ids.replace(/['"]/g, '').split(/\s+/)) if (id) named.add(id);
  }
  tokens.forEach((tag, index) => {
    if (tag.type !== 'open' || tag.selfClosing) return;
    const id = (attrValue(tag, 'id') ?? '').replace(/['"]/g, '').trim();
    if (!id || !named.has(id)) return;
    const end = closeIndexOf(tokens, index);
    for (let k = index + 1; k < end; k++) {
      const t = tokens[k];
      if (t.type !== 'open' || !ICON_CLASS.test(t.attrs.get('class') ?? '')) continue;
      const hidden = (attrValue(t, 'aria-hidden') ?? '').replace(/['"]/g, '').trim() === 'true';
      if (hidden) continue;
      out.push({
        line: t.line,
        rule: 'label-icon',
        anchor: `#${id}`,
        message:
          'an icon inside the element that names a dialog has no aria-hidden="true": its ligature is read as part of the name',
      });
    }
  });
  return out;
}
