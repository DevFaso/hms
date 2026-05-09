Adds v1.0 Clinical Safety CDS Hooks capabilities to the backend: new order-select and medication-prescribe services with RxNorm-first medication identification, plus an expansion of the existing order-sign medication safety service to include drug–drug interaction (DDI) cards. Also introduces schema support for RxNorm lookups and “tall-man” lettering, alongside unit/integration test coverage and discovery-contract assertions.

Changes:

Introduce RxNorm extraction + shared draft parsing to prefer canonical RxCUI in CDS Hooks payloads.
Add new CDS Hook services (order-select, medication-prescribe) and extend hms-medication-allergy-check to include DDI evaluation.
Add DB migration for RxNorm format enforcement/indexing and tall_man_name, plus tests and discovery assertions.
Reviewed changes
Copilot reviewed 18 out of 19 changed files in this pull request and generated 5 comments.

Show a summary per file
...-core/src/main/java/com/example/hms/cdshooks/service/MedicationPrescribeRulesCdsService.java
Comment on lines +8 to +12
import com.example.hms.cdshooks.rules.CdsRuleEngine;
import com.example.hms.cdshooks.service.MedicationDraftExtractor.ProposedMedication;
import com.example.hms.cdshooks.terminology.RxNormCodingExtractor;
import com.example.hms.model.Patient;
import com.example.hms.model.medication.MedicationCatalogItem;
Copilot commented 1 minute ago
Copilot
Copilot
AI
1 minute ago
RxNormCodingExtractor is imported but never referenced in this class (Javadoc links don’t count as a use), which will fail compilation due to an unused import. Remove the import or use it in code (e.g., if you intended to extract RxCUI directly here).

Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
hospital-core/src/main/resources/db/migration/V93__cds_rxnorm_bindings.sql
Comment on lines +1 to +6
-- V93: v1.0 / Clinical Safety / CDS Hooks expansion (roadmap row 2)
--
-- Strengthens the RxNorm binding on clinical.medication_catalog_items so the
-- new order-select / medication-prescribe CDS Hooks services can resolve a
-- catalog row from a FHIR CodeableConcept whose only typed coding is
-- {"system": "http://www.nlm.nih.gov/research/umls/rxnorm", "code": "..."}.
Copilot commented 1 minute ago
Copilot
Copilot
AI
1 minute ago
This migration file won’t be executed unless it is added to hospital-core/src/main/resources/db/migration/changelog.xml (the project uses an explicit Liquibase changelog, not include-all). Please add a changeSet entry referencing V93__cds_rxnorm_bindings.sql so the new constraint/index/column are applied in real deployments.

Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
hospital-core/src/main/java/com/example/hms/model/medication/MedicationCatalogItem.java
Comment on lines +95 to +103
    /**
     * Optional ISMP "tall-man" lettering for confusable drug pairs
     * (e.g. {@code "predniSONE"} vs {@code "prednisoLONE"}). When present,
     * the medication-prescribe CDS hook service surfaces this in card
     * detail to disambiguate at authoring time. Added in V93.
     */
    @Size(max = 200)
    @Column(name = "tall_man_name", length = 200)
    private String tallManName;
Copilot commented 1 minute ago
Copilot
Copilot
AI
1 minute ago
The new tallManName field is persisted on the entity, but it is not exposed via the existing medication-catalog REST API DTOs/mapper (e.g., MedicationCatalogItemRequestDTO/ResponseDTO and MedicationCatalogItemMapper). If this value is meant to be configured and viewed through the app (rather than only via direct DB writes), wire it through the DTOs + mapper and any validation so it can be managed like the other catalog fields.

Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
hospital-core/src/main/java/com/example/hms/cdshooks/service/CdsHookContext.java
            String s = entry.toString().trim();
            if (s.isEmpty()) continue;
            int slash = s.lastIndexOf('/');
            out.add(slash >= 0 ? s.substring(slash + 1) : s);
Copilot commented 1 minute ago
Copilot
Copilot
AI
1 minute ago
selectionIds() strips everything up to the last '/', but it will still add an empty string when a selection reference ends with a trailing slash (e.g., "MedicationRequest/"). Consider skipping entries where the post-slash identifier is blank so a malformed selection entry doesn’t accidentally filter out all drafts.

Suggested changeset 1 (1)
hospital-core/src/main/java/com/example/hms/cdshooks/service/CdsHookContext.java
Original file line number	Diff line number	Diff line change
             String s = entry.toString().trim();
             if (s.isEmpty()) continue;
             int slash = s.lastIndexOf('/');
             out.add(slash >= 0 ? s.substring(slash + 1) : s);
             String id = (slash >= 0 ? s.substring(slash + 1) : s).trim();
             if (id.isEmpty()) continue;
             out.add(id);
         }
         return List.copyOf(out);
     }
Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
...-core/src/main/java/com/example/hms/cdshooks/service/MedicationPrescribeRulesCdsService.java
Comment on lines +116 to +126
    private Optional<CdsCard> tallManAdvisory(UUID hospitalId, ProposedMedication proposed) {
        if (hospitalId == null) return Optional.empty();
        String code = proposed.code();
        if (code == null || code.isBlank()) return Optional.empty();
        Optional<MedicationCatalogItem> match =
            catalogRepository.findByHospitalIdAndCode(hospitalId, code);
        if (match.isEmpty()) {
            // RxNorm-keyed fallback so RxNorm-only payloads still get the advisory.
            List<MedicationCatalogItem> byRxnorm =
                catalogRepository.findActiveByHospitalIdAndRxnormCode(hospitalId, code);
            match = byRxnorm.isEmpty() ? Optional.empty() : Optional.of(byRxnorm.get(0));
Copilot commented 1 minute ago
Copilot
Copilot
AI
1 minute ago
In the tall-man advisory fallback, findActiveByHospitalIdAndRxnormCode(hospitalId, code) is called with proposed.code() even when that value is not an RxCUI (e.g., an ATC/local code when RxNorm isn’t present). To avoid unnecessary queries and accidental matches, gate the RxNorm lookup behind TerminologyCodes.isValidRxNorm(code.trim()) (similar to CdsRuleEngine.resolveCatalogItem) and pass a trimmed value.