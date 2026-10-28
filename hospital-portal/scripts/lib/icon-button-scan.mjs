/**
 * Accessible names (QA D988, D999, D1006, D993; earlier D173, D200).
 *
 * The recurring shape: a `<button>` whose only content is a Material icon, so
 * a screen reader announces the ligature text ("person_search", "edit",
 * "delete"); and a form control with no label at all. Two rules on whole tags
 * (lib/html-tags.mjs):
 *
 *  icon-button  — a `<button>`, an `<a>` with a `(click)`, or any element with
 *                 role="button", whose content once icons are removed is
 *                 empty, and that has no aria-label / aria-labelledby. A
 *                 `title` alone does not count: it is a tooltip, read by some
 *                 assistive technology and not others.
 *  field-label  — an `<input>` (not hidden/submit/button), `<textarea>` or
 *                 `<select>` with no aria-label / aria-labelledby, no `id` a
 *                 `<label for>` in the same template points at, and no
 *                 enclosing `<label>`. A placeholder is not a label (D200).
 *
 * Icons: `<span class="material-symbols-outlined">`, `<span class="material-icons">`,
 * `<mat-icon>`, `<svg>`, `<i class="...">`, and an `<img>` with an EMPTY alt. An
 * `<img alt="Delete">` names the button and is kept as content.
 */
import { tags, innerHtml, attrValue } from './html-tags.mjs';

