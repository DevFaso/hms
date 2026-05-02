package com.example.hms.service;

import com.example.hms.enums.SmartPhraseScope;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.SmartPhrase;
import com.example.hms.model.User;
import com.example.hms.payload.dto.SmartPhraseRequestDTO;
import com.example.hms.payload.dto.SmartPhraseResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.SmartPhraseRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SmartPhraseServiceImpl}. Pins:
 *  - validation rejects scope/owner mismatches
 *  - autocomplete normalises the prefix and applies USER > HOSPITAL > GLOBAL
 *    precedence so a user macro shadows a hospital macro with the same trigger
 *  - recordUsage delegates to the repository and 404s when the row is gone
 */
@DisplayName("SmartPhraseServiceImpl")
class SmartPhraseServiceImplTest {

    private SmartPhraseRepository repository;
    private HospitalRepository hospitalRepository;
    private UserRepository userRepository;
    private UserRoleHospitalAssignmentRepository assignmentRepository;
    private Clock fixedClock;
    private SmartPhraseServiceImpl service;

    private User caller;
    private Hospital hospital;
    private UUID userId;
    private UUID hospitalId;

    @BeforeEach
    void setUp() {
        repository = mock(SmartPhraseRepository.class);
        hospitalRepository = mock(HospitalRepository.class);
        userRepository = mock(UserRepository.class);
        assignmentRepository = mock(UserRoleHospitalAssignmentRepository.class);
        fixedClock = Clock.fixed(Instant.parse("2026-05-01T10:00:00Z"), ZoneOffset.UTC);
        service = new SmartPhraseServiceImpl(
            repository, hospitalRepository, userRepository, assignmentRepository, fixedClock);

        userId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();

        caller = new User();
        caller.setId(userId);
        caller.setUsername("dr.alice");

        hospital = Hospital.builder().name("City Clinic").build();
        hospital.setId(hospitalId);

        // Default test principal resolves to `caller`; tests that walk an unauthenticated
        // path can clear the context themselves.
        when(userRepository.findByUsernameIgnoreCase("dr.alice")).thenReturn(Optional.of(caller));
        // Default to SUPER_ADMIN so existing test bodies that touch GLOBAL / HOSPITAL
        // scopes pass the new scope authz; a few tests below switch to a clinician
        // principal to pin the negative path.
        authenticateAs("dr.alice", "ROLE_SUPER_ADMIN");
    }

