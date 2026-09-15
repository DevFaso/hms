/**
 * The role vocabulary, read where it actually lives: the migrations.
 *
 * Every other enum domain names a Java enum. Roles are not an enum — they are
 * rows in `security.roles`, created by migrations and by two seeders, and a
 * hospital admin can add more at runtime from the Roles screen. So the gate
 * reads the files that create them, for the reason `RoleRegistryTest` reads
 * the migrations: a role becomes real the moment one of them creates it, and
 * nothing else in the tree lists them all.
 *
 * NOT the same parse as RoleRegistryTest, and worth knowing how it differs —
 * two regexes over the same SQL with different blind spots is how a role goes
 * missing quietly:
 *
 *  1. **DELETEs are not subtracted.** V159 retires seven roles, but only
 *     `WHERE NOT EXISTS` a user still holds one — so on any environment where
 *     somebody held ROLE_MANAGER, the row is still there and the label still
 *     renders. And `audit_event_logs.role_name` keeps whatever string was
 *     stamped at the time forever, so a patient's "who viewed my records" page
 *     can show a retired role years after the row is gone. For i18n the union
 *     of everything ever seeded is the honest set; for guard coverage it is
 *     not, which is why that test subtracts and this does not.
 *  2. **The seeders count too.** RoleRegistryTest reads only the migration
 *     folder; this reads whatever `scripts/i18n-enum-domains.json` declares,
 *     which includes `RoleSeeder` and `DevSyntheticDataSeeder`.
 *  3. **Names come back bare** (`DOCTOR`, not `ROLE_DOCTOR`), because that is
 *     what the portal pipes. `security.roles.name` carries the prefix, the
 *     write-audit interceptor strips it, and `AuditEventLogServiceImpl` does
 *     not — so the column holds both spellings of one role. The portal
 *     normalises to the bare form at the service boundary (`bareRole` in
 *     patient-portal.service.ts) and the bundle keys that.
 *
 * Pure function on text, like {@link javaEnumConstants}, so the gate stays the
 * only thing that touches the filesystem.
 */

/**
 * An `INSERT INTO "security".roles ...;` statement.
 *
 * Either identifier may be quoted, the schema may be any schema or omitted
 * entirely under a set `search_path`, and the trailing `;` may be absent on
 * the last statement in a file. A spelling the parser misses is a role that
 * ships unkeyed and nothing notices, because only a grand total of zero is an
 * error and the other thirty names make that impossible.
 */
