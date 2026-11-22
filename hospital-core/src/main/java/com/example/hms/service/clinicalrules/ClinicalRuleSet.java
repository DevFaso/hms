package com.example.hms.service.clinicalrules;

import java.util.List;

/**
 * One provisional rule set, as a hospital's approver signs it off. Its texts (title, description, source, approver
 * role) are in the {@code messages-admin} bundles, EN/FR/ES, under {@link #titleKey()}, {@link #descriptionKey()},
 * {@link #sourceKey()} and {@link #approverKey()}: a published citation as written, a decision in the reader's
 * words.
 *
 * @param key        the stable key (stored with every sign-off)
 * @param version    the version a sign-off is recorded against; a new version reopens every hospital's sign-off
 * @param enforcedIn the classes that enforce it, named from {@code com.example.hms} ("service.vitals.SevereHypertension":
 *                   a name, not a class literal, so a package-private rule class can be named too); each carries
 *                   {@link ProvisionalRule} with {@code key} ({@code ClinicalRuleRegistryGuardTest})
 */
public record ClinicalRuleSet(ClinicalRuleSetKey key, String version, List<String> enforcedIn) {

    /** The package every name of {@link #enforcedIn} is relative to. */
    public static final String BASE_PACKAGE = "com.example.hms.";
    /** The bundle prefix of every rule set's texts. */
    private static final String TEXT_PREFIX = "clinicalRules.rule.";

    public ClinicalRuleSet {
        enforcedIn = List.copyOf(enforcedIn);
    }

    public String titleKey() {
        return TEXT_PREFIX + key.name() + ".title";
    }

    public String descriptionKey() {
        return TEXT_PREFIX + key.name() + ".description";
    }

    /** The published source or decision the rule rests on (not re-verified here). */
    public String sourceKey() {
        return TEXT_PREFIX + key.name() + ".source";
    }

    public String approverKey() {
        return TEXT_PREFIX + key.name() + ".approver";
    }

    /** The simple names of the enforcing classes, for the screen and the sign-off pack. */
    public List<String> enforcedInNames() {
        return enforcedIn.stream().map(name -> name.substring(name.lastIndexOf('.') + 1)).toList();
    }
}
