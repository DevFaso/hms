package com.example.hms.service.billing;

import com.example.hms.enums.BloodProductType;
import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.PriceListLinkType;
import com.example.hms.enums.PriceListSystemKey;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Treatment;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.TreatmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Billing-money plan PR 2 (plan section 3.3): checks a price-list link and writes its key the one way a charge source
 * (PR 3) will look it up. Every refusal names {@code linkKey} (or {@code linkType}).
 *
 * <ul>
 *   <li>SYSTEM: one of {@link PriceListSystemKey}.</li>
 *   <li>LAB_TEST_DEFINITION: a live lab test the hospital can order (its own or the platform catalogue's), given by
 *       its id or its test code (a finance lead cannot type an id, README A-1); stored as the id.</li>
 *   <li>PROCEDURE_CODE: the procedure order's code, trimmed.</li>
 *   <li>IMAGING_STUDY: {@code <ImagingModality>:<study type>}, the study type folded to lower case without accents
 *       and single spaces (A-2).</li>
 *   <li>TRANSFUSION_PRODUCT: a {@link BloodProductType} name.</li>
 *   <li>TREATMENT: a service of the same hospital, by id.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class PriceListLinks {

    static final String FIELD_LINK_TYPE = "linkType";
    static final String FIELD_LINK_KEY = "linkKey";
    static final int LINK_KEY_MAX = 255;

    private final LabTestDefinitionRepository labTestDefinitionRepository;
    private final TreatmentRepository treatmentRepository;

    /** A link both given or both absent; returns the stored key, or null for no link. */
    public String normalise(UUID hospitalId, PriceListLinkType type, String rawKey) {
        String key = rawKey == null ? null : rawKey.trim();
        boolean hasKey = key != null && !key.isEmpty();
        if (type == null) {
            if (hasKey) {
                throw new FieldBusinessException(FIELD_LINK_TYPE, "billing.priceList.linkTypeRequired");
            }
            return null;
        }
        if (!hasKey) {
            throw new FieldBusinessException(FIELD_LINK_KEY, "billing.priceList.linkKeyRequired");
        }
        String stored = switch (type) {
            case SYSTEM -> systemKey(key);
            case LAB_TEST_DEFINITION -> labTest(hospitalId, key).getId().toString();
            case PROCEDURE_CODE -> key;
            case IMAGING_STUDY -> imagingKey(key);
            case TRANSFUSION_PRODUCT -> transfusionProduct(key);
            case TREATMENT -> treatment(hospitalId, key).getId().toString();
        };
        if (stored.length() > LINK_KEY_MAX) {
            throw new FieldBusinessException(FIELD_LINK_KEY, "billing.priceList.linkKeyTooLong", LINK_KEY_MAX);
        }
        return stored;
    }

    /** {@code <MODALITY>:<study type folded>}; public so the charge source (PR 3) folds the same way. */
    public static String imagingKey(String key) {
        int colon = key.indexOf(':');
        if (colon <= 0 || colon == key.length() - 1) {
            throw new FieldBusinessException(FIELD_LINK_KEY, "billing.priceList.linkImagingFormat");
        }
        String modality = key.substring(0, colon).trim().toUpperCase(Locale.ROOT);
        String study = fold(key.substring(colon + 1));
        if (study.isEmpty()) {
            throw new FieldBusinessException(FIELD_LINK_KEY, "billing.priceList.linkImagingFormat");
        }
        try {
            return ImagingModality.valueOf(modality).name() + ":" + study;
        } catch (IllegalArgumentException unknown) {
            throw new FieldBusinessException(FIELD_LINK_KEY, "billing.priceList.linkUnknownModality", modality);
        }
    }

    /** Lower case, no accents, single spaces. */
    public static String fold(String text) {
        return Normalizer.normalize(text.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .replaceAll("\\s+", " ");
    }

    private static String systemKey(String key) {
        try {
            return PriceListSystemKey.valueOf(key.toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException unknown) {
            throw new FieldBusinessException(FIELD_LINK_KEY, "billing.priceList.linkUnknownSystem", key);
        }
    }

    private static String transfusionProduct(String key) {
        try {
            return BloodProductType.valueOf(key.toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException unknown) {
            throw new FieldBusinessException(FIELD_LINK_KEY, "billing.priceList.linkUnknownProduct", key);
        }
    }

    /** A live test visible at the hospital, by id or by test code; anything else is "unknown" in the same words. */
    private LabTestDefinition labTest(UUID hospitalId, String key) {
        Optional<LabTestDefinition> found = parseUuid(key)
            .flatMap(labTestDefinitionRepository::findById)
            .filter(definition -> definition.getHospital() == null
                || hospitalId.equals(definition.getHospital().getId()))
            .or(() -> single(labTestDefinitionRepository.findLiveByCodeAtHospital(key, hospitalId)))
            .or(() -> single(labTestDefinitionRepository.findLiveGlobalByCode(key)))
            .filter(LabTestDefinition::isActive);
        return found.orElseThrow(() ->
            new FieldBusinessException(FIELD_LINK_KEY, "billing.priceList.linkUnknownLabTest", key));
    }

    private Treatment treatment(UUID hospitalId, String key) {
        return parseUuid(key)
            .flatMap(treatmentRepository::findById)
            .filter(t -> t.getHospital() != null && hospitalId.equals(t.getHospital().getId()))
            .orElseThrow(() ->
                new FieldBusinessException(FIELD_LINK_KEY, "billing.priceList.linkUnknownTreatment", key));
    }

    private static Optional<LabTestDefinition> single(List<LabTestDefinition> rows) {
        return rows.size() == 1 ? Optional.of(rows.get(0)) : Optional.empty();
    }

    private static Optional<UUID> parseUuid(String key) {
        try {
            return Optional.of(UUID.fromString(key));
        } catch (IllegalArgumentException notAnId) {
            return Optional.empty();
        }
    }
}
