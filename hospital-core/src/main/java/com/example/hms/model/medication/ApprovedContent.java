package com.example.hms.model.medication;

import com.example.hms.enums.MedicationCatalogVersionKind;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Go-live D2 (V385): marks a field of {@link MedicationCatalogItem} that prescribing or dispensing reads, and
 * that therefore changes only through a version a second person approved.
 *
 * <p>Its setter (and its builder method) is called by {@link MedicationCatalogApprovedWrite} alone:
 * {@code MedicationCatalogApprovedWriteGuardTest} reads the compiled classes and fails on any other caller, and on
 * a field of the entry that is neither marked nor listed there as edited directly, with the reason.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ApprovedContent {

    /** The kind of version that carries the field. */
    MedicationCatalogVersionKind value();
}
