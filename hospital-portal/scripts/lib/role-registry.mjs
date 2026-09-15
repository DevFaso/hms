/**
 * The role vocabulary, read where it actually lives: the migrations.
 *
 * Every other enum domain names a Java enum. Roles are not an enum — they are
 * rows in `security.roles`, created by migrations and by the dev-profile
 * RoleSeeder, and a hospital admin can add more at runtime from the Roles
 * screen. So the gate reads the same sources `RoleRegistryTest` reads, for the
 * same reason: a role becomes real the moment a migration creates it, and
 * nothing else in the tree lists them all.
 *
 * TWO DELIBERATE DIFFERENCES from RoleRegistryTest:
 *
 *  1. **DELETEs are not subtracted.** V159 retires seven roles, but only
 *     `WHERE NOT EXISTS` a user still holds one — so on any environment where
 *     somebody held ROLE_MANAGER, the row is still there and the label still
 *     renders. And `audit_event_logs.role_name` keeps whatever string was
 *     stamped at the time forever, so a patient's "who viewed my records" page
 *     can show a retired role years after the row is gone. For i18n the union
 *     of everything ever seeded is the honest set; for guard coverage it is
 *     not, which is why that test subtracts and this does not.
 *  2. **Names come back bare** (`DOCTOR`, not `ROLE_DOCTOR`), because that is
 *     what the portal pipes. `security.roles.name` carries the prefix, the
 *     write-audit interceptor strips it, and `AuditEventLogServiceImpl` does
 *     not — so the column holds both spellings of one role. The portal
 *     normalises to the bare form at the service boundary
 *     (`bareRole` in patient-portal.service.ts) and the bundle keys that.
 *
 * Pure function on text, like {@link javaEnumConstants}, so the gate stays the
 * only thing that touches the filesystem.
 */

/** `-- ...` to end of line. V30 carries its rollback DELETE as a comment. */
const SQL_COMMENT = /--[^\n]*/g;
/** `//` and `/* *\/` in Java. */
const JAVA_LINE_COMMENT = /\/\/[^\n]*/g;
const JAVA_BLOCK_COMMENT = /\/\*[\s\S]*?\*\//g;

/** An `INSERT INTO "security".roles ...;` statement, comments already gone. */
const ROLES_INSERT = /INSERT\s+INTO\s+"?security"?\.roles\b[\s\S]*?;/gi;
/** A seeded name inside one of those, or inside RoleSeeder's catalog. */
const ROLE_LITERAL = /'(ROLE_[A-Z0-9_]+)'/g;
const SEEDER_PUT = /roles\.put\(\s*"(ROLE_[A-Z0-9_]+)"/g;

const ROLE_PREFIX = 'ROLE_';

/** Keep the newlines so a blanked comment cannot join two statements. */
const blank = (text) => text.replaceAll(/[^\n]/g, ' ');

/**
 * Bare role names from a set of `{ path, text }` sources, sorted and deduped.
 *
 * A `.sql` source contributes the literals inside its role INSERTs; a `.java`
 * source contributes its `roles.put("ROLE_X", …)` catalog. Anything else
 * contributes nothing — silently, because the gate validates the paths.
 */
export function roleNamesFrom(sources) {
  const names = new Set();
  for (const { path, text } of sources) {
    if (path.endsWith('.sql')) {
      const clean = text.replaceAll(SQL_COMMENT, blank);
      for (const [statement] of clean.matchAll(ROLES_INSERT)) {
        for (const [, name] of statement.matchAll(ROLE_LITERAL)) names.add(name);
      }
    } else if (path.endsWith('.java')) {
      const clean = text.replaceAll(JAVA_BLOCK_COMMENT, blank).replaceAll(JAVA_LINE_COMMENT, blank);
      for (const [, name] of clean.matchAll(SEEDER_PUT)) names.add(name);
    }
  }
  return [...names].map(bareRoleName).sort();
}

/** `ROLE_DOCTOR` -> `DOCTOR`; anything without the prefix is already bare. */
export const bareRoleName = (name) =>
  name.startsWith(ROLE_PREFIX) ? name.slice(ROLE_PREFIX.length) : name;
