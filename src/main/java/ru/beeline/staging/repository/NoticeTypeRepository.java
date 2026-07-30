package ru.beeline.staging.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.beeline.staging.domain.NoticeTypeEntity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface NoticeTypeRepository extends JpaRepository<NoticeTypeEntity, Long> {

    Optional<NoticeTypeEntity> findByCode(String code);

    List<NoticeTypeEntity> findByState(String state);

    @Modifying
    @Query("UPDATE NoticeTypeEntity n SET n.state = :state, n.updatedAt = :now, n.confirmedBy = :by WHERE n.code = :code")
    void updateState(@Param("code") String code,
                     @Param("state") String state,
                     @Param("by") String by,
                     @Param("now") OffsetDateTime now);
}
