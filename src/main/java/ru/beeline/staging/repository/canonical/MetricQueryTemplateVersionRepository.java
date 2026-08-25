package ru.beeline.staging.repository.canonical;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.beeline.staging.domain.canonical.MetricQueryTemplateVersion;

public interface MetricQueryTemplateVersionRepository extends JpaRepository<MetricQueryTemplateVersion, Long> {

    @Modifying
    @Query("UPDATE MetricQueryTemplateVersion v SET v.current = false " +
            "WHERE v.metricQueryTemplateId = :metricQueryTemplateId AND v.current = true")
    void clearCurrentFlag(@Param("metricQueryTemplateId") Long metricQueryTemplateId);
}
