package com.example.hms.enums;

/**
 * Public-payer schemes serviced by the real-time eligibility / prior-auth API.
 *
 * <p>The HMS deliberately models West-African public coverage rather than the
 * US X12 270/271/278 transaction set: integration is per-country, partner-API
 * decisions are deferred (P1 #12 follow-up #4), and providers are pluggable
 * via {@link com.example.hms.service.integration.eligibility.EligibilityProvider}.
 *
 * <ul>
 *   <li>{@link #NHIS_GH}    — Ghana National Health Insurance Scheme</li>
 *   <li>{@link #NHIA_NG}    — Nigeria National Health Insurance Authority</li>
 *   <li>{@link #CNAMGS_GA}  — Gabon Caisse Nationale d'Assurance Maladie et de Garantie Sociale</li>
 *   <li>{@link #MUTUELLE_RW} — Rwanda Mutuelle de Santé (community-based)</li>
 *   <li>{@link #MUTUELLE_BF} — Burkina Faso Régime d'Assurance Maladie Universelle</li>
 *   <li>{@link #GENERIC}    — fallback for any private payer or unsupported scheme</li>
 * </ul>
 */
public enum EligibilityScheme {
    NHIS_GH,
    NHIA_NG,
    CNAMGS_GA,
    MUTUELLE_RW,
    MUTUELLE_BF,
    GENERIC
}
