package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.assertThat;

class ClinicalActorDisplayGuardTest {
    private static final Path MAIN = Path.of("src/main/java");
    private static final Pattern NAME_WRITE = Pattern.compile("\\.(?:set)?\\w*ByName\\(");

    private static List<String> usernameWrites(String code) {
        var problems = new ArrayList<String>();
        var matcher = NAME_WRITE.matcher(code);
        while (matcher.find()) {
            int depth = 1;
            int end = matcher.end();
            for (; end < code.length() && depth > 0; end++) {
                if (code.charAt(end) == '(') depth++;
                if (code.charAt(end) == ')') depth--;
            }
            String value = code.substring(matcher.end(), end);
            if ((value.contains("currentUsername()") || value.contains("getUsername()"))
                && !value.contains("actorNames.display(") && !value.contains("ClinicalActorNames.display(")) {
                problems.add(matcher.group() + " line " + SourceScan.lineOf(code, matcher.start()));
            }
        }
        return problems;
    }

    @Test
    void allClinicalActorNameWritesResolveHumanNames() throws Exception {
        var problems = new ArrayList<String>();
        try (var paths = Files.walk(MAIN)) {
            for (var path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                for (var finding : usernameWrites(SourceScan.stripComments(Files.readString(path)))) {
                    problems.add(path + ": " + finding);
                }
            }
        }
        assertThat(problems).as("D1520: display-name fields must not copy login identifiers").isEmpty();
        for (var file : List.of("service/impl/DeathDisputeServiceImpl.java", "service/impl/OrderReviewHoldServiceImpl.java", "mapper/MortalityMapper.java")) {
            String code = Files.readString(MAIN.resolve("com/example/hms").resolve(file));
            assertThat(code).contains("actorNames.display(");
            assertThat(code).as(file + " batches list actor lookups").contains("actorNames.batch(");
        }
        String mortality = Files.readString(MAIN.resolve("com/example/hms/service/impl/MortalityServiceImpl.java"));
        assertThat(mortality).contains("setEnteredInErrorByName(actorNames.display(actorId, actorName))", "mapper.toDtos(all)");
        String disputes = Files.readString(MAIN.resolve("com/example/hms/service/impl/DeathDisputeServiceImpl.java"));
        assertThat(disputes).doesNotContain("actorId, currentUsername(), reason");
        assertThat(Files.readString(MAIN.resolve("com/example/hms/service/support/ClinicalActorNames.java")))
            .contains("users.findClinicalActorNames(unique)");
    }

    @Test
    void scanCatchesEveryOriginalWriterShapeWithoutRejectingAuditOrResolvedFallbacks() {
        assertThat(usernameWrites("x.disputedByName(currentUsername());")).hasSize(1);
        assertThat(usernameWrites("x.setDecidedByName(currentUsername());")).hasSize(1);
        assertThat(usernameWrites("x.recordedByName(user == null ? null : user.getUsername());")).hasSize(1);
        assertThat(usernameWrites("x.disputedByName(actorNames.display(id, currentUsername()));")).isEmpty();
        assertThat(usernameWrites("audit.userName(currentUsername());")).isEmpty();
    }
}
