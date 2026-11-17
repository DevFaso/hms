package com.example.hms.cdshooks.rules;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.Source;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.service.pharmacy.DispenseVerificationService;
import com.example.hms.service.pharmacy.OrderedDrugIdentity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * QA D1223 (round 24): the proposed order's name and the catalogue item its
 * code (formulary or RxNorm) resolves to name different drugs. Every other
 * rule of the engine reads the item - the paediatric ceiling, the interaction
 * check's RxNorm - so without this card an order-sign, order-select or
 * medication-prescribe hook typed "Bristopen" with paracetamol's code was
 * judged as paracetamol in silence. A CRITICAL card: the prescriber must look
 * at it, and the prescription service never saves such an order (it refuses
 * the mismatch before the engine runs).
 */
@Component
public class OrderedDrugIdentityRule implements CdsRule {

    private static final String ID = "ordered-drug-identity";
    private static final String SOURCE_LABEL = "HMS Drug Identity Check";

    /** Card wording in the caller's language (D61); English when built outside Spring. */
    private com.example.hms.cdshooks.CdsCardText text = com.example.hms.cdshooks.CdsCardText.ENGLISH;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCardText(com.example.hms.cdshooks.CdsCardText text) {
        this.text = text;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<CdsCard> evaluate(CdsRuleContext context) {
        MedicationCatalogItem item = context.proposedCatalogItem();
        String typed = context.proposedMedicationName();
        if (!OrderedDrugIdentity.contradicts(typed, item)) {
            return List.of();
        }
        String itemName = DispenseVerificationService.describe(item);
        return List.of(new CdsCard(
            text.get("cds.orderedDrugIdentity.summary",
                "{0} does not name the catalogue item {1}", typed.trim(), itemName),
            text.get("cds.orderedDrugIdentity.detail",
                "The medication name and its code name two different drugs. The checks read the catalogue item, "
                    + "not the name written on the order. Correct the name or the code before signing.",
                typed.trim(), itemName),
            CdsCard.Indicator.CRITICAL,
            new Source(text.get("cds.source.orderedDrugIdentity", SOURCE_LABEL), null, null),
            null, null, null, UUID.randomUUID().toString()));
    }
}
