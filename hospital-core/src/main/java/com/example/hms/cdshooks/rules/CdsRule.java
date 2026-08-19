package com.example.hms.cdshooks.rules;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;

import java.util.List;

/**
 * A single CDS rule. Implementations are stateless Spring beans
 * automatically wired into {@link CdsRuleEngine}.
 *
 * <p>Contract:
 * <ul>
 *   <li>Return an empty list when nothing fires — never null.</li>
 *   <li>Never throw on a missing optional input — degrade to no card.
 *       Throwing here would block a clinician from signing because of
 *       a sparse data set, which is the opposite of what we want.</li>
 *   <li>One rule should produce at most one card per finding so the UI
 *       can render them as a flat list.</li>
 * </ul>
 */
public interface CdsRule {

    /** Stable id used for telemetry and selectionBehavior dedupe in tests. */
    String id();

    /** Evaluate the proposed order against the patient context. */
    List<CdsCard> evaluate(CdsRuleContext context);
}
