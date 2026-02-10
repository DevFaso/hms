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

	@PostMapping("/orders")
	@PreAuthorize("hasAuthority('REQUEST_IMAGING_STUDIES') or hasAnyRole('SUPER_ADMIN','DOCTOR','NURSE','MIDWIFE')")
	@Operation(summary = "Create a new imaging order")
	public ResponseEntity<ImagingOrderResponseDTO> createOrder(
		@Valid @RequestBody ImagingOrderRequestDTO request,
		Authentication authentication
	) {
		UUID userId = extractUserId(authentication);
		ImagingOrderResponseDTO responseDTO = imagingOrderService.createOrder(request, userId);
		return ResponseEntity.status(HttpStatus.CREATED).body(responseDTO);
	}

	@PutMapping("/orders/{orderId}")
	@PreAuthorize("hasAuthority('REQUEST_IMAGING_STUDIES') or hasAnyRole('SUPER_ADMIN','DOCTOR','NURSE','MIDWIFE')")
	@Operation(summary = "Update imaging order details")
	public ResponseEntity<ImagingOrderResponseDTO> updateOrder(
		@PathVariable UUID orderId,
		@Valid @RequestBody ImagingOrderRequestDTO request
	) {
		ImagingOrderResponseDTO responseDTO = imagingOrderService.updateOrder(orderId, request);
		return ResponseEntity.ok(responseDTO);
	}

	@PutMapping("/orders/{orderId}/status")
	@PreAuthorize("hasAuthority('SCHEDULE_IMAGING_APPOINTMENTS') or hasAnyRole('SUPER_ADMIN','DOCTOR','RADIOLOGIST')")
	@Operation(summary = "Update imaging order status, scheduling, or cancellation details")
	public ResponseEntity<ImagingOrderResponseDTO> updateStatus(
		@PathVariable UUID orderId,
		@Valid @RequestBody ImagingOrderStatusUpdateRequestDTO request
	) {
		ImagingOrderResponseDTO responseDTO = imagingOrderService.updateOrderStatus(orderId, request);
		return ResponseEntity.ok(responseDTO);
	}

	@PostMapping("/orders/{orderId}/signature")
	@PreAuthorize("hasAuthority('SIGN_IMAGING_REPORTS') or hasAnyRole('SUPER_ADMIN','DOCTOR')")
	@Operation(summary = "Attach ordering provider signature/attestation")
	public ResponseEntity<ImagingOrderResponseDTO> captureSignature(
		@PathVariable UUID orderId,
		@Valid @RequestBody ImagingOrderSignatureRequestDTO request
	) {
		ImagingOrderResponseDTO responseDTO = imagingOrderService.captureProviderSignature(orderId, request);
		return ResponseEntity.ok(responseDTO);
	}

	@GetMapping("/orders/{orderId}")
	@PreAuthorize("hasAuthority('VIEW_IMAGING_ORDERS') or hasAnyRole('SUPER_ADMIN','DOCTOR','NURSE','RADIOLOGIST')")
	@Operation(summary = "Retrieve imaging order by ID")
	public ResponseEntity<ImagingOrderResponseDTO> getOrder(@PathVariable UUID orderId) {
		ImagingOrderResponseDTO responseDTO = imagingOrderService.getOrder(orderId);
		return ResponseEntity.ok(responseDTO);
	}

	@GetMapping("/orders/patient/{patientId}")
	@PreAuthorize("hasAuthority('VIEW_IMAGING_ORDERS') or hasAnyRole('SUPER_ADMIN','DOCTOR','NURSE','RADIOLOGIST','PATIENT')")
	@Operation(summary = "List imaging orders for a patient")
	public ResponseEntity<List<ImagingOrderResponseDTO>> getOrdersByPatient(
		@PathVariable UUID patientId,
		@RequestParam(required = false) ImagingOrderStatus status
	) {
		List<ImagingOrderResponseDTO> results = imagingOrderService.getOrdersByPatient(patientId, status);
		return ResponseEntity.ok(results);
	}

	@GetMapping("/orders/hospital/{hospitalId}")
	@PreAuthorize("hasAuthority('VIEW_IMAGING_ORDERS') or hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','RADIOLOGIST')")
	@Operation(summary = "List imaging orders for a hospital")
	public ResponseEntity<List<ImagingOrderResponseDTO>> getOrdersByHospital(
		@PathVariable UUID hospitalId,
		@RequestParam(required = false) ImagingOrderStatus status
	) {
		List<ImagingOrderResponseDTO> results = imagingOrderService.getOrdersByHospital(hospitalId, status);
		return ResponseEntity.ok(results);
	}

	@GetMapping("/orders/patient/{patientId}/duplicates")
	@PreAuthorize("hasAuthority('REQUEST_IMAGING_STUDIES') or hasAnyRole('SUPER_ADMIN','DOCTOR','NURSE','MIDWIFE')")
	@Operation(summary = "Preview potential duplicate imaging orders before submission")
	public ResponseEntity<List<ImagingOrderDuplicateMatchDTO>> getDuplicatePreview(
		@PathVariable UUID patientId,
		@RequestParam ImagingModality modality,
		@RequestParam(required = false) String bodyRegion,
		@RequestParam(required = false, defaultValue = "30") Integer lookbackDays
	) {
		List<ImagingOrderDuplicateMatchDTO> duplicates = imagingOrderService.previewDuplicates(patientId, modality, bodyRegion, lookbackDays);
		return ResponseEntity.ok(duplicates);
	}

	private UUID extractUserId(Authentication authentication) {
		if (authentication == null || authentication.getPrincipal() == null) {
			return null;
		}
		Object principal = authentication.getPrincipal();
		if (principal instanceof org.springframework.security.core.userdetails.UserDetails userDetails) {
			try {
				return UUID.fromString(userDetails.getUsername());
			} catch (IllegalArgumentException ignored) {
				return null;
			}
		}
		if (principal instanceof String principalString) {
			try {
				return UUID.fromString(principalString);
			} catch (IllegalArgumentException ignored) {
				return null;
			}
		}
		return null;
	}
}
