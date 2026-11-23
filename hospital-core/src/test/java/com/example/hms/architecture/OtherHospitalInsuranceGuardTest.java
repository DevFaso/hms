package com.example.hms.architecture;

import com.example.hms.payload.dto.OtherHospitalInsuranceDTO;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Owner decision 2026-11-22 (replaces "cross-hospital insurance stays hidden", 2026-11-12): hospital B sees a policy
 * recorded at hospital A by its IDENTITY only, to verify. This pins the new contract:
 *
 * <ul>
 *   <li>the to-verify payload carries exactly the identity fields: never a claim, an amount, a ceiling, an approval,
 *       a pre-authorisation, an invoice, a note, a document, A's verification, A's plan or payer ids, or who at A
 *       entered it - a new field here is a deliberate change to this list;</li>
 *   <li>the read and the reuse reach another hospital's rows only through {@code RecordAccessPolicy}
 *       (the patient's opt-out, the source's EXPLICIT_CONSENT or SCHEMA isolation), and account the reach;</li>
 *   <li>the read never maps another hospital's row with the full coverage mapper.</li>
 * </ul>
 */
class OtherHospitalInsuranceGuardTest {

    private static final Path SERVICE =
        Path.of("src/main/java/com/example/hms/service/PatientInsuranceServiceImpl.java");

    @Test
    void theToVerifyPayloadCarriesTheIdentityOnly() {
        String[] fields = Arrays.stream(OtherHospitalInsuranceDTO.class.getDeclaredFields())
            .filter(f -> !Modifier.isStatic(f.getModifiers()))
            .map(Field::getName)
            .sorted()
            .toArray(String[]::new);
        assertThat(fields).as("another hospital's policy, shown to verify: identity fields only (owner 2026-11-22)")
            .containsExactly("agreementHere", "declaredOn", "effectiveDate", "expirationDate", "holderRelationship",
                "insurerName", "planName", "policyNumber", "sourceHospitalName", "sourceInsuranceId");
        for (String name : fields) {
            String lower = name.toLowerCase(Locale.ROOT);
            for (String forbidden : new String[] {"amount", "claim", "ceiling", "approv", "auth", "invoice", "note",
                "document", "verified", "linkedby", "payerplanid", "eligib"}) {
                assertThat(lower).as("never shown across hospitals: " + forbidden).doesNotContain(forbidden);
            }
        }
    }

    @Test
    void theReadAndTheReuseGoThroughRecordAccessPolicyAndAreAccounted() throws IOException {
        String source = Files.readString(SERVICE, StandardCharsets.UTF_8);
        for (String method : new String[] {"getOtherHospitalPolicies", "reuseOtherHospitalPolicy"}) {
            String body = methodBody(source, method);
            assertThat(body).as(method + " reaches another hospital only through RecordAccessPolicy")
                .contains("recordAccessPolicy.readableHospitalIds(");
            assertThat(body).as(method + " accounts the cross-hospital reach").contains("reachRecorder.recordReach(");
            assertThat(body).as(method + " is the desk's only").contains("requirePlanLinker(");
        }
        assertThat(methodBody(source, "getOtherHospitalPolicies"))
            .as("another hospital's row is never mapped with the full coverage mapper")
            .doesNotContain("patientInsuranceMapper");
    }

    private static String methodBody(String source, String name) {
        Pattern decl = Pattern.compile("(?m)^    (?:public|private|protected)[^(\\n=]*?\\b(\\w+)\\(");
        Matcher m = decl.matcher(source);
        int from = -1;
        while (m.find()) {
            if (from >= 0) {
                return source.substring(from, m.start());
            }
            if (name.equals(m.group(1))) {
                from = m.start();
            }
        }
        assertThat(from).as("method " + name + " found").isNotNegative();
        return source.substring(from);
    }
}