    private void authenticateAs(String username, String... authorities) {
        TestingAuthenticationToken token = new TestingAuthenticationToken(
            username, "n/a",
            java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
        token.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("GLOBAL phrase with hospitalId is rejected")
        void globalRejectsHospital() {
            SmartPhraseRequestDTO req = SmartPhraseRequestDTO.builder()
                .trigger(".normros").title("Normal ROS").expansion("...")
                .scope(SmartPhraseScope.GLOBAL).hospitalId(hospitalId).build();

            assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("GLOBAL");
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("HOSPITAL phrase without hospitalId is rejected")
        void hospitalRequiresHospitalId() {
            SmartPhraseRequestDTO req = SmartPhraseRequestDTO.builder()
                .trigger(".htn").title("HTN").expansion("...")
                .scope(SmartPhraseScope.HOSPITAL).build();

            assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("hospitalId");
        }

        @Test
        @DisplayName("USER phrase without ownerUserId is rejected")
        void userRequiresOwner() {
            SmartPhraseRequestDTO req = SmartPhraseRequestDTO.builder()
                .trigger(".my").title("My").expansion("...")
                .scope(SmartPhraseScope.USER).build();

            assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ownerUserId");
        }

        @Test
        @DisplayName("creates a USER macro and lowercases the trigger")
        void createsUserMacro() {
            SmartPhraseRequestDTO req = SmartPhraseRequestDTO.builder()
                .trigger(".My-FavMacro")
                .title("My Macro")
                .expansion("Some text")
                .scope(SmartPhraseScope.USER)
                .ownerUserId(userId)
                .build();
            when(userRepository.findById(userId)).thenReturn(Optional.of(caller));
            when(repository
                .findFirstByTriggerIgnoreCaseAndScopeAndHospital_IdAndOwner_Id(
                    anyString(), eq(SmartPhraseScope.USER), any(), eq(userId)))
                .thenReturn(Optional.empty());
            when(repository.save(any(SmartPhrase.class)))
                .thenAnswer(inv -> {
                    SmartPhrase sp = inv.getArgument(0);
                    sp.setId(UUID.randomUUID());
                    return sp;
                });

            SmartPhraseResponseDTO response = service.create(req);

            assertThat(response.getTrigger()).isEqualTo(".my-favmacro");
            assertThat(response.getScope()).isEqualTo(SmartPhraseScope.USER);
            assertThat(response.getOwnerUserId()).isEqualTo(userId);
        }

        @Test
        @DisplayName("rejects duplicate trigger at the same scope")
        void rejectsDuplicate() {
            SmartPhraseRequestDTO req = SmartPhraseRequestDTO.builder()
                .trigger(".normros").title("dup").expansion("x")
                .scope(SmartPhraseScope.GLOBAL).build();
            SmartPhrase existing = SmartPhrase.builder()
                .trigger(".normros").title("orig").expansion("y").scope(SmartPhraseScope.GLOBAL).build();
            existing.setId(UUID.randomUUID());
            when(repository.findFirstByTriggerIgnoreCaseAndScopeAndHospitalIsNullAndOwnerIsNull(
                ".normros", SmartPhraseScope.GLOBAL))
                .thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already exists");
        }
    }

    @Nested
    @DisplayName("autocomplete")
    class Autocomplete {

        @Test
        @DisplayName("empty / non-dot prefix returns empty list without hitting the DB")
        void shortCircuitsNonDot() {
            assertThat(service.autocomplete(null, hospitalId)).isEmpty();
            assertThat(service.autocomplete("", hospitalId)).isEmpty();
            assertThat(service.autocomplete("normexam", hospitalId)).isEmpty();
            verify(repository, never()).searchByTriggerPrefix(anyString(), any(), any());
        }

        @Test
        @DisplayName("USER macro shadows HOSPITAL macro with the same trigger")
        void userShadowsHospital() {
            SmartPhrase global = SmartPhrase.builder()
                .trigger(".normros").title("Global").expansion("g").scope(SmartPhraseScope.GLOBAL).build();
            global.setId(UUID.randomUUID());
            SmartPhrase hospitalPhrase = SmartPhrase.builder()
                .trigger(".normros").title("Hospital").expansion("h").scope(SmartPhraseScope.HOSPITAL)
                .hospital(hospital).build();
            hospitalPhrase.setId(UUID.randomUUID());
            SmartPhrase user = SmartPhrase.builder()
                .trigger(".normros").title("Mine").expansion("u").scope(SmartPhraseScope.USER)
                .owner(caller).build();
            user.setId(UUID.randomUUID());
            when(userRepository.findByUsernameIgnoreCase("dr.alice")).thenReturn(Optional.of(caller));
            when(repository.searchByTriggerPrefix(".normros", userId, hospitalId))
                .thenReturn(List.of(user, hospitalPhrase, global));

            List<SmartPhraseResponseDTO> results = service.autocomplete(".normros", hospitalId);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getTitle()).isEqualTo("Mine");
            assertThat(results.get(0).getScope()).isEqualTo(SmartPhraseScope.USER);
        }

        @Test
        @DisplayName("HOSPITAL shadows GLOBAL when no USER macro exists")
        void hospitalShadowsGlobal() {
            SmartPhrase global = SmartPhrase.builder()
                .trigger(".normros").title("Global").expansion("g").scope(SmartPhraseScope.GLOBAL).build();
            global.setId(UUID.randomUUID());
            SmartPhrase hospitalPhrase = SmartPhrase.builder()
                .trigger(".normros").title("Hospital").expansion("h").scope(SmartPhraseScope.HOSPITAL)
                .hospital(hospital).build();
            hospitalPhrase.setId(UUID.randomUUID());
            when(userRepository.findByUsernameIgnoreCase("dr.alice")).thenReturn(Optional.of(caller));
            when(repository.searchByTriggerPrefix(".normros", userId, hospitalId))
                .thenReturn(List.of(hospitalPhrase, global));

            List<SmartPhraseResponseDTO> results = service.autocomplete(".normros", hospitalId);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getScope()).isEqualTo(SmartPhraseScope.HOSPITAL);
        }
    }

    @Nested
    @DisplayName("recordUsage")
    class RecordUsage {
        @Test
        @DisplayName("delegates to repository.incrementUsage with the fixed clock")
        void delegates() {
            UUID id = UUID.randomUUID();
            when(repository.incrementUsage(eq(id), any())).thenReturn(1);
            service.recordUsage(id);
            verify(repository, times(1)).incrementUsage(eq(id), any());
        }

