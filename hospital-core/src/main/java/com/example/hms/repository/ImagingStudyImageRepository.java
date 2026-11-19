package com.example.hms.repository;

import com.example.hms.model.ImagingStudyImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ImagingStudyImageRepository extends JpaRepository<ImagingStudyImage, UUID> {

    List<ImagingStudyImage> findByImagingOrder_IdOrderByCreatedAtAsc(UUID imagingOrderId);

    long countByImagingOrder_Id(UUID imagingOrderId);

    /**
     * Per-order image counts for the chart's imaging rows and the record PDF: {orderId, count}.
     * QA D2468 / D2464 (r64): what the record shows, so neither a withdrawn image nor one on an
     * addendum still waiting for its signature (on a study already signed) is counted.
     */
    @Query("select i.imagingOrder.id, count(i) from ImagingStudyImage i left join i.imagingReport r "
        + "where i.imagingOrder.id in :orderIds and i.withdrawnAt is null "
        + "and (r is null or r.signedAt is not null or not exists ("
        + "select s.id from ImagingReport s where s.imagingOrder = i.imagingOrder and s.signedAt is not null)) "
        + "group by i.imagingOrder.id")
    List<Object[]> countByOrderIds(@Param("orderIds") Collection<UUID> orderIds);

    /** D2342: the key images of several studies at once (the patient's imaging page). */
    List<ImagingStudyImage> findByImagingOrder_IdInOrderByCreatedAtAsc(Collection<UUID> imagingOrderIds);
}
