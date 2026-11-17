package com.example.hms.security.provider.confinement;

import java.util.List;

/**
 * The handlers a LABORATORY provider reaches beyond {@link CommonProviderConfinement}
 * (provider plan §6.4, decided handler by handler there). Empty until P2-LAB
 * adds them; only P2-LAB edits this file, and
 * {@code provider-confinement/laboratory.txt} is its frozen snapshot.
 */
public final class LaboratoryConfinement {

    public static final List<ConfinementRule> RULES = List.of();

    private LaboratoryConfinement() {
    }
}
