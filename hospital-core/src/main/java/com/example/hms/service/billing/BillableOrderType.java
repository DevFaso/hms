package com.example.hms.service.billing;

import com.example.hms.enums.PriceListCategory;
import com.example.hms.enums.PriceListLinkType;
import com.example.hms.model.Admission;
import com.example.hms.model.EncounterTreatment;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.LabOrder;
import com.example.hms.model.Prescription;
import com.example.hms.model.ProcedureOrder;
import com.example.hms.model.TransfusionRequest;
import com.example.hms.model.labor.DeliveryRecord;

import java.util.List;

/**
 * QA D972 (rounds 16, 17 and 18): the registry of everything a visit can be
 * billed for, and where each is charged.
 *
 * <p>Round 17 billed the consultation, the lab orders and the procedures of a
 * checked-out visit; round 18 found the imaging orders missing, because the
 * charge listener listed its order types by hand and nothing said the list was
 * complete. Every kind of billable item now has one entry here, and every
 * entry charged on the visit invoice has exactly one {@link VisitChargeSource}
 * ({@code BillableOrderRegistryGuardTest}). An order entity written on a visit
 * that is neither in this registry nor declared not billable fails the same
 * guard, so a new order type cannot reach checkout without a charge mapping.
 */
public enum BillableOrderType {

    /** The services recorded on the visit, else one consultation line. */
    CONSULTATION(Route.VISIT_INVOICE, List.of(EncounterTreatment.class),
        List.of(PriceListLinkType.TREATMENT, PriceListLinkType.SYSTEM), null),
    /** Each lab order not withdrawn. */
    LAB_ORDER(Route.VISIT_INVOICE, List.of(LabOrder.class), List.of(PriceListLinkType.LAB_TEST_DEFINITION),
        PriceListCategory.LAB_TEST),
    /** Each imaging order not withdrawn (QA D972, round 18). */
    IMAGING_ORDER(Route.VISIT_INVOICE, List.of(ImagingOrder.class), List.of(PriceListLinkType.IMAGING_STUDY),
        PriceListCategory.IMAGING),
    /** Each procedure order not withdrawn. */
    PROCEDURE_ORDER(Route.VISIT_INVOICE, List.of(ProcedureOrder.class), List.of(PriceListLinkType.PROCEDURE_CODE),
        PriceListCategory.PROCEDURE),
    /** Each transfusion request whose blood was issued, by the units issued. */
    TRANSFUSION(Route.VISIT_INVOICE, List.of(TransfusionRequest.class),
        List.of(PriceListLinkType.TRANSFUSION_PRODUCT), PriceListCategory.TRANSFUSION),
    /** The days of the stay an inpatient visit belongs to. */
    BED_DAYS(Route.VISIT_INVOICE, List.of(Admission.class), List.of(PriceListLinkType.SYSTEM),
        PriceListCategory.BED_DAY),
    /**
     * A dispensed medicine is paid at the pharmacy checkout (its own payment,
     * QA D264); on the visit invoice it would be billed twice.
     */
    PHARMACY_DISPENSE(Route.PHARMACY_CHECKOUT, List.of(Prescription.class), List.of(), null),
    /** A birth is charged when the delivery is recorded ({@code DeliveryChargeService}, QA D516). */
    DELIVERY(Route.DELIVERY_CHARGE, List.of(DeliveryRecord.class), List.of(PriceListLinkType.SYSTEM),
        PriceListCategory.DELIVERY);

    /** Where an item is charged. */
    public enum Route {
        /** A line on the visit's draft invoice, opened at checkout. */
        VISIT_INVOICE,
        /** Paid at the pharmacy checkout, never on the visit invoice. */
        PHARMACY_CHECKOUT,
        /** Its own draft invoice, opened when the delivery is recorded. */
        DELIVERY_CHARGE
    }

    private final Route route;
    private final List<Class<?>> entities;
    private final List<PriceListLinkType> linkTypes;
    private final PriceListCategory coverageCategory;

    BillableOrderType(Route route, List<Class<?>> entities, List<PriceListLinkType> linkTypes,
                      PriceListCategory coverageCategory) {
        this.route = route;
        this.entities = entities;
        this.linkTypes = linkTypes;
        this.coverageCategory = coverageCategory;
    }

    /**
     * QA D3603 (r71): the price-list category a payer plan's category rule sees this charge's lines as, however each
     * line is priced (price list, service of the same name, by hand at the till, or overridden). An imaging line
     * priced by hand was billed at the plan's general rate instead of its IMAGING rule. Null for the services
     * recorded on a visit (a mix: its default consultation line names CONSULTATION itself) and for a medicine
     * (never on an invoice line).
     */
    public PriceListCategory coverageCategory() {
        return coverageCategory;
    }

    public Route route() {
        return route;
    }

    /** The records this kind of charge is derived from. */
    public List<Class<?>> entities() {
        return entities;
    }

    /**
     * Billing-money plan PR 3: the price-list links this charge is priced by (plan section 3.3). Every type charged
     * on an invoice declares at least one, and its charge code looks each one up ({@code BillableOrderRegistryGuardTest});
     * a medicine is priced at the pharmacy and declares none.
     */
    public List<PriceListLinkType> linkTypes() {
        return linkTypes;
    }

    /** Whether this item is a line on the visit invoice. */
    public boolean onVisitInvoice() {
        return route == Route.VISIT_INVOICE;
    }
}
