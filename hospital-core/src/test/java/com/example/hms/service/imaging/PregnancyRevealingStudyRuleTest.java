package com.example.hms.service.imaging;

import com.example.hms.enums.ImagingModality;
import com.example.hms.model.ImagingOrder;
import com.example.hms.service.imaging.PregnancyRevealingStudyRule.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * QA D3450 (r70, High): « Échographie du 1er trimestre » reached the mother of a 15-year-old because no word of it
 * was on the rule's list. The guard of the class: a list of the names a French-speaking clinician really writes for
 * an obstetric, pelvic or gynaecological study, each of which the rule must name by itself, whoever the patient is;
 * and the fail-closed band for whatever wording the list will miss next.
 */
@DisplayName("D3450: the pregnancy-revealing imaging rule (provisoire)")
class PregnancyRevealingStudyRuleTest {

    private static ImagingOrder order(ImagingModality modality, String studyType, String region, String question) {
        ImagingOrder order = new ImagingOrder();
        order.setModality(modality);
        order.setStudyType(studyType);
        order.setBodyRegion(region);
        order.setClinicalQuestion(question);
        return order;
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Échographie du 1er trimestre",
        "Echographie du premier trimestre",
        "Écho T1 - 12 SA",
        "Échographie de datation",
        "Écho de datation précoce",
        "Échographie morphologique",
        "Écho morpho T2",
        "Échographie du 2e trimestre",
        "Échographie de croissance fœtale",
        "Biométrie fœtale",
        "Écho biométrie 32SA",
        "Échographie obstétricale",
        "Échographie de grossesse",
        "Clarté nucale",
        "Mesure de la CN",
        "Longueur du col",
        "Échographie du col utérin",
        "Cervicométrie",
        "Doppler ombilical",
        "Doppler des artères utérines",
        "Échographie à terme",
        "Échographie de vitalité",
        "Écho pelvienne sus-pubienne",
        "Échographie endovaginale",
        "Échographie transvaginale",
        "Échographie gynécologique",
        "Hystérosalpingographie",
        "Hystérosonographie",
        "Suspicion de GEU",
        "Contrôle après IVG",
        "Bilan post-partum",
        "Contrôle de cicatrice de césarienne",
        "Radiographie du bassin",
        "IRM pelvienne",
        "Radiopelvimétrie",
        "Contrôle de stérilet",
        "First trimester scan",
        "Dating scan",
        "Obstetric ultrasound",
        "Fetal growth scan",
        "Pregnancy ultrasound",
        "Ecografía obstétrica",
        "Ecografía del primer trimestre",
        "Ecografía de embarazo",
    })
    @DisplayName("the names a clinician writes for an obstetric, pelvic or gynaecological study are named, for any patient")
    void realStudyNamesAreNamed(String studyType) {
        assertThat(PregnancyRevealingStudyRule.namesPregnancyStudy(studyType)).as(studyType).isTrue();
        // Whoever the patient is: a recorded man of 70 included (the record may be wrong; a false match only hides).
        assertThat(PregnancyRevealingStudyRule.judge(order(ImagingModality.OTHER, studyType, null, null), null, true, 70))
            .as(studyType).isEqualTo(Verdict.REVEALING);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Radiographie du thorax (face)",
        "Radiographie du col du fémur",
        "Radiographie du col fémoral gauche",
        "Colonne cervicale face et profil",
        "Scanner cérébral",
        "Mammographie bilatérale",
        "Radiographie de la main",
        "IRM du genou",
        "Radiographie du crâne",
        "Panoramique dentaire",
    })
    @DisplayName("a study that names nothing obstetric is not named: a femoral neck is not a cervix, mammography stays visible")
    void otherStudiesAreNotNamed(String studyType) {
        assertThat(PregnancyRevealingStudyRule.namesPregnancyStudy(studyType)).as(studyType).isFalse();
    }

    @Test
    @DisplayName("weeks of amenorrhoea are read only after a number: « 12 SA », « 32SA », never the possessive « sa »")
    void weeksAreReadAfterANumber() {
        assertThat(PregnancyRevealingStudyRule.namesPregnancyStudy("Douleur, 12 SA")).isTrue();
        assertThat(PregnancyRevealingStudyRule.namesPregnancyStudy("contrôle 32SA")).isTrue();
        assertThat(PregnancyRevealingStudyRule.namesPregnancyStudy("8 SG")).isTrue();
        assertThat(PregnancyRevealingStudyRule.namesPregnancyStudy("Douleur depuis sa chute")).isFalse();
        assertThat(PregnancyRevealingStudyRule.namesPregnancyStudy("SA")).isFalse();
    }

    @Test
    @DisplayName("fail closed: sex not recorded as male, 10 to 55 or unknown age, any ultrasound or abdominal / pelvic study is uncleared")
    void theFailClosedBand() {
        ImagingOrder thyroid = order(ImagingModality.ULTRASOUND, "Échographie thyroïdienne", "Cou", null);
        ImagingOrder abdomenCt = order(ImagingModality.CT, "Scanner abdominal", null, null);
        ImagingOrder chest = order(ImagingModality.XRAY, "Radiographie du thorax", "Thorax", null);

        assertThat(PregnancyRevealingStudyRule.judge(thyroid, null, false, 15)).isEqualTo(Verdict.UNCLEARED);
        assertThat(PregnancyRevealingStudyRule.judge(abdomenCt, null, false, 30)).isEqualTo(Verdict.UNCLEARED);
        assertThat(PregnancyRevealingStudyRule.judge(chest, null, false, 15)).isEqualTo(Verdict.SHOWN);

        assertThat(PregnancyRevealingStudyRule.judge(thyroid, null, false, 9)).as("9").isEqualTo(Verdict.SHOWN);
        assertThat(PregnancyRevealingStudyRule.judge(thyroid, null, false, 10)).as("10").isEqualTo(Verdict.UNCLEARED);
        assertThat(PregnancyRevealingStudyRule.judge(thyroid, null, false, 55)).as("55").isEqualTo(Verdict.UNCLEARED);
        assertThat(PregnancyRevealingStudyRule.judge(thyroid, null, false, 56)).as("56").isEqualTo(Verdict.SHOWN);
        assertThat(PregnancyRevealingStudyRule.judge(thyroid, null, false, null)).as("age unknown")
            .isEqualTo(Verdict.UNCLEARED);
        assertThat(PregnancyRevealingStudyRule.judge(thyroid, null, true, 30)).as("recorded male")
            .isEqualTo(Verdict.SHOWN);
        assertThat(PregnancyRevealingStudyRule.judge(thyroid, null, null)).as("no patient in hand")
            .isEqualTo(Verdict.UNCLEARED);
    }

    @Test
    @DisplayName("the catalogue flag comes first: flagged is revealing for anybody, cleared is shown unless this order's question names a pregnancy")
    void theCatalogueFlagComesFirst() {
        ImagingOrder abdominal = order(ImagingModality.ULTRASOUND, "Échographie abdominale", "Abdomen", null);
        assertThat(PregnancyRevealingStudyRule.judge(abdominal, Boolean.FALSE, false, 15)).isEqualTo(Verdict.SHOWN);
        assertThat(PregnancyRevealingStudyRule.judge(abdominal, Boolean.TRUE, true, 70)).isEqualTo(Verdict.REVEALING);

        abdominal.setClinicalQuestion("Aménorrhée, grossesse ?");
        assertThat(PregnancyRevealingStudyRule.judge(abdominal, Boolean.FALSE, false, 15)).isEqualTo(Verdict.REVEALING);

        assertThat(PregnancyRevealingStudyRule.judge(null, Boolean.FALSE, false, 15)).as("no order: fails closed")
            .isEqualTo(Verdict.REVEALING);
    }

    @Test
    @DisplayName("a catalogue exam read on its own (an invoice line) follows the same three steps")
    void aCatalogueExamOnItsOwn() {
        assertThat(PregnancyRevealingStudyRule.judgeExam(Boolean.TRUE, ImagingModality.XRAY, "Radiographie du thorax",
            null, null)).isEqualTo(Verdict.REVEALING);
        assertThat(PregnancyRevealingStudyRule.judgeExam(Boolean.FALSE, ImagingModality.ULTRASOUND,
            "Échographie abdominale", "Abdomen", null)).isEqualTo(Verdict.SHOWN);
        assertThat(PregnancyRevealingStudyRule.judgeExam(null, ImagingModality.ULTRASOUND,
            "Échographie du 1er trimestre", null, null)).isEqualTo(Verdict.REVEALING);
        assertThat(PregnancyRevealingStudyRule.judgeExam(null, ImagingModality.ULTRASOUND,
            "Échographie abdominale", "Abdomen", null)).as("no patient in hand").isEqualTo(Verdict.UNCLEARED);
        assertThat(PregnancyRevealingStudyRule.judgeExam(null, ImagingModality.XRAY, "Radiographie du thorax",
            "Thorax", null)).isEqualTo(Verdict.SHOWN);
    }

    @Test
    @DisplayName("the read is one bounded pass: a hostile megabyte of text answers at once (no regular expression reads user text)")
    void hostileTextIsReadInBoundedTime() {
        String hostile = "a".repeat(500_000) + " ".repeat(500_000) + "é".repeat(100_000) + "!";
        ImagingOrder order = order(ImagingModality.XRAY, hostile, hostile, hostile);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            for (int i = 0; i < 50; i++) {
                PregnancyRevealingStudyRule.judge(order, null, false, 30);
            }
        });
    }

    @Test
    @DisplayName("the word lists stay bounded: a longer list is a decision for the sign-off, not a change made in passing")
    void theListsAreBounded() {
        assertThat(PregnancyRevealingStudyRule.TERMS).hasSizeLessThanOrEqualTo(80).doesNotHaveDuplicates();
        assertThat(PregnancyRevealingStudyRule.WORDS).hasSizeLessThanOrEqualTo(20);
        assertThat(PregnancyRevealingStudyRule.FAIL_CLOSED_MIN_AGE).isEqualTo(10);
        assertThat(PregnancyRevealingStudyRule.FAIL_CLOSED_MAX_AGE).isEqualTo(55);
    }
}