const ROLES_INSERT =
  /INSERT\s+INTO\s+(?:"?[A-Za-z_]\w*"?\s*\.\s*)?"?roles"?[\s(][\s\S]*?(?:;|$)/gi;
/** A seeded name inside one of those. */
const ROLE_LITERAL = /'(ROLE_[A-Z0-9_]+)'/g;
/**
 * Any `"ROLE_X"` literal in a declared Java seeder.
 *
 * Deliberately not tied to the shape of the call. The first version matched
 * `roles.put("ROLE_X"`, which couples the gate to a local variable being
 * named `roles`: rename it, switch to `Map.of(...)`, or use a `String[]` as
 * `DevSyntheticDataSeeder` does, and the source silently yields nothing. It
 * cannot even be caught by a total-is-zero check, because the migrations are
 * a superset today. `RoleRegistryTest` reads any Java string literal for the
 * same reason. Over-matching inside a file someone declared AS a seeder is
 * accepted, but it is NOT free: a parsed name the bundle has no key for is an
 * UNKEYED build failure, not a note (the note is the other direction — a key
 * no source emits). So a `hasAuthority("ROLE_FOO")` added to one of these two
 * files fails `npm run i18n:enums` until FOO is keyed. That is the trade: a
 * parser that cannot be silently defeated by a refactor, at the cost of
 * occasionally asking for a key nobody sends.
 */
const SEEDER_LITERAL = /"(ROLE_[A-Z0-9_]+)"/g;

const ROLE_PREFIX = 'ROLE_';

/** Extensions this module knows how to read. The gate rejects the rest. */
export const READABLE = ['.sql', '.java'];

const SYNTAX = {
  '.sql': { line: '--', block: ['/*', '*/'], quotes: "'", escapeByDoubling: true, dollar: true },
  '.java': { line: '//', block: ['/*', '*/'], quotes: '"\'', escapeByDoubling: false },
};

/**
 * `$$` or `$tag$`, anchored — Postgres dollar quoting.
 *
 * Twenty-one migrations in this repo already use it, and this repo has a rule
 * about it (`liquibase-do-block-split-statements`). One apostrophe inside a
 * dollar-quoted body — `patient's`, `l'hopital` — opened a string that
 * swallowed the rest of the file, so every role INSERT after it vanished.
 * Sticky rather than a slice, so a `$` that is not a quote costs one test.
 *
 * The body is SCANNED, not blanked — see `scanRange`. Blanking it would hide
 * the `DO $$ … INSERT … $$` conditional-seed idiom, which is the shape a
 * future migration is most likely to use.
 */
const DOLLAR_TAG = /\$(?:[A-Za-z_]\w*)?\$/y;

/**
 * Two views of one source, offsets preserved: `noComments` has comments
 * blanked, `scanned` has string CONTENTS blanked as well.
 *
 * Both are needed, and the reason is the bug this replaced. Statement
 * boundaries have to be found where a `;` inside a description cannot end a
 * statement early and a `--` inside one cannot start a comment — that is
 * `scanned`. But the role names live inside those very strings, so they have
 * to be read back out of `noComments` over the same offsets. Matching both on
 * one view loses either the boundaries or the names.
 *
 * `V2__seed_roles.sql` seeds twenty-four roles in a SINGLE statement, so one
 * semicolon in one description used to drop every role after it, silently, and
 * with the count still comfortably above any floor a test could assert.
 *
 * Same shape and the same reason as `blankCommentsAndStrings` in
 * java-enum.mjs, which the enum parser needed for a `;` inside a Javadoc.
 */
/** The dollar-quote tag opening at `i`, or null. */
function dollarTagAt(source, i) {
  DOLLAR_TAG.lastIndex = i;
  const match = DOLLAR_TAG.exec(source);
  return match ? match[0] : null;
}

export function sqlViews(source, ext) {
  const syntax = SYNTAX[ext];
  const noComments = source.split('');
  const scanned = source.split('');
  /** Ranges of dollar-quoted bodies, for roleNamesFrom to parse on their own. */
  const dollarBodies = [];
  const blank = (out, from, to) => {
    for (let k = from; k < to && k < out.length; k += 1) {
      if (out[k] !== '\n') out[k] = ' ';
    }
  };

  /**
   * One lexical scope, and a dollar-quoted body is NOT part of it.
   *
   * Three attempts, because the body has to be two contradictory things at
   * once and only one of them belongs in this view:
   *
   *  - scan it in the OUTER scope, and one apostrophe in a prose body —
   *    `COMMENT ON TABLE roles IS $$the patient's catalogue$$` — opens a
   *    string that swallows the rest of the file.
   *  - blank it, and `DO $$ … INSERT INTO "security".roles … $$` becomes
   *    invisible. That is the conditional-seed idiom, and twenty-one
   *    migrations here already use `DO $$`.
   *  - scan it as a NESTED scope, and its own punctuation survives into
   *    `scanned`, so a `;` in `('ROLE_B', $$runs the bench; signs$$)` ends the
   *    statement early and drops every role after it — the bug of two rounds
   *    ago, one level down.
   *
   * So: blanked HERE, where the only question is where statements end, and
   * returned as a range for {@link roleNamesFrom} to parse separately, where
   * the question is which roles a file seeds. Neither view has to compromise.
   */
  const scanRange = (from, to) => {
    let i = from;
    while (i < to) {
      const two = source.slice(i, i + 2);
      if (two === syntax.line) {
        let end = source.indexOf('\n', i);
        if (end === -1 || end > to) end = to;
        blank(noComments, i, end);
        blank(scanned, i, end);
        i = end;
      } else if (two === syntax.block[0]) {
        // Postgres nests block comments, so the first `*/` need not close the
        // one that opened. Ending there let a commented-out INSERT back out —
        // and an extra parsed name is an UNKEYED build failure, not a note.
        let depth = 1;
        let k = i + 2;
        while (k < to && depth > 0) {
          const pair = source.slice(k, k + 2);
          if (pair === syntax.block[0]) {
            depth += 1;
            k += 2;
          } else if (pair === syntax.block[1]) {
            depth -= 1;
            k += 2;
          } else {
            k += 1;
          }
        }
        const stop = Math.min(k, to);
        blank(noComments, i, stop);
        blank(scanned, i, stop);
        i = stop;
      } else if (syntax.dollar && source[i] === '$' && dollarTagAt(source, i)) {
        const tag = dollarTagAt(source, i);
        const end = source.indexOf(tag, i + tag.length);
        const close = end === -1 || end > to ? to : end;
        blank(scanned, i + tag.length, close);
        dollarBodies.push([i + tag.length, close]);
        i = close === to ? to : close + tag.length;
      } else if (syntax.quotes.includes(source[i])) {
        const quote = source[i];
        let k = i + 1;
        while (k < to) {
          if (!syntax.escapeByDoubling && source[k] === '\\') {
            k += 2;
            continue;
          }
          if (source[k] === quote) {
            // `'it''s'` is one SQL string, not two.
            if (syntax.escapeByDoubling && source[k + 1] === quote) {
              k += 2;
              continue;
            }
            break;
          }
          k += 1;
        }
        // Contents only: the quotes stay, so the token still reads as a literal.
        blank(scanned, i + 1, Math.min(k, to));
        i = Math.min(k + 1, to);
      } else {
        i += 1;
      }
    }
  };

  scanRange(0, source.length);
  return { noComments: noComments.join(''), scanned: scanned.join(''), dollarBodies };
}

/**
 * Bare role names from a set of `{ path, text }` sources, sorted and deduped.
 *
 * A `.sql` source contributes the literals inside its role INSERTs; a `.java`
 * source contributes every `"ROLE_X"` literal it names. Anything else
 * contributes nothing — which is why the gate refuses to declare a source it
 * cannot read, rather than letting it drop out here in silence.
 */
export function roleNamesFrom(sources) {
  const names = new Set();
  const addFrom = (text, ext) => {
    const { noComments, scanned, dollarBodies } = sqlViews(text, ext);
    if (ext === '.sql') {
      for (const match of scanned.matchAll(ROLES_INSERT)) {
        const statement = noComments.slice(match.index, match.index + match[0].length);
        for (const [, name] of statement.matchAll(ROLE_LITERAL)) names.add(bareRoleName(name));
      }
      // Each dollar-quoted body is its own little SQL file: a `DO $$ … $$`
      // block seeds roles, and a prose one seeds none. Parsing them here
      // rather than in the view is what lets the view blank them, so their
      // punctuation can never reach the statement boundaries around them.
      for (const [from, to] of dollarBodies) addFrom(text.slice(from, to), ext);
    } else {
      for (const [, name] of noComments.matchAll(SEEDER_LITERAL)) names.add(bareRoleName(name));
    }
  };
  for (const { path, text } of sources) {
    const ext = READABLE.find((e) => path.endsWith(e));
    if (ext) addFrom(text, ext);
  }
  return [...names].sort();
}

/** `ROLE_DOCTOR` -> `DOCTOR`; anything without the prefix is already bare. */
export const bareRoleName = (name) =>
  name.startsWith(ROLE_PREFIX) ? name.slice(ROLE_PREFIX.length) : name;
