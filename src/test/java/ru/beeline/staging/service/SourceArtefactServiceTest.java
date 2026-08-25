package ru.beeline.staging.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.beeline.staging.domain.Configuration;
import ru.beeline.staging.domain.SourceArtefact;
import ru.beeline.staging.domain.SourceArtefactType;
import ru.beeline.staging.repository.SourceArtefactRepository;
import ru.beeline.staging.repository.SourceArtefactTypeRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты для {@link SourceArtefactService#recordSeen} в контексте поля name (BLG-002).
 *
 * Покрывают критерии приёмки:
 *  - CR2: name заполняется при обработке артефакта (recordSeen ... из metadata);
 *  - CR3: name опционален, защита от перезаписи непустого пустым (BR-13/FR-003-17);
 *  - CR5: обратная совместимость — обработка без name не падает (FR-003-17, EC-005).
 */
class SourceArtefactServiceTest {

    private SourceArtefactTypeRepository typeRepository;
    private SourceArtefactRepository artefactRepository;
    private SourceArtefactService service;

    private final AtomicLong typeSeq = new AtomicLong(1);
    private final AtomicLong artefactSeq = new AtomicLong(1);

    /** In-memory fake, имитирующий JPA-репозиторий (ключ: typeId|extUid). */
    private final Map<String, SourceArtefact> store = new HashMap<>();

    @BeforeEach
    void setUp() {
        typeRepository = mock(SourceArtefactTypeRepository.class);
        artefactRepository = mock(SourceArtefactRepository.class);

        when(typeRepository.findByDataTypeIdAndSourceSystemId(any(), any()))
                .thenReturn(Optional.of(type("e2e-sequence from Sparx")));

        when(artefactRepository.findBySourceArtefactTypeIdAndExtUid(anyLong(), any()))
                .thenAnswer(inv -> {
                    Long typeId = inv.getArgument(0);
                    String extUid = inv.getArgument(1);
                    return Optional.ofNullable(store.get(key(typeId, extUid)));
                });

        // save(): кладём сущность в store с сохранением id (как это делает JPA persistence context).
        when(artefactRepository.save(any(SourceArtefact.class)))
                .thenAnswer(inv -> {
                    SourceArtefact a = inv.getArgument(0);
                    if (a.getId() == null) {
                        a.setId(artefactSeq.getAndIncrement());
                    }
                    store.put(key(a.getSourceArtefactTypeId(), a.getExtUid()), a);
                    return a;
                });

        service = new SourceArtefactService(typeRepository, artefactRepository);
    }

    private Configuration config() {
        Configuration c = new Configuration();
        c.setId(1L);
        c.setCode("e2e-sparx");
        c.setArtifactType("e2e-sequence from Sparx");
        c.setDataTypeId(1L);
        c.setSourceSystemId(1L);
        return c;
    }

    private SourceArtefactType type(String name) {
        SourceArtefactType t = new SourceArtefactType();
        t.setId(typeSeq.getAndIncrement());
        t.setDataTypeId(1L);
        t.setSourceSystemId(1L);
        t.setName(name);
        return t;
    }

    private String key(Long typeId, String extUid) {
        return typeId + "|" + extUid;
    }

    private SourceArtefact saved(String extUid) {
        return store.values().stream()
                .filter(a -> a.getExtUid().equals(extUid))
                .findFirst()
                .orElse(null);
    }

    // ------------------------------------------------------------------
    // 1. recordSeen с непустым name — новый артефакт (FR-003-16, CR2)
    // ------------------------------------------------------------------
    @Test
    @DisplayName("recordSeen с непустым name сохраняет name для нового артефакта")
    void recordSeenWithNamePersistsNameForNewArtefact() {
        service.recordSeen(config(), "scenario-1", 100L, 200L, "Сценарий регистрации");

        SourceArtefact a = saved("scenario-1");
        assertThat(a).isNotNull();
        assertThat(a.getName()).isEqualTo("Сценарий регистрации");
        assertThat(a.getExtUid()).isEqualTo("scenario-1");
        assertThat(a.getStatus()).isEqualTo("active");
        assertThat(a.getLastRunId()).isEqualTo(200L);
        assertThat(a.getLastSeenScanRunId()).isEqualTo(100L);
    }

    // ------------------------------------------------------------------
    // 2. Повторный скан: non-empty name перезаписывает непустое имя
    // ------------------------------------------------------------------
    @Test
    @DisplayName("recordSeen с новым непустым name перезаписывает существующее (переименование в источнике)")
    void recordSeenWithNewNonEmptyNameOverwritesExisting() {
        service.recordSeen(config(), "scenario-1", 100L, 200L, "Старое имя");
        service.recordSeen(config(), "scenario-1", 101L, 201L, "Новое имя");

        SourceArtefact a = saved("scenario-1");
        assertThat(a.getName()).isEqualTo("Новое имя");
        assertThat(a.getLastSeenScanRunId()).isEqualTo(101L);
    }

    // ------------------------------------------------------------------
    // 3. Защита от перезаписи непустого пустым (BR-13/FR-003-17, CR3)
    // ------------------------------------------------------------------
    @Test
    @DisplayName("recordSeen с пустой строкой НЕ перезаписывает существующее непустое name")
    void recordSeenWithBlankDoesNotOverwriteExistingName() {
        service.recordSeen(config(), "scenario-1", 100L, 200L, "Старое имя");
        service.recordSeen(config(), "scenario-1", 101L, 201L, "   "); // blank

        SourceArtefact a = saved("scenario-1");
        assertThat(a.getName()).isEqualTo("Старое имя");
        assertThat(a.getLastSeenScanRunId()).isEqualTo(101L); // остальные поля штатно обновлены
    }

    @Test
    @DisplayName("recordSeen с null НЕ перезаписывает существующее непустое name")
    void recordSeenWithNullDoesNotOverwriteExistingName() {
        service.recordSeen(config(), "scenario-1", 100L, 200L, "Старое имя");
        service.recordSeen(config(), "scenario-1", 101L, 201L, null);

        SourceArtefact a = saved("scenario-1");
        assertThat(a.getName()).isEqualTo("Старое имя");
    }

    @Test
    @DisplayName("recordSeen с пустой строкой ('') НЕ перезаписывает существующее непустое name")
    void recordSeenWithEmptyDoesNotOverwriteExistingName() {
        service.recordSeen(config(), "scenario-1", 100L, 200L, "Старое имя");
        service.recordSeen(config(), "scenario-1", 101L, 201L, "");

        SourceArtefact a = saved("scenario-1");
        assertThat(a.getName()).isEqualTo("Старое имя");
    }

    // ------------------------------------------------------------------
    // 4. Непустой name поверх null/null поверх null (обратная совместимость)
    // ------------------------------------------------------------------
    @Test
    @DisplayName("recordSeen с непустым name заполняет name после первого скана без name")
    void recordSeenWithNameFillsAfterNull() {
        service.recordSeen(config(), "scenario-1", 100L, 200L, null);
        assertThat(saved("scenario-1").getName()).isNull();

        service.recordSeen(config(), "scenario-1", 101L, 201L, "Имя появилось");
        assertThat(saved("scenario-1").getName()).isEqualTo("Имя появилось");
    }

    // ------------------------------------------------------------------
    // 5. Без name (null/blank) для нового артефакта — не падает (FR-003-17, CR12/CR5)
    // ------------------------------------------------------------------
    @Test
    @DisplayName("recordSeen без name (null) для нового артефакта не падает, name остаётся null")
    void recordSeenWithoutNameForNewArtefactDoesNotFail() {
        assertThatCode(() -> service.recordSeen(config(), "product-a", 300L, 301L, null))
                .doesNotThrowAnyException();

        SourceArtefact a = saved("product-a");
        assertThat(a).isNotNull();
        assertThat(a.getName()).isNull();
        assertThat(a.getStatus()).isEqualTo("active");
        assertThat(a.getExtUid()).isEqualTo("product-a");
        assertThat(a.getLastRunId()).isEqualTo(301L);
    }

    @Test
    @DisplayName("recordSeen без name (blank) для нового артефакта не падает, name остаётся null")
    void recordSeenWithBlankForNewArtefactDoesNotFail() {
        assertThatCode(() -> service.recordSeen(config(), "product-a", 300L, 301L, "   "))
                .doesNotThrowAnyException();

        SourceArtefact a = saved("product-a");
        assertThat(a).isNotNull();
        assertThat(a.getName()).isNull();
        assertThat(a.getLastSeenScanRunId()).isEqualTo(300L);
    }

    @Test
    @DisplayName("recordSeen без name (пустая строка) для нового артефакта не падает, name остаётся null")
    void recordSeenWithEmptyForNewArtefactDoesNotFail() {
        assertThatCode(() -> service.recordSeen(config(), "product-a", 300L, 301L, ""))
                .doesNotThrowAnyException();

        SourceArtefact a = saved("product-a");
        assertThat(a).isNotNull();
        assertThat(a.getName()).isNull();
    }

    // ------------------------------------------------------------------
    // 6. Инварианты: поля bookkeeping обновляются независимо от name
    // ------------------------------------------------------------------
    @Test
    @DisplayName("recordSeen вызывает save один раз и обновляет updatedAt")
    void recordSeenSavesAndUpdatesTimestamps() {
        service.recordSeen(config(), "scenario-1", 100L, 200L, "Имя");

        verify(artefactRepository, times(1)).save(argThat(a ->
                a.getUpdatedAt() != null && "Имя".equals(a.getName())));
    }
}