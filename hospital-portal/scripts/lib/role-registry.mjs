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
 * safe: an extra name is a keyed label nothing sends, which the coverage gate
 * reports as a note.
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
  const blank = (out, from, to) => {
    for (let k = from; k < to && k < out.length; k += 1) {
      if (out[k] !== '\n') out[k] = ' ';
    }
  };
  let i = 0;
  while (i < source.length) {
    const two = source.slice(i, i + 2);
    if (two === syntax.line) {
      let end = source.indexOf('\n', i);
      if (end === -1) end = source.length;
      blank(noComments, i, end);
      blank(scanned, i, end);
      i = end;
    } else if (two === syntax.block[0]) {
      const end = source.indexOf(syntax.block[1], i + 2);
      const stop = end === -1 ? source.length : end + 2;
      blank(noComments, i, stop);
      blank(scanned, i, stop);
      i = stop;
    } else if (syntax.dollar && source[i] === '$' && dollarTagAt(source, i)) {
      const tag = dollarTagAt(source, i);
      const end = source.indexOf(tag, i + tag.length);
      const close = end === -1 ? source.length : end;
      // The body is opaque: it may hold quotes, semicolons and `--` that mean
      // nothing to the statement around it.
      blank(scanned, i + tag.length, close);
      i = end === -1 ? source.length : end + tag.length;
    } else if (syntax.quotes.includes(source[i])) {
      const quote = source[i];
      let k = i + 1;
      while (k < source.length) {
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
      blank(scanned, i + 1, k);
      i = k + 1;
    } else {
      i += 1;
    }
  }
  return { noComments: noComments.join(''), scanned: scanned.join('') };
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
  for (const { path, text } of sources) {
    const ext = READABLE.find((e) => path.endsWith(e));
    if (!ext) continue;
    const { noComments, scanned } = sqlViews(text, ext);
    if (ext === '.sql') {
      for (const match of scanned.matchAll(ROLES_INSERT)) {
        const statement = noComments.slice(match.index, match.index + match[0].length);
        for (const [, name] of statement.matchAll(ROLE_LITERAL)) names.add(name);
      }
    } else {
      for (const [, name] of noComments.matchAll(SEEDER_LITERAL)) names.add(name);
    }
  }
  return [...names].map(bareRoleName).sort();
}

/** `ROLE_DOCTOR` -> `DOCTOR`; anything without the prefix is already bare. */
export const bareRoleName = (name) =>
  name.startsWith(ROLE_PREFIX) ? name.slice(ROLE_PREFIX.length) : name;
