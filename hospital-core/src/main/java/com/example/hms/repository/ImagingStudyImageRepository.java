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

    /** Per-order image counts for the chart's imaging rows: {orderId, count}. */
    @Query("select i.imagingOrder.id, count(i) from ImagingStudyImage i "
        + "where i.imagingOrder.id in :orderIds group by i.imagingOrder.id")
    List<Object[]> countByOrderIds(@Param("orderIds") Collection<UUID> orderIds);
}
