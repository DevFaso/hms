package com.example.hms.architecture;

import com.example.hms.enums.LabTestDefinitionApprovalStatus;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import static org.assertj.core.api.Assertions.assertThat;

class CultureDefinitionWorkflowGuardTest {
    @Test
    void everyCultureStartsThroughTheFlaggedOrderGateAndEverySaveUsesCompletion() throws Exception {
        Path root = Path.of("src/main/java/com/example/hms");
        int constructors = 0;
        var direct = java.util.regex.Pattern.compile("\\bnew\\s+(?:com\\.example\\.hms\\.model\\.)?MicroCultureResult\\s*\\(");
        var builder = java.util.regex.Pattern.compile("\\bMicroCultureResult\\s*\\.\\s*builder\\s*\\(");
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = com.example.hms.testsupport.SourceScan.stripComments(Files.readString(path));
                assertThat(direct.matcher(source).find()).as("culture construction must use the gated builder: " + path).isFalse();
                assertThat(source).doesNotContain("import static com.example.hms.model.MicroCultureResult.builder");
                if (builder.matcher(source).find()) {
                    assertThat(root.relativize(path).toString().replace('\\', '/'))
                        .isEqualTo("service/impl/MicroCultureServiceImpl.java");
                    constructors += (int) builder.matcher(source).results().count();
                }
            }
        }
        assertThat(constructors).as("one culture creation path, gated before any entity is built").isEqualTo(1);
        String service = Files.readString(root.resolve("service/impl/MicroCultureServiceImpl.java"));
        int create = service.indexOf("public MicroCultureResponseDTO createCulture(");
        int open = service.indexOf('{', create);
        String body = service.substring(open, com.example.hms.testsupport.SourceScan.closingBrace(service, open));
        assertThat(body.indexOf("!order.getLabTestDefinition().isCulture()"))
            .isGreaterThanOrEqualTo(0).isLessThan(body.indexOf("MicroCultureResult.builder()"));
        assertThat(body).doesNotContain("getCategory()", "getTestCode()");
        assertThat(builder.matcher(body).results().count()).isEqualTo(1);
        assertThat(body.indexOf("labOrderRepository.findWithLockByIdAndHospitalId(order.getId(), hospitalId)"))
            .isGreaterThanOrEqualTo(0).isLessThan(body.indexOf("MicroCultureResult.builder()"));
        assertThat(body).contains("LabOrderLifecycle.statusAfterNewResult(committed)",
            "labOrderRepository.updateStatusFromInHospital(order.getId(), hospitalId, committed, target)");
        assertThat(body).contains("entityManager.refresh(order)");
        assertThat(service).contains("entityManager.refresh(lockedOrder)");
        assertThat(service.split("cultureRepository\\.save\\(", -1)).hasSize(2);
        assertThat(service).contains("statusAfterAllResultsReleased(committed)",
            "labOrderRepository.updateStatusFromInHospital(orderId, hospitalId, committed, target)",
            "findWithLockByIdAndHospitalId(orderId, hospitalId)", "!lockedOrder.getLabTestDefinition().isCulture()");

        String ordinaryRelease = Files.readString(root.resolve("service/LabResultServiceImpl.java"));
        assertThat(ordinaryRelease).as("ordinary results must also wait for cultures")
            .contains("labResultRepository.countPendingCulturesForOrder(orderId) == 0");
        assertThat(ordinaryRelease).contains("LabOrderCompletion.allReleased(results,", "!reflexDef.isOrderable()");
        assertThat(service).contains("LabOrderCompletion.allReleased(results, culturesFinished, true)",
            "LabDefinitionEligibility.canResultOrderedVersion(order.getLabTestDefinition())");
        assertThat(Files.readString(root.resolve("repository/LabResultRepository.java")))
            .contains("c.labOrder.labTestDefinition.culture = true");
        String orderWriter = Files.readString(root.resolve("service/LabOrderServiceImpl.java"));
        assertThat(orderWriter).contains("requireOrderableHere(testDefinition, hospital, base)", "!definition.isOrderable()");
        assertThat(Files.readString(root.resolve("service/impl/SuperAdminLabOrderServiceImpl.java")))
            .contains("labOrderService.createLabOrder(labOrderRequest, locale)");

        String catalogue = Files.readString(root.resolve("service/LabTestDefinitionServiceImpl.java"));
        assertThat(catalogue).as("a new draft preserves the flag and active catalogue changes stay governed")
            .contains(".culture(current.isCulture())", "dto.getCulture() != null && dto.getCulture() != definition.isCulture()");
        String mapper = Files.readString(root.resolve("mapper/LabTestDefinitionMapper.java"));
        assertThat(mapper).as("old clients cannot clear an existing culture setting by omitting it")
            .contains(".culture(Boolean.TRUE.equals(dto.getCulture()))", "if (dto.getCulture() != null)",
                "entity.setCulture(dto.getCulture())", ".culture(entity.isCulture())");
    }

    @Test
    void cultureBackfillNeverInfersEligibilityFromCategoryOrAmbiguousCodes() throws Exception {
        String migration = com.example.hms.testsupport.SourceScan.stripComments(Files.readString(
            Path.of("src/main/resources/db/migration/V394__lab_culture_definition_flag.sql")));
        assertThat(migration).doesNotContain("category", "'HEMO'");
        assertThat(migration).contains("'ECBU'", "'COPROCULTURE'", "'TWP-ECBU'", "antibiogram(me)?s?");
    }

    @Test
    void everyCatalogueStatusHasAnActionInEveryLanguage() throws Exception {
        for (String language : List.of("", "_en", "_fr", "_es")) {
            Properties messages = new Properties();
            try (var reader = Files.newBufferedReader(Path.of("src/main/resources/i18n/messages-lab" + language + ".properties"))) {
                messages.load(reader);
            }
            for (LabTestDefinitionApprovalStatus status : LabTestDefinitionApprovalStatus.values()) {
                assertThat(messages.getProperty("lab.definition.nextAction." + status.name())).as(language + status).isNotBlank();
            }
            assertThat(messages.getProperty("micro.culture.cultureOrderRequired")).isNotBlank();
            assertThat(messages.getProperty("micro.culture.orderDefinitionUnapproved")).isNotBlank();
        }
        String service = Files.readString(Path.of("src/main/java/com/example/hms/service/LabTestDefinitionServiceImpl.java"));
        assertThat(service).contains("lab.definition.nextAction.").doesNotContain("allowed.stream().map(LabTestDefinitionServiceImpl::statusLabel)");
    }
}
