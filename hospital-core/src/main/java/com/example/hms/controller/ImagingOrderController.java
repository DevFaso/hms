package com.example.hms.controller;

import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.ImagingOrderStatus;
import com.example.hms.payload.dto.imaging.ImagingOrderDuplicateMatchDTO;
import com.example.hms.payload.dto.imaging.ImagingOrderRequestDTO;
import com.example.hms.payload.dto.imaging.ImagingOrderResponseDTO;
import com.example.hms.payload.dto.imaging.ImagingOrderSignatureRequestDTO;
import com.example.hms.payload.dto.imaging.ImagingOrderStatusUpdateRequestDTO;
import com.example.hms.service.ImagingOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/imaging")
@Validated
@RequiredArgsConstructor
@Tag(name = "Imaging Orders", description = "Order intake and duplicate checking for radiology studies")
public class ImagingOrderController {

	private final ImagingOrderService imagingOrderService;
	private final com.example.hms.controller.support.RecordsWithheldHeaders recordsWithheld;

	@PostMapping("/orders")
	@PreAuthorize("T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, 'DOCTOR','NURSE','MIDWIFE')")
	@Operation(summary = "Create a new imaging order")
	public ResponseEntity<ImagingOrderResponseDTO> createOrder(
		@Valid @RequestBody ImagingOrderRequestDTO request,
		Authentication authentication
	) {
		UUID userId = com.example.hms.security.PrincipalUserIds.of(authentication).orElse(null);
		ImagingOrderResponseDTO responseDTO = imagingOrderService.createOrder(request, userId);
		return ResponseEntity.status(HttpStatus.CREATED).body(responseDTO);
	}

	@PutMapping("/orders/{orderId}")
	@PreAuthorize("T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, 'DOCTOR','NURSE','MIDWIFE')")
	@Operation(summary = "Update imaging order details")
	public ResponseEntity<ImagingOrderResponseDTO> updateOrder(
		@PathVariable UUID orderId,
		@Valid @RequestBody ImagingOrderRequestDTO request,
		Authentication authentication
	) {
		UUID callerUserId = com.example.hms.security.PrincipalUserIds.of(authentication).orElse(null);
		ImagingOrderResponseDTO responseDTO = imagingOrderService.updateOrder(orderId, request, callerUserId);
		return ResponseEntity.ok(responseDTO);
	}

	@PutMapping("/orders/{orderId}/status")
	@PreAuthorize("T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, 'DOCTOR','RADIOLOGIST')")
	@Operation(summary = "Update imaging order status, scheduling, or cancellation details")
	public ResponseEntity<ImagingOrderResponseDTO> updateStatus(
		@PathVariable UUID orderId,
		@Valid @RequestBody ImagingOrderStatusUpdateRequestDTO request
	) {
		ImagingOrderResponseDTO responseDTO = imagingOrderService.updateOrderStatus(orderId, request);
		return ResponseEntity.ok(responseDTO);
	}

	/**
	 * Provisional rule 1 (2026-11-15): the pre-authorisation number obtained
	 * after an urgent study, at any status the study reached. The status
	 * endpoint's roles: whoever may start a study records its number.
	 */
	@PutMapping("/orders/{orderId}/authorization")
	@PreAuthorize("T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, 'DOCTOR','RADIOLOGIST')")
	@Operation(summary = "Record the pre-authorisation number of an imaging order")
	public ResponseEntity<ImagingOrderResponseDTO> recordAuthorization(
		@PathVariable UUID orderId,
		@Valid @RequestBody com.example.hms.payload.dto.imaging.ImagingOrderAuthorizationRequestDTO request
	) {
		return ResponseEntity.ok(imagingOrderService.recordAuthorization(orderId, request));
	}

	@PostMapping("/orders/{orderId}/signature")
	@PreAuthorize("T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, 'DOCTOR')")
	@Operation(summary = "Attach ordering provider signature/attestation")
	public ResponseEntity<ImagingOrderResponseDTO> captureSignature(
		@PathVariable UUID orderId,
		@Valid @RequestBody ImagingOrderSignatureRequestDTO request,
		Authentication authentication
	) {
		UUID signerUserId = com.example.hms.security.PrincipalUserIds.of(authentication).orElse(null);
		ImagingOrderResponseDTO responseDTO = imagingOrderService.captureProviderSignature(orderId, request, signerUserId);
		return ResponseEntity.ok(responseDTO);
	}

