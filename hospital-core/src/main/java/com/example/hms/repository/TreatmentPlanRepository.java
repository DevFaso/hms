package com.example.hms.repository;

import com.example.hms.enums.TreatmentPlanStatus;
import com.example.hms.model.treatment.TreatmentPlan;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface TreatmentPlanRepository extends JpaRepository<TreatmentPlan, UUID>,
    JpaSpecificationExecutor<TreatmentPlan> {

    @EntityGraph(attributePaths = {"patient", "hospital", "encounter", "assignment", "author", "author.user", "supervisingStaff", "supervisingStaff.user", "signOffBy", "signOffBy.user"})
    Page<TreatmentPlan> findAllByHospitalIdAndStatus(UUID hospitalId, TreatmentPlanStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"patient", "hospital", "encounter", "assignment", "author", "author.user", "supervisingStaff", "supervisingStaff.user", "signOffBy", "signOffBy.user"})
    Page<TreatmentPlan> findAllByHospitalId(UUID hospitalId, Pageable pageable);

    @EntityGraph(attributePaths = {"patient", "hospital", "encounter", "assignment", "author", "author.user", "supervisingStaff", "supervisingStaff.user", "signOffBy", "signOffBy.user"})
    Page<TreatmentPlan> findAllByPatientId(UUID patientId, Pageable pageable);

    @EntityGraph(attributePaths = {"patient", "hospital", "encounter", "assignment", "author", "author.user", "supervisingStaff", "supervisingStaff.user", "signOffBy", "signOffBy.user"})
    Page<TreatmentPlan> findAllByPatientIdAndHospitalId(UUID patientId, UUID hospitalId, Pageable pageable);

    /** QA D1910 class: the patient's plans, only in the statuses the patient may read. */
    @EntityGraph(attributePaths = {"patient", "hospital", "encounter", "assignment", "author", "author.user", "author.user.patientProfile", "author.user.staffProfile",
        "supervisingStaff", "supervisingStaff.user", "supervisingStaff.user.patientProfile", "supervisingStaff.user.staffProfile",
        "signOffBy", "signOffBy.user", "signOffBy.user.patientProfile", "signOffBy.user.staffProfile"})
    Page<TreatmentPlan> findAllByPatientIdAndStatusIn(UUID patientId, java.util.Collection<TreatmentPlanStatus> statuses,
                                                      Pageable pageable);

    @EntityGraph(attributePaths = {"patient", "hospital", "encounter", "assignment", "author", "author.user", "supervisingStaff", "supervisingStaff.user", "signOffBy", "signOffBy.user"})
    Page<TreatmentPlan> findAllByStatus(TreatmentPlanStatus status, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"patient", "hospital", "encounter", "assignment", "author", "author.user", "supervisingStaff", "supervisingStaff.user", "signOffBy", "signOffBy.user"})
    Page<TreatmentPlan> findAll(Pageable pageable);

    Optional<TreatmentPlan> findByIdAndHospitalId(UUID id, UUID hospitalId);

    /** Hospital-scoped tile count for the super-admin dashboard. */
    long countByHospital_Id(UUID hospitalId);
}
