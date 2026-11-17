package com.example.hms.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.hms.enums.UltrasoundFindingCategory;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.mapper.UltrasoundMapper;
import com.example.hms.model.UltrasoundReport;
import com.example.hms.payload.dto.ultrasound.UltrasoundReportRequestDTO;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * QA D1525: a finding is charted only when the clinician states it. The one
 * empty-report rule is {@link UltrasoundServiceImpl#requireReportFindings}
 * (QA D1150, on {@code ClinicalContent}); since D1525 nothing defaults an
 * observation, so a stated one (true or false) is a finding.
 */
class UltrasoundReportFindingsTest {

    private static UltrasoundReportRequestDTO.UltrasoundReportRequestDTOBuilder<?, ?> base() {
        return UltrasoundReportRequestDTO.builder().scanDate(LocalDate.now());
    }

    private static boolean stated(UltrasoundReportRequestDTO request) {
        try {
            UltrasoundServiceImpl.requireReportFindings(request);
            return true;
        } catch (FieldBusinessException refused) {
            return false;
        }
    }

    @Test
    void everyObservationNotAssessedAndNothingElseIsAnEmptyReport() {
        UltrasoundReportRequestDTO blank = base()
                .fetalCardiacActivity(null).fetalMovementObserved(null).fetalToneNormal(null)
                .anomaliesDetected(null).anatomySurveyComplete(false).dueDateConfirmed(false)
                .findingCategory(UltrasoundFindingCategory.NORMAL)
                .findingsSummary("   ")
                .build();

        assertThat(stated(blank)).isFalse();
        assertThatThrownBy(() -> UltrasoundServiceImpl.requireReportFindings(blank))
                .isInstanceOfSatisfying(FieldBusinessException.class, ex -> {
                    assertThat(ex.getField()).isEqualTo("findingsSummary");
                    assertThat(ex.getMessageKey()).isEqualTo("ultrasound.report.findingsRequired");
                });
    }

    @Test
    void aStatedObservationIsAFindingWhetherTrueOrFalse() {
        assertThat(stated(base().fetalCardiacActivity(false).build())).as("cardiac activity absent").isTrue();
        assertThat(stated(base().fetalCardiacActivity(true).build())).as("cardiac activity present").isTrue();
        assertThat(stated(base().fetalMovementObserved(false).build())).as("no movement").isTrue();
        assertThat(stated(base().fetalToneNormal(false).build())).as("abnormal tone").isTrue();
        assertThat(stated(base().anomaliesDetected(false).build())).as("no anomalies, stated").isTrue();
        assertThat(stated(base().nasalBonePresent(false).build())).as("nasal bone absent").isTrue();
        assertThat(stated(base().numberOfFetuses(1).build())).as("one fetus, counted").isTrue();
        assertThat(stated(base().numberOfFetuses(0).build())).as("no fetus seen").isTrue();
    }

    @Test
    void aMeasurementOrAWrittenFindingIsAStatement() {
        assertThat(stated(base().fetalHeartRate(150).build())).isTrue();
        assertThat(stated(base().crownRumpLengthMm(55.0).build())).isTrue();
        assertThat(stated(base().interpretation("No cardiac activity").build())).isTrue();
        assertThat(stated(base().gestationalAgeAtScan(12).build())).isTrue();
    }

    @Test
    void theCategoryIsAskedForNotAssumed() {
        assertThatThrownBy(() -> UltrasoundServiceImpl.requireFindingCategory(base().build()))
                .isInstanceOfSatisfying(FieldBusinessException.class,
                        ex -> assertThat(ex.getField()).isEqualTo("findingCategory"));
        assertThatCode(() -> UltrasoundServiceImpl.requireFindingCategory(
                base().findingCategory(UltrasoundFindingCategory.VARIANT).build())).doesNotThrowAnyException();
    }

    @Test
    void normalMayNotContradictAStatedObservation() {
        for (UltrasoundReportRequestDTO r : new UltrasoundReportRequestDTO[] {
                base().fetalCardiacActivity(false).findingCategory(UltrasoundFindingCategory.NORMAL).build(),
                base().fetalToneNormal(false).findingCategory(UltrasoundFindingCategory.NORMAL).build(),
                base().anomaliesDetected(true).findingCategory(UltrasoundFindingCategory.NORMAL).build()}) {
            assertThatThrownBy(() -> UltrasoundServiceImpl.requireCategoryMatchesObservations(r))
                    .isInstanceOfSatisfying(FieldBusinessException.class, ex -> {
                        assertThat(ex.getField()).isEqualTo("findingCategory");
                        assertThat(ex.getMessageKey()).isEqualTo("ultrasound.report.normalContradictsObservation");
                    });
        }
        assertThatCode(() -> UltrasoundServiceImpl.requireCategoryMatchesObservations(
                base().fetalCardiacActivity(false).findingCategory(UltrasoundFindingCategory.URGENT).build()))
                .doesNotThrowAnyException();
        assertThatCode(() -> UltrasoundServiceImpl.requireCategoryMatchesObservations(
                base().fetalCardiacActivity(true).fetalToneNormal(true).anomaliesDetected(false)
                        .findingCategory(UltrasoundFindingCategory.NORMAL).build()))
                .doesNotThrowAnyException();
    }

    @Test
    void theObservationsStayNotAssessedWhenNobodyStatesThem() {
        UltrasoundReportRequestDTO request = base().fetalHeartRate(150)
                .findingCategory(UltrasoundFindingCategory.NORMAL).build();

        UltrasoundReport created = new UltrasoundMapper().toReportEntity(request, null, null);

        assertThat(created.getFetalCardiacActivity()).as("not assessed").isNull();
        assertThat(created.getFetalMovementObserved()).as("not assessed").isNull();
        assertThat(created.getFetalToneNormal()).as("not assessed").isNull();
        assertThat(created.getAnomaliesDetected()).as("not stated").isNull();
        assertThat(created.getNumberOfFetuses()).as("not counted").isNull();
    }

    @Test
    void anAbsentObservationIsKeptAsAbsent() {
        UltrasoundReportRequestDTO request = base().interpretation("No cardiac activity seen")
                .fetalCardiacActivity(false).fetalMovementObserved(false).fetalToneNormal(false)
                .anomaliesDetected(false).findingCategory(UltrasoundFindingCategory.URGENT).build();

        UltrasoundReport created = new UltrasoundMapper().toReportEntity(request, null, null);

        assertThat(created.getFetalCardiacActivity()).isFalse();
        assertThat(created.getFetalMovementObserved()).isFalse();
        assertThat(created.getFetalToneNormal()).isFalse();
        assertThat(created.getAnomaliesDetected()).isFalse();
    }

    @Test
    void aNewEntityStatesNothingAndIsStated() {
        for (UltrasoundReport r : new UltrasoundReport[] {UltrasoundReport.builder().build(), new UltrasoundReport()}) {
            assertThat(r.getFetalCardiacActivity()).isNull();
            assertThat(r.getFetalMovementObserved()).isNull();
            assertThat(r.getFetalToneNormal()).isNull();
            assertThat(r.getAnomaliesDetected()).isNull();
            assertThat(r.getFindingCategory()).isNull();
            assertThat(r.getNumberOfFetuses()).isNull();
            assertThat(r.getObservationsStated()).as("created after V282: stated").isTrue();
        }
    }

    @Test
    void theResponseSaysWhetherTheObservationsWereStated() {
        UltrasoundReport legacy = new UltrasoundReport();
        legacy.setFetalCardiacActivity(null);
        legacy.setObservationsStated(false);
        UltrasoundReport stated = new UltrasoundReport();
        UltrasoundMapper mapper = new UltrasoundMapper();
        assertThat(mapper.toReportResponseDTO(legacy).getObservationsStated()).isFalse();
        assertThat(mapper.toReportResponseDTO(stated).getObservationsStated()).isTrue();
    }

    /** QA D1525 review B: an edit that sets an observation back to "not assessed" saves null. */
    @Test
    void anEditBackToNotAssessedSavesNull() {
        UltrasoundReport stored = new UltrasoundReport();
        stored.setFetalCardiacActivity(true);
        stored.setFetalMovementObserved(false);
        stored.setFetalToneNormal(true);
        stored.setAnomaliesDetected(false);
        stored.setNumberOfFetuses(2);

        new UltrasoundMapper().updateReportFromRequest(stored,
                base().interpretation("Revu").findingCategory(UltrasoundFindingCategory.VARIANT).build());

        assertThat(stored.getFetalCardiacActivity()).isNull();
        assertThat(stored.getFetalMovementObserved()).isNull();
        assertThat(stored.getFetalToneNormal()).isNull();
        assertThat(stored.getAnomaliesDetected()).isNull();
        assertThat(stored.getNumberOfFetuses()).isNull();
    }

    /** QA D1525 review A: a report with every observation and the fetus count null reads as null, never "no". */
    @Test
    void aReportWithEveryObservationNullReadsAsNull() {
        UltrasoundReport report = new UltrasoundReport();
        report.setObservationsStated(true);
        var dto = new UltrasoundMapper().toReportResponseDTO(report);
        assertThat(dto.getFetalCardiacActivity()).isNull();
        assertThat(dto.getFetalMovementObserved()).isNull();
        assertThat(dto.getFetalToneNormal()).isNull();
        assertThat(dto.getNumberOfFetuses()).isNull();
        assertThat(dto.getAnomaliesDetected()).isNull();
        assertThat(dto.getObservationsStated()).isTrue();
    }

    /** QA D1525 review C: both category refusals resolve from the lab bundle in EN, FR and ES. */
    @Test
    void theCategoryRefusalsResolveInEveryLanguage() {
        for (java.util.Locale locale : new java.util.Locale[] {
                java.util.Locale.ENGLISH, java.util.Locale.FRENCH, java.util.Locale.forLanguageTag("es")}) {
            java.util.ResourceBundle bundle = java.util.ResourceBundle.getBundle("i18n/messages-lab", locale);
            for (String key : new String[] {"ultrasoundReport.findingCategory.required",
                    "ultrasound.report.normalContradictsObservation", "ultrasound.report.findingsRequired"}) {
                assertThat(bundle.getString(key)).as(locale + " " + key).isNotBlank();
            }
        }
        assertThat(java.util.ResourceBundle.getBundle("i18n/messages-lab", java.util.Locale.FRENCH)
                .getString("ultrasound.report.normalContradictsObservation")).contains("NORMAL").contains("catégorie");
    }

    /**
     * QA D1525 review: 0 fetuses is a finding; a count outside 0..8 is refused on
     * the field, but only when it changes (a stored legacy count stays editable
     * on the report's other fields).
     */
    @Test
    void theFetusCountIsBoundedWhenItChanges() {
        for (int bad : new int[] {-1, 9}) {
            UltrasoundReportRequestDTO request = base().numberOfFetuses(bad).build();
            assertThatThrownBy(() -> UltrasoundServiceImpl.requireFetusCountInRange(request, null))
                    .as("fetuses " + bad)
                    .isInstanceOfSatisfying(FieldBusinessException.class, ex -> {
                        assertThat(ex.getField()).isEqualTo("numberOfFetuses");
                        assertThat(ex.getMessageKey()).isEqualTo("ultrasoundReport.numberOfFetuses.range");
                    });
        }
        for (int ok : new int[] {0, 1, 2, 8}) {
            UltrasoundReportRequestDTO request = base().numberOfFetuses(ok).build();
            assertThatCode(() -> UltrasoundServiceImpl.requireFetusCountInRange(request, null)).doesNotThrowAnyException();
        }
        UltrasoundReport legacy = new UltrasoundReport();
        legacy.setNumberOfFetuses(9);
        UltrasoundReportRequestDTO unchanged = base().numberOfFetuses(9).build();
        assertThatCode(() -> UltrasoundServiceImpl.requireFetusCountInRange(unchanged, legacy))
                .as("a stored 9 sent back unchanged").doesNotThrowAnyException();
        UltrasoundReportRequestDTO changed = base().numberOfFetuses(10).build();
        assertThatThrownBy(() -> UltrasoundServiceImpl.requireFetusCountInRange(changed, legacy))
                .isInstanceOf(FieldBusinessException.class);
        assertThat(java.util.ResourceBundle.getBundle("i18n/messages-lab", java.util.Locale.FRENCH)
                .getString("ultrasoundReport.numberOfFetuses.range")).contains("0");
    }

    /**
     * QA D1525 review: an absent nasal bone does NOT contradict NORMAL (an
     * isolated absent or hypoplastic nasal bone occurs in euploid fetuses, more
     * often in African-descent populations; no clinician approved a hard rule),
     * and neither does absent movement.
     */
    @Test
    void neitherAnAbsentNasalBoneNorAbsentMovementContradictsNormal() {
        UltrasoundReportRequestDTO noNasalBone =
                base().nasalBonePresent(false).findingCategory(UltrasoundFindingCategory.NORMAL).build();
        UltrasoundReportRequestDTO noMovement =
                base().fetalMovementObserved(false).findingCategory(UltrasoundFindingCategory.NORMAL).build();
        assertThatCode(() -> UltrasoundServiceImpl.requireCategoryMatchesObservations(noNasalBone))
                .doesNotThrowAnyException();
        assertThatCode(() -> UltrasoundServiceImpl.requireCategoryMatchesObservations(noMovement))
                .doesNotThrowAnyException();
    }

    /**
     * QA D1525 review: no fetus seen contradicts every sign of a fetus (on the
     * count): cardiac activity or movement present, a heart rate, any fetal
     * biometry, an estimated fetal weight. It does NOT contradict NORMAL.
     */
    @Test
    void noFetusSeenContradictsEverySignOfAFetusButNotNormal() {
        UltrasoundReportRequestDTO normal = base().numberOfFetuses(0).findingCategory(UltrasoundFindingCategory.NORMAL).build();
        assertThatCode(() -> UltrasoundServiceImpl.requireCategoryMatchesObservations(normal))
                .as("a very early sac with no fetal pole can be normal: no hard rule without a clinician")
                .doesNotThrowAnyException();
        UltrasoundReportRequestDTO[] signs = {
            base().nuchalTranslucencyMm(2.1).build(),
            base().nasalBonePresent(false).build(),
            base().fetalToneNormal(false).build(),
            base().anomaliesDetected(true).build(),
            base().fetalCardiacActivity(true).build(),
            base().fetalMovementObserved(true).build(),
            base().fetalHeartRate(140).build(),
            base().biparietalDiameterMm(48.0).build(),
            base().headCircumferenceMm(170.0).build(),
            base().abdominalCircumferenceMm(150.0).build(),
            base().femurLengthMm(32.0).build(),
            base().crownRumpLengthMm(55.0).build(),
            base().estimatedFetalWeightGrams(320).build(),
        };
        for (UltrasoundReportRequestDTO sign : signs) {
            sign.setNumberOfFetuses(0);
            sign.setFindingCategory(UltrasoundFindingCategory.URGENT);
            assertThatThrownBy(() -> UltrasoundServiceImpl.requireFetusCountMatchesObservations(sign))
                    .as(sign.toString())
                    .isInstanceOfSatisfying(FieldBusinessException.class, ex -> {
                        assertThat(ex.getField()).isEqualTo("numberOfFetuses");
                        assertThat(ex.getMessageKey()).isEqualTo("ultrasound.report.noFetusContradictsObservation");
                    });
        }
        UltrasoundReportRequestDTO empty = base().numberOfFetuses(0).fetalCardiacActivity(false)
                .interpretation("Sac vide").findingCategory(UltrasoundFindingCategory.URGENT).build();
        assertThatCode(() -> UltrasoundServiceImpl.requireFetusCountMatchesObservations(empty))
                .doesNotThrowAnyException();
        for (java.util.Locale locale : new java.util.Locale[] {
                java.util.Locale.ENGLISH, java.util.Locale.FRENCH, java.util.Locale.forLanguageTag("es")}) {
            assertThat(java.util.ResourceBundle.getBundle("i18n/messages-lab", locale)
                    .getString("ultrasound.report.noFetusContradictsObservation")).isNotBlank();
        }
    }

    /** QA D1525 review: VARIANT also claims no significant finding, so it is refused like NORMAL. */
    @Test
    void variantClaimsNoSignificantFindingLikeNormal() {
        for (UltrasoundReportRequestDTO r : new UltrasoundReportRequestDTO[] {
                base().fetalCardiacActivity(false).findingCategory(UltrasoundFindingCategory.VARIANT).build(),
                base().fetalToneNormal(false).findingCategory(UltrasoundFindingCategory.VARIANT).build(),
                base().anomaliesDetected(true).findingCategory(UltrasoundFindingCategory.VARIANT).build()}) {
            assertThatThrownBy(() -> UltrasoundServiceImpl.requireCategoryMatchesObservations(r))
                    .isInstanceOfSatisfying(FieldBusinessException.class,
                            ex -> assertThat(ex.getField()).isEqualTo("findingCategory"));
        }
        UltrasoundReportRequestDTO monitoring = base().fetalCardiacActivity(false)
                .findingCategory(UltrasoundFindingCategory.MONITORING_REQUIRED).build();
        assertThatCode(() -> UltrasoundServiceImpl.requireCategoryMatchesObservations(monitoring))
                .doesNotThrowAnyException();
    }

    /** QA D1525 review: "no anomaly" with a written anomaly description is refused on the description. */
    @Test
    void noAnomalyWithADescriptionIsRefusedOnTheDescription() {
        UltrasoundReportRequestDTO contradiction = base().anomaliesDetected(false).anomalyDescription("Hygroma")
                .findingCategory(UltrasoundFindingCategory.URGENT).build();
        assertThatThrownBy(() -> UltrasoundServiceImpl.requireAnomalyDescriptionMatchesAnswer(contradiction))
                .isInstanceOfSatisfying(FieldBusinessException.class, ex -> {
                    assertThat(ex.getField()).isEqualTo("anomalyDescription");
                    assertThat(ex.getMessageKey()).isEqualTo("ultrasound.report.anomalyDescriptionWithoutAnomaly");
                });
        UltrasoundReportRequestDTO notAssessed = base().anomaliesDetected(null).anomalyDescription("Hygroma").build();
        assertThatThrownBy(() -> UltrasoundServiceImpl.requireAnomalyDescriptionMatchesAnswer(notAssessed))
                .as("a description with anomalies not assessed")
                .isInstanceOfSatisfying(FieldBusinessException.class,
                        ex -> assertThat(ex.getField()).isEqualTo("anomalyDescription"));
        for (UltrasoundReportRequestDTO ok : new UltrasoundReportRequestDTO[] {
                base().anomaliesDetected(true).anomalyDescription("Hygroma").build(),
                base().anomaliesDetected(null).anomalyDescription(" ").build(),
                base().anomaliesDetected(false).anomalyDescription("  ").build()}) {
            assertThatCode(() -> UltrasoundServiceImpl.requireAnomalyDescriptionMatchesAnswer(ok)).doesNotThrowAnyException();
        }
        for (java.util.Locale locale : new java.util.Locale[] {
                java.util.Locale.ENGLISH, java.util.Locale.FRENCH, java.util.Locale.forLanguageTag("es")}) {
            assertThat(java.util.ResourceBundle.getBundle("i18n/messages-lab", locale)
                    .getString("ultrasound.report.anomalyDescriptionWithoutAnomaly")).isNotBlank();
        }
    }

    /** QA D1525 review: a legacy report flips only when an observation changes; a new report is stated. */
    @Test
    void onlyAChangedObservationStatesALegacyReport() {
        UltrasoundReport legacy = new UltrasoundReport();
        legacy.setObservationsStated(false);
        legacy.setNumberOfFetuses(2);
        legacy.setFetalToneNormal(false);
        assertThat(UltrasoundServiceImpl.statesAnObservation(
                base().findingsSummary("Typo").numberOfFetuses(2).fetalToneNormal(false).build(), legacy))
                .as("the surviving values sent back unchanged").isFalse();
        assertThat(UltrasoundServiceImpl.statesAnObservation(
                base().numberOfFetuses(2).fetalToneNormal(false).fetalCardiacActivity(true).build(), legacy))
                .as("a newly answered observation").isTrue();
        assertThat(UltrasoundServiceImpl.statesAnObservation(
                base().numberOfFetuses(3).fetalToneNormal(false).build(), legacy)).isTrue();
        assertThat(UltrasoundServiceImpl.statesAnObservation(base().build(), null)).as("a new report").isTrue();
    }

    /** QA D1525 review: free-text anatomy findings are a finding, never a sign of a fetus. */
    @Test
    void anatomyTextDoesNotContradictNoFetusSeen() {
        UltrasoundReportRequestDTO emptySac = base().numberOfFetuses(0)
                .anatomyFindings("Sac gestationnel 25 mm, pas d'embryon visible")
                .findingCategory(UltrasoundFindingCategory.URGENT).build();
        assertThatCode(() -> UltrasoundServiceImpl.requireFetusCountMatchesObservations(emptySac))
                .doesNotThrowAnyException();
        assertThat(stated(base().anatomyFindings("Quatre cavités").build())).as("still a finding").isTrue();
    }

    /** QA D1525 review: a cleared text (blank) equals a stored null, as on the portal. */
    @Test
    void aBlankTextEqualsAStoredNull() {
        UltrasoundReport stored = new UltrasoundReport();
        stored.setObservationsStated(false);
        UltrasoundReportRequestDTO cleared = base().anomalyDescription("   ").build();
        assertThat(com.example.hms.service.maternity.UltrasoundObservations.differ(
                com.example.hms.service.maternity.UltrasoundObservations.CONTRADICTION_INPUTS, cleared, stored))
                .as("\"   \" vs null").isFalse();
        UltrasoundReportRequestDTO typed = base().anomalyDescription("Kyste").build();
        assertThat(com.example.hms.service.maternity.UltrasoundObservations.differ(
                com.example.hms.service.maternity.UltrasoundObservations.CONTRADICTION_INPUTS, typed, stored))
                .isTrue();
    }

    /** QA D1525 review: the contradiction inputs name each field once. */
    @Test
    void theContradictionInputsNameEachFieldOnce() {
        java.util.List<String> names = com.example.hms.service.maternity.UltrasoundObservations.CONTRADICTION_INPUTS
                .stream().map(com.example.hms.service.maternity.UltrasoundObservations.Field::name).toList();
        assertThat(names).doesNotHaveDuplicates().contains("findingCategory", "anomalyDescription", "numberOfFetuses");
    }
}
