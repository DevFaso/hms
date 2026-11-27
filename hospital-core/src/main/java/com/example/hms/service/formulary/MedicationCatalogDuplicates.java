package com.example.hms.service.formulary;

import com.example.hms.enums.MedicationCatalogVersionKind;
import com.example.hms.enums.MedicationCatalogVersionStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.medication.FormularySnapshot;
import com.example.hms.model.medication.MedicationCatalogVersion;
import com.example.hms.payload.dto.medication.MedicationCatalogSimilarEntryDTO;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.repository.MedicationCatalogVersionRepository;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * QA D4180 (r81): the one place that says whether a medicine is already in a hospital's catalogue. Two proposals
 * « Céfixime 200 mg » were both accepted and both approved, and the prescriber's search then listed the medicine
 * twice.
 *
 * <p>An entry is compared by its name, its dosage form and its strength, read as a person reads them: case,
 * accents, spacing and punctuation do not make two medicines ("Paracetamol  500MG" is « Paracétamol 500 mg »).
 * <ul>
 *   <li><strong>Exact</strong> (same name, same form, same strength) against an entry in the catalogue, or against
 *       another proposal still waiting: refused, on the name field, naming the entry that is there.</li>
 *   <li><strong>Similar</strong> (the same name with another form or strength, or the same medicine once the
 *       strength written in the name is set aside): listed, for the proposer before sending and for the approver
 *       beside the proposal. Two strengths of one medicine are two legitimate entries, so this only warns.</li>
 * </ul>
 * A hospital's catalogue is its own entries and the platform's (the national list): both are compared. A platform
 * proposal is compared with the platform's entries only. A withdrawn entry is listed as such and never refuses:
 * putting it back is its own proposal.
 */
@Component
@RequiredArgsConstructor
public class MedicationCatalogDuplicates {

    static final String DUPLICATE = "medication.catalog.duplicate";
    static final String DUPLICATE_PENDING = "medication.catalog.duplicatePending";
    static final String DUPLICATE_AT_APPROVAL = "medication.catalog.duplicateAtApproval";

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern DIGIT_THEN_LETTER = Pattern.compile("(\\d)(\\p{L})");
    /** A strength written at the end of a name: "500 mg", "120 mg/5 ml", "0,5 %", "1 000 000 ui". */
    private static final Pattern TRAILING_STRENGTH = Pattern.compile(
        "(\\s+\\d[\\d ]*(\\s\\d+)?\\s*(mg|g|kg|mcg|µg|ug|ml|l|ui|iu|mmol|meq)?(\\s+\\d[\\d ]*\\s*(mg|g|ml|l))?)+$");

    private final MedicationCatalogItemRepository catalogue;
    private final MedicationCatalogVersionRepository versions;

    /** What a hospital's catalogue (or the platform's, {@code hospitalId} null) holds, read once for a request. */
    public Known knownAt(UUID hospitalId) {
        List<Entry> entries = new ArrayList<>();
        for (Object[] row : hospitalId == null ? catalogue.identityOfPlatformEntries()
                : catalogue.identityOfEntriesVisibleAt(hospitalId)) {
            FormularySnapshot identity = new FormularySnapshot((String) row[1], null, null, null, (String) row[2],
                (String) row[3], (String) row[4], null, null, null, false, Boolean.TRUE.equals(row[5]), null, false,
                null, null);
            entries.add(new Entry((UUID) row[0], null, identity,
                Boolean.TRUE.equals(row[5]) ? "IN_CATALOGUE" : "WITHDRAWN", row[6] == null));
        }
        for (MedicationCatalogVersion waiting : versions.findInScope(hospitalId,
                MedicationCatalogVersionStatus.PENDING_APPROVAL, MedicationCatalogVersionKind.FORMULARY,
                Pageable.unpaged()).getContent()) {
            entries.add(new Entry(waiting.getCatalogItemId(), waiting.getId(),
                MedicationCatalogVersionService.readFormulary(waiting.getPayload()), "PENDING", hospitalId == null));
        }
        return new Known(entries);
    }

    /** One entry, or one waiting proposal, as it is compared. */
    record Entry(UUID catalogItemId, UUID versionId, FormularySnapshot identity, String state, boolean platform) {
    }

    /** The catalogue as compared with one proposal after another (the approval queue reads it once). */
    public static final class Known {
        private final List<Entry> entries;

        Known(List<Entry> entries) {
            this.entries = entries;
        }

