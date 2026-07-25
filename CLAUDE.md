# Compliance Tracker — Backend

See `../CLAUDE.md` for overall project purpose and how I want to collaborate — this file only
covers backend/Java-specific details.

## Tech stack (decided, don't change without discussing)

- **Language:** Java 21 (LTS — deliberately not 25/26)
- **Framework:** Spring Boot 4.1.0
- **Build tool:** Maven
- **Database:** PostgreSQL 16 (Docker locally), schema managed by **Flyway** migrations (`src/main/resources/db/migration/`) — not `ddl-auto`
- **Queue:** AWS SQS — LocalStack locally/CI, real AWS at deployment
- **Deployed later:** AWS ECS/Fargate + RDS (not done yet — blocked on having an AWS account)

## Current environment (macOS)

- Java 21 installed via Homebrew (Temurin), `JAVA_HOME` pinned in `~/.zshrc`
- Maven and AWS CLI installed via Homebrew
- Docker Desktop must be manually opened (`open -a Docker`) before `docker` commands work
- Project lives at `~/Documents/Projects/compliance-tracker/backend` (moved into this subfolder from the old top-level `compliance-tracker/` when the frontend project was added alongside it)
- VS Code is the editor (not IntelliJ)

## Running locally

See `README.md` in this folder for the exact commands (Postgres, LocalStack, DLQ setup, running the app). Don't duplicate those commands here — keep one source of truth to avoid drift.

## Known gotchas already hit — watch for these recurring

- **`pom.xml` dependencies have gone missing/gotten reset more than once** during editing — always double check the full `<dependencies>` block after any edit, rather than assuming a prior edit persisted.
- **`application.properties` similarly got wiped back to near-empty once** — same caution applies.
- **New Java files must be placed inside the correct package folder** (`src/main/java/com/chrainx/compliance_tracker/`), matching the `package` declaration — a file placed one level too high fails to compile with confusing "cannot find symbol" errors.
- **DevTools auto-restart doesn't always reliably pick up `application.properties` changes** — do a full manual stop/restart if a config change doesn't seem to take effect.
- **When verifying anything, check actual state directly** (`docker exec ... psql ... "\dt"`, `aws sqs receive-message`, etc.) over trusting terminal/log output alone.
- **Spring Boot 4.1 renamed Jackson's Java packages**: `com.fasterxml.jackson.*` → `tools.jackson.*` (Jackson 3). Also `writeValueAsString` now throws an *unchecked* `JacksonException`, not the old checked `JsonProcessingException`.
- **Spring Boot 4 split autoconfiguration into per-technology starter modules.** Adding a library's core jar alone (e.g. plain `flyway-core`) no longer triggers its autoconfiguration — need the dedicated starter (`spring-boot-starter-flyway`). If something silently doesn't activate with no error, check whether Spring Boot 4 moved its autoconfiguration to a separate module before assuming the code is wrong.
- **`@Transactional` doesn't work via self-invocation** (a method calling another `@Transactional` method on `this` within the same class) — Spring's proxy isn't involved, so it's silently a no-op, no error raised.
- **`@SpringBootTest` boots the entire real app, including real `@Scheduled` jobs.** Once a background poller existed (`ReminderWorkerService`), it started racing integration tests for messages they'd just enqueued. Fixed via `SchedulingConfig` (gated behind `scheduling.enabled`) + a `test` Spring profile disabling scheduling in `@SpringBootTest` classes.
- **LocalStack versions newer than `3.8.1` require a paid license/auth token even for SQS** on the free community tier — pin to `3.8.1`.
- **LocalStack's queue state is in-memory and does not survive a container restart** — re-run queue/DLQ creation any time LocalStack was stopped, even if reusing the same container.

## Project status

Issues #1–4, #7–14 are closed (rules engine, REST API, full reminder pipeline with SQS dispatch/worker/idempotency/dead-letter handling, Flyway migrations). Open: **#5** (deploy to real AWS) and **#6** (load testing) — both blocked pending an AWS account. See `README.md` and GitHub issues for full detail; don't duplicate that detail here.