        @Test
        @DisplayName("404s when the row is gone")
        void notFound() {
            UUID id = UUID.randomUUID();
            when(repository.incrementUsage(eq(id), any())).thenReturn(0);
            assertThatThrownBy(() -> service.recordUsage(id))
                .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("update")
    class Update {
        @Test
        @DisplayName("re-saves the existing row with normalised trigger and resolved scope refs")
        void updatesAndNormalises() {
            UUID id = UUID.randomUUID();
            SmartPhrase existing = SmartPhrase.builder()
                .trigger(".old").title("Old").expansion("…").scope(SmartPhraseScope.GLOBAL).build();
            existing.setId(id);
            when(repository.findById(id)).thenReturn(Optional.of(existing));
            // Same id surfaces as a duplicate but the excludeId guard lets it through
            when(repository.findFirstByTriggerIgnoreCaseAndScopeAndHospitalIsNullAndOwnerIsNull(
                ".new", SmartPhraseScope.GLOBAL))
                .thenReturn(Optional.of(existing));
            when(repository.save(any(SmartPhrase.class))).thenAnswer(inv -> inv.getArgument(0));

            SmartPhraseRequestDTO req = SmartPhraseRequestDTO.builder()
                .trigger(".New")
                .title("New title")
                .expansion("New expansion")
                .scope(SmartPhraseScope.GLOBAL)
                .build();

            SmartPhraseResponseDTO out = service.update(id, req);

            assertThat(out.getTrigger()).isEqualTo(".new");
            assertThat(out.getTitle()).isEqualTo("New title");
            assertThat(out.getExpansion()).isEqualTo("New expansion");
        }

        @Test
        @DisplayName("404s when the SmartPhrase is gone")
        void notFound() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.empty());
            SmartPhraseRequestDTO req = SmartPhraseRequestDTO.builder()
                .trigger(".x").title("t").expansion("e")
                .scope(SmartPhraseScope.GLOBAL).build();

            assertThatThrownBy(() -> service.update(id, req))
                .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("HOSPITAL scope: re-uses the hospital-scoped uniqueness lookup")
        void hospitalScopeUpdate() {
            UUID id = UUID.randomUUID();
            SmartPhrase existing = SmartPhrase.builder()
                .trigger(".old").title("o").expansion("e")
                .scope(SmartPhraseScope.HOSPITAL).hospital(hospital).build();
            existing.setId(id);
            when(repository.findById(id)).thenReturn(Optional.of(existing));
            when(repository.findFirstByTriggerIgnoreCaseAndScopeAndHospital_IdAndOwnerIsNull(
                ".new", SmartPhraseScope.HOSPITAL, hospitalId))
                .thenReturn(Optional.empty());
            when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
            when(repository.save(any(SmartPhrase.class))).thenAnswer(inv -> inv.getArgument(0));

            SmartPhraseRequestDTO req = SmartPhraseRequestDTO.builder()
                .trigger(".new").title("t").expansion("x")
                .scope(SmartPhraseScope.HOSPITAL).hospitalId(hospitalId).build();

            SmartPhraseResponseDTO out = service.update(id, req);
            assertThat(out.getScope()).isEqualTo(SmartPhraseScope.HOSPITAL);
            assertThat(out.getHospitalId()).isEqualTo(hospitalId);
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {
        @Test
        @DisplayName("delegates to repository.deleteById when the row exists")
        void deletesExisting() {
            UUID id = UUID.randomUUID();
            // delete now loads the row to authorize against its scope; the default
            // SUPER_ADMIN setUp authority lets the GLOBAL gate pass.
            SmartPhrase row = SmartPhrase.builder()
                .trigger(".g").title("g").expansion("e").scope(SmartPhraseScope.GLOBAL).build();
            row.setId(id);
            when(repository.findById(id)).thenReturn(Optional.of(row));

            service.delete(id);

            verify(repository, times(1)).deleteById(id);
        }

        @Test
        @DisplayName("404s when the row is gone")
        void notFound() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.delete(id))
                .isInstanceOf(ResourceNotFoundException.class);
            verify(repository, never()).deleteById(any());
        }
    }

    @Nested
    @DisplayName("get / listGlobal / findByTrigger")
    class Reads {
        @Test
        @DisplayName("get(id) maps the persisted row to a DTO")
        void getMaps() {
            UUID id = UUID.randomUUID();
            SmartPhrase row = SmartPhrase.builder()
                .trigger(".normros").title("ROS").expansion("body")
                .scope(SmartPhraseScope.HOSPITAL).hospital(hospital).build();
            row.setId(id);
            when(repository.findById(id)).thenReturn(Optional.of(row));

            SmartPhraseResponseDTO out = service.get(id);

            assertThat(out.getTrigger()).isEqualTo(".normros");
            assertThat(out.getHospitalId()).isEqualTo(hospitalId);
        }

