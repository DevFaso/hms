package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D4225 (round 81), the guard of the class: a department or a member of staff named by id in a front-desk
 * request is read through the helper that holds it to the acting hospital ({@code departmentOfHospital},
 * {@code clinicianOfHospital}), never straight from its repository by id.
 */
@DisplayName("Front desk: a department or clinician named in a request belongs to the acting hospital")
class DeskScopedReferenceGuardTest {

    private static final Path SERVICE =
        Path.of("src/main/java/com/example/hms/service/ReceptionServiceImpl.java");

    private static final Pattern BY_ID = Pattern.compile("(departmentRepo|staffRepo)\\.(findById|getReferenceById|getById)\\(");

    @Test
    void theDeskNeverLoadsADepartmentOrAClinicianByIdOutsideTheScopedHelpers() throws IOException {
        String code = SourceScan.stripComments(Files.readString(SERVICE, StandardCharsets.UTF_8));
        String outside = code;
        for (String helper : new String[] {"departmentOfHospital", "clinicianOfHospital"}) {
            String body = SourceScan.methodBody(code, helper);
            assertThat(body).as("the helper %s exists and filters on the hospital", helper)
                .contains("getHospital()").contains("hospitalId.equals(");
            outside = outside.replace(body, "");
        }
        Matcher matcher = BY_ID.matcher(outside);
        assertThat(matcher.find())
            .as("ReceptionServiceImpl reads a department or a clinician by id outside departmentOfHospital / "
                + "clinicianOfHospital: hold it to the acting hospital there")
            .isFalse();
    }
}
