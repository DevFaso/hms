package com.example.hms.architecture;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.assertThat;

class SenderValidationMessageGuardTest {
    @Test
    void senderValidationDoesNotPresentLengthAsTheOnlyPossibleFailure() throws Exception {
        var found = new ArrayList<String>();
        try (var paths = Files.walk(Path.of("src/main/java"))) {
            for (var path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = Files.readString(path);
                if (code.contains("SENDER_NAME.matcher") && code.contains("(it is {} characters)")) found.add(path.toString());
            }
        }
        assertThat(found).as("X6: a combined character/length validation must explain the character rule").isEmpty();
        String orange = Files.readString(Path.of("src/main/java/com/example/hms/service/integration/OrangeSmsGatewayImpl.java"));
        assertThat(orange).contains("[A-Za-z0-9 ]{1,11}", "Hyphens and other symbols are not allowed", "traits d’union");
    }
}
