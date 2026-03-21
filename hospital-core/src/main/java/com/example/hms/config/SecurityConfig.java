package com.example.hms.config;

import com.example.hms.security.JwtAuthenticationEntryPoint;
import com.example.hms.security.JwtAuthenticationFilter;
import com.example.hms.security.HospitalUserDetailsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.example.hms.config.SecurityConstants.ROLE_DOCTOR;
import static com.example.hms.config.SecurityConstants.ROLE_HOSPITAL_ADMIN;
import static com.example.hms.config.SecurityConstants.ROLE_LAB_SCIENTIST;
import static com.example.hms.config.SecurityConstants.ROLE_LAB_TECHNICIAN;
import static com.example.hms.config.SecurityConstants.ROLE_LAB_MANAGER;
import static com.example.hms.config.SecurityConstants.ROLE_MIDWIFE;
import static com.example.hms.config.SecurityConstants.ROLE_NURSE;
import static com.example.hms.config.SecurityConstants.ROLE_PATIENT;
import static com.example.hms.config.SecurityConstants.ROLE_RECEPTIONIST;
import static com.example.hms.config.SecurityConstants.ROLE_STAFF;
import static com.example.hms.config.SecurityConstants.ROLE_SUPER_ADMIN;
import static com.example.hms.config.SecurityConstants.ROLE_BILLING_SPECIALIST;
import static com.example.hms.config.SecurityConstants.ROLE_ACCOUNTANT;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

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

    private static final String API_REGISTRATIONS = "/registrations";
    private static final String API_REGISTRATIONS_PATTERN = API_REGISTRATIONS + "/**";

    private static final String API_LAB_ORDERS = "/lab-orders";
    private static final String API_LAB_ORDERS_PATTERN = API_LAB_ORDERS + "/**";

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

    private final HospitalUserDetailsService userDetailsService;
    private final JwtAuthenticationEntryPoint unauthorizedHandler;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Value("${app.cors.allowed-origins:http://localhost:4200}")
    private String allowedOrigins;

    // ===== Shared beans =====

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        var provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        provider.setAuthoritiesMapper(authoritiesMapper());
        return provider;
    }

    /**
     * Expand ROLE_SUPER_ADMIN with operational roles so existing checks remain simple.
     * NOTE: Authorization/tenant isolation must still be enforced at service layer.
     */
    @Bean
    public GrantedAuthoritiesMapper authoritiesMapper() {
        final Set<String> inherited = Set.of(
            ROLE_HOSPITAL_ADMIN, ROLE_RECEPTIONIST, ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE,
            ROLE_LAB_SCIENTIST, ROLE_LAB_TECHNICIAN, ROLE_LAB_MANAGER,
            ROLE_STAFF, ROLE_PATIENT, ROLE_BILLING_SPECIALIST, ROLE_ACCOUNTANT
        );
        return (Collection<? extends GrantedAuthority> authorities) -> {
            boolean isSuper = authorities.stream().anyMatch(a -> ROLE_SUPER_ADMIN.equals(a.getAuthority()));
            if (!isSuper) return authorities;
            var extended = new HashSet<GrantedAuthority>(authorities);
            inherited.forEach(r -> extended.add(new SimpleGrantedAuthority(r)));
            return extended;
        };
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        var cfg = new CorsConfiguration();

        // Build origin patterns from env-driven property + local defaults
        var patterns = new java.util.ArrayList<>(List.of(
                "http://localhost:*",
                "http://127.0.0.1:*",
                "https://*.bitnesttechs.com"   // deployed portals (dev/uat/prod)
        ));
        if (allowedOrigins != null && !allowedOrigins.isBlank()) {
            for (String origin : allowedOrigins.split(",")) {
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
    public SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {

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
                    new AntPathRequestMatcher("/**", "OPTIONS"),
                    // Public auth endpoints return JWTs — no cookie session to protect.
                    // The patient-mobile-app (React/fetch) does not use the XSRF-TOKEN
                    // dance, so these must be CSRF-exempt.
                    new AntPathRequestMatcher("/auth/login", "POST"),
                    new AntPathRequestMatcher("/auth/register", "POST"),
                    new AntPathRequestMatcher("/auth/bootstrap-signup", "POST"),
                    new AntPathRequestMatcher("/auth/token/refresh", "POST"),
                    new AntPathRequestMatcher("/auth/password/**"),
                    new AntPathRequestMatcher("/auth/resend-verification", "POST"),
                    // SockJS handshake & transport (xhr_send, xhr_streaming are POSTs
                    // that bypass Angular's HttpClient and therefore carry no XSRF token)
                    new AntPathRequestMatcher("/ws-chat/**"),
                    // REST chat endpoints
                    new AntPathRequestMatcher("/chat/**")
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

                // Public auth endpoints (login, register, csrf-token bootstrap, etc.)
                .requestMatchers("/auth/csrf-token").permitAll()
                .requestMatchers("/auth/bootstrap-signup").permitAll()
                .requestMatchers("/auth/**").permitAll()

                // -------------------- Swagger / OpenAPI --------------------
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/api-docs/**").permitAll()

                // -------------------- Public / Health --------------------
                .requestMatchers("/error").permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()

                // Feature flags
                .requestMatchers(HttpMethod.PUT, API_FEATURE_FLAGS, API_FEATURE_FLAGS_PATTERN).hasAuthority(ROLE_SUPER_ADMIN)
                .requestMatchers(HttpMethod.DELETE, API_FEATURE_FLAGS, API_FEATURE_FLAGS_PATTERN).hasAuthority(ROLE_SUPER_ADMIN)
                .requestMatchers(HttpMethod.GET, API_FEATURE_FLAGS, API_FEATURE_FLAGS_PATTERN).permitAll()

                // -------------------- Patients --------------------
                .requestMatchers(HttpMethod.GET, API_PATIENTS, API_PATIENTS_PATTERN)
                .hasAnyAuthority(ROLE_HOSPITAL_ADMIN, ROLE_RECEPTIONIST, ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE, ROLE_SUPER_ADMIN, ROLE_LAB_SCIENTIST, ROLE_LAB_TECHNICIAN, ROLE_LAB_MANAGER)

                .requestMatchers(HttpMethod.POST, API_PATIENTS)
                .hasAnyAuthority(ROLE_HOSPITAL_ADMIN, ROLE_RECEPTIONIST, ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE)

                .requestMatchers(HttpMethod.POST, API_PATIENT_VITALS, API_PATIENT_VITALS_PATTERN)
                .hasAnyAuthority(ROLE_NURSE, ROLE_MIDWIFE, ROLE_DOCTOR, ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN)

                .requestMatchers(HttpMethod.GET, API_PATIENT_VITALS, API_PATIENT_VITALS_PATTERN)
                .hasAnyAuthority(ROLE_NURSE, ROLE_MIDWIFE, ROLE_DOCTOR, ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN)

                .requestMatchers(HttpMethod.PUT, API_PATIENTS_PATTERN)
                .hasAnyAuthority(ROLE_HOSPITAL_ADMIN, ROLE_RECEPTIONIST, ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE)

                .requestMatchers(HttpMethod.PATCH, API_PATIENTS_PATTERN)
                .hasAnyAuthority(ROLE_HOSPITAL_ADMIN, ROLE_RECEPTIONIST, ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE)

                .requestMatchers(HttpMethod.DELETE, API_PATIENTS_PATTERN)
                .hasAnyAuthority(ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN)

                // -------------------- Registrations --------------------
                .requestMatchers(HttpMethod.GET, API_REGISTRATIONS, API_REGISTRATIONS_PATTERN)
                .hasAnyAuthority(ROLE_HOSPITAL_ADMIN, ROLE_RECEPTIONIST, ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE, ROLE_SUPER_ADMIN)

                .requestMatchers(HttpMethod.POST, API_REGISTRATIONS)
                .hasAnyAuthority(ROLE_HOSPITAL_ADMIN, ROLE_RECEPTIONIST)

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
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_RECEPTIONIST, ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE)

                // Global hospital directory (read): open to clinical staff so they can
                // pick destination hospitals in referral / consultation workflows.
                // The controller's @PreAuthorize further narrows allowed roles.
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
                .requestMatchers(HttpMethod.GET, API_STAFF, API_STAFF_PATTERN)
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_RECEPTIONIST, ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE)

                .requestMatchers(HttpMethod.POST, API_STAFF)
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN)

                .requestMatchers(HttpMethod.PUT, API_STAFF_PATTERN)
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN)

                .requestMatchers(HttpMethod.DELETE, API_STAFF_PATTERN)
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN)

                // -------------------- Departments / Roles --------------------
                .requestMatchers(HttpMethod.GET, "/departments/**")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE, ROLE_RECEPTIONIST)

                .requestMatchers(HttpMethod.POST, "/departments/filter")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE, ROLE_RECEPTIONIST)

                .requestMatchers(HttpMethod.POST, "/departments/**")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN)

                .requestMatchers(HttpMethod.PUT, "/departments/**")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN)

                .requestMatchers(HttpMethod.PATCH, "/departments/**")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN)

                .requestMatchers(HttpMethod.DELETE, "/departments/**")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN)

                .requestMatchers("/roles/**")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN)

                // -------------------- Billing --------------------
                .requestMatchers(HttpMethod.GET, "/billing-invoices/**")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_BILLING_SPECIALIST, ROLE_ACCOUNTANT, ROLE_RECEPTIONIST, ROLE_DOCTOR)

                .requestMatchers(HttpMethod.POST, "/billing-invoices/search")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_BILLING_SPECIALIST, ROLE_ACCOUNTANT, ROLE_RECEPTIONIST)

                .requestMatchers(HttpMethod.POST, "/billing-invoices/*/email")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_BILLING_SPECIALIST)

                .requestMatchers(HttpMethod.POST, "/billing-invoices/**")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_BILLING_SPECIALIST, ROLE_ACCOUNTANT)

                .requestMatchers(HttpMethod.PUT, "/billing-invoices/**")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_BILLING_SPECIALIST)

                .requestMatchers(HttpMethod.DELETE, "/billing-invoices/**")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN)

                .requestMatchers(HttpMethod.GET, "/invoice-items/**")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_BILLING_SPECIALIST, ROLE_ACCOUNTANT)

                .requestMatchers(HttpMethod.POST, "/invoice-items/**")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_BILLING_SPECIALIST)

                .requestMatchers(HttpMethod.PUT, "/invoice-items/**")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_BILLING_SPECIALIST)

                .requestMatchers(HttpMethod.DELETE, "/invoice-items/**")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN)

                .requestMatchers("/invoices/*/email", "/invoices/*/send-to")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_BILLING_SPECIALIST)

                // -------------------- Chat / Notifications --------------------
                .requestMatchers("/chat/**")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE, ROLE_RECEPTIONIST, ROLE_STAFF, ROLE_PATIENT)

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
                .requestMatchers(HttpMethod.GET, "/lab-test-definitions/**")
                .hasAnyAuthority(ROLE_LAB_SCIENTIST, ROLE_LAB_TECHNICIAN, ROLE_LAB_MANAGER,
                        ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE, ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN)

                .requestMatchers(HttpMethod.GET, API_LAB_ORDERS, API_LAB_ORDERS_PATTERN)
                .hasAnyAuthority(ROLE_LAB_SCIENTIST, ROLE_LAB_TECHNICIAN, ROLE_LAB_MANAGER,
                        ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE, ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN)

                // Only providers (doctors, nurses, admins) can place orders
                .requestMatchers(HttpMethod.POST, API_LAB_ORDERS, API_LAB_ORDERS_PATTERN)
                .hasAnyAuthority(ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE, ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN)

                .requestMatchers(HttpMethod.GET, API_LAB_RESULTS, API_LAB_RESULTS_PATTERN)
                .hasAnyAuthority(ROLE_LAB_SCIENTIST, ROLE_LAB_TECHNICIAN, ROLE_LAB_MANAGER,
                        ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE, ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN)

                // Technicians can enter preliminary results; scientists/managers verify/release
                .requestMatchers(HttpMethod.POST, API_LAB_RESULTS)
                .hasAnyAuthority(ROLE_LAB_SCIENTIST, ROLE_LAB_TECHNICIAN, ROLE_LAB_MANAGER,
                        ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE)

                .requestMatchers(HttpMethod.POST, API_LAB_RESULTS + "/*/acknowledge")
                .hasAnyAuthority(ROLE_LAB_SCIENTIST, ROLE_LAB_TECHNICIAN, ROLE_LAB_MANAGER,
                        ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE)

                .requestMatchers(HttpMethod.PATCH, API_LAB_ORDERS_PATTERN, API_LAB_RESULTS_PATTERN)
                .hasAnyAuthority(ROLE_LAB_SCIENTIST, ROLE_LAB_MANAGER, ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN)

                // ---- Specimen endpoints (POST /lab-orders/{id}/specimens handled via API_LAB_ORDERS_PATTERN) ----
                .requestMatchers(HttpMethod.GET,  API_LAB_SPECIMENS, API_LAB_SPECIMENS_PATTERN)
                .hasAnyAuthority(ROLE_LAB_SCIENTIST, ROLE_LAB_TECHNICIAN, ROLE_LAB_MANAGER,
                        ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE, ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN)
                .requestMatchers(HttpMethod.POST, API_LAB_SPECIMENS, API_LAB_SPECIMENS_PATTERN)
                .hasAnyAuthority(ROLE_LAB_SCIENTIST, ROLE_LAB_TECHNICIAN, ROLE_LAB_MANAGER,
                        ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN)

                // ---- QC Events ----
                .requestMatchers(HttpMethod.GET,  API_LAB_QC_EVENTS, API_LAB_QC_EVENTS_PATTERN)
                .hasAnyAuthority(ROLE_LAB_SCIENTIST, ROLE_LAB_TECHNICIAN, ROLE_LAB_MANAGER,
                        ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN)
                .requestMatchers(HttpMethod.POST, API_LAB_QC_EVENTS)
                .hasAnyAuthority(ROLE_LAB_SCIENTIST, ROLE_LAB_TECHNICIAN, ROLE_LAB_MANAGER,
                        ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN)

                // ---- Reflex Rules ----
                .requestMatchers(HttpMethod.GET,  API_LAB_REFLEX_RULES, API_LAB_REFLEX_RULES_PATTERN)
                .hasAnyAuthority(ROLE_LAB_SCIENTIST, ROLE_LAB_TECHNICIAN, ROLE_LAB_MANAGER,
                        ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN)
                .requestMatchers(HttpMethod.POST, API_LAB_REFLEX_RULES, API_LAB_REFLEX_RULES_PATTERN)
                .hasAnyAuthority(ROLE_LAB_SCIENTIST, ROLE_LAB_MANAGER, ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN)
                .requestMatchers(HttpMethod.PUT, API_LAB_REFLEX_RULES_PATTERN)
                .hasAnyAuthority(ROLE_LAB_SCIENTIST, ROLE_LAB_MANAGER, ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN)

                // ---- HL7 inbound (system-to-system; restrict to SUPER_ADMIN / HOSPITAL_ADMIN or a service account) ----
                .requestMatchers(HttpMethod.POST, API_LAB_HL7, API_LAB_HL7_PATTERN)
                .hasAnyAuthority(ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN)

                // ---- Instrument outbox monitoring ----
                .requestMatchers(HttpMethod.GET, API_LAB_INSTRUMENT_OUTBOX, API_LAB_INSTRUMENT_OUTBOX_PATTERN)
                .hasAnyAuthority(ROLE_LAB_SCIENTIST, ROLE_LAB_TECHNICIAN, ROLE_LAB_MANAGER,
                        ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN)

                // Public access to uploaded profile images (static assets)
                .requestMatchers(HttpMethod.GET, "/uploads/**").permitAll()

                .anyRequest().authenticated()
            )
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
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

        return http.build();
    }
}