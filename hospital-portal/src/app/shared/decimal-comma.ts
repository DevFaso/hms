/**
 * QA D505: a comma decimal typed into an `<input type="number">` was dropped
 * by a browser in an English locale ("7,5" kg saved as 75, a glucose of
 * "5,6" as 56) while every placeholder shows a comma ("ex. 70,5"). The
 * browser sanitises the value before any component sees it, so no per-field
 * parser can recover it: the comma has to become a point as it is typed.
 *
 * Installed once at start-up for the whole document, so every numeric
 * clinical input (vitals, triage, maternity, fluid balance, dosing, lab)
 * is covered, not only the one QA tried.
 */

/** Whether the event target is a numeric input the user is typing into. */
function isNumberInput(target: EventTarget | null): target is HTMLInputElement {
  return target instanceof HTMLInputElement && target.type === 'number';
}

/** A comma key (main keyboard or the numeric pad of a French layout). */
function isCommaKey(event: KeyboardEvent): boolean {
  return event.key === ',' || (event.code === 'NumpadDecimal' && event.key !== '.');
}

/**
 * Keydown: a comma in a number input is typed as a point instead. The point
 * is inserted through the editing pipeline (`insertText`), so the caret and
 * the half-typed text ("7.") are kept and an `input` event reaches the form.
 */
export function handleDecimalCommaKeydown(event: KeyboardEvent, doc: Document = document): void {
  if (!isNumberInput(event.target) || !isCommaKey(event)) return;
  if (event.ctrlKey || event.metaKey || event.altKey) return;
  event.preventDefault();
  doc.execCommand('insertText', false, '.');
}

/** Paste: "7,5" pasted into a number input arrives as "7.5". */
export function handleDecimalCommaPaste(event: ClipboardEvent, doc: Document = document): void {
  if (!isNumberInput(event.target)) return;
  const text = event.clipboardData?.getData('text') ?? '';
  const normalised = normaliseDecimalComma(text);
  if (normalised === text) return;
  event.preventDefault();
  doc.execCommand('insertText', false, normalised);
}

/**
 * "7,5" → "7.5"; a lone comma between digits is a decimal separator. Text
 * with both separators or several commas is left alone (the browser then
 * refuses it as invalid rather than us guessing).
 */
export function normaliseDecimalComma(text: string): string {
  const trimmed = text.trim();
  return /^-?\d*,\d+$/.test(trimmed) ? trimmed.replace(',', '.') : text;
}

/** Wires both listeners on the document (capture phase, before any component). */
export function installDecimalCommaSupport(doc: Document = document): void {
  doc.addEventListener('keydown', (e) => handleDecimalCommaKeydown(e, doc), true);
  doc.addEventListener('paste', (e) => handleDecimalCommaPaste(e, doc), true);
}