const ICON_SPAN =
  /<span\b[^>]*class="[^"]*\bmaterial-(?:symbols-outlined|symbols-rounded|icons)\b[^"]*"[^>]*>[\s\S]*?<\/span>/g;
const ICON_SPAN_BOUND =
  /<span\b[^>]*\[class(?:\.[^\]]+)?\]="[^"]*material-[^"]*"[^>]*>[\s\S]*?<\/span>/g;
const MAT_ICON = /<mat-icon\b[^>]*>[\s\S]*?<\/mat-icon>/g;
const SVG = /<svg\b[^>]*>[\s\S]*?<\/svg>/g;
const I_TAG = /<i\b[^>]*><\/i>/g;
const IMG_NAMED = /<img\b[^>]*(?:\balt="([^"]+)"|\[(?:attr\.)?alt\]="[^"]+")[^>]*>/g;
const IMG_EMPTY_ALT = /<img\b(?=[^>]*\balt=""|(?![^>]*\balt=))[^>]*>/g;
const COMMENT = /<!--[\s\S]*?-->/g;
const CONTROL_FLOW =
  /@(?:if|else if|else|for|switch|case|default|empty|let|defer|placeholder|loading|error)\b[^{]*\{|\}/g;
// Projected content and a child component (`<app-status-chip>`, `<app-avatar>`)
// render text of their own: they are content, not an icon.
// `<ng-container>`/`<ng-template>` are structural wrappers, not content.
const PROJECTED_OR_CHILD =
  /<ng-content\b[^>]*>(?:<\/ng-content>)?|<(?!mat-icon\b|ng-container\b|ng-template\b)[a-z]+-[a-z-]+\b[^>]*>/g;
const ANY_TAG = /<\/?[A-Za-z][^>]*>/g;

export function visibleText(inner) {
  return inner
    .replace(COMMENT, ' ')
    .replace(ICON_SPAN, ' ')
    .replace(ICON_SPAN_BOUND, ' ')
    .replace(MAT_ICON, ' ')
    .replace(SVG, ' ')
    .replace(I_TAG, ' ')
    .replace(IMG_NAMED, (_, alt) => ` ${alt ?? 'alt'} `)
    .replace(IMG_EMPTY_ALT, ' ')
    .replace(PROJECTED_OR_CHILD, ' content ')
    .replace(CONTROL_FLOW, ' ')
    .replace(ANY_TAG, ' ')
    .replace(/&nbsp;|&#160;/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

/** A name that is present AND not a literal empty string: `aria-label=""` names nothing. */
function isNamed(tag) {
  for (const name of ['aria-label', 'aria-labelledby']) {
    const value = attrValue(tag, name);
    if (value !== undefined && value.trim() !== '') return true;
  }
  return false;
}

/**
 * A control that needs a name: a <button>, an <a> that does something (a
 * (click), a routerLink or an href — the D173 shape was an icon-only sidebar
 * link), or anything with role="button" / role="link".
 */
function isButton(tag) {
  if (tag.name === 'button') return true;
  if (
    tag.name === 'a' &&
    [...tag.attrs.keys()].some((k) => /^\(click\)|^\(keydown|routerLink|^href$|^\[href\]$/.test(k))
  ) {
    return true;
  }
  const role = attrValue(tag, 'role');
  return role !== undefined && /^(?:button|link)$/.test(role.replace(/['"]/g, '').trim());
}

function iconSummary(inner) {
  const m =
    /material-(?:symbols-outlined|symbols-rounded|icons)\b[^>]*>\s*([\w{}().|' :-]+?)\s*</.exec(
      inner,
    );
  return m ? m[1].trim() : 'icon';
}

const UNLABELLED_INPUT_TYPES = new Set(['hidden', 'submit', 'button', 'reset', 'image']);

/**
 * @param {string} html
 * @returns {{ line: number, rule: string, anchor: string, message: string }[]}
 */
export function nameFindings(html) {
  const tokens = tags(html);
  const out = [];
  const labelledIds = new Set();
  for (const t of tokens) {
    if (t.type === 'open' && t.name === 'label') {
      const f = attrValue(t, 'for');
      if (f) labelledIds.add(f.trim());
    }
  }
  const stack = [];
  tokens.forEach((tag, index) => {
    if (tag.type === 'close') {
      for (let k = stack.length - 1; k >= 0; k--) {
        if (stack[k] === tag.name) {
          stack.length = k;
          break;
        }
      }
      return;
    }
    if (isButton(tag) && !isNamed(tag)) {
      const inner = innerHtml(html, tokens, index);
      if (inner != null && visibleText(inner) === '') {
        const icon = iconSummary(inner);
        // The anchor names WHAT the control does, so a fixed "delete" button
        // cannot lend its pin to a new unnamed "delete" button on the same page.
        const action = [...tag.attrs.entries()].find(([k]) =>
          /^\((?:click|keydown\S*)\)$|routerLink|^\[?href\]?$/.test(k),
        );
        const does = action
          ? `${action[0]}=${(action[1] ?? '').replace(/\s+/g, ' ').slice(0, 60)}`
          : '';
        out.push({
          line: tag.line,
          rule: 'icon-button',
          anchor: `${tag.name} ${icon} ${does}`.trim(),
          message: `icon-only <${tag.name}> (${icon}) with no aria-label: announced by the icon's ligature text`,
        });
      }
    }
    if (tag.name === 'input' || tag.name === 'textarea' || tag.name === 'select') {
      const type = (attrValue(tag, 'type') ?? 'text').replace(/['"]/g, '').trim();
      const skip = tag.name === 'input' && UNLABELLED_INPUT_TYPES.has(type);
      if (!skip && !isNamed(tag) && !stack.includes('label')) {
        const id = attrValue(tag, 'id')?.trim();
        if (!id || !labelledIds.has(id)) {
          const name =
            attrValue(tag, 'formControlName') ??
            attrValue(tag, 'name') ??
            attrValue(tag, 'ngModel') ??
            // The two-way binding the portal writes (`[(ngModel)]="m.medicationName"`):
            // without it every such field fell back to `input text` and shared one pin.
            tag.attrs.get('[(ngModel)]') ??
            attrValue(tag, 'formControl') ??
            id ??
            type;
          out.push({
            line: tag.line,
            rule: 'field-label',
            anchor: `${tag.name} ${name ?? ''}`.trim(),
            message: `<${tag.name}> (${name ?? type}) with no label: add aria-label, aria-labelledby or a <label for>`,
          });
        }
      }
    }
    if (!tag.selfClosing) stack.push(tag.name);
  });
  return out;
}
