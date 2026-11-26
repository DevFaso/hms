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
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = com.example.hms.testsupport.SourceScan.stripComments(Files.readString(path));
                if (source.contains("MicroCultureResult.builder(") || source.contains("new MicroCultureResult(")) {
                    assertThat(root.relativize(path).toString().replace('\\', '/'))
                        .isEqualTo("service/impl/MicroCultureServiceImpl.java");
                    constructors += source.split("MicroCultureResult\\.builder\\(", -1).length - 1;
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
        assertThat(service.split("cultureRepository\\.save\\(", -1)).hasSize(2);
        assertThat(service).contains("statusAfterAllResultsReleased(committed)",
            "labOrderRepository.updateStatusFrom(orderId, committed, target)");

        String catalogue = Files.readString(root.resolve("service/LabTestDefinitionServiceImpl.java"));
        assertThat(catalogue).as("a new draft preserves the flag and active catalogue changes stay governed")
            .contains(".culture(current.isCulture())", "dto.getCulture() != null && dto.getCulture() != definition.isCulture()");
        String mapper = Files.readString(root.resolve("mapper/LabTestDefinitionMapper.java"));
        assertThat(mapper).as("old clients cannot clear an existing culture setting by omitting it")
            .contains(".culture(Boolean.TRUE.equals(dto.getCulture()))", "if (dto.getCulture() != null)",
                "entity.setCulture(dto.getCulture())", ".culture(entity.isCulture())");
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
        }
        String service = Files.readString(Path.of("src/main/java/com/example/hms/service/LabTestDefinitionServiceImpl.java"));
        assertThat(service).contains("lab.definition.nextAction.").doesNotContain("allowed.stream().map(LabTestDefinitionServiceImpl::statusLabel)");
    }
}
