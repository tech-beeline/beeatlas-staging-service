package ru.beeline.staging.pipeline.saver;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.beeline.staging.domain.canonical.Product;
import ru.beeline.staging.domain.canonical.ProductVersion;
import ru.beeline.staging.dto.notice.ArtifactNotice;
import ru.beeline.staging.repository.canonical.ProductRepository;
import ru.beeline.staging.repository.canonical.ProductVersionRepository;
import ru.beeline.staging.service.ArtifactNoticeService;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Find-or-create + versioning for the product identity (BLG-004/ADR-011, CMP-03).
 * Non-primary attributes (description, author) are serialized into {@code json_data}
 * via {@link JsonDataValidator} instead of individual column setters.
 */
@Service
@RequiredArgsConstructor
public class ProductMatchService {

    private final ProductRepository        productRepository;
    private final ProductVersionRepository productVersionRepository;
    private final ArtifactNoticeService    noticeService;

    @Transactional
    public ProductVersion matchOrCreate(String uid, String extUid, String name, String description, String author,
                                         String jsonPointer, Long rawDataRefId, Long batchId) {
        boolean[] created = {false};
        Product entity = productRepository.findByUid(uid).orElseGet(() -> {
            created[0] = true;
            Product e = new Product();
            e.setUid(uid);
            e.setCreatedAt(LocalDateTime.now());
            return productRepository.save(e);
        });

        String code = created[0] ? "match.product.created" : "match.product.matched_by_uid";
        ArtifactNotice matchNotice = saveMatchNotice(code, rawDataRefId, uid, jsonPointer);

        ProductVersion version = new ProductVersion();
        version.setProductId(entity.getId());
        version.setExtUid(extUid);
        version.setName(name);
        // CMP-03: serialize non-primary attributes into json_data instead of column setters
        Map<String, Object> attrs = new HashMap<>();
        if (description != null) attrs.put("description", description);
        if (author != null) attrs.put("author", author);
        String jsonData = JsonDataValidator.toJsonData(attrs);
        JsonDataValidator.validate(jsonData);
        version.setJsonData(jsonData);
        version.setCreatedAt(LocalDateTime.now());
        version.setMatchNoticeId(matchNotice != null ? matchNotice.id() : null);
        version.setRawDataContextId(matchNotice != null ? matchNotice.rawDataContextId() : null);
        return productVersionRepository.save(version);
    }

    private ArtifactNotice saveMatchNotice(String code, Long rawDataRefId, String entityUid, String jsonPointer) {
        ArtifactNotice notice = new ArtifactNotice(null, null, code, "info", "match",
                rawDataRefId, "product", entityUid, null, code, null, jsonPointer, null);
        List<ArtifactNotice> saved = noticeService.saveNotices(rawDataRefId, List.of(notice));
        return saved.isEmpty() ? null : saved.get(0);
    }
}
