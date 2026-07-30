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
import java.util.List;
import java.util.Objects;

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

        ProductVersion latestVersion = productVersionRepository.findTopByProductIdOrderByIdDesc(entity.getId()).orElse(null);
        if (!created[0] && isUnchanged(latestVersion, extUid, name, description, author)) {
            saveMatchNotice("match.product.matched_unchanged", rawDataRefId, uid, jsonPointer);
            return latestVersion;
        }

        String code = created[0] ? "match.product.created" : "match.product.matched_by_uid";
        ArtifactNotice matchNotice = saveMatchNotice(code, rawDataRefId, uid, jsonPointer);

        ProductVersion version = new ProductVersion();
        version.setProductId(entity.getId());
        version.setExtUid(extUid);
        version.setName(name);
        version.setDescription(description);
        version.setAuthor(author);
        version.setCreatedAt(LocalDateTime.now());
        version.setMatchNoticeId(matchNotice != null ? matchNotice.id() : null);
        version.setRawDataContextId(matchNotice != null ? matchNotice.rawDataContextId() : null);
        return productVersionRepository.save(version);
    }

    private boolean isUnchanged(ProductVersion latest, String extUid, String name, String description, String author) {
        if (latest == null) return false;
        return Objects.equals(latest.getExtUid(), extUid)
                && Objects.equals(latest.getName(), name)
                && Objects.equals(latest.getDescription(), description)
                && Objects.equals(latest.getAuthor(), author);
    }

    private ArtifactNotice saveMatchNotice(String code, Long rawDataRefId, String entityUid, String jsonPointer) {
        ArtifactNotice notice = new ArtifactNotice(null, null, code, "info", "match",
                rawDataRefId, "product", entityUid, null, code, null, jsonPointer, null);
        List<ArtifactNotice> saved = noticeService.saveNotices(rawDataRefId, List.of(notice));
        return saved.isEmpty() ? null : saved.get(0);
    }
}
