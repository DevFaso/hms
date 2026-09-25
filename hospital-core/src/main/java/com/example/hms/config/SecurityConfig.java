package com.example.hms.config;

import com.example.hms.security.JwtAuthenticationEntryPoint;
import com.example.hms.security.JwtAuthenticationFilter;
import com.example.hms.security.RoleExpansion;
import com.example.hms.security.HospitalUserDetailsService;
import com.example.hms.security.oidc.KeycloakHospitalContextFilter;
import com.example.hms.security.oidc.KeycloakJwtAuthenticationConverter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

import static com.example.hms.config.SecurityConstants.ROLE_DOCTOR;
import static com.example.hms.config.SecurityConstants.ROLE_HOSPITAL_ADMIN;
import static com.example.hms.config.SecurityConstants.ROLE_LAB_SCIENTIST;
import static com.example.hms.config.SecurityConstants.ROLE_LAB_TECHNICIAN;
import static com.example.hms.config.SecurityConstants.ROLE_LAB_MANAGER;
import static com.example.hms.config.SecurityConstants.ROLE_LAB_DIRECTOR;
import static com.example.hms.config.SecurityConstants.ROLE_QUALITY_MANAGER;
import static com.example.hms.config.SecurityConstants.ROLE_MIDWIFE;
import static com.example.hms.config.SecurityConstants.ROLE_NURSE;
import static com.example.hms.config.SecurityConstants.ROLE_PATIENT;
import static com.example.hms.config.SecurityConstants.ROLE_PHARMACIST;
import static com.example.hms.config.SecurityConstants.ROLE_RECEPTIONIST;
import static com.example.hms.config.SecurityConstants.ROLE_ANESTHESIOLOGIST;
import static com.example.hms.config.SecurityConstants.ROLE_PHYSIOTHERAPIST;
import static com.example.hms.config.SecurityConstants.ROLE_RADIOLOGIST;
import static com.example.hms.config.SecurityConstants.ROLE_STAFF;
import static com.example.hms.config.SecurityConstants.ROLE_SUPER_ADMIN;
import static com.example.hms.config.SecurityConstants.ROLE_SURGEON;
import static com.example.hms.config.SecurityConstants.ROLE_BILLING_SPECIALIST;
import static com.example.hms.config.SecurityConstants.ROLE_ACCOUNTANT;
import static com.example.hms.config.SecurityConstants.ROLE_ADMIN;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /** The machine role for FHIR clients — see {@link #FHIR_READER_AUTHORITIES}. */
    static final String FHIR_CLIENT_AUTHORITY = "ROLE_FHIR_CLIENT";

    private static final Logger SEC_LOG = LoggerFactory.getLogger(SecurityConfig.class);

    // -----------------------------------------------------------------------
    // Path constants — context-path is /api, so Spring Security sees paths
    // *after* the context-path is stripped. All matchers are relative.
    // -----------------------------------------------------------------------
    private static final String API_FEATURE_FLAGS = "/feature-flags";
    private static final String API_FEATURE_FLAGS_PATTERN = API_FEATURE_FLAGS + "/**";

    private static final String API_PATIENTS = "/patients";
    private static final String API_PATIENTS_PATTERN = API_PATIENTS + "/**";

    private static final String API_PATIENT_VITALS = "/patients/*/vitals";
    private static final String API_PATIENT_VITALS_PATTERN = API_PATIENT_VITALS + "/**";
    /**
     * E9 #67 (D5) — the clinical sub-resources of a patient. Matched ahead of
     * the /patients/** blanket so HOSPITAL_ADMIN keeps the demographics the
     * blanket admits and none of these; the controller annotations stay the
     * precise gate per surface.
     */
    static final String[] API_PATIENT_CHART_PATTERNS = {
        "/patients/*/allergies", "/patients/*/allergies/**",
        "/patients/*/diagnoses", "/patients/*/diagnoses/**",
        "/patients/*/chart-updates", "/patients/*/chart-updates/**",
        "/patients/*/storyboard", "/patients/*/chart-review",
        "/patients/*/lab-results", "/patients/*/lab-results/**",
        "/patients/*/medications", "/patients/*/medications/**",
        "/patients/*/micro-cultures", "/patients/*/micro-cultures/**",
        "/patients/*/fhir-record",
        "/patients/*/growth-chart", "/patients/*/growth-chart/**",
        "/patients/*/intake-output", "/patients/*/intake-output/**"
    };

    /**
     * E8 #52 — the patient's own sharing opt-out: the one /patients/* surface a
     * PATIENT reaches. Matched per verb ahead of the chart patterns and the
     * /patients/** blanket, which lists no patient role (see the block below and
     * RecordAccessController.OPT_OUT_ROLES / OPT_OUT_REVOKE_ROLES).
     */
    static final String API_PATIENT_OPT_OUT = "/patients/*/record-sharing/opt-out";
    /**
     * E8 #52 — a hospital's record-access posture. Its controller admits
     * HOSPITAL_ADMIN on PUT (the admin's own legal escape hatch to
     * EXPLICIT_CONSENT) and QUALITY_MANAGER on GET, but the /hospitals/**
     * matchers below say SUPER_ADMIN-only on PUT and no quality manager on GET,
     * so both were 403 at the edge while every slice test passed — the #654
     * class on the endpoint next door. The controller still pins a hospital
     * admin to the hospital they are acting in.
     */
    static final String API_HOSPITAL_POSTURE = "/hospitals/*/record-access-posture";

    /**
     * The HAPI FHIR servlet ({@code FhirConfig}, mounted at {@code /fhir/*}).
     * It serves server-to-server clients and SMART EHR launches, and it answers
     * with the whole chart — Encounter, Condition, MedicationRequest,
     * Immunization, Observation, DiagnosticReport, DocumentReference — so it
     * admits exactly the roles that read the chart elsewhere: the
     * {@code ENCOUNTER_LIST_ROLES} set of {@code EncounterController}.
     *
     * <p>Until this matcher existed {@code /fhir/**} fell through to
     * {@code anyRequest().authenticated()}, so a patient's mobile-app bearer
     * token, a receptionist or a billing clerk reached every provider.
     *
     * <p>Named explicitly rather than left to {@code RoleExpansion}: the
     * expansion runs on the password path only, so {@code ROLE_PHYSICIAN} and
     * {@code ROLE_SURGEON} would be refused over Keycloak if only
     * {@code ROLE_DOCTOR} were listed. {@code ROLE_FHIR_CLIENT} is the machine
     * role {@code IdleSessionGate} already names for FHIR clients; no account
     * holds it yet, and it is listed so a service account can be given it
     * without also being given a clinician's role.
     */
    static final String[] FHIR_READER_AUTHORITIES = {
        ROLE_DOCTOR, RoleExpansion.ROLE_PHYSICIAN, ROLE_SURGEON, ROLE_NURSE, ROLE_MIDWIFE,
        ROLE_RADIOLOGIST, ROLE_ANESTHESIOLOGIST, ROLE_PHYSIOTHERAPIST,
        ROLE_SUPER_ADMIN, FHIR_CLIENT_AUTHORITY
    };

    /**
     * {@code $export} (system and Patient level) is a POST, and its service
     * ({@code FhirBulkExportService.requireBulkExportRole}) and the status
     * controller admit SUPER_ADMIN and HOSPITAL_ADMIN. The matcher carries the
     * same pair, ahead of the {@code /fhir/**} reader matcher, so the hospital
     * admin keeps the export and gains no clinical read.
     */
    static final String[] FHIR_BULK_EXPORT_PATHS = {"/fhir/$export", "/fhir/Patient/$export"};

    private static final String API_REGISTRATIONS = "/registrations";
    private static final String API_REGISTRATIONS_PATTERN = API_REGISTRATIONS + "/**";

    private static final String API_LAB_ORDERS = "/lab-orders";
    private static final String API_LAB_ORDERS_PATTERN = API_LAB_ORDERS + "/**";

    private static final String API_LAB_TEST_DEFINITIONS = "/lab-test-definitions";
    private static final String API_LAB_TEST_DEFINITIONS_PATTERN = API_LAB_TEST_DEFINITIONS + "/**";

    private static final String API_LAB_RESULTS = "/lab-results";
    private static final String API_LAB_RESULTS_PATTERN = API_LAB_RESULTS + "/**";

    private static final String API_LAB_SPECIMENS = "/lab-specimens";
    private static final String API_LAB_SPECIMENS_PATTERN = API_LAB_SPECIMENS + "/**";

    private static final String API_LAB_QC_EVENTS = "/lab-qc-events";
    private static final String API_LAB_QC_EVENTS_PATTERN = API_LAB_QC_EVENTS + "/**";

    private static final String API_LAB_REFLEX_RULES = "/lab-reflex-rules";
    private static final String API_LAB_REFLEX_RULES_PATTERN = API_LAB_REFLEX_RULES + "/**";

    private static final String API_LAB_HL7 = "/lab/hl7";
    private static final String API_LAB_HL7_PATTERN = API_LAB_HL7 + "/**";

    private static final String API_LAB_INSTRUMENT_OUTBOX = "/lab-instrument-outbox";
    private static final String API_LAB_INSTRUMENT_OUTBOX_PATTERN = API_LAB_INSTRUMENT_OUTBOX + "/**";

    private static final String API_NURSE = "/nurse";
    private static final String API_NURSE_PATTERN = API_NURSE + "/**";

    private static final String API_ME_PATIENT_PATTERN = "/me/patient/**";

    private static final String API_STAFF = "/staff";
    private static final String API_STAFF_PATTERN = API_STAFF + "/**";

    private static final String API_HOSPITALS = "/hospitals";
    private static final String API_HOSPITALS_PATTERN = API_HOSPITALS + "/**";

    // Extracted to avoid duplicated string literals (Sonar S1192).
    // Follows Pattern 5 of docs/SonarQubeInstructions.md.
    private static final String API_DEPARTMENTS = "/departments";
    private static final String API_DEPARTMENTS_PATTERN = API_DEPARTMENTS + "/**";

    private static final String API_BILLING_INVOICES = "/billing-invoices";
    private static final String API_BILLING_INVOICES_PATTERN = API_BILLING_INVOICES + "/**";

    private static final String API_INVOICE_ITEMS = "/invoice-items";
    private static final String API_INVOICE_ITEMS_PATTERN = API_INVOICE_ITEMS + "/**";

    private final HospitalUserDetailsService userDetailsService;
    private final JwtAuthenticationEntryPoint unauthorizedHandler;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * Optional Keycloak resource-server beans (S-03 phase 1). Present only when
     * {@code app.auth.oidc.issuer-uri} is set (see {@code OidcResourceServerConfig}).
     * When absent, the resource-server stack is not wired into the filter chain
     * and behaviour is identical to the pre-S-03 baseline.
     */
    private final ObjectProvider<JwtDecoder> oidcJwtDecoderProvider;
    private final ObjectProvider<BearerTokenResolver> oidcBearerTokenResolverProvider;
    private final KeycloakJwtAuthenticationConverter oidcJwtAuthenticationConverter;
    private final ObjectProvider<KeycloakHospitalContextFilter> oidcHospitalContextFilterProvider;

    /**
     * ObjectProvider on purpose (the PR #536 lesson): the API-key filter is
     * constructed here, not component-scanned, and in a {@code @WebMvcTest}
     * slice with no ApiKeyService bean it degrades to a no-op instead of
     * breaking every controller slice.
     */
    private final ObjectProvider<com.example.hms.service.apikey.ApiKeyService> apiKeyServiceProvider;

    @Value("${app.cors.allowed-origins:http://localhost:4200}")
    private String allowedOrigins;

    /**
     * CDS Hooks sandbox + SMART App Launcher origins. Honored when
     * {@code app.cors.cds-hooks-sandbox.enabled=true} (default).
     * Per the CDS Hooks 1.0 spec the discovery endpoint
     * ({@code GET /cds-services}) must be reachable from the
     * partner EHR's browser context; explicit allowlisting these
     * origins lets the Cerner + Epic + SMART App Launcher
     * validation pages probe HMS without `*` wildcarding.
     *
     * <p>Override with {@code APP_CORS_CDS_HOOKS_SANDBOX_ORIGINS}
     * to add private validation environments. Leave the default
     * intact in production — these are public testing sandboxes
     * and they do not carry PHI.
     */
    @Value("${app.cors.cds-hooks-sandbox.enabled:true}")
    private boolean cdsHooksSandboxOriginsEnabled;

    @Value("${app.cors.cds-hooks-sandbox.origins:"
        + "https://fhir.epic.com,"
        + "https://*.epic.com,"
        + "https://fhir-ehr-code.cerner.com,"
        + "https://sandbox.cerner.com,"
        + "https://*.cerner.com,"
        + "https://launcher.smarthealthit.org,"
        + "https://*.smarthealthit.org}")
    private String cdsHooksSandboxOrigins;

    // ===== Shared beans =====

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        // Spring Security 7: the UserDetailsService is a constructor argument.
        var provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        provider.setAuthoritiesMapper(authoritiesMapper());
        return provider;
    }

    /**
     * E9 #67 (D6): the password-login path widens a super-admin and a
     * doctor-like role through {@link RoleExpansion}, the same rule the JWT
     * path applies per request. This used to carry a fourteen-role list of
     * its own against the JWT path's seven; the login role picker showed the
     * difference. Authorization and tenant isolation are still enforced at
     * the service layer.
     */
    @Bean
    public GrantedAuthoritiesMapper authoritiesMapper() {
        return RoleExpansion.authoritiesMapper();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) {
        return cfg.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        var cfg = new CorsConfiguration();

        // Build origin patterns from env-driven property + local defaults
        var patterns = new java.util.ArrayList<>(List.of(
                "http://localhost:*",
                "http://127.0.0.1:*",
                "https://e-keneya.com",        // prod portal — the apex is NOT matched by the wildcard below
                "https://*.e-keneya.com"       // deployed portals + patient app (dev/prod)
        ));
        if (allowedOrigins != null && !allowedOrigins.isBlank()) {
            for (String origin : allowedOrigins.split(",")) {
                String trimmed = origin.trim();
                if (!trimmed.isEmpty() && !patterns.contains(trimmed)) {
                    patterns.add(trimmed);
                }
            }
        }

        // CDS Hooks sandbox + SMART App Launcher origins (roadmap row 27).
        // Discovery is public per the spec; explicit allowlisting lets the
        // partner sandbox UIs probe HMS without `*` wildcarding.
        if (cdsHooksSandboxOriginsEnabled
            && cdsHooksSandboxOrigins != null
            && !cdsHooksSandboxOrigins.isBlank()) {
            for (String origin : cdsHooksSandboxOrigins.split(",")) {
                String trimmed = origin.trim();
                if (!trimmed.isEmpty() && !patterns.contains(trimmed)) {
                    patterns.add(trimmed);
                }
            }
        }

        cfg.setAllowedOriginPatterns(patterns);
        // TRACE is intentionally excluded (should be denied/disabled)
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setExposedHeaders(List.of("Authorization", "Content-Type"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }

    @Bean
    @Order(1)
    @SuppressWarnings({"java:S3330", "java:S4502"})
    // S3330: XSRF-TOKEN cookie intentionally lacks HttpOnly so Angular can read it when CSRF is enabled.
    // S4502: CSRF is enabled by default, but selectively ignored for preflight and specific public bootstrap endpoint.
    public SecurityFilterChain apiSecurity(HttpSecurity http) {

        var csrfTokenRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokenRepo.setCookiePath("/");

        var csrfRequestHandler = new CsrfTokenRequestAttributeHandler();

        http
            .securityMatcher("/**")
            .cors(c -> {})
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfTokenRepo)
                .csrfTokenRequestHandler(csrfRequestHandler)
                // Keep CSRF enabled for browser-cookie flows; ignore only what is necessary.
                .ignoringRequestMatchers(
                    // CORS preflight (no cookies should mutate state anyway)
                    PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.OPTIONS, "/**"),
                    // Public auth endpoints return JWTs — no cookie session to protect.
                    // The patient-mobile-app (React/fetch) does not use the XSRF-TOKEN
                    // dance, so these must be CSRF-exempt.
                    PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/auth/login"),
                    PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/auth/register"),
                    PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/auth/bootstrap-signup"),
                    PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/auth/token/refresh"),
                    PathPatternRequestMatcher.withDefaults().matcher("/auth/password/**"),
                    PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/auth/resend-verification"),
                    // SockJS handshake & transport (xhr_send, xhr_streaming are POSTs
                    // that bypass Angular's HttpClient and therefore carry no XSRF token)
                    PathPatternRequestMatcher.withDefaults().matcher("/ws-chat/**"),
                    // REST chat endpoints
                    PathPatternRequestMatcher.withDefaults().matcher("/chat/**"),
                    // Patient portal self-service (native mobile apps use Bearer JWT,
                    // not browser cookies, so CSRF protection is unnecessary)
                    PathPatternRequestMatcher.withDefaults().matcher(API_ME_PATIENT_PATTERN),
                    PathPatternRequestMatcher.withDefaults().matcher("/me/notifications/**"),
                    PathPatternRequestMatcher.withDefaults().matcher("/notifications/**"),
                    PathPatternRequestMatcher.withDefaults().matcher("/me/chat/**"),
                    // File uploads from mobile apps (multipart — no XSRF token)
                    PathPatternRequestMatcher.withDefaults().matcher("/files/**"),
                    // Appointment booking from mobile apps (Bearer JWT, no cookies)
                    PathPatternRequestMatcher.withDefaults().matcher("/appointments/**"),
                    // Partner pharmacy SMS webhook (T-55) — shared-secret header auth, no cookies
                    PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/webhooks/partner-sms"),
                    // FHIR R4 endpoints — server-to-server clients (OpenMRS/DHIS2/HIE)
                    // authenticate via Bearer JWT, not browser cookies.
                    PathPatternRequestMatcher.withDefaults().matcher("/fhir/**"),
                    // CDS Hooks invocations — server-to-server callers post JSON
                    // with Bearer JWT.
                    PathPatternRequestMatcher.withDefaults().matcher("/cds-services/**")
                )
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(unauthorizedHandler)
                .accessDeniedHandler((req, res, e) -> res.sendError(HttpServletResponse.SC_FORBIDDEN))
            )
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth

                // Hard deny TRACE everywhere
                .requestMatchers(HttpMethod.TRACE, "/**").denyAll()

                // Preflight
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                // -------------------- Auth / Tokens --------------------
                // Credential / token endpoints require authentication
                .requestMatchers("/auth/credentials/**").authenticated()
                // Refresh is public (access token may be expired)
                .requestMatchers(HttpMethod.POST, "/auth/token/refresh").permitAll()
                .requestMatchers("/auth/token/**").authenticated()
                .requestMatchers("/auth/logout").authenticated()
                .requestMatchers("/auth/verify-password").authenticated()
                .requestMatchers("/auth/me/**").authenticated()
                .requestMatchers("/auth/session/bootstrap").authenticated()

                // Public auth endpoints (login, register, csrf-token bootstrap, etc.)
                .requestMatchers("/auth/csrf-token").permitAll()
                .requestMatchers("/auth/bootstrap-signup").permitAll()
                .requestMatchers("/auth/**").permitAll()

                // -------------------- Swagger / OpenAPI --------------------
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/api-docs/**").permitAll()

                // -------------------- Public / Health --------------------
                .requestMatchers("/error").permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                .requestMatchers("/.well-known/jwks.json").permitAll()
                // FHIR conformance discovery — public per HL7 FHIR R4 spec
                // (clients query /fhir/metadata before authenticating)
                .requestMatchers(HttpMethod.GET, "/fhir/metadata").permitAll()
                // SMART-on-FHIR App Launch 1.0 discovery — public per spec.
                .requestMatchers(HttpMethod.GET, "/fhir/.well-known/smart-configuration").permitAll()
                // Everything else on the FHIR servlet: the chart-reader roles only
                // (FHIR_READER_AUTHORITIES). $export first, because it also
                // admits HOSPITAL_ADMIN; first match wins.
                .requestMatchers(HttpMethod.POST, FHIR_BULK_EXPORT_PATHS)
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN)
                .requestMatchers("/fhir", "/fhir/**").hasAnyAuthority(FHIR_READER_AUTHORITIES)
                // CDS Hooks discovery — public per HL7 CDS Hooks 1.0 spec
                // (decision-support clients enumerate services before auth).
                .requestMatchers(HttpMethod.GET, "/cds-services").permitAll()
                // Partner pharmacy SMS webhook — authenticated inside the controller
                // via the X-HMS-Partner-Signature shared-secret header (T-55).
                .requestMatchers(HttpMethod.POST, "/webhooks/partner-sms").permitAll()

                // Third-party partner surface (Tier 2 item 45): authenticated by
                // ApiKeyAuthenticationFilter from the X-API-Key header. The
                // authority exists ONLY on a verified key — no staff token
                // carries it, so a JWT cannot reach /partner/**.
                .requestMatchers("/partner/**")
                    .hasAuthority(com.example.hms.security.ApiKeyAuthenticationFilter.ROLE_PARTNER_API)

                // Feature flags
                .requestMatchers(HttpMethod.PUT, API_FEATURE_FLAGS, API_FEATURE_FLAGS_PATTERN).hasAuthority(ROLE_SUPER_ADMIN)
                .requestMatchers(HttpMethod.DELETE, API_FEATURE_FLAGS, API_FEATURE_FLAGS_PATTERN).hasAuthority(ROLE_SUPER_ADMIN)
                .requestMatchers(HttpMethod.GET, API_FEATURE_FLAGS, API_FEATURE_FLAGS_PATTERN).permitAll()

                // Phone-first registration: SMS OTP verification shares the
                // registration role set (see PhoneVerificationController).
                .requestMatchers("/patients/phone-verification", "/patients/phone-verification/**")
                .hasAnyAuthority(ROLE_HOSPITAL_ADMIN, ROLE_RECEPTIONIST, ROLE_NURSE, ROLE_MIDWIFE, ROLE_SUPER_ADMIN)

                // ---------------- Patient sharing opt-out (E8 #52) ----------------
                // One verb per line, so nothing depends on which line comes first.
                // GET and POST carry RecordAccessController.OPT_OUT_ROLES; DELETE
                // carries OPT_OUT_REVOKE_ROLES — revoking re-opens the record to
                // other hospitals, so it stays with the patient and the two admin
                // roles the blanket DELETE /patients/** already names. Verbs with no
                // handler fall to the blanket like every other /patients route. A
                // single path, not a prefix: a patient is still refused every other
                // chart route. The service's tenant reach is tasklist debt (E8 #49).
                .requestMatchers(HttpMethod.GET, API_PATIENT_OPT_OUT)
                .hasAnyAuthority(ROLE_PATIENT, ROLE_RECEPTIONIST, ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN)
                .requestMatchers(HttpMethod.POST, API_PATIENT_OPT_OUT)
                .hasAnyAuthority(ROLE_PATIENT, ROLE_RECEPTIONIST, ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN)
                .requestMatchers(HttpMethod.DELETE, API_PATIENT_OPT_OUT)
                .hasAnyAuthority(ROLE_PATIENT, ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN)

                // -------------------- Patient chart (E9 #67, D5) --------------------
                // First match wins: the chart sub-resources sit ahead of the
                // /patients/** blanket below, which HOSPITAL_ADMIN and ADMIN keep
                // for demographics. Same list as the blanket minus those two.
                .requestMatchers(HttpMethod.GET, API_PATIENT_CHART_PATTERNS)
                .hasAnyAuthority(ROLE_RECEPTIONIST, ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE,
                        ROLE_LAB_SCIENTIST, ROLE_LAB_TECHNICIAN, ROLE_LAB_MANAGER,
                        ROLE_LAB_DIRECTOR, ROLE_QUALITY_MANAGER, ROLE_PHARMACIST,
                        ROLE_RADIOLOGIST, ROLE_ANESTHESIOLOGIST, ROLE_PHYSIOTHERAPIST, ROLE_SUPER_ADMIN)

                // -------------------- Patients --------------------
                // PHARMACIST: the shared patient picker (/patients/search, /patients/lookup)
                // backs pharmacist-reachable pages such as /medication-history.
                // ROLE_ADMIN (general administrative user) reads patient
                // demographics for front-office oversight — 2026-08-23 role
                // audit decision C1; the /patients route guard and the
                // patient-tracker backend admitted the role all along.
                // RADIOLOGIST and PHYSIOTHERAPIST join PHARMACIST for the same
                // reason: the shared patient picker lives under /patients/**,
                // and their pages embed it (/imaging, /treatment-plans). The
                // matcher rejected the picker call, so both pages had a dead
                // search box — 2026-08-23 role audit, D4. Chart list/detail
                // stays narrower at the controller.
                .requestMatchers(HttpMethod.GET, API_PATIENTS, API_PATIENTS_PATTERN)
                .hasAnyAuthority(ROLE_HOSPITAL_ADMIN, ROLE_ADMIN, ROLE_RECEPTIONIST, ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE,
                        ROLE_LAB_SCIENTIST, ROLE_LAB_TECHNICIAN, ROLE_LAB_MANAGER,
                        ROLE_LAB_DIRECTOR, ROLE_QUALITY_MANAGER, ROLE_PHARMACIST,
                        ROLE_RADIOLOGIST, ROLE_ANESTHESIOLOGIST, ROLE_PHYSIOTHERAPIST, ROLE_SUPER_ADMIN)

                .requestMatchers(HttpMethod.POST, API_PATIENTS)
                .hasAnyAuthority(ROLE_HOSPITAL_ADMIN, ROLE_RECEPTIONIST, ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE)

                .requestMatchers(HttpMethod.POST, API_PATIENT_VITALS, API_PATIENT_VITALS_PATTERN)
                .hasAnyAuthority(ROLE_NURSE, ROLE_MIDWIFE, ROLE_DOCTOR, ROLE_SUPER_ADMIN)

                // Consulting clinicians READ vitals (pre-operative assessment,
                // exercise tolerance before therapy) but never write them —
                // the POST matcher above stays narrow. Role audit D7.
                // E9 #69: the pharmacist reads vitals to verify a prescription.
                .requestMatchers(HttpMethod.GET, API_PATIENT_VITALS, API_PATIENT_VITALS_PATTERN)
                .hasAnyAuthority(ROLE_NURSE, ROLE_MIDWIFE, ROLE_DOCTOR, ROLE_PHARMACIST, ROLE_RADIOLOGIST,
                        ROLE_ANESTHESIOLOGIST, ROLE_PHYSIOTHERAPIST, ROLE_SUPER_ADMIN)

                .requestMatchers(HttpMethod.PUT, API_PATIENTS_PATTERN)
                .hasAnyAuthority(ROLE_HOSPITAL_ADMIN, ROLE_RECEPTIONIST, ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE)

                .requestMatchers(HttpMethod.PATCH, API_PATIENTS_PATTERN)
                .hasAnyAuthority(ROLE_HOSPITAL_ADMIN, ROLE_RECEPTIONIST, ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE)

                .requestMatchers(HttpMethod.DELETE, API_PATIENTS_PATTERN)
                .hasAnyAuthority(ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN)

                // -------------------- Registrations --------------------
                .requestMatchers(HttpMethod.GET, API_REGISTRATIONS, API_REGISTRATIONS_PATTERN)
                .hasAnyAuthority(ROLE_HOSPITAL_ADMIN, ROLE_RECEPTIONIST, ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE, ROLE_SUPER_ADMIN)

                // Mirrors the controller @PreAuthorize: every role that can create a
                // patient (which auto-registers) can also link an existing one; the
                // service pins non-super-admin writes to the caller's hospital.
                .requestMatchers(HttpMethod.POST, API_REGISTRATIONS)
                .hasAnyAuthority(ROLE_HOSPITAL_ADMIN, ROLE_RECEPTIONIST, ROLE_NURSE, ROLE_MIDWIFE, ROLE_SUPER_ADMIN)

                .requestMatchers(HttpMethod.PUT, API_REGISTRATIONS_PATTERN)
                .hasAnyAuthority(ROLE_HOSPITAL_ADMIN, ROLE_RECEPTIONIST)

                .requestMatchers(HttpMethod.DELETE, API_REGISTRATIONS_PATTERN)
                .hasAnyAuthority(ROLE_HOSPITAL_ADMIN, ROLE_RECEPTIONIST)

                // Allow all clinical staff to register users via admin-register
                .requestMatchers(HttpMethod.POST, "/users/admin-register")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_RECEPTIONIST, ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE)

                // -------------------- Hospitals (tenant-safe) --------------------
                // /me/hospital and /me/hospitals return only the caller's assigned hospital(s).
                .requestMatchers(HttpMethod.GET, "/me/hospital", "/me/hospitals")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_RECEPTIONIST, ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE,
                        ROLE_LAB_SCIENTIST, ROLE_LAB_TECHNICIAN, ROLE_LAB_MANAGER, ROLE_LAB_DIRECTOR, ROLE_QUALITY_MANAGER,
                        // Role audit D7: every authenticated page resolves its
                        // hospital scope here, so the chart 403s without it.
                        ROLE_RADIOLOGIST, ROLE_ANESTHESIOLOGIST, ROLE_PHYSIOTHERAPIST)

                // Global hospital directory (read): open to clinical staff so they can
                // pick destination hospitals in referral / consultation workflows.
                // The controller's @PreAuthorize further narrows allowed roles.
                // Record-access posture (E8 #52): ahead of the /hospitals matchers so
                // the controller's own role sets are the ones that decide.
                .requestMatchers(HttpMethod.GET, API_HOSPITAL_POSTURE)
                .hasAnyAuthority(ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN, ROLE_QUALITY_MANAGER)
                .requestMatchers(HttpMethod.PUT, API_HOSPITAL_POSTURE)
                .hasAnyAuthority(ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN)

                .requestMatchers(HttpMethod.GET, API_HOSPITALS, API_HOSPITALS + "/", API_HOSPITALS_PATTERN)
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_RECEPTIONIST, ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE)

                // Hospital mutations: super-admin only
                .requestMatchers(HttpMethod.POST, API_HOSPITALS_PATTERN).hasAuthority(ROLE_SUPER_ADMIN)
                .requestMatchers(HttpMethod.PUT, API_HOSPITALS_PATTERN).hasAuthority(ROLE_SUPER_ADMIN)
                .requestMatchers(HttpMethod.PATCH, API_HOSPITALS_PATTERN).hasAuthority(ROLE_SUPER_ADMIN)
                .requestMatchers(HttpMethod.DELETE, API_HOSPITALS_PATTERN).hasAuthority(ROLE_SUPER_ADMIN)

                // Organizations and security management
                .requestMatchers("/organizations/**")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN)

                // Public assignment endpoints — no auth required (onboarding flow)
                .requestMatchers(HttpMethod.GET, "/assignments/public/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/assignments/public/**").permitAll()

                // All other assignment endpoints — admin only
                .requestMatchers("/assignments/**")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN)

                // -------------------- Staff --------------------
                // Narrow scheduling matcher FIRST (first-match-wins): the
                // StaffSchedulingController annotations admit STAFF, PHARMACIST
                // and RADIOLOGIST on their own-shift/leave
                // endpoints, but the broad /staff/** matchers 403'd them before
                // any annotation ran (2026-08-23 role audit, B1). The union
                // below mirrors the controller; each endpoint's @PreAuthorize
                // stays the precise gate.
                .requestMatchers("/staff/scheduling/**")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE,
                        ROLE_RECEPTIONIST, ROLE_STAFF, ROLE_PHARMACIST, ROLE_RADIOLOGIST,
                        ROLE_LAB_DIRECTOR, ROLE_LAB_MANAGER, ROLE_LAB_SCIENTIST, ROLE_LAB_TECHNICIAN, ROLE_QUALITY_MANAGER)

                .requestMatchers(HttpMethod.GET, API_STAFF, API_STAFF_PATTERN)
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_RECEPTIONIST, ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE,
                        ROLE_LAB_DIRECTOR, ROLE_LAB_MANAGER, ROLE_LAB_SCIENTIST, ROLE_LAB_TECHNICIAN, ROLE_QUALITY_MANAGER)

                .requestMatchers(HttpMethod.POST, API_STAFF)
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN)

                .requestMatchers(HttpMethod.PUT, API_STAFF_PATTERN)
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN)

                .requestMatchers(HttpMethod.DELETE, API_STAFF_PATTERN)
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN)

                // -------------------- Departments / Roles --------------------
                .requestMatchers(HttpMethod.GET, API_DEPARTMENTS_PATTERN)
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE, ROLE_RECEPTIONIST,
                        ROLE_LAB_DIRECTOR, ROLE_LAB_MANAGER, ROLE_LAB_SCIENTIST, ROLE_LAB_TECHNICIAN, ROLE_QUALITY_MANAGER)

                .requestMatchers(HttpMethod.POST, "/departments/filter")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE, ROLE_RECEPTIONIST,
                        ROLE_LAB_DIRECTOR, ROLE_LAB_MANAGER, ROLE_LAB_SCIENTIST, ROLE_LAB_TECHNICIAN, ROLE_QUALITY_MANAGER)

                .requestMatchers(HttpMethod.POST, API_DEPARTMENTS_PATTERN)
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN)

                .requestMatchers(HttpMethod.PUT, API_DEPARTMENTS_PATTERN)
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN)

                .requestMatchers(HttpMethod.PATCH, API_DEPARTMENTS_PATTERN)
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN)

                .requestMatchers(HttpMethod.DELETE, API_DEPARTMENTS_PATTERN)
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN)

                .requestMatchers("/roles/**")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN)

                // -------------------- Billing --------------------
                .requestMatchers(HttpMethod.GET, API_BILLING_INVOICES_PATTERN)
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_BILLING_SPECIALIST, ROLE_ACCOUNTANT, ROLE_RECEPTIONIST, ROLE_DOCTOR)

                .requestMatchers(HttpMethod.POST, "/billing-invoices/search")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_BILLING_SPECIALIST, ROLE_ACCOUNTANT, ROLE_RECEPTIONIST)

                .requestMatchers(HttpMethod.POST, "/billing-invoices/*/email")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_BILLING_SPECIALIST)

                // Receptionists are included for the payments sub-path, whose
                // controller annotation admits front-desk payment capture. The
                // create and update endpoints still exclude receptionists at the
                // annotation, which remains the precise per-endpoint gate.
                .requestMatchers(HttpMethod.POST, API_BILLING_INVOICES_PATTERN)
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_BILLING_SPECIALIST, ROLE_ACCOUNTANT, ROLE_RECEPTIONIST)

                .requestMatchers(HttpMethod.PUT, API_BILLING_INVOICES_PATTERN)
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_BILLING_SPECIALIST)

                .requestMatchers(HttpMethod.DELETE, API_BILLING_INVOICES_PATTERN)
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN)

                .requestMatchers(HttpMethod.GET, API_INVOICE_ITEMS_PATTERN)
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_BILLING_SPECIALIST, ROLE_ACCOUNTANT)

                .requestMatchers(HttpMethod.POST, API_INVOICE_ITEMS_PATTERN)
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_BILLING_SPECIALIST)

                .requestMatchers(HttpMethod.PUT, API_INVOICE_ITEMS_PATTERN)
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_BILLING_SPECIALIST)

                .requestMatchers(HttpMethod.DELETE, API_INVOICE_ITEMS_PATTERN)
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN)

                .requestMatchers("/invoices/*/email", "/invoices/*/send-to")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_BILLING_SPECIALIST)

                // -------------------- Chat / Notifications --------------------
                // Internal messaging is for EVERY hospital user (2026-08-23
                // role audit, decision C3: real-world semantics) — role
                // enumeration here kept locking out whichever staff role the
                // list forgot (pharmacists, radiologists, therapists…). The
                // real protection is the participant gating inside
                // ChatMessageService: you only read threads you are part of.
                .requestMatchers("/chat/**").authenticated()

                // WebSocket endpoints should NOT be public in an HMS; require authentication.
                .requestMatchers("/ws-chat/**").authenticated()

                // Notifications - allow all authenticated users
                .requestMatchers("/notifications/**").authenticated()

                // -------------------- Patient portal — self-service --------------------
                .requestMatchers(HttpMethod.GET, API_ME_PATIENT_PATTERN).hasAuthority(ROLE_PATIENT)
                .requestMatchers(HttpMethod.PUT, API_ME_PATIENT_PATTERN).hasAuthority(ROLE_PATIENT)
                .requestMatchers(HttpMethod.POST, API_ME_PATIENT_PATTERN).hasAuthority(ROLE_PATIENT)
                .requestMatchers(HttpMethod.DELETE, API_ME_PATIENT_PATTERN).hasAuthority(ROLE_PATIENT)

                // -------------------- Nurse workflow dashboard endpoints --------------------
                .requestMatchers(HttpMethod.GET, API_NURSE, API_NURSE_PATTERN)
                .hasAnyAuthority(ROLE_NURSE, ROLE_MIDWIFE, ROLE_DOCTOR, ROLE_SUPER_ADMIN)
                .requestMatchers(HttpMethod.PUT, API_NURSE, API_NURSE_PATTERN)
                .hasAnyAuthority(ROLE_NURSE, ROLE_MIDWIFE, ROLE_DOCTOR, ROLE_SUPER_ADMIN)
                .requestMatchers(HttpMethod.POST, API_NURSE, API_NURSE_PATTERN)
                .hasAnyAuthority(ROLE_NURSE, ROLE_MIDWIFE, ROLE_DOCTOR, ROLE_SUPER_ADMIN)

                // -------------------- Lab modules --------------------
                .requestMatchers(HttpMethod.GET, API_LAB_TEST_DEFINITIONS, API_LAB_TEST_DEFINITIONS_PATTERN)
                .hasAnyAuthority(ROLE_LAB_SCIENTIST, ROLE_LAB_TECHNICIAN, ROLE_LAB_MANAGER,
                        ROLE_LAB_DIRECTOR, ROLE_QUALITY_MANAGER,
                        ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE, ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN)

                // ROLE_STAFF added to match LabOrderController's GET
                // @PreAuthorize — the filter chain is first-match-wins and
                // terminal, so a role missing HERE gets 403 before the
                // annotation that explicitly permits it ever runs.
                .requestMatchers(HttpMethod.GET, API_LAB_ORDERS, API_LAB_ORDERS_PATTERN)
                .hasAnyAuthority(ROLE_LAB_SCIENTIST, ROLE_LAB_TECHNICIAN, ROLE_LAB_MANAGER,
                        ROLE_LAB_DIRECTOR, ROLE_QUALITY_MANAGER, ROLE_STAFF,
                        ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE, ROLE_SUPER_ADMIN)

                // Workflow sub-resources FIRST (first-match-wins): the state
                // machine requires lab staff for every step past PENDING
                // (LabOrderServiceImpl.validateTransitionAuthority), and
                // specimen collection is lab work — but the broad
                // provider-only POST matcher below used to swallow these
                // paths and 403 exactly the roles the endpoints exist for.
                // This coarse gate carries the UNION of both endpoints'
                // @PreAuthorize lists; the annotations stay the precise gate
                // (the /lab-instrument-outbox/*/retry narrow-scoping
                // precedent).
                .requestMatchers(HttpMethod.POST,
                        API_LAB_ORDERS + "/*/transition",
                        API_LAB_ORDERS + "/*/specimens")
                .hasAnyAuthority(ROLE_LAB_SCIENTIST, ROLE_LAB_TECHNICIAN, ROLE_LAB_MANAGER,
                        ROLE_LAB_DIRECTOR, ROLE_QUALITY_MANAGER,
                        ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE, ROLE_SUPER_ADMIN)

                // Only providers (doctors, nurses, midwives) can place orders (E9 #67: no admins).
                // Exact path only — a /** pattern here is what swallowed the
                // workflow sub-resources above; future sub-resources ride
                // anyRequest().authenticated() + their own @PreAuthorize.
                .requestMatchers(HttpMethod.POST, API_LAB_ORDERS)
                .hasAnyAuthority(ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE, ROLE_SUPER_ADMIN)

                // E9 #69: PHARMACIST reads results (renal function before verifying, V139).
                .requestMatchers(HttpMethod.GET, API_LAB_RESULTS, API_LAB_RESULTS_PATTERN)
                .hasAnyAuthority(ROLE_LAB_SCIENTIST, ROLE_LAB_TECHNICIAN, ROLE_LAB_MANAGER,
                        ROLE_LAB_DIRECTOR, ROLE_QUALITY_MANAGER,
                        ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE, ROLE_PHARMACIST, ROLE_SUPER_ADMIN)

                // The UNION of LabResultAuthority.ENTRY_EXPRESSION, which is the
                // annotation on POST /lab-results: LAB_DIRECTOR, QUALITY_MANAGER
                // and SUPER_ADMIN were missing here and got 403 before the
                // annotation that admits them ever ran
                // (SecurityConfigLabMatcherTest pins the two lists together).
                .requestMatchers(HttpMethod.POST, API_LAB_RESULTS)
                .hasAnyAuthority(ROLE_LAB_SCIENTIST, ROLE_LAB_TECHNICIAN, ROLE_LAB_MANAGER,
                        ROLE_LAB_DIRECTOR, ROLE_QUALITY_MANAGER,
                        ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE, ROLE_SUPER_ADMIN)

                // Aligned to LabResultController's acknowledge @PreAuthorize
                // exactly: the old list omitted HOSPITAL_ADMIN / LAB_DIRECTOR /
                // QUALITY_MANAGER / SUPER_ADMIN (403'd from their own
                // endpoint) and admitted LAB_TECHNICIAN, whom the annotation
                // then rejected anyway.
                .requestMatchers(HttpMethod.POST, API_LAB_RESULTS + "/*/acknowledge")
                .hasAnyAuthority(ROLE_LAB_SCIENTIST, ROLE_LAB_MANAGER, ROLE_LAB_DIRECTOR,
                        ROLE_QUALITY_MANAGER, ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE,
                        ROLE_SUPER_ADMIN)

                .requestMatchers(HttpMethod.PATCH, API_LAB_ORDERS_PATTERN, API_LAB_RESULTS_PATTERN)
                .hasAnyAuthority(ROLE_LAB_SCIENTIST, ROLE_LAB_MANAGER, ROLE_LAB_DIRECTOR, ROLE_QUALITY_MANAGER,
                        ROLE_SUPER_ADMIN)

                // ---- Specimen endpoints (POST /lab-orders/{id}/specimens has its own matcher above) ----
                .requestMatchers(HttpMethod.GET,  API_LAB_SPECIMENS, API_LAB_SPECIMENS_PATTERN)
                .hasAnyAuthority(ROLE_LAB_SCIENTIST, ROLE_LAB_TECHNICIAN, ROLE_LAB_MANAGER,
                        ROLE_LAB_DIRECTOR, ROLE_QUALITY_MANAGER,
                        ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE, ROLE_SUPER_ADMIN)
                .requestMatchers(HttpMethod.POST, API_LAB_SPECIMENS, API_LAB_SPECIMENS_PATTERN)
                .hasAnyAuthority(ROLE_LAB_SCIENTIST, ROLE_LAB_TECHNICIAN, ROLE_LAB_MANAGER,
                        ROLE_LAB_DIRECTOR, ROLE_QUALITY_MANAGER,
                        ROLE_SUPER_ADMIN)

                // ---- QC Events ----
                .requestMatchers(HttpMethod.GET,  API_LAB_QC_EVENTS, API_LAB_QC_EVENTS_PATTERN)
                .hasAnyAuthority(ROLE_LAB_SCIENTIST, ROLE_LAB_TECHNICIAN, ROLE_LAB_MANAGER,
                        ROLE_LAB_DIRECTOR, ROLE_QUALITY_MANAGER,
                        ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN)
                .requestMatchers(HttpMethod.POST, API_LAB_QC_EVENTS)
                .hasAnyAuthority(ROLE_LAB_SCIENTIST, ROLE_LAB_TECHNICIAN, ROLE_LAB_MANAGER,
                        ROLE_LAB_DIRECTOR, ROLE_QUALITY_MANAGER,
                        ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN)

                // ---- Reflex Rules ----
                .requestMatchers(HttpMethod.GET,  API_LAB_REFLEX_RULES, API_LAB_REFLEX_RULES_PATTERN)
                .hasAnyAuthority(ROLE_LAB_SCIENTIST, ROLE_LAB_TECHNICIAN, ROLE_LAB_MANAGER,
                        ROLE_LAB_DIRECTOR, ROLE_QUALITY_MANAGER,
                        ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN)
                .requestMatchers(HttpMethod.POST, API_LAB_REFLEX_RULES, API_LAB_REFLEX_RULES_PATTERN)
                .hasAnyAuthority(ROLE_LAB_SCIENTIST, ROLE_LAB_MANAGER, ROLE_LAB_DIRECTOR, ROLE_QUALITY_MANAGER,
                        ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN)
                .requestMatchers(HttpMethod.PUT, API_LAB_REFLEX_RULES_PATTERN)
                .hasAnyAuthority(ROLE_LAB_SCIENTIST, ROLE_LAB_MANAGER, ROLE_LAB_DIRECTOR, ROLE_QUALITY_MANAGER,
                        ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN)

                // ---- HL7 inbound (system-to-system: an analyzer or middleware account) ----
                // These are the roles Hl7InboundController's own @PreAuthorize names.
                // They must match: the matchers here are first-match-wins, so a
                // matcher narrower than the annotation answers 403 in the filter
                // chain and the annotation never runs. It was narrower, and the
                // three lab roles the endpoint is written for could not reach it at
                // all - the door was open only to HOSPITAL_ADMIN, who is not in the
                // result-author allow-list either.
                //
                // Widening this is safe only because the endpoint now has a tenant
                // boundary that does not come from the caller: the message's sending
                // pair must resolve to an active MLLP allowlist entry and the order
                // must belong to that entry's hospital, and a caller who DOES have a
                // hospital scope is still pinned to it as well. Before that, role
                // was the only gate and the order id came from a header the caller
                // chose.
                .requestMatchers(HttpMethod.POST, API_LAB_HL7, API_LAB_HL7_PATTERN)
                .hasAnyAuthority(ROLE_LAB_TECHNICIAN, ROLE_LAB_SCIENTIST, ROLE_LAB_MANAGER,
                        ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN)

                // ---- Instrument outbox monitoring ----
                .requestMatchers(HttpMethod.GET, API_LAB_INSTRUMENT_OUTBOX, API_LAB_INSTRUMENT_OUTBOX_PATTERN)
                .hasAnyAuthority(ROLE_LAB_SCIENTIST, ROLE_LAB_TECHNICIAN, ROLE_LAB_MANAGER,
                        ROLE_LAB_DIRECTOR, ROLE_QUALITY_MANAGER,
                        ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN)
                // Manual requeue of a failed message. Scoped to exactly this
                // path so the rule cannot drift against the @PreAuthorize on
                // the unrelated POST /lab-instrument-outbox/hl7/parse.
                .requestMatchers(HttpMethod.POST, API_LAB_INSTRUMENT_OUTBOX + "/*/retry")
                .hasAnyAuthority(ROLE_LAB_MANAGER, ROLE_LAB_DIRECTOR,
                        ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN)

                // Public access to profile images ONLY — avatars render via
                // bare <img src> on web + both mobile apps, which cannot
                // carry a bearer token, and new filenames embed a random
                // UUID so they are not guessable. The old "/uploads/**"
                // form also exposed patient documents, chat attachments
                // and chart/referral attachments UNAUTHENTICATED; those
                // classes now have no static mapping at all (WebConfig)
                // and stream only through authenticated, ownership-checked
                // endpoints (/me/patient/documents/{id}/download,
                // /chat/attachments/{id}/download).
                .requestMatchers(HttpMethod.GET, "/uploads/profile-images/**").permitAll()

                .anyRequest().authenticated()
            )
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            // Partner API keys (Tier 2 item 45); no-op outside /partner/**.
            .addFilterBefore(
                new com.example.hms.security.ApiKeyAuthenticationFilter(apiKeyServiceProvider),
                UsernamePasswordAuthenticationFilter.class)
            // Helpful debug for 401/403
            .addFilterAfter((request, response, chain) -> {
                chain.doFilter(request, response);
                if (request instanceof HttpServletRequest req && response instanceof HttpServletResponse res) {
                    int status = res.getStatus();
                    if (status == 401 || status == 403) {
                        var ctx = org.springframework.security.core.context.SecurityContextHolder.getContext();
                        var auth = ctx.getAuthentication();
                        var principal = (auth != null ? auth.getName() : "anonymous");
                        var roles = (auth != null ? auth.getAuthorities() : List.of());
                        SEC_LOG.debug("[SEC API] {} {} -> status={} principal={} roles={}",
                            req.getMethod(), req.getRequestURI(), status, principal, roles);
                    }
                }
            }, UsernamePasswordAuthenticationFilter.class);

        // ── Hardened HTTP response headers ──────────────────────────────────
        http.headers(headers -> headers
            .contentTypeOptions(cto -> {})   // X-Content-Type-Options: nosniff
            .frameOptions(fo -> fo.deny())   // X-Frame-Options: DENY
            .httpStrictTransportSecurity(hsts -> hsts
                .includeSubDomains(true)
                .maxAgeInSeconds(63072000L)
            )
            .contentSecurityPolicy(csp -> csp
                .policyDirectives(
                    "default-src 'self'; " +
                    "script-src 'self'; " +
                    "style-src 'self' 'unsafe-inline'; " +
                    "img-src 'self' data:; " +
                    "font-src 'self'; " +
                    "connect-src 'self'; " +
                    "frame-ancestors 'none'; " +
                    "form-action 'self';"
                )
            )
            .referrerPolicy(rp -> rp
                .policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)
            )
        );

        configureOidcResourceServer(http);

        return http.build();
    }

    /**
     * Optional Keycloak resource-server stack (S-03 phase 1+).
     * Activated only when {@code app.auth.oidc.issuer-uri} is configured. The
     * {@code IssuerAwareBearerTokenResolver} routes only Keycloak-issued
     * tokens (matched by {@code iss} claim) to this filter, so the existing
     * custom {@code JwtAuthenticationFilter} continues to handle internal
     * HMAC/RSA tokens unchanged.
     */
    private void configureOidcResourceServer(HttpSecurity http) {
        JwtDecoder oidcDecoder = oidcJwtDecoderProvider.getIfAvailable();
        BearerTokenResolver oidcResolver = oidcBearerTokenResolverProvider.getIfAvailable();
        if (oidcDecoder == null || oidcResolver == null) {
            return;
        }

        SEC_LOG.info("[OIDC] Keycloak resource-server is enabled — accepting JWTs alongside internal tokens");
        http.oauth2ResourceServer(oauth -> oauth
            .bearerTokenResolver(oidcResolver)
            .jwt(jwt -> jwt
                .decoder(oidcDecoder)
                .jwtAuthenticationConverter(oidcJwtAuthenticationConverter)
            )
        );

        // Populate HospitalContextHolder from Keycloak custom claims
        // (hospital_id, role_assignments) so tenant-scoped specifications
        // and @PreAuthorize rules see the same data they get from the
        // legacy JwtAuthenticationFilter path.
        KeycloakHospitalContextFilter hospitalContextFilter =
                oidcHospitalContextFilterProvider.getIfAvailable();
        if (hospitalContextFilter != null) {
            http.addFilterAfter(hospitalContextFilter, BearerTokenAuthenticationFilter.class);
        }
    }
}