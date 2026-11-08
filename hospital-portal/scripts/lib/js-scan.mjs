/**
 * The shared TypeScript/JavaScript source walker for the regression gates —
 * the portal counterpart of hospital-core's testsupport/SourceScan: one place
 * that knows where a string literal or a comment starts and ends, so every
 * scanner steps over them the same way (an apostrophe in
 * `// the patient's amount` is not the start of a string, and a `)` inside
 * `'a (b'` closes nothing).
 */

/**
 * When `source[i]` opens a string literal (quoted or template) or a comment
 * (`//` or `/* … *\/`), the index of its last character; otherwise -1.
 */
export function endOfLiteralOrComment(source, i) {
  const ch = source[i];
  if (ch === "'" || ch === '"' || ch === '`') {
    let j = i + 1;
    for (; j < source.length && source[j] !== ch; j++) {
      if (source[j] === '\\') j++;
    }
    return j;
  }
  if (ch === '/' && source[i + 1] === '/') {
    const nl = source.indexOf('\n', i);
    return nl < 0 ? source.length - 1 : nl;
  }
  if (ch === '/' && source[i + 1] === '*') {
    const close = source.indexOf('*/', i + 2);
    return close < 0 ? source.length - 1 : close + 1;
  }
  return -1;
}

/**
 * The balanced `opener … closer` group that starts at `open` (`(` → `)`,
 * `{` → `}`), strings and comments stepped over; the rest of the source when
 * it never closes.
 */
export function balanced(source, open, opener = '(', closer = ')') {
  let depth = 0;
  for (let i = open; i < source.length; i++) {
    const skipped = endOfLiteralOrComment(source, i);
    if (skipped >= 0) {
      i = skipped;
      continue;
    }
    const ch = source[i];
    if (ch === opener) depth++;
    else if (ch === closer && --depth === 0) return source.slice(open, i + 1);
  }
  return source.slice(open);
}
