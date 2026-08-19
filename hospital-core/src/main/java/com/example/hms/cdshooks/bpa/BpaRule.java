package com.example.hms.cdshooks.bpa;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;

import java.util.List;

/**
 * A single Best-Practice-Advisory rule. Implementations are stateless
 * Spring beans automatically wired into {@link BpaRuleEngine}.
 *
 * <p>Different from {@code CdsRule} (the order-sign engine): BPAs are
 * driven by patient state — recent vitals, active problems, recent
 * encounters — not by a proposed clinical action. They are advisory
 * only ({@link CdsCard.Indicator#WARNING} or {@code INFO}, never
 * {@code CRITICAL}); BPAs nudge clinicians toward protocols, they do
 * not block actions.
 *
 * <p>Contract:
 * <ul>
 *   <li>Return an empty list when nothing fires — never null.</li>
 *   <li>Never throw on a missing optional input — degrade to no card.
 *       A buggy BPA must not crash the chart load.</li>
 *   <li>Emit at most one card per rule per evaluation so the UI can
 *       render them as a flat advisory list.</li>
 * </ul>
 */
public interface BpaRule {

    /** Stable id used for telemetry and the protocol_code lookup in V64. */
    String id();

    /** Evaluate the patient's recent state against the protocol's trigger. */
    List<CdsCard> evaluate(BpaRuleContext context);
}
