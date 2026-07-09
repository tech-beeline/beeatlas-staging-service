-- structurizr-sequence reused generic-looking codes ("validation.missing_required_field",
-- "transform.map_failed", "transform.data_loss") that V0011 already registered and confirmed for
-- e2e-sequence — staging.notice_types.code is globally UNIQUE (not scoped by artifact type), so those
-- occurrences were silently inheriting e2e's description/state. Give structurizr-sequence its own
-- prefixed codes and register them explicitly here, same as V0011 does for e2e-sequence.

INSERT INTO staging.notice_types (code, level, category, description, state)
VALUES
    ('structurizr-sequence.validation.no_dynamic_views', 'info', 'validation',
     'Воркспейс продукта в Structurizr не содержит views.dynamicViews — продукт не моделирует sequence-диаграммы (обычное состояние: большинство продуктов используют Structurizr только для C4-ландшафта). Не ошибка, транформация просто не создаёт ни одной Sequence для этого продукта.',
     'confirmed'),

    ('structurizr-sequence.validation.missing_dynamic_view_key', 'warning', 'validation',
     'У элемента views.dynamicViews[] отсутствует обязательное поле key — эта dynamicView пропускается (не становится Sequence), остальной снапшот продукта строится как обычно.',
     'confirmed'),

    ('structurizr-sequence.validation.duplicate_key', 'warning', 'validation',
     'Значение views.dynamicViews[].key повторяется в пределах одного воркспейса — key должен быть уникален, иначе Sequence-версии перезатирают друг друга при матчинге по (tc_id, key).',
     'confirmed'),

    ('structurizr-sequence.validation.empty_relationships', 'warning', 'validation',
     'У dynamicView нет ни одного relationship — диаграмма будет сохранена как Sequence без единого шага вызова.',
     'confirmed'),

    ('structurizr-sequence.transform.map_failed', 'warning', 'transform',
     'Не удалось сопоставить шаг dynamicView.relationships[] с элементом модели — конкретная причина в details.reason (missing_required_field — нет key у dynamicView; missing_reference — id шага не резолвится ни в одной relationships[] модели). Шаг пропускается, остальная цепочка вызовов сохраняется.',
     'confirmed'),

    ('structurizr-sequence.transform.data_loss', 'info', 'transform',
     'Шаг dynamicView.relationships[] с response=true — это возвратное сообщение (ответ), а не вызов, поэтому не попадает в цепочку operation → operation.',
     'confirmed')

ON CONFLICT (code) DO UPDATE SET
    description = EXCLUDED.description,
    level       = EXCLUDED.level,
    category    = EXCLUDED.category,
    updated_at  = NOW();
