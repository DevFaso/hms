package com.example.hms.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E9 #68 — the guards and the role registry agree.
 *
 * <p>Authorities are roles and nothing else: {@link RoleExpansion} builds them
 * from the token's role claim, so a {@code hasAuthority('VIEW_X')} clause can
 * never match anything and a {@code 'ROLE_X'} nobody seeds can never be held.
 * Both drifted for years (106 dead permission tokens, six roles referenced by
 * guards that no migration created) because nothing read the annotations
 * against the migrations. This does.
 *
 * <p>The seeded set is parsed from the migration SQL itself — every
 * {@code INSERT INTO "security".roles} minus every
 * {@code DELETE FROM "security".roles} — so a role becomes "real" the moment a
 * migration creates it and stops being real the moment one retires it.
 */
class RoleRegistryTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");
    private static final Path MIGRATIONS = Paths.get("src/main/resources/db/migration");

    private static final Pattern PRE_AUTHORIZE =
        Pattern.compile("@PreAuthorize\\(((?:\"[^\"]*\"\\s*\\+?\\s*|[A-Za-z_.]+\\s*\\+?\\s*)+)\\)");
    private static final Pattern QUOTED = Pattern.compile("'([A-Z_]+)'");
    private static final Pattern ROLE_CALL = Pattern.compile("has(?:Any)?Role\\(([^)]*)\\)");
    private static final Pattern ROLE_LITERAL = Pattern.compile("'(ROLE_[A-Z_]+)'");
    private static final Pattern JAVA_ROLE_LITERAL = Pattern.compile("\"(ROLE_[A-Z_]+)\"");
    private static final Pattern ROLES_INSERT =
        Pattern.compile("INSERT INTO \"?security\"?\\.roles\\b.*?;", Pattern.DOTALL);
    private static final Pattern ROLES_DELETE =
        Pattern.compile("DELETE FROM \"?security\"?\\.roles\\b.*?;", Pattern.DOTALL);
    /** RoleValidator names roles without the prefix, inside these two calls. */
    private static final Pattern VALIDATOR_CODES =
        Pattern.compile("(?:hasAnyAuthority|hasAnyCode)\\(([^)]*)\\)");
    private static final Pattern JAVA_QUOTED = Pattern.compile("\"([A-Z_]+)\"");

    /** Granted by ApiKeyAuthenticationFilter to partner API keys, never held by a user. */
    private static final Set<String> SYNTHETIC_ROLES = Set.of("ROLE_PARTNER_API");

    /** {@code -- ...} to end of line: V30 carries its rollback DELETE as a comment. */
    private static final Pattern SQL_LINE_COMMENT = Pattern.compile("--[^\n]*");

    /**
     * Every role a migration inserts minus every role a migration deletes. A
     * role deleted and later re-seeded would need replay order instead of set
     * difference; none does today, and the sanity test below would notice.
     */
    static Set<String> seededRoles() throws IOException {
        Set<String> seeded = new TreeSet<>();
        Set<String> retired = new TreeSet<>();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".sql")).toList()) {
                String sql = SQL_LINE_COMMENT.matcher(Files.readString(p, StandardCharsets.UTF_8)).replaceAll("");
                collect(ROLES_INSERT.matcher(sql), seeded);
                collect(ROLES_DELETE.matcher(sql), retired);
            }
        }
        seeded.removeAll(retired);
        return seeded;
    }

    private static void collect(Matcher blocks, Set<String> into) {
        while (blocks.find()) {
            Matcher lit = ROLE_LITERAL.matcher(blocks.group());
            while (lit.find()) {
                into.add(lit.group(1));
            }
        }
    }

    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> walk = Files.walk(MAIN)) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    /** role token → first file that names it, from every guard, the constants and the validator. */
    static Map<String, String> referencedRoles() throws IOException {
        Map<String, String> refs = new TreeMap<>();
        for (Path p : mainSources()) {
            String src = Files.readString(p, StandardCharsets.UTF_8);
            String where = p.getFileName().toString();
            Matcher ann = PRE_AUTHORIZE.matcher(src);
            while (ann.find()) {
                String body = ann.group(1);
                Matcher call = ROLE_CALL.matcher(body);
                while (call.find()) {
                    Matcher tok = QUOTED.matcher(call.group(1));
                    while (tok.find()) {
                        refs.putIfAbsent("ROLE_" + tok.group(1), where);
                    }
                }
                Matcher tok = QUOTED.matcher(ROLE_CALL.matcher(body).replaceAll(""));
                while (tok.find()) {
                    if (tok.group(1).startsWith("ROLE_")) {
                        refs.putIfAbsent(tok.group(1), where);
                    }
                }
            }
            if (where.equals("SecurityConstants.java")) {
                Matcher lit = JAVA_ROLE_LITERAL.matcher(src);
                while (lit.find()) {
                    refs.putIfAbsent(lit.group(1), where);
                }
            }
            if (where.equals("RoleValidator.java")) {
                Matcher call = VALIDATOR_CODES.matcher(src);
                while (call.find()) {
                    Matcher tok = JAVA_QUOTED.matcher(call.group(1));
                    while (tok.find()) {
                        String code = tok.group(1);
                        refs.putIfAbsent(code.startsWith("ROLE_") ? code : "ROLE_" + code, where);
                    }
                }
            }
        }
        return refs;
    }

    @Test
    @DisplayName("the registry parse sees V159: ROLE_STAFF seeded, ROLE_USER retired")
    void registryParseReflectsTheMigrations() throws IOException {
        Set<String> seeded = seededRoles();
        assertThat(seeded).contains("ROLE_STAFF", "ROLE_DOCTOR", "ROLE_PATIENT", "ROLE_SUPER_ADMIN");
        assertThat(seeded).doesNotContain("ROLE_USER", "ROLE_MODERATOR", "ROLE_TECHNICIAN", "ROLE_CLEANER",
            "ROLE_SECURITY", "ROLE_SUPPORT", "ROLE_MANAGER");
    }

    @Test
    @DisplayName("no @PreAuthorize names a permission token: authorities are roles only")
    void guardsCarryNoPermissionTokens() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path p : mainSources()) {
            String src = Files.readString(p, StandardCharsets.UTF_8);
            Matcher ann = PRE_AUTHORIZE.matcher(src);
            while (ann.find()) {
                String outsideRoleCalls = ROLE_CALL.matcher(ann.group(1)).replaceAll("");
                Matcher tok = QUOTED.matcher(outsideRoleCalls);
                while (tok.find()) {
                    if (!tok.group(1).startsWith("ROLE_")) {
                        int line = src.substring(0, ann.start()).split("\n", -1).length;
                        offenders.add(p.getFileName() + ":" + line + " '" + tok.group(1) + "'");
                    }
                }
            }
        }
        assertThat(offenders)
            .as("RoleExpansion grants roles only; a permission token in a guard can never match")
            .isEmpty();
    }

    @Test
    @DisplayName("every role a guard, matcher constant or validator names is seeded by a migration")
    void everyReferencedRoleIsSeeded() throws IOException {
        Set<String> seeded = seededRoles();
        Map<String, String> phantoms = new TreeMap<>(referencedRoles());
        phantoms.keySet().removeAll(seeded);
        phantoms.keySet().removeAll(SYNTHETIC_ROLES);
        assertThat(phantoms)
            .as("roles named in code that no migration creates (a user can never hold them)")
            .isEmpty();
    }
}
