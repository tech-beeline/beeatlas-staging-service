package ru.beeline.staging.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.beeline.staging.domain.ArtifactNoticeEntity;
import ru.beeline.staging.domain.NoticeTypeEntity;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.dto.notice.NoticeType;
import ru.beeline.staging.repository.ArtifactNoticeRepository;
import ru.beeline.staging.repository.NoticeTypeRepository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArtifactNoticeService {

    private final NoticeTypeRepository noticeTypeRepository;
    private final ArtifactNoticeRepository artifactNoticeRepository;
    private final RawDataContextService rawDataContextService;

    @Transactional("stagingTransactionManager")
    public List<ArtifactNotice> saveNotices(Long rawDataRefId, List<ArtifactNotice> notices) {
        if (notices == null || notices.isEmpty()) return List.of();

        List<ArtifactNotice> saved = new ArrayList<>();
        for (ArtifactNotice notice : notices) {
            NoticeTypeEntity type = resolveOrRegisterType(notice);
            if ("rejected".equals(type.getState())) {
                log.debug("Skipping notice with rejected type: code={}", notice.code());
                continue;
            }

            // artifact_notices.raw_data_context_id is NOT NULL: a json_path (starting with "/") in
            // context() gets materialized with its byte range, everything else falls back to a
            // free-text context row so the FK is always satisfiable.
            Long rawDataContextId = notice.rawDataContextId();
            String contextText = notice.context();
            if (rawDataContextId == null) {
                rawDataContextId = (contextText != null && contextText.startsWith("/"))
                        ? rawDataContextService.pointTo(rawDataRefId, contextText)
                        : rawDataContextService.pointToFreeText(rawDataRefId, contextText);
            }

            ArtifactNoticeEntity entity = new ArtifactNoticeEntity();
            entity.setNoticeTypeId(type.getId());
            entity.setDetails(notice.details());
            entity.setRawDataContextId(rawDataContextId);
            ArtifactNoticeEntity persisted = artifactNoticeRepository.save(entity);

            saved.add(new ArtifactNotice(
                    persisted.getId(), type.getId(),
                    notice.code(), notice.level(), notice.category(),
                    rawDataRefId,
                    notice.entityType(), notice.entityUid(), notice.entityVersionId(),
                    notice.message(), notice.details(), contextText,
                    rawDataContextId
            ));
        }
        return saved;
    }

    @Transactional(value = "stagingTransactionManager", propagation = Propagation.REQUIRES_NEW)
    NoticeTypeEntity resolveOrRegisterType(ArtifactNotice notice) {
        return noticeTypeRepository.findByCode(notice.code()).orElseGet(() -> {
            NoticeTypeEntity entity = new NoticeTypeEntity();
            entity.setCode(notice.code());
            entity.setLevel(notice.level());
            entity.setCategory(notice.category());
            entity.setState("pending");
            try {
                NoticeTypeEntity saved = noticeTypeRepository.saveAndFlush(entity);
                log.info("Auto-registered new notice type: code={} level={} category={}", notice.code(), notice.level(), notice.category());
                return saved;
            } catch (DataIntegrityViolationException ex) {
                return noticeTypeRepository.findByCode(notice.code()).orElseThrow();
            }
        });
    }

    @Transactional("stagingTransactionManager")
    public NoticeType confirmNoticeType(String code, String confirmedBy) {
        noticeTypeRepository.updateState(code, "confirmed", confirmedBy, OffsetDateTime.now());
        return toDto(noticeTypeRepository.findByCode(code).orElseThrow());
    }

    @Transactional("stagingTransactionManager")
    public void rejectNoticeType(String code, String rejectedBy) {
        noticeTypeRepository.updateState(code, "rejected", rejectedBy, OffsetDateTime.now());
    }

    public List<NoticeType> listByState(String state) {
        return noticeTypeRepository.findByState(state).stream().map(this::toDto).toList();
    }

    private NoticeType toDto(NoticeTypeEntity e) {
        return new NoticeType(
                e.getId(), e.getCode(), e.getLevel(), e.getCategory(),
                e.getDescription(), e.getSourceArtifactType(), e.getState(),
                e.getCreatedAt(), e.getUpdatedAt(), e.getConfirmedBy()
        );
    }
}