        /**
         * The entries and waiting proposals that look like {@code proposed}, the exact ones first.
         * {@code ownItemId} and {@code ownVersionId} are the entry and the version the proposal itself is.
         */
        public List<MedicationCatalogSimilarEntryDTO> similarTo(FormularySnapshot proposed, UUID ownItemId,
                                                                UUID ownVersionId) {
            List<MedicationCatalogSimilarEntryDTO> found = new ArrayList<>();
            if (proposed == null || nameKey(proposed.nameFr()).isEmpty()) {
                return found;
            }
            for (Entry entry : entries) {
                boolean self = (ownItemId != null && ownItemId.equals(entry.catalogItemId()))
                    || (ownVersionId != null && ownVersionId.equals(entry.versionId()));
                if (self) {
                    continue;
                }
                boolean exact = exact(proposed, entry.identity());
                if (exact || similar(proposed, entry.identity())) {
                    found.add(new MedicationCatalogSimilarEntryDTO(entry.catalogItemId(), entry.versionId(),
                        entry.identity().label(), entry.identity().form(), exact, entry.state(), entry.platform()));
                }
            }
            found.sort(java.util.Comparator.comparing((MedicationCatalogSimilarEntryDTO e) -> !e.exact())
                .thenComparing(MedicationCatalogSimilarEntryDTO::label, String.CASE_INSENSITIVE_ORDER));
            return found;
        }

        /** The exact duplicate that refuses {@code proposed}: an entry in the catalogue, or a waiting proposal. */
        private MedicationCatalogSimilarEntryDTO refusing(FormularySnapshot proposed, UUID ownItemId,
                                                          UUID ownVersionId, boolean countWaiting) {
            for (MedicationCatalogSimilarEntryDTO entry : similarTo(proposed, ownItemId, ownVersionId)) {
                boolean counts = "IN_CATALOGUE".equals(entry.state()) || (countWaiting && "PENDING".equals(entry.state()));
                if (entry.exact() && counts) {
                    return entry;
                }
            }
            return null;
        }
    }

    /**
     * Refuses a proposal that is exactly an entry of the catalogue, or exactly another proposal still waiting.
     * The refusal is on the name field and names what is already there.
     */
    public void requireNewAtProposal(UUID hospitalId, FormularySnapshot proposed, UUID ownItemId) {
        MedicationCatalogSimilarEntryDTO twin = knownAt(hospitalId).refusing(proposed, ownItemId, null, true);
        if (twin != null) {
            throw new FieldBusinessException("nameFr",
                "PENDING".equals(twin.state()) ? DUPLICATE_PENDING : DUPLICATE, twin.label());
        }
    }

    /**
     * Refuses the approval of a version that became a duplicate while it waited (its twin was approved first).
     * Another proposal that still waits does not refuse an approval: the first approved one wins.
     */
    public void requireNewAtApproval(MedicationCatalogVersion version, FormularySnapshot proposed) {
        MedicationCatalogSimilarEntryDTO twin = knownAt(version.getHospitalId())
            .refusing(proposed, version.getCatalogItemId(), version.getId(), false);
        if (twin != null) {
            throw new BusinessException(DUPLICATE_AT_APPROVAL, twin.label());
        }
    }

    /* ───────────────────────────── comparing ───────────────────────────── */

    static boolean exact(FormularySnapshot a, FormularySnapshot b) {
        return same(a, b, MedicationCatalogDuplicates::nameKey)
            && formKey(a.form()).equals(formKey(b.form()))
            && strengthKey(a).equals(strengthKey(b));
    }

    /** The same name, or the same medicine once the strength written in the name is set aside. */
    static boolean similar(FormularySnapshot a, FormularySnapshot b) {
        return same(a, b, MedicationCatalogDuplicates::nameKey) || same(a, b, MedicationCatalogDuplicates::baseNameKey);
    }

    private static boolean same(FormularySnapshot a, FormularySnapshot b, Function<String, String> key) {
        String mine = key.apply(a.nameFr());
        return !mine.isEmpty() && mine.equals(key.apply(b.nameFr()));
    }

    /** A name as compared: no case, no accent, one space between words, no punctuation. */
    static String nameKey(String name) {
        if (name == null) {
            return "";
        }
        String plain = MARKS.matcher(Normalizer.normalize(name, Normalizer.Form.NFD)).replaceAll("");
        String words = NOT_WORD.matcher(plain.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
        // "500mg" and "500 mg" are one way of writing a strength; "b12" stays a word.
        return DIGIT_THEN_LETTER.matcher(words).replaceAll("$1 $2");
    }

    /** The name without a strength written at its end: « Paracétamol 500 mg » and « Paracétamol » are one medicine. */
    static String baseNameKey(String name) {
        String key = nameKey(name);
        String base = TRAILING_STRENGTH.matcher(key).replaceAll("").trim();
        return base.isEmpty() ? key : base;
    }

    static String formKey(String form) {
        return form == null ? "" : form.trim().toUpperCase(Locale.ROOT);
    }

    /** "500" + "mg", "500 mg" and "500MG" are one strength; so are "0,5" and "0.5". */
    static String strengthKey(FormularySnapshot entry) {
        String written = (entry.strength() == null ? "" : entry.strength())
            + (entry.strengthUnit() == null ? "" : entry.strengthUnit());
        return written.toLowerCase(Locale.ROOT).replace(',', '.').replaceAll("\\s+", "");
    }
}
