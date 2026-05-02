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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
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
        fixedClock = Clock.fixed(Instant.parse("2026-05-01T10:00:00Z"), ZoneOffset.UTC);
        service = new SmartPhraseServiceImpl(repository, hospitalRepository, userRepository, fixedClock);

        userId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();

        caller = new User();
        caller.setId(userId);
        caller.setUsername("dr.alice");

        hospital = Hospital.builder().name("City Clinic").build();
        hospital.setId(hospitalId);

        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("dr.alice", "n/a"));
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
}
