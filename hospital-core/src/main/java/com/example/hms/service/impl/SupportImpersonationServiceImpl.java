package com.example.hms.service.impl;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.exception.UnauthorizedException;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.superadmin.ImpersonationActiveResponseDTO;
import com.example.hms.payload.dto.superadmin.ImpersonationStartRequestDTO;
import com.example.hms.payload.dto.superadmin.ImpersonationStartResponseDTO;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.ImpersonationSessionTracker;
import com.example.hms.security.JwtTokenProvider;
import com.example.hms.security.SecurityUtils;
import com.example.hms.security.TokenBlacklistService;
import com.example.hms.security.TokenUserDescriptor;
import com.example.hms.security.auth.TenantRoleAssignment;
import com.example.hms.security.auth.TenantRoleAssignmentAccessor;
import com.example.hms.security.context.ImpersonationContext;
import com.example.hms.security.context.ImpersonationContextHolder;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.MfaService;
import com.example.hms.service.SupportImpersonationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class SupportImpersonationServiceImpl implements SupportImpersonationService {

    private static final String ROLE_SUPER_ADMIN = "ROLE_SUPER_ADMIN";
    private static final String ENTITY_TYPE_USER = "User";

    private final UserRepository userRepository;
    private final TenantRoleAssignmentAccessor tenantRoleAssignmentAccessor;
    private final JwtTokenProvider tokenProvider;
    private final MfaService mfaService;
    private final AuditEventLogService auditEventLogService;
    private final TokenBlacklistService tokenBlacklistService;
    private final ImpersonationSessionTracker sessionTracker;

    /** Duration of the impersonation token in milliseconds. Default 30 minutes. */
    @Value("${hms.support-impersonation.ttl-ms:1800000}")
    private long impersonationTtlMs;

    /**
     * When true, an actor without MFA enrolled is rejected outright. Off by
     * default so an unenrolled super admin who needs to take an emergency
     * support session is not locked out — the bypass is audited so ops sees
     * which session ran without MFA.
     */
    @Value("${hms.support-impersonation.require-mfa-strict:false}")
    private boolean requireMfaStrict;

    public SupportImpersonationServiceImpl(UserRepository userRepository,
                                           TenantRoleAssignmentAccessor tenantRoleAssignmentAccessor,
                                           JwtTokenProvider tokenProvider,
                                           MfaService mfaService,
                                           AuditEventLogService auditEventLogService,
                                           TokenBlacklistService tokenBlacklistService,
                                           ImpersonationSessionTracker sessionTracker) {
        this.userRepository = userRepository;
        this.tenantRoleAssignmentAccessor = tenantRoleAssignmentAccessor;
        this.tokenProvider = tokenProvider;
        this.mfaService = mfaService;
        this.auditEventLogService = auditEventLogService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.sessionTracker = sessionTracker;
    }

    @Override
    @Transactional
    public ImpersonationStartResponseDTO start(ImpersonationStartRequestDTO request,
                                               String mfaToken,
                                               String bearerJwt) {
        if (request == null || request.getTargetUserId() == null) {
            throw new BusinessException("targetUserId is required");
        }
        if (request.getReason() == null || request.getReason().isBlank()) {
            throw new BusinessException("reason is required to start impersonation");
        }
        if (ImpersonationContextHolder.isImpersonating()) {
            // Belt-and-braces: the controller is gated on ROLE_SUPER_ADMIN
            // and an impersonation token does not carry that role, so this
            // path should be unreachable. Block explicitly anyway so a
            // future role-claim regression cannot enable nested sessions.
            throw new BusinessException("Cannot start impersonation while already impersonating");
        }

        User actor = resolveCurrentSuperAdmin();
        User target = userRepository.findById(request.getTargetUserId())
            .orElseThrow(() -> new ResourceNotFoundException(
                "user.notFound", request.getTargetUserId().toString()));

        if (actor.getId().equals(target.getId())) {
            throw new BusinessException("Cannot impersonate yourself");
        }

        // Defence in depth — also block if the actor already has an active
        // session registered in the cross-process tracker (a second tab or a
        // duplicate browser would otherwise win this race).
        if (sessionTracker.hasActive(actor.getId())) {
            throw new BusinessException("An impersonation session is already active for this account");
        }

        List<TenantRoleAssignment> targetAssignments =
            tenantRoleAssignmentAccessor.findAssignmentsForUser(target.getId());
        boolean targetIsSuperAdmin = targetAssignments.stream()
            .filter(TenantRoleAssignment::active)
            .map(TenantRoleAssignment::roleCode)
            .filter(java.util.Objects::nonNull)
            .anyMatch(code -> code.equalsIgnoreCase("SUPER_ADMIN")
                || code.equalsIgnoreCase(ROLE_SUPER_ADMIN));
        if (targetIsSuperAdmin) {
            throw new BusinessException("Cannot impersonate another super admin");
        }

        verifyMfaStepUp(actor.getId(), mfaToken);

        List<String> targetRoles = targetAssignments.stream()
            .filter(TenantRoleAssignment::active)
            .map(this::roleLabel)
            .filter(s -> s != null && !s.isBlank())
            .distinct()
            .toList();
        if (targetRoles.isEmpty()) {
            throw new BusinessException(
                "Target user has no active role assignments and cannot be impersonated");
        }

        TokenUserDescriptor descriptor = new TokenUserDescriptor(
            target.getId(), target.getUsername(), targetRoles);
        String accessToken = tokenProvider.generateImpersonationAccessToken(
            descriptor, actor.getId(), actor.getUsername(), impersonationTtlMs);
        Instant expiresAt = Instant.now().plusMillis(impersonationTtlMs);

        // Closes Copilot review #2 (PR #224): the original super-admin access
        // token may still be sitting in the client's localStorage from a
        // remember-me login; blacklist it now so a stale getToken() reading
        // it cannot keep authenticating as super admin alongside the new
        // impersonation token.
        blacklistBearerIfPossible(bearerJwt, "original super-admin token at impersonation start");

        // Closes Copilot review #4: register the active session so
        // /auth/token/refresh refuses to mint a new super-admin access
        // token from the original refresh cookie until the impersonation
        // explicitly ends.
        String impersonationJti = tokenProvider.getJtiFromToken(accessToken);
        sessionTracker.register(actor.getId(), target.getId(), impersonationJti, expiresAt);

        emitBoundaryAudit(
            actor, target, AuditEventType.IMPERSONATION_STARTED,
            "Started impersonating " + target.getUsername() + ". Reason: " + request.getReason());

        log.info("[IMPERSONATION] {} (super-admin {}) started impersonating {} ({}); ttl={}ms",
            actor.getUsername(), actor.getId(), target.getUsername(), target.getId(), impersonationTtlMs);

        return ImpersonationStartResponseDTO.builder()
            .accessToken(accessToken)
            .expiresAt(expiresAt)
            .impersonatorUserId(actor.getId())
            .impersonatorUsername(actor.getUsername())
            .targetUserId(target.getId())
            .targetUsername(target.getUsername())
            .build();
    }

    @Override
    @Transactional
    public ImpersonationActiveResponseDTO stop(String bearerJwt) {
        ImpersonationContext ctx = ImpersonationContextHolder.get().orElse(null);
        if (ctx == null) {
            throw new BusinessException("No impersonation session is active for this token");
        }
        // Resolve impersonator + target without trusting the JWT claims for
        // anything that ends up in the audit row beyond the username.
        User actor = userRepository.findById(ctx.impersonatorUserId()).orElse(null);
        User target = SecurityUtils.getCurrentUsername() != null
            ? userRepository.findByUsername(SecurityUtils.getCurrentUsername()).orElse(null)
            : null;

        if (actor != null && target != null) {
            emitBoundaryAudit(actor, target, AuditEventType.IMPERSONATION_ENDED,
                "Ended impersonation of " + target.getUsername());
        } else {
            log.warn("[IMPERSONATION] stop() called but actor or target could not be resolved (actor={}, target={})",
                ctx.impersonatorUserId(), SecurityUtils.getCurrentUsername());
        }

        // Closes Copilot review #6 (PR #224): blacklist the impersonation
        // token so a copy of it (curl, devtools, browser plugin) cannot
        // continue to authenticate after the super admin clicks Exit.
        blacklistBearerIfPossible(bearerJwt, "impersonation token at stop");

        // Unblock /auth/token/refresh for the original super admin so they
        // can mint a fresh super-admin access token from the surviving
        // refresh cookie.
        sessionTracker.unregister(ctx.impersonatorUserId());

        return ImpersonationActiveResponseDTO.builder()
            .impersonating(false)
            .impersonatorUserId(ctx.impersonatorUserId())
            .impersonatorUsername(ctx.impersonatorUsername())
            .targetUserId(target == null ? null : target.getId())
            .targetUsername(target == null ? null : target.getUsername())
            .build();
    }

    @Override
    @Transactional(readOnly = true)
    public ImpersonationActiveResponseDTO getActive() {
        ImpersonationContext ctx = ImpersonationContextHolder.get().orElse(null);
        if (ctx == null) {
            return ImpersonationActiveResponseDTO.builder().impersonating(false).build();
        }
        String username = SecurityUtils.getCurrentUsername();
        UUID targetId = username != null
            ? userRepository.findByUsername(username).map(User::getId).orElse(null)
            : null;
        return ImpersonationActiveResponseDTO.builder()
            .impersonating(true)
            .impersonatorUserId(ctx.impersonatorUserId())
            .impersonatorUsername(ctx.impersonatorUsername())
            .targetUserId(targetId)
            .targetUsername(username)
            .build();
    }

    /**
     * Best-effort: blacklist {@code bearerJwt} via the {@link TokenBlacklistService}
     * so subsequent requests using the same token are rejected by
     * {@code JwtAuthenticationFilter}. Swallows any token-parsing or
     * blacklist failure with a warning — the boundary action (start / stop)
     * must succeed even if blacklisting hits a transient issue. Returns
     * silently when the bearer is null or blank (defensive — the controller
     * may be called by a test that omits the header).
     */
    private void blacklistBearerIfPossible(String bearerJwt, String reason) {
        if (bearerJwt == null || bearerJwt.isBlank()) {
            return;
        }
        try {
            String jti = tokenProvider.getJtiFromToken(bearerJwt);
            if (jti == null || jti.isBlank()) {
                return;
            }
            long expirationMs = tokenProvider.getExpiration(bearerJwt).getTime();
            tokenBlacklistService.blacklist(jti, expirationMs);
            log.debug("[IMPERSONATION] Blacklisted token jti={} ({})", jti, reason);
        } catch (RuntimeException ex) {
            log.warn("[IMPERSONATION] Failed to blacklist {} — {}", reason, ex.getMessage());
        }
    }

    private User resolveCurrentSuperAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new UnauthorizedException("Not authenticated");
        }
        boolean hasSuperAdmin = auth.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(ROLE_SUPER_ADMIN::equalsIgnoreCase);
        if (!hasSuperAdmin) {
            throw new UnauthorizedException("Caller is not a super admin");
        }
        String username = auth.getName();
        return userRepository.findByUsername(username)
            .orElseThrow(() -> new UnauthorizedException(
                "Super-admin actor user record not found for " + username));
    }

    private void verifyMfaStepUp(UUID actorId, String mfaToken) {
        boolean enrolled;
        try {
            enrolled = mfaService.isMfaEnabled(actorId);
        } catch (RuntimeException ex) {
            log.warn("[IMPERSONATION] MFA enrollment lookup failed for actor {}: {}",
                actorId, ex.getMessage());
            throw new UnauthorizedException("mfa_required: enrollment lookup unavailable");
        }
        if (enrolled) {
            if (mfaToken == null || mfaToken.isBlank() || !mfaService.verifyCode(actorId, mfaToken)) {
                throw new UnauthorizedException(
                    "mfa_required: invalid or missing X-Mfa-Token for impersonation");
            }
            return;
        }
        if (requireMfaStrict) {
            throw new UnauthorizedException(
                "mfa_required: actor must enrol MFA before starting impersonation");
        }
        // Unenrolled actor — let it through but audit the bypass.
        try {
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userId(actorId)
                .eventType(AuditEventType.SECURITY_ALERT_TRIGGERED)
                .eventDescription("Impersonation start performed without MFA step-up "
                    + "(actor not enrolled, non-strict mode).")
                .entityType(ENTITY_TYPE_USER)
                .status(AuditStatus.SUCCESS)
                .build());
        } catch (RuntimeException ex) {
            log.error("[IMPERSONATION] Failed to audit MFA-bypass event", ex);
        }
    }

    private void emitBoundaryAudit(User actor, User target, AuditEventType type, String description) {
        try {
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userId(actor.getId())
                .userName(actor.getUsername())
                .eventType(type)
                .eventDescription(description)
                .entityType(ENTITY_TYPE_USER)
                .resourceId(target.getId().toString())
                .resourceName(target.getUsername())
                .status(AuditStatus.SUCCESS)
                .impersonatorUserId(actor.getId())
                .impersonatorUsername(actor.getUsername())
                .build());
        } catch (RuntimeException ex) {
            log.error("[IMPERSONATION] Failed to emit boundary audit event {}", type, ex);
        }
    }

    private String roleLabel(TenantRoleAssignment assignment) {
        String candidate = assignment.roleName();
        if (candidate == null || candidate.isBlank()) {
            candidate = assignment.roleCode();
        }
        return candidate;
    }
}
