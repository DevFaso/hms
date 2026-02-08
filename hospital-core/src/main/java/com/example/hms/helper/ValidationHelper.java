//package com.example.hms.helper;
//
//import com.example.hms.exception.ResourceNotFoundException;
//import com.example.hms.model.User;
//import com.example.hms.payload.dto.PatientRequestDTO;
//import com.example.hms.repository.HospitalRepository;
//import com.example.hms.repository.PatientRepository;
//import com.example.hms.repository.UserRepository;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.context.MessageSource;
//import org.springframework.stereotype.Component;
//
//import java.util.Locale;
//
//@Component
//@RequiredArgsConstructor
//@Slf4j
//public class ValidationHelper {
//
//    private final HospitalRepository hospitalRepository;
//    private final UserRepository userRepository;
//    private final PatientHospitalRegistrationRepository registrationRepository;
//    private final MessageSource messageSource;
//
//    public User validatePatientCreation(PatientRequestDTO dto, Locale locale) {
//        // Validate Hospital
//        if (!hospitalRepository.existsById(dto.getRegistrationHospitalId())) {
//            throw new ResourceNotFoundException(
//                    messageSource.getMessage("hospital.notFound", new Object[]{dto.getRegistrationHospitalId()}, "Hospital not found", locale)
//            );
//        }
//
//        // Validate User
//        User user = userRepository.findById(dto.getUserId())
//                .orElseThrow(() -> new ResourceNotFoundException(
//                        messageSource.getMessage("user.notFound", new Object[]{dto.getUserId()}, "User not found", locale)
//                ));
//
//        // ✅ Correct Check on PatientHospitalRegistration
//        if (registrationRepository.existsByPatientUserIdAndHospitalId(user.getId(), dto.getRegistrationHospitalId())) {
//            throw new IllegalStateException("User already has a Patient registration for this hospital.");
//        }
//
//        log.info("Validation passed for Patient Creation - User ID: {}, Hospital ID: {}", dto.getUserId(), dto.getRegistrationHospitalId());
//        return user;
//    }
//}
