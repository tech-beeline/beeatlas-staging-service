-- Заполняет staging.notice_types человекочитаемыми description для всех кодов замечаний,
-- которые сейчас реально пишет пайплайн e2e-sequence (валидация/трансформация/сохранение).
-- Коды/уровни/категории — контракт, известный заранее, поэтому регистрируем их здесь явно
-- со state='confirmed', а не ждём авто-регистрации при первом появлении с description=NULL.
-- При повторном запуске (ON CONFLICT) description/level/category обновляются, а state и
-- confirmed_by не трогаются — если админ уже что-то отклонил/подтвердил вручную, это не отменяется.

INSERT INTO staging.notice_types (code, level, category, description, state)
VALUES
    ('validation.missing_required_field', 'error', 'validation',
     'В сыром экспорте сценария отсутствует обязательное поле или блок — entrance_diagram_uid, либо один из массивов diagrams/objects/systems/interfaces/operations.',
     'confirmed'),

    ('validation.invalid_format', 'error', 'validation',
     'Значение поля не согласуется с остальными данными — например entrance_diagram_uid не резолвится ни в одном из diagrams[].uid.',
     'confirmed'),

    ('transform.map_failed', 'error', 'transform',
     'Не удалось сопоставить элемент сырых данных с канонической сущностью или найти обязательную ссылку (диаграмма, operation_guid, interface_id, объект). Конкретная причина — в details.reason. Уровень error останавливает трансформацию сценария целиком (нет корневой диаграммы или step_id); warning — фрагмент пропускается, обработка продолжается.',
     'confirmed'),

    ('transform.data_loss', 'warning', 'transform',
     'Фрагмент сценария (вызов/сообщение) не попал в итоговое дерево. details.reason различает рутинный шум последовательности (is_ret, use_message, self_call — эти всегда info) и реальное схлопывание внутреннего вызова (app_front, app_front_no_method, same_system, self_reference, unresolved_system — эти warning).',
     'confirmed'),

    ('transform.implicit_cast', 'info', 'transform',
     'Неявное преобразование значения SLA-тега операции (rps/latency/error_rate) из строки в число при маппинге в каноническую модель.',
     'confirmed'),

    ('transform.included', 'info', 'transform',
     'Вызов прошёл фильтрацию внутренних вызовов и попал в итоговое дерево сценария. details.reason: external_call — обычный межсистемный вызов; show_in_e2e_override — вызов внутри той же системы, но принудительно показан тегом show_in_e2e на интерфейсе операции.',
     'confirmed'),

    ('match.interface.created', 'info', 'match',
     'Интерфейс с таким uid встречен впервые — создана новая идентити-запись в staging.interfaces.',
     'confirmed'),

    ('match.interface.matched_by_uid', 'info', 'match',
     'Интерфейс сопоставлен с уже существующей записью в staging.interfaces по совпадению uid.',
     'confirmed'),

    ('match.operation.created', 'info', 'match',
     'Операция с таким ext_uid встречена впервые — создана новая идентити-запись в staging.operations.',
     'confirmed'),

    ('match.operation.matched_by_ext_uid', 'info', 'match',
     'Операция сопоставлена с уже существующей записью в staging.operations по совпадению ext_uid.',
     'confirmed'),

    ('match.bi_step.created', 'info', 'match',
     'bi_step с таким uid (step_id) встречен впервые — создана новая идентити-запись в staging.bi_steps.',
     'confirmed'),

    ('match.bi_step.matched_by_uid', 'info', 'match',
     'bi_step сопоставлен с уже существующей записью в staging.bi_steps по совпадению uid (step_id).',
     'confirmed')

ON CONFLICT (code) DO UPDATE SET
    description = EXCLUDED.description,
    level       = EXCLUDED.level,
    category    = EXCLUDED.category,
    updated_at  = NOW();
