package com.example.hms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/**
 * QA D921 guard. {@code AuthController} runs with no persistence context
 * (open-in-view is off, and the controller is not transactional): every
 * repository call commits on its own and returns detached rows. The
 * activation link read {@code assignment.getRole()} on rows from
 * {@code findByUserId} (no fetch graph), threw, and left the account half
 * activated. A multi-row write belongs in a transactional service; a read of
 * an assignment's role or hospital here must come from a query that fetches
 * them ({@code findByUser}, {@code findAllDetailedByUserId}).
 */
class AuthControllerSessionlessReadGuardTest {

    private static final Path CONTROLLER =
        Paths.get("src/main/java/com/example/hms/controller/AuthController.java");

    /** How far after the call the rows are still being used (one loop or stream body). */
    private static final int WINDOW = 900;

    @Test
    void noLazyAssociationIsReadOnUnfetchedAssignments() throws IOException {
        String src = Files.readString(CONTROLLER, StandardCharsets.UTF_8).replace("\r\n", "\n");
        String call = "assignmentRepository.findByUserId(";
        for (int at = src.indexOf(call); at >= 0; at = src.indexOf(call, at + 1)) {
            String body = src.substring(at, Math.min(src.length(), at + WINDOW));
            // The use ends at the method's end.
            int end = body.indexOf("\n    }\n");
            if (end > 0) {
                body = body.substring(0, end);
            }
            assertThat(body).as("AuthController reads a LAZY association of findByUserId rows with no session "
                    + "(QA D921); move the work into a @Transactional service or use a fetching query")
                .doesNotContain(".getRole()")
                .doesNotContain(".getHospital()")
                .doesNotContain(".getUser().get");
        }
    }

    @Test
    void theActivationLinkDelegatesToTheTransactionalService() throws IOException {
        String src = Files.readString(CONTROLLER, StandardCharsets.UTF_8).replace("\r\n", "\n");
        int at = src.indexOf("public ResponseEntity<Object> verifyEmail(");
        assertThat(at).isPositive();
        String body = src.substring(at, src.indexOf("\n    }\n", at));
        assertThat(body).contains("userService.verifyEmail(")
            .doesNotContain("userRepository.save(")
            .doesNotContain("assignmentRepository.");
    }
}
