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
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
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
import static com.example.hms.config.SecurityConstants.ROLE_MIDWIFE;
import static com.example.hms.config.SecurityConstants.ROLE_NURSE;
import static com.example.hms.config.SecurityConstants.ROLE_PATIENT;
import static com.example.hms.config.SecurityConstants.ROLE_RECEPTIONIST;
import static com.example.hms.config.SecurityConstants.ROLE_STAFF;
import static com.example.hms.config.SecurityConstants.ROLE_SUPER_ADMIN;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private static final Logger SEC_LOG = LoggerFactory.getLogger(SecurityConfig.class);

    // -----------------------------------------------------------------------
    // Path constants — context-path is /api, so Spring Security sees paths
    // *after* the context-path is stripped.  All matchers are relative.
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
    private static final String API_NURSE = "/nurse";
    private static final String API_NURSE_PATTERN = API_NURSE + "/**";
    private static final String API_ME_PATIENT_PATTERN = "/me/patient/**";

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

    /** Expand ROLE_SUPER_ADMIN with operational roles so existing checks remain simple. */
    @Bean
    public GrantedAuthoritiesMapper authoritiesMapper() {
        final Set<String> inherited = Set.of(
            ROLE_HOSPITAL_ADMIN, ROLE_RECEPTIONIST, ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE,
            ROLE_LAB_SCIENTIST, ROLE_STAFF, ROLE_PATIENT
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
        var patterns = new java.util.ArrayList<>(List.of("http://localhost:*", "http://127.0.0.1:*"));
        if (allowedOrigins != null && !allowedOrigins.isBlank()) {
            for (String origin : allowedOrigins.split(",")) {
                String trimmed = origin.trim();
                if (!trimmed.isEmpty() && !patterns.contains(trimmed)) {
                    patterns.add(trimmed);
                }
            }
        }
        cfg.setAllowedOriginPatterns(patterns);
        cfg.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS","HEAD"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setExposedHeaders(List.of("Authorization","Content-Type"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }

    // =====================================================================
    // Chain #1 — Primary API security (all paths inside context-path /api)
    // =====================================================================

    @Bean
    @Order(1)
    public SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/**")
            .cors(c -> {})
            .csrf(AbstractHttpConfigurer::disable) // NOSONAR — stateless JWT API, no session/cookie auth
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(unauthorizedHandler)
                .accessDeniedHandler((req, res, e) -> res.sendError(HttpServletResponse.SC_FORBIDDEN))
            )
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Preflight
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                // Credential / token endpoints require authentication
                .requestMatchers("/auth/credentials/**").authenticated()
                .requestMatchers("/auth/token/**").authenticated()
                .requestMatchers("/auth/logout").authenticated()

                // Public auth endpoints (login, register, etc.)
                .requestMatchers("/auth/**").permitAll()

                // Swagger / OpenAPI
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()

                // Actuator health
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers(HttpMethod.PUT, API_FEATURE_FLAGS, API_FEATURE_FLAGS_PATTERN)
                .hasAuthority(ROLE_SUPER_ADMIN)
                .requestMatchers(HttpMethod.DELETE, API_FEATURE_FLAGS, API_FEATURE_FLAGS_PATTERN)
                .hasAuthority(ROLE_SUPER_ADMIN)
                .requestMatchers(HttpMethod.GET, API_FEATURE_FLAGS, API_FEATURE_FLAGS_PATTERN).permitAll()

                // Patients
                .requestMatchers(HttpMethod.GET, API_PATIENTS, API_PATIENTS_PATTERN)
                .hasAnyAuthority(ROLE_HOSPITAL_ADMIN, ROLE_RECEPTIONIST, ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE, ROLE_SUPER_ADMIN)
                .requestMatchers(HttpMethod.POST, API_PATIENTS)
                .hasAnyAuthority(ROLE_HOSPITAL_ADMIN, ROLE_RECEPTIONIST, ROLE_NURSE, ROLE_MIDWIFE)
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

                // Registrations
                .requestMatchers(HttpMethod.GET, API_REGISTRATIONS, API_REGISTRATIONS_PATTERN)
                .hasAnyAuthority(ROLE_HOSPITAL_ADMIN, ROLE_RECEPTIONIST, ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE, ROLE_SUPER_ADMIN)
                .requestMatchers(HttpMethod.POST, API_REGISTRATIONS)
                .hasAnyAuthority(ROLE_HOSPITAL_ADMIN, ROLE_RECEPTIONIST)
                .requestMatchers(HttpMethod.PUT, API_REGISTRATIONS_PATTERN)
                .hasAnyAuthority(ROLE_HOSPITAL_ADMIN, ROLE_RECEPTIONIST)
                .requestMatchers(HttpMethod.DELETE, API_REGISTRATIONS_PATTERN)
                .hasAnyAuthority(ROLE_HOSPITAL_ADMIN, ROLE_RECEPTIONIST)

                            // Allow receptionist to register patients via admin-register
                            .requestMatchers(HttpMethod.POST, "/users/admin-register")
                            .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_RECEPTIONIST)

                // Hospitals / Staff / Departments / Roles (specific before broad)
                .requestMatchers(HttpMethod.GET, "/hospitals/{id}")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_RECEPTIONIST, ROLE_NURSE, ROLE_MIDWIFE)
                .requestMatchers(HttpMethod.GET, "/hospitals", "/hospitals/")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_RECEPTIONIST, ROLE_NURSE, ROLE_MIDWIFE)
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/me/hospital")
                .hasAnyAuthority(ROLE_RECEPTIONIST, ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN)
                .requestMatchers("/hospitals/**")
                .hasAnyAuthority(ROLE_SUPER_ADMIN)

                // Organizations and security management
                .requestMatchers("/organizations/**")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN)

                .requestMatchers("/assignments/**")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN)
                .requestMatchers("/staff/**")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN)
                .requestMatchers(HttpMethod.GET, "/departments/**")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE)
                .requestMatchers(HttpMethod.POST, "/departments/filter")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN, ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE)
                .requestMatchers("/departments/**")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN)
                .requestMatchers("/roles/**")
                .hasAnyAuthority(ROLE_SUPER_ADMIN, ROLE_HOSPITAL_ADMIN)
                .requestMatchers("/chat/send/**")
                .hasAnyAuthority(ROLE_HOSPITAL_ADMIN, ROLE_STAFF, ROLE_PATIENT, ROLE_RECEPTIONIST, ROLE_NURSE, ROLE_MIDWIFE)

                // Patient portal — self-service endpoints (MyChart equivalent)
                .requestMatchers(HttpMethod.GET, API_ME_PATIENT_PATTERN)
                .hasAuthority(ROLE_PATIENT)
                .requestMatchers(HttpMethod.PUT, API_ME_PATIENT_PATTERN)
                .hasAuthority(ROLE_PATIENT)
                .requestMatchers(HttpMethod.POST, API_ME_PATIENT_PATTERN)
                .hasAuthority(ROLE_PATIENT)
                .requestMatchers(HttpMethod.DELETE, API_ME_PATIENT_PATTERN)
                .hasAuthority(ROLE_PATIENT)

                // Notifications - allow all authenticated users
                .requestMatchers("/notifications/**")
                .authenticated()

                // Nurse workflow dashboard endpoints
                .requestMatchers(HttpMethod.GET, API_NURSE, API_NURSE_PATTERN)
                .hasAnyAuthority(ROLE_NURSE, ROLE_MIDWIFE, ROLE_DOCTOR, ROLE_SUPER_ADMIN)
                .requestMatchers(HttpMethod.PUT, API_NURSE, API_NURSE_PATTERN)
                .hasAnyAuthority(ROLE_NURSE, ROLE_MIDWIFE, ROLE_DOCTOR, ROLE_SUPER_ADMIN)
                .requestMatchers(HttpMethod.POST, API_NURSE, API_NURSE_PATTERN)
                .hasAnyAuthority(ROLE_NURSE, ROLE_MIDWIFE, ROLE_DOCTOR, ROLE_SUPER_ADMIN)

                // ---------- Lab modules ----------
                .requestMatchers(HttpMethod.GET,   "/lab-test-definitions/**")
                .hasAnyAuthority(ROLE_LAB_SCIENTIST, ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE, ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN)
                .requestMatchers(HttpMethod.GET,   API_LAB_ORDERS, API_LAB_ORDERS_PATTERN)
                .hasAnyAuthority(ROLE_LAB_SCIENTIST, ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE, ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN)
                .requestMatchers(HttpMethod.POST,  API_LAB_ORDERS, API_LAB_ORDERS_PATTERN)
                .hasAnyAuthority(ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE, ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN)
                .requestMatchers(HttpMethod.GET,   API_LAB_RESULTS, API_LAB_RESULTS_PATTERN)
                .hasAnyAuthority(ROLE_LAB_SCIENTIST, ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE, ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN)
                .requestMatchers(HttpMethod.POST,  API_LAB_RESULTS)
                .hasAnyAuthority(ROLE_LAB_SCIENTIST, ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE)
                .requestMatchers(HttpMethod.POST,  API_LAB_RESULTS + "/*/acknowledge")
                .hasAnyAuthority(ROLE_LAB_SCIENTIST, ROLE_DOCTOR, ROLE_NURSE, ROLE_MIDWIFE)
                .requestMatchers(HttpMethod.PATCH, API_LAB_ORDERS_PATTERN, API_LAB_RESULTS_PATTERN)
                .hasAnyAuthority(ROLE_LAB_SCIENTIST, ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN)
                .requestMatchers(HttpMethod.POST,  "/lab-results/{id}/attachments")
                .hasAnyAuthority(ROLE_LAB_SCIENTIST, ROLE_HOSPITAL_ADMIN)

                // Public access to uploaded profile images (served as static assets)
                .requestMatchers(HttpMethod.GET, "/uploads/**").permitAll()

                .anyRequest().authenticated()
            )
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            // Helpful debug for 401/403 (cast before calling HttpServletX methods)
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

        return http.build();
    }

    // Chain #2 removed — with context-path=/api, Chain #1's securityMatcher("/**")
    // already handles all requests.  Static assets are served by the Angular SPA
    // on a separate port, not through the Spring Boot backend.
}
