/**
 * The shape of one entry in scripts/i18n-enum-domains.json.
 *
 * Separate from the gate so it can be unit-tested: the gate runs `main()` on
 * import (deliberately — a `import.meta.url === process.argv[1]` guard is
 * false under a symlinked directory and turns the whole gate into a silent
 * no-op), which makes anything left inside it untestable. The validator is the
 * part most worth testing, because every one of its branches is a way for a
 * domain to stop being checked.
 */

/**
 * Only these may appear in a declaration; anything else is a typo.
 *
 * There is deliberately no `group` override. EnumLabelPipe computes the group
 * as toUpperSnake(domain) with no way to redirect it, so an override here
 * could only ever point the gate at a group the pipe does not read — and
 * pointing it at a group that happens to hold the constants (the shared STATUS
 * pool, say) would turn the gate green while every value renders Title-Cased
 * English. A validated escape hatch is worse than none.
 */
export const DECLARATION_KEYS = ['enum', 'enums', 'reason'];

const filled = (value) => typeof value === 'string' && value.trim() !== '';
const javaPath = (value) => filled(value) && value.endsWith('.java');

/**
 * Push a message per problem and return false, or return true.
 *
 * Counting the declared keys is not enough. `{"enums": []}` declares exactly
 * one source and checks nothing, which made it cheaper than the `{}` this
 * function exists to reject; `{"enum": null}` used to die on an unhandled
 * TypeError downstream; and `{"enum": "…/Foo.txt"}` passed a check whose own
 * message promises a .java file, then failed later as MISSING ENUM FILE — a
 * misleading diagnosis, and a name that reaches `new RegExp` with a live `.`
 * in it.
 */
export function validateDeclaration(domain, entry, errors) {
  if (entry === null || typeof entry !== 'object' || Array.isArray(entry)) {
    errors.push(`BAD DECLARATION ${domain} — the value must be an object.`);
    return false;
  }
  const unknown = Object.keys(entry).filter((key) => !DECLARATION_KEYS.includes(key));
  if (unknown.length) {
    errors.push(
      `BAD DECLARATION ${domain} — unknown propert${unknown.length > 1 ? 'ies' : 'y'} ` +
        `${unknown.join(', ')}; expected one of ${DECLARATION_KEYS.join(', ')}.`,
    );
    return false;
  }
  const sources = ['enum', 'enums', 'reason'].filter((key) => key in entry);
  if (sources.length !== 1) {
    errors.push(
      `BAD DECLARATION ${domain} — declare exactly one of enum / enums / reason ` +
        `(found ${sources.length ? sources.join(' + ') : 'none'}).`,
    );
    return false;
  }
  if ('enum' in entry && !javaPath(entry.enum)) {
    errors.push(`BAD DECLARATION ${domain} — enum must be a path ending in .java.`);
    return false;
  }
  if (
    'enums' in entry &&
    (!Array.isArray(entry.enums) || entry.enums.length === 0 || !entry.enums.every(javaPath))
  ) {
    errors.push(`BAD DECLARATION ${domain} — enums must be a non-empty array of .java paths.`);
    return false;
  }
  if ('reason' in entry && !filled(entry.reason)) {
    errors.push(`BAD DECLARATION ${domain} — an exemption needs a reason someone can read.`);
    return false;
  }
  return true;
}

/** The enum's type name, from the path that declares it. */
export const enumNameOf = (path) =>
  path
    .split('/')
    .pop()
    .replace(/\.java$/, '');
