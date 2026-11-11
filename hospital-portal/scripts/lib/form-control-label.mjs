// QA D993: a form control's name must not live only in its placeholder.
//
// The MAR reason box and the eMAR scan fields had a placeholder and nothing
// else, so a screen reader announced an unnamed edit field (WCAG 1.3.1 /
// 4.1.2). A control is named when it has aria-label / aria-labelledby, a
// title (the accessible name of last resort), a <label for="its id"> (or a
// <label [for]="x"> beside an [id]="x") in the same template, or when it sits
// inside a <label>. Hidden, button-like and file inputs are skipped.

/** Offending controls (file:line and the tag) in one template's text. */
export function unlabelledControls(html, file = 'template') {
  const offenders = [];
  const at = (index) => `${file}:${html.slice(0, index).split('\n').length}`;
  const labelFor = new Set([...html.matchAll(/<label\b[^>]*\sfor="([^"]+)"/g)].map((m) => m[1]));
  const boundFor = new Set([...html.matchAll(/<label\b[^>]*\[for\]="([^"]+)"/g)].map((m) => m[1]));
  for (const m of html.matchAll(/<(input|textarea|select)\b([^>]*)>/g)) {
    const attrs = m[2];
    if (/\btype="(hidden|submit|button|reset|image|file)"/.test(attrs)) continue;
    if (/aria-label(ledby)?\b|\[ariaLabel\]/.test(attrs)) continue;
    if (/(^|\s)(title|\[title\]|\[attr\.title\])=/.test(attrs)) continue;
    const id = /\sid="([^"]+)"/.exec(attrs)?.[1];
    if (id && labelFor.has(id)) continue;
    const boundId = /\[id\]="([^"]+)"/.exec(attrs)?.[1];
    if (boundId && boundFor.has(boundId)) continue;
    // Inside an open <label> … </label> that has not closed before this tag.
    const before = html.slice(0, m.index);
    if (before.lastIndexOf('<label') > before.lastIndexOf('</label>')) continue;
    offenders.push(`${at(m.index)} <${m[1]}> has no label`);
  }
  return offenders;
}
