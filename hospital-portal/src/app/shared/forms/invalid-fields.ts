/**
 * QA D1072 (WCAG 3.3.1, 2.4.3): a form the browser-side check refuses says
 * WHICH field is wrong, where the reader is looking. The registration form
 * showed only the toast "Veuillez remplir tous les champs obligatoires": the
 * missing field had no `aria-invalid`, focus stayed on the button, and the
 * field stayed scrolled out of view.
 *
 * Every client-side refusal of a form goes through {@link refuseFields} (or,
 * when the template already binds `aria-invalid` and a message per field,
 * {@link focusFirstInvalid}). `scripts/lib/required-refusal.test.mjs` fails the
 * build when a component refuses with a "required / missing" message and does
 * not call one of them in the same method.
 */

/** The ids among `ids` whose element is in `root`, in the order given. */
function elementsById(root: ParentNode, ids: readonly string[]): HTMLElement[] {
  const out: HTMLElement[] = [];
  for (const id of ids) {
    const el = root.querySelector<HTMLElement>(`#${CSS.escape(id)}`);
    if (el) out.push(el);
  }
  return out;
}

/**
 * Scrolls the first element of `ids` found in `root` into view and moves the
 * keyboard focus to it. Runs after the current change-detection pass, so a
 * message or an `aria-invalid` the template binds from the same refusal is
 * already rendered. Returns whether a field was found.
 */
export function focusFirstInvalid(
  root: ParentNode | null | undefined,
  ids: readonly string[],
): boolean {
  if (!root || ids.length === 0) return false;
  const run = (): boolean => {
    const [first] = elementsById(root, ids);
    if (!first) return false;
    first.scrollIntoView?.({ block: 'center', behavior: 'smooth' });
    first.focus({ preventScroll: true });
    return true;
  };
  // Found now (the usual case): focus at once. Otherwise the field may be
  // rendered by this same refusal (an @if on the error), so try once more
  // after the view has updated.
  if (run()) return true;
  setTimeout(run, 0);
  return false;
}

/** The id of the message {@link refuseFields} shows under a refused field. */
export function invalidMessageId(fieldId: string): string {
  return `${fieldId}-invalid-msg`;
}

/**
 * Marks the refused fields: `aria-invalid="true"`, a visible message right
 * under the field (`role="alert"`, the global `.invalid-field-msg` style in styles.scss)
 * that `aria-describedby` points at; clears both from the other fields of
 * `all` (fixed since the last attempt); then focuses and scrolls to the
 * first refused field.
 *
 * For a form whose template does not bind a message per field. A template
 * that does (the patient registration form) calls {@link focusFirstInvalid}.
 *
 * @param root the form's host element
 * @param invalid the ids of the refused fields, in reading order
 * @param all every id this form may refuse, so a fixed field loses its mark
 * @param message the message shown under each refused field (already translated)
 */
export function refuseFields(
  root: ParentNode | null | undefined,
  invalid: readonly string[],
  all: readonly string[],
  message: string,
): boolean {
  if (!root) return false;
  const refused = new Set(invalid);
  for (const el of elementsById(root, all)) {
    const msgId = invalidMessageId(el.id);
    const others = (el.getAttribute('aria-describedby') ?? '')
      .split(/\s+/)
      .filter((t) => t !== '' && t !== msgId);
    let msg = root.querySelector<HTMLElement>(`#${CSS.escape(msgId)}`);
    if (refused.has(el.id)) {
      el.setAttribute('aria-invalid', 'true');
      if (!msg) {
        msg = document.createElement('small');
        msg.id = msgId;
        msg.className = 'invalid-field-msg';
        msg.setAttribute('role', 'alert');
        el.insertAdjacentElement('afterend', msg);
      }
      msg.textContent = message;
      others.push(msgId);
    } else {
      el.removeAttribute('aria-invalid');
      msg?.remove();
    }
    if (others.length > 0) el.setAttribute('aria-describedby', others.join(' '));
    else el.removeAttribute('aria-describedby');
  }
  return focusFirstInvalid(root, invalid);
}

/**
 * The fields of `required` whose value in `values` is blank, in the given
 * order — the ids a form passes to {@link refuseFields}.
 */
export function blankFields(
  values: Readonly<Record<string, unknown>>,
  required: readonly string[],
): string[] {
  return required.filter((key) => {
    const v = values[key];
    return v === null || v === undefined || (typeof v === 'string' && v.trim() === '');
  });
}
