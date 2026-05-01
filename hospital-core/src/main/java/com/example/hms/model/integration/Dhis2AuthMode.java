package com.example.hms.model.integration;

/**
 * How the {@link Dhis2FacilityConfig} authenticates to its DHIS2 instance.
 *
 * <p>The actual secret value is never stored — the column references an
 * environment-variable name and the {@code DhisHttpClient} resolves it at
 * push time.
 */
public enum Dhis2AuthMode {

    /** Personal Access Token, sent as {@code Authorization: ApiToken &lt;value&gt;}. */
    PAT,

    /** HTTP Basic ({@code username:password} base64-encoded). */
    BASIC
}