	@GetMapping("/orders/{orderId}")
	@PreAuthorize("hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','ADMIN','DOCTOR','NURSE','MIDWIFE','RADIOLOGIST')")
	@Operation(summary = "Retrieve imaging order by ID")
	public ResponseEntity<ImagingOrderResponseDTO> getOrder(@PathVariable UUID orderId) {
		ImagingOrderResponseDTO responseDTO = imagingOrderService.getOrder(orderId);
		return ResponseEntity.ok(responseDTO);
	}

	@GetMapping("/orders/patient/{patientId}")
	@PreAuthorize("hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','ADMIN','DOCTOR','NURSE','MIDWIFE','RADIOLOGIST','PATIENT')")
	@Operation(summary = "List imaging orders for a patient")
	public ResponseEntity<List<ImagingOrderResponseDTO>> getOrdersByPatient(
		@PathVariable UUID patientId,
		@RequestParam(required = false) ImagingOrderStatus status,
		Authentication auth
	) {
		List<ImagingOrderResponseDTO> results = imagingOrderService.getOrdersByPatient(patientId, status);
		// QA D610 (D504): a list narrowed by the patient's opt-out says so.
		return org.springframework.http.ResponseEntity.ok().headers(recordsWithheld.forRead(auth, patientId,
			com.example.hms.security.tenant.ActingScopeResolver.pinnedHospitalIdOrNull())).body(results);
	}

	/**
	 * QA D1841: the radiologist's worklist (dashboard counts and the /imaging page) — the
	 * service scopes it to the caller's active hospital. The same readers as every other
	 * imaging order read ({@code ImagingReadRoleGuardTest}).
	 */
	@GetMapping("/orders")
	@PreAuthorize("hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','ADMIN','DOCTOR','NURSE','MIDWIFE','RADIOLOGIST')")
	@Operation(summary = "List all imaging orders")
	public ResponseEntity<List<ImagingOrderResponseDTO>> getAllOrders(
		@RequestParam(required = false) ImagingOrderStatus status
	) {
		List<ImagingOrderResponseDTO> results = imagingOrderService.getAllOrders(status);
		return ResponseEntity.ok(results);
	}

	@GetMapping("/orders/hospital/{hospitalId}")
	@PreAuthorize("hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','ADMIN','DOCTOR','NURSE','MIDWIFE','RADIOLOGIST')")
	@Operation(summary = "List imaging orders for a hospital")
	public ResponseEntity<List<ImagingOrderResponseDTO>> getOrdersByHospital(
		@PathVariable UUID hospitalId,
		@RequestParam(required = false) ImagingOrderStatus status
	) {
		List<ImagingOrderResponseDTO> results = imagingOrderService.getOrdersByHospital(hospitalId, status);
		return ResponseEntity.ok(results);
	}

	@GetMapping("/orders/patient/{patientId}/duplicates")
	@PreAuthorize("T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, 'DOCTOR','NURSE','MIDWIFE')")
	@Operation(summary = "Preview potential duplicate imaging orders before submission",
		description = "The patient's non-cancelled orders of the same modality (and region) at the caller's hospital "
			+ "only, within lookbackDays (default 30, capped at 90). A patient not registered at that hospital "
			+ "answers 404. hospitalId is read only for a super-admin in global view.")
	public ResponseEntity<List<ImagingOrderDuplicateMatchDTO>> getDuplicatePreview(
		@PathVariable UUID patientId,
		@RequestParam ImagingModality modality,
		@RequestParam(required = false) String bodyRegion,
		@RequestParam(required = false, defaultValue = "30") Integer lookbackDays,
		@RequestParam(required = false) UUID hospitalId
	) {
		// QA D1920: scoped to the caller's hospital, the window capped server-side.
		List<ImagingOrderDuplicateMatchDTO> duplicates = imagingOrderService.previewDuplicates(patientId, hospitalId, modality, bodyRegion, lookbackDays);
		return ResponseEntity.ok(duplicates);
	}
}
