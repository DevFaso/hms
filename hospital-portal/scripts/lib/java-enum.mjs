/**
 * Just enough Java to read an enum's constant list.
 *
 * Lives here rather than in check-i18n-enum-coverage.mjs so that gate can call
 * `main()` unconditionally: a `import.meta.url === process.argv[1]` guard is
 * false under a symlinked directory or a drive-letter case difference, and a
 * gate that prints nothing and exits 0 is worse than no gate at all.
 */

/** Replace every comment and string literal with spaces, preserving offsets. */
export function blankCommentsAndStrings(source) {
  // split('') gives UTF-16 units, which is what every offset below is measured
  // in. `[...source]` splits by CODE POINT, so a single astral character (an
  // emoji in a comment) shifted the mapping by one and silently dropped the
  // next constant — the same quiet false-negative the blanking exists to stop.
  const out = source.split('');
  let i = 0;
  const blank = (from, to) => {
    for (let k = from; k < to && k < out.length; k += 1) {
      if (out[k] !== '\n') out[k] = ' ';
    }
  };
  while (i < source.length) {
    const two = source.slice(i, i + 2);
    if (two === '/*') {
      const end = source.indexOf('*/', i + 2);
      const stop = end === -1 ? source.length : end + 2;
      blank(i, stop);
      i = stop;
    } else if (two === '//') {
      let end = source.indexOf('\n', i);
      if (end === -1) end = source.length;
      blank(i, end);
      i = end;
    } else if (source[i] === '"' || source[i] === "'") {
      const quote = source[i];
      let k = i + 1;
      while (k < source.length && source[k] !== quote) k += source[k] === '\\' ? 2 : 1;
      blank(i, k + 1);
      i = k + 1;
    } else {
      i += 1;
    }
  }
  return out.join('');
}

/**
 * The constants of a Java enum: the tokens before the `;` that ends the
 * constant list (or before the closing brace when there is no body).
 *
 * Comments AND string literals are blanked first, and for the same reason. The
 * original parser split the raw text and ended `PrescriptionStatus` at the
 * semicolon inside "Medication not in stock; awaiting restock", silently
 * hiding seven partner-pharmacy statuses — it reported them as values the enum
 * "cannot emit" and exited 0. A `;` inside a constant's own argument, as in
 * `A("x;y")`, does exactly the same thing.
 *
 * Constants are read as tokens rather than line by line, so an annotated
 * constant (`@Deprecated A`), several on one line (`A, B, C`), and one that
 * carries a class body (`A { void f() {} },`) are all found.
 *
 * Returns null when the file has no such enum, [] when it has one with no
 * constants — the caller must tell those apart.
 */
export function javaEnumConstants(source, name) {
  // Match the declaration on the BLANKED copy: a comment that quotes the
  // declaration ("see enum AuditEventType {...}") would otherwise win the
  // match and send every offset below into a blanked region.
  const blanked = blankCommentsAndStrings(source);
  const decl = new RegExp(`enum\\s+${name}\\s*(?:implements[^{]*)?\\{`).exec(blanked);
  if (!decl) return null;

  const open = decl.index + decl[0].length - 1;

  // Walk to the enum's own closing brace, so a brace inside a comment or
  // string cannot end it early.
  let depth = 0;
  let close = open;
  for (; close < blanked.length; close += 1) {
    if (blanked[close] === '{') depth += 1;
    else if (blanked[close] === '}') {
      depth -= 1;
      if (depth === 0) break;
    }
  }

  // The constant list ends at the first `;` at the enum's own brace depth —
  // one nested inside a constant's class body does not end it.
  const body = blanked.slice(open + 1, close);
  let end = body.length;
  depth = 0;
  for (let i = 0; i < body.length; i += 1) {
    if (body[i] === '{' || body[i] === '(') depth += 1;
    else if (body[i] === '}' || body[i] === ')') depth -= 1;
    else if (body[i] === ';' && depth === 0) {
      end = i;
      break;
    }
  }

  // In the constant list, a name is a bare token that is not part of an
  // annotation and not inside a constant's arguments or class body.
  const names = [];
  depth = 0;
  for (const match of body.slice(0, end).matchAll(/@?[A-Za-z_$][\w$]*|[(){}]/g)) {
    const token = match[0];
    if ('({'.includes(token)) depth += 1;
    else if (')}'.includes(token)) depth -= 1;
    else if (depth === 0 && !token.startsWith('@') && /^[A-Z][A-Z0-9_]*$/.test(token)) {
      names.push(token);
    }
  }
  return names;
}

/**
 * camelCase domain -> UPPER_SNAKE group.
 *
 * MUST stay identical to EnumLabelPipe.toUpperSnake (enum-label.pipe.ts), or
 * the gate verifies a group the pipe never reads. An underscore goes at a
 * lower/digit-to-upper boundary only: `patientMRN` is PATIENT_MRN to the pipe,
 * and splitting before every capital would have the gate checking
 * PATIENT_M_R_N and passing against a group nothing renders.
 */
export const groupOf = (domain) => domain.replaceAll(/([a-z0-9])([A-Z])/g, '$1_$2').toUpperCase();
