package com.example.hms.security.provider.confinement;

import java.util.List;

/**
 * The handlers a PHARMACY provider reaches beyond {@link CommonProviderConfinement}
 * (provider plan §6.4). Empty until P2-PH adds the offer queue
 * ({@code /provider/pharmacy/offers/**}); only P2-PH edits this file, and
 * {@code provider-confinement/pharmacy.txt} is its frozen snapshot.
 */
public final class PharmacyConfinement {

    public static final List<ConfinementRule> RULES = List.of();

    private PharmacyConfinement() {
    }
}
