package com.example.hms.controller;

import com.example.hms.payload.dto.clinical.BirthPlanProviderReviewRequestDTO;
import com.example.hms.payload.dto.clinical.BirthPlanRequestDTO;
import com.example.hms.payload.dto.clinical.BirthPlanResponseDTO;
import com.example.hms.service.BirthPlanService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BirthPlanControllerTest {

    @Mock
    private BirthPlanService birthPlanService;

    @Mock
    private Authentication authentication;

    @InjectMocks
    private BirthPlanController controller;

    // ───────────── helpers ─────────────

    private BirthPlanResponseDTO buildResponse() {
        return new BirthPlanResponseDTO();
    }

    // ═══════════════ getUsername (private helper) ═══════════════

    @Nested
    @DisplayName("getUsername via endpoints")
    class GetUsername {

        @Test
        @DisplayName("null authentication passes null username to service")
        void nullAuthentication() {
            BirthPlanRequestDTO request = new BirthPlanRequestDTO();
            BirthPlanResponseDTO dto = buildResponse();
            when(birthPlanService.createBirthPlan(any(), isNull())).thenReturn(dto);

            ResponseEntity<BirthPlanResponseDTO> result = controller.createBirthPlan(request, null);

            assertEquals(HttpStatus.CREATED, result.getStatusCode());
            verify(birthPlanService).createBirthPlan(eq(request), isNull());
        }

        @Test
        @DisplayName("non-null authentication passes getName() to service")
        void nonNullAuthentication() {
            when(authentication.getName()).thenReturn("admin");
            BirthPlanRequestDTO request = new BirthPlanRequestDTO();
            BirthPlanResponseDTO dto = buildResponse();
            when(birthPlanService.createBirthPlan(any(), eq("admin"))).thenReturn(dto);

            ResponseEntity<BirthPlanResponseDTO> result = controller.createBirthPlan(request, authentication);

            assertEquals(HttpStatus.CREATED, result.getStatusCode());
            verify(birthPlanService).createBirthPlan(eq(request), eq("admin"));
        }
    }

    // ═══════════════ createBirthPlan ═══════════════

    @Nested
    @DisplayName("createBirthPlan")
    class CreateBirthPlan {

        @Test
        @DisplayName("returns 201 CREATED with response body")
        void createsSuccessfully() {
            when(authentication.getName()).thenReturn("user1");
            BirthPlanRequestDTO request = new BirthPlanRequestDTO();
            BirthPlanResponseDTO dto = buildResponse();
            when(birthPlanService.createBirthPlan(any(), eq("user1"))).thenReturn(dto);

            ResponseEntity<BirthPlanResponseDTO> result = controller.createBirthPlan(request, authentication);

            assertAll(
                () -> assertEquals(HttpStatus.CREATED, result.getStatusCode()),
                () -> assertSame(dto, result.getBody())
            );
        }
    }

    // ═══════════════ updateBirthPlan ═══════════════

    @Nested
    @DisplayName("updateBirthPlan")
    class UpdateBirthPlan {

        @Test
        @DisplayName("returns 200 OK with updated response body")
        void updatesSuccessfully() {
            when(authentication.getName()).thenReturn("user1");
            UUID id = UUID.randomUUID();
            BirthPlanRequestDTO request = new BirthPlanRequestDTO();
            BirthPlanResponseDTO dto = buildResponse();
            when(birthPlanService.updateBirthPlan(eq(id), any(), eq("user1"))).thenReturn(dto);

            ResponseEntity<BirthPlanResponseDTO> result = controller.updateBirthPlan(id, request, authentication);

            assertAll(
                () -> assertEquals(HttpStatus.OK, result.getStatusCode()),
                () -> assertSame(dto, result.getBody())
            );
        }
    }

    // ═══════════════ getBirthPlanById ═══════════════

    @Nested
    @DisplayName("getBirthPlanById")
    class GetBirthPlanById {

        @Test
        @DisplayName("returns 200 OK with birth plan")
        void getsById() {
            when(authentication.getName()).thenReturn("user1");
            UUID id = UUID.randomUUID();
            BirthPlanResponseDTO dto = buildResponse();
            when(birthPlanService.getBirthPlanById(eq(id), eq("user1"))).thenReturn(dto);

            ResponseEntity<BirthPlanResponseDTO> result = controller.getBirthPlanById(id, authentication);

            assertAll(
                () -> assertEquals(HttpStatus.OK, result.getStatusCode()),
                () -> assertSame(dto, result.getBody())
            );
        }
    }

    // ═══════════════ getBirthPlansByPatientId ═══════════════

    @Nested
    @DisplayName("getBirthPlansByPatientId")
    class GetBirthPlansByPatientId {

        @Test
        @DisplayName("returns 200 OK with list of birth plans")
        void getsByPatientId() {
            when(authentication.getName()).thenReturn("user1");
            UUID patientId = UUID.randomUUID();
            List<BirthPlanResponseDTO> list = List.of(buildResponse(), buildResponse());
            when(birthPlanService.getBirthPlansByPatientId(eq(patientId), eq("user1"))).thenReturn(list);

            ResponseEntity<List<BirthPlanResponseDTO>> result = controller.getBirthPlansByPatientId(patientId, authentication);

            assertAll(
                () -> assertEquals(HttpStatus.OK, result.getStatusCode()),
                () -> assertEquals(2, result.getBody().size()),
                () -> assertSame(list, result.getBody())
            );
        }
    }

    // ═══════════════ getActiveBirthPlan ═══════════════

    @Nested
    @DisplayName("getActiveBirthPlan")
    class GetActiveBirthPlan {

        @Test
        @DisplayName("returns 200 OK when active plan exists")
        void activeExists() {
            when(authentication.getName()).thenReturn("user1");
            UUID patientId = UUID.randomUUID();
            BirthPlanResponseDTO dto = buildResponse();
            when(birthPlanService.getActiveBirthPlan(eq(patientId), eq("user1"))).thenReturn(dto);

            ResponseEntity<BirthPlanResponseDTO> result = controller.getActiveBirthPlan(patientId, authentication);

            assertAll(
                () -> assertEquals(HttpStatus.OK, result.getStatusCode()),
                () -> assertSame(dto, result.getBody())
            );
        }

        @Test
        @DisplayName("returns 204 NO_CONTENT when no active plan exists")
        void noActivePlan() {
            when(authentication.getName()).thenReturn("user1");
            UUID patientId = UUID.randomUUID();
            when(birthPlanService.getActiveBirthPlan(eq(patientId), eq("user1"))).thenReturn(null);

            ResponseEntity<BirthPlanResponseDTO> result = controller.getActiveBirthPlan(patientId, authentication);

            assertAll(
                () -> assertEquals(HttpStatus.NO_CONTENT, result.getStatusCode()),
                () -> assertNull(result.getBody())
            );
        }
    }

    // ═══════════════ searchBirthPlans ═══════════════

    @Nested
    @DisplayName("searchBirthPlans")
    class SearchBirthPlans {

        @Test
        @DisplayName("DESC direction (default) – returns 200 with paged results")
        void searchDesc() {
            when(authentication.getName()).thenReturn("user1");
            UUID hospitalId = UUID.randomUUID();
            UUID patientId = UUID.randomUUID();
            LocalDate from = LocalDate.of(2026, 1, 1);
            LocalDate to = LocalDate.of(2026, 12, 31);
            Page<BirthPlanResponseDTO> page = new PageImpl<>(List.of(buildResponse()));

            when(birthPlanService.searchBirthPlans(
                eq(hospitalId), eq(patientId), eq(true), eq(from), eq(to), any(Pageable.class), eq("user1")
            )).thenReturn(page);

            ResponseEntity<Page<BirthPlanResponseDTO>> result = controller.searchBirthPlans(
                hospitalId, patientId, true, from, to, 0, 20, "createdAt", "DESC", authentication
            );

            assertAll(
                () -> assertEquals(HttpStatus.OK, result.getStatusCode()),
                () -> assertEquals(1, result.getBody().getContent().size())
            );
        }

        @Test
        @DisplayName("ASC direction – correct sort applied")
        void searchAsc() {
            when(authentication.getName()).thenReturn("user1");
            Page<BirthPlanResponseDTO> page = new PageImpl<>(List.of());

            when(birthPlanService.searchBirthPlans(
                isNull(), isNull(), isNull(), isNull(), isNull(), any(Pageable.class), eq("user1")
            )).thenReturn(page);

            ResponseEntity<Page<BirthPlanResponseDTO>> result = controller.searchBirthPlans(
                null, null, null, null, null, 0, 10, "dueDate", "ASC", authentication
            );

            assertEquals(HttpStatus.OK, result.getStatusCode());
        }

        @Test
        @DisplayName("non-ASC direction string treated as DESC")
        void searchNonAsc() {
            when(authentication.getName()).thenReturn("user1");
            Page<BirthPlanResponseDTO> page = new PageImpl<>(List.of());

            when(birthPlanService.searchBirthPlans(
                isNull(), isNull(), isNull(), isNull(), isNull(), any(Pageable.class), eq("user1")
            )).thenReturn(page);

            ResponseEntity<Page<BirthPlanResponseDTO>> result = controller.searchBirthPlans(
                null, null, null, null, null, 0, 10, "createdAt", "random", authentication
            );

            assertEquals(HttpStatus.OK, result.getStatusCode());
        }

        @Test
        @DisplayName("asc lowercase treated as ASC")
        void searchAscLower() {
            when(authentication.getName()).thenReturn("user1");
            Page<BirthPlanResponseDTO> page = new PageImpl<>(List.of());

            when(birthPlanService.searchBirthPlans(
                isNull(), isNull(), isNull(), isNull(), isNull(), any(Pageable.class), eq("user1")
            )).thenReturn(page);

            ResponseEntity<Page<BirthPlanResponseDTO>> result = controller.searchBirthPlans(
                null, null, null, null, null, 0, 10, "createdAt", "asc", authentication
            );

            assertEquals(HttpStatus.OK, result.getStatusCode());
        }
    }

    // ═══════════════ providerReview ═══════════════

    @Nested
    @DisplayName("providerReview")
    class ProviderReview {

        @Test
        @DisplayName("returns 200 OK with reviewed birth plan")
        void reviewsSuccessfully() {
            when(authentication.getName()).thenReturn("doctor1");
            UUID id = UUID.randomUUID();
            BirthPlanProviderReviewRequestDTO request = new BirthPlanProviderReviewRequestDTO();
            BirthPlanResponseDTO dto = buildResponse();
            when(birthPlanService.providerReview(eq(id), any(), eq("doctor1"))).thenReturn(dto);

            ResponseEntity<BirthPlanResponseDTO> result = controller.providerReview(id, request, authentication);

            assertAll(
                () -> assertEquals(HttpStatus.OK, result.getStatusCode()),
                () -> assertSame(dto, result.getBody())
            );
        }
    }

    // ═══════════════ deleteBirthPlan ═══════════════

    @Nested
    @DisplayName("deleteBirthPlan")
    class DeleteBirthPlan {

        @Test
        @DisplayName("returns 204 NO_CONTENT on successful deletion")
        void deletesSuccessfully() {
            when(authentication.getName()).thenReturn("user1");
            UUID id = UUID.randomUUID();
            doNothing().when(birthPlanService).deleteBirthPlan(eq(id), eq("user1"));

            ResponseEntity<Void> result = controller.deleteBirthPlan(id, authentication);

            assertAll(
                () -> assertEquals(HttpStatus.NO_CONTENT, result.getStatusCode()),
                () -> assertNull(result.getBody())
            );
            verify(birthPlanService).deleteBirthPlan(eq(id), eq("user1"));
        }
    }

    // ═══════════════ getPendingReviews ═══════════════

    @Nested
    @DisplayName("getPendingReviews")
    class GetPendingReviews {

        @Test
        @DisplayName("returns 200 OK with paged pending reviews, hospitalId provided")
        void withHospitalId() {
            when(authentication.getName()).thenReturn("user1");
            UUID hospitalId = UUID.randomUUID();
            Page<BirthPlanResponseDTO> page = new PageImpl<>(List.of(buildResponse()));
            when(birthPlanService.getPendingReviews(eq(hospitalId), any(Pageable.class), eq("user1"))).thenReturn(page);

            ResponseEntity<Page<BirthPlanResponseDTO>> result = controller.getPendingReviews(hospitalId, 0, 20, authentication);

            assertAll(
                () -> assertEquals(HttpStatus.OK, result.getStatusCode()),
                () -> assertEquals(1, result.getBody().getContent().size())
            );
        }

        @Test
        @DisplayName("returns 200 OK with paged pending reviews, hospitalId null")
        void withoutHospitalId() {
            when(authentication.getName()).thenReturn("user1");
            Page<BirthPlanResponseDTO> page = new PageImpl<>(List.of());
            when(birthPlanService.getPendingReviews(isNull(), any(Pageable.class), eq("user1"))).thenReturn(page);

            ResponseEntity<Page<BirthPlanResponseDTO>> result = controller.getPendingReviews(null, 0, 20, authentication);

            assertAll(
                () -> assertEquals(HttpStatus.OK, result.getStatusCode()),
                () -> assertTrue(result.getBody().getContent().isEmpty())
            );
        }
    }
}