        @Test
        @DisplayName("get(id) 404s when the row is gone")
        void getNotFound() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.get(id))
                .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("listGlobal pages through the GLOBAL scope")
        void listGlobalPasses() {
            SmartPhrase row = SmartPhrase.builder()
                .trigger(".normros").title("ROS").expansion("body")
                .scope(SmartPhraseScope.GLOBAL).usageCount(7L).build();
            row.setId(UUID.randomUUID());
            org.springframework.data.domain.Page<SmartPhrase> page =
                new org.springframework.data.domain.PageImpl<>(java.util.List.of(row));
            when(repository.findByScope(eq(SmartPhraseScope.GLOBAL), any())).thenReturn(page);

            org.springframework.data.domain.Page<SmartPhraseResponseDTO> out =
                service.listGlobal(org.springframework.data.domain.PageRequest.of(0, 50));

            assertThat(out.getContent()).hasSize(1);
            assertThat(out.getContent().get(0).getUsageCount()).isEqualTo(7L);
        }

        @Test
        @DisplayName("findByTrigger returns empty for null / blank input without hitting the DB")
        void findByTriggerShortCircuits() {
            assertThat(service.findByTrigger(null, hospitalId)).isEmpty();
            assertThat(service.findByTrigger("  ", hospitalId)).isEmpty();
            verify(repository, never()).searchByTriggerPrefix(anyString(), any(), any());
        }

        @Test
        @DisplayName("findByTrigger returns the precedence-narrowed match for an exact trigger")
        void findByTriggerExactMatch() {
            when(userRepository.findByUsernameIgnoreCase("dr.alice")).thenReturn(Optional.of(caller));
            SmartPhrase global = SmartPhrase.builder()
                .trigger(".normros").title("Global").expansion("g").scope(SmartPhraseScope.GLOBAL).build();
            global.setId(UUID.randomUUID());
            when(repository.searchByTriggerPrefix(".normros", userId, hospitalId))
                .thenReturn(java.util.List.of(global));

            assertThat(service.findByTrigger(".NormROS", hospitalId))
                .isPresent()
                .hasValueSatisfying(dto -> {
                    assertThat(dto.getTrigger()).isEqualTo(".normros");
                    assertThat(dto.getScope()).isEqualTo(SmartPhraseScope.GLOBAL);
                });
        }
    }

    @Nested
    @DisplayName("validateRequest + scope refs")
    class Validation {
        @Test
        @DisplayName("null request → BusinessException")
        void nullRequest() {
            assertThatThrownBy(() -> service.create(null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("required");
        }

        @Test
        @DisplayName("HOSPITAL scope: missing hospital id rejects before save")
        void hospitalRequiresId() {
            SmartPhraseRequestDTO req = SmartPhraseRequestDTO.builder()
                .trigger(".x").title("t").expansion("e")
                .scope(SmartPhraseScope.HOSPITAL).build();
            assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("hospitalId");
        }

        @Test
        @DisplayName("HOSPITAL scope: hospital not found yields ResourceNotFoundException")
        void hospitalNotFound() {
            when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.empty());
            when(repository.findFirstByTriggerIgnoreCaseAndScopeAndHospital_IdAndOwnerIsNull(
                ".x", SmartPhraseScope.HOSPITAL, hospitalId))
                .thenReturn(Optional.empty());

            SmartPhraseRequestDTO req = SmartPhraseRequestDTO.builder()
                .trigger(".x").title("t").expansion("e")
                .scope(SmartPhraseScope.HOSPITAL).hospitalId(hospitalId).build();

            assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(hospitalId.toString());
        }

        @Test
        @DisplayName("USER scope: owner not found yields ResourceNotFoundException")
        void ownerNotFound() {
            // applyOwnershipDefaults overrides the request's ownerUserId with the caller's
            // id, so the only realistic way for resolveOwner to 404 is the caller's User
            // row being deleted between authentication and create — defense in depth.
            when(userRepository.findById(userId)).thenReturn(Optional.empty());
            when(repository.findFirstByTriggerIgnoreCaseAndScopeAndHospitalIsNullAndOwner_Id(
                ".x", SmartPhraseScope.USER, userId)).thenReturn(Optional.empty());

            SmartPhraseRequestDTO req = SmartPhraseRequestDTO.builder()
                .trigger(".x").title("t").expansion("e")
                .scope(SmartPhraseScope.USER).ownerUserId(UUID.randomUUID()).build();

            assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(userId.toString());
        }
    }
}
