# рефакторинг staging-service

## Контекст

Staging-service — ETL сервис для выгрузки архитектурных артефактов


- **Исходный код**
  Java 17 + Spring Boot 3.1.1 + Camunda 7.20 (external tasks, BPMN). Один BPMN-процесс на конфигурацию×тик: `Pre-Adapter → Multi-Instance(Adapter → Validator → Transformer → Saver)`. Два конвейера: `e2e-sequence` (Sparx EA) и `structurizr-sequence` (Structurizr).

- **Документация**
  Архитектура и lineage — [`README.md`](README.md:1). Отчёт аудита — `.local/audit-report.md` (43 проблемы, roadmap 3 фаз).
  Анализ boundary error в BPMN — [`.local/analysis-boundary-error.md`](.local/analysis-boundary-error.md) (P3-2).
  План исправления P3-2 — [`.local/plan-p3-2-boundary-error.md`](.local/plan-p3-2-boundary-error.md).

### Зависимости

**Исходящие**
- Sparx EA (datasource, e2e-sequence)
- Structurizr API (`{structurizrApiUrl}/json`, structurizr-sequence)
- fdm-products (мнемоники продуктов для pre-adapter)
- product-service (публикация E2E-сценариев)
- dashboard (base-url в `application.yml`)
- MinIO/S3 (в docker-compose; в коде использование не найдено — статус уточнить)

**Входящие**
- Потребители выгруженных данных (открытый вопрос — список не определён)

## 🎯 Глобальная цель
Ключевые требования
- Обеспечение наблюдаемости прохождения данных
- Обеспечение качества данных
- Надёжность выгрузок
- Наблюдаемость происхождения данных
- Возможность найти причину, почему данные не попали в итоговый результат
- Возможность подключения новых источников
- Возможность расширения канонической модели


## 🛠️ Технологический стек и ограничения
* Язык/Фреймворк: Java 17 + Spring Boot 3.1.1 + Camunda 7.20 (external tasks, BPMN)
* База данных: PostgreSQL 15, Flyway (V0001–V0008)
* Мониторинг: Micrometer + Prometheus (зависимость есть, метрики в коде отсутствуют)
* API-документация: springdoc (Swagger UI)
* Ограничения: см. раздел «Ограничения»

## 🧠 Принятые решения (ADR — Architecture Decision Records)
* Отсутствуют