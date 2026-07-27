# Architecture

See the main [README.md](../README.md) for tech stack, quick start, and the API reference. This
covers how the pieces fit together and why.

```
Client (curl/browser)
        │
        ├──► AuthController ──► JwtService / PasswordEncoder ──► User / app_user table
        │         (register/login, no token required)
        │
        │  Authorization: Bearer <token>
        ▼
JwtAuthenticationFilter ──► SecurityConfig (401 if missing/invalid)
        │
        ▼                       @Scheduled 01:00      @Scheduled 01:15         poll every 30s
BusinessController                     │                    │                        │
(scoped to caller's own                ▼                    ▼                        ▼
 businesses only)              DeadlineSyncService ───► SqsDispatchService      ReminderWorkerService
    │        │                       │      │                  │       │            │        │
    ▼        ▼                       ▼      ▼                  ▼       ▼            ▼        ▼
BusinessRepo  RuleEngine ◄────────────┘   DeadlineRecordRepo ◄──────────┴── AWS SQS ─┘   NotificationSender
    │        (pure logic,                        │                    (LocalStack           │
    ▼         no DB/HTTP dep)                    ▼                     locally,             ▼
PostgreSQL (app_user, business, work_pass,     real AWS prod)   (see notifications.md)
            deadline_record)
```

Auth model and security-specific detail (JWT internals, rate limiting, IDOR history, secrets
handling) live in [security.md](security.md), not here. Notification channel setup lives in
[notifications.md](notifications.md).

## Package structure

Package-by-feature, not one flat package - `com.chrainx.compliance_tracker` grew past 30 files in
a single directory before this split (issue #90):

| Package | Contents |
|---|---|
| `auth` | `AuthController`/`AuthRequest`/`AuthResponse`, `JwtService`, `JwtAuthenticationFilter`, `LoginRateLimiter`, `TokenBlocklist`, `User`, `UserRepository` |
| `business` | `Business`/`BusinessController`/`BusinessRepository`, `WorkPass`/`WorkPassController`/`WorkPassRepository`, `DeadlineRecord`/`DeadlineRecordRepository`, `DeadlineSyncService` |
| `config` | `SecurityConfig`, `CorsConfig`, `SchedulingConfig`, `SqsConfig` — cross-cutting `@Configuration` classes, not owned by any one feature |
| `notifications` | `NotificationSender` interface, `EmailNotificationSender`, `LoggingNotificationSender` |
| `queue` | `SqsDispatchService`, `ReminderWorkerService`, `ReminderMessage` |
| `rules` | Pure rules-engine logic (`RuleEngine`, `Deadline`, `ObligationType`) — predates this split, was already its own package |
| *(root)* | `ComplianceTrackerApplication` (entry point), `HelloController` (smoke test) |

Test packages mirror this exactly (`src/test/.../auth`, `.../business`, etc.) - standard Maven/
Gradle convention, and it kept import parity easy to check while doing the split.

## Domain layer

- **`Business`** — entity representing an SME and the parameters its compliance deadlines are
  computed from (`name`, `financialYearEnd`, `gstRegistered`). `name`/`financialYearEnd` carry
  Bean Validation annotations (`@NotBlank`/`@NotNull`, issue #20), enforced via `@Valid` on the
  `@RequestBody` in `BusinessController`'s create/update endpoints — a blank name or missing FYE
  gets a `400` instead of silently persisting.
- **`BusinessRepository`** — Spring Data JPA repository interface. Extending `JpaRepository`
  gives `save`/`findAll`/`findById`/etc. for free, with no method bodies written — Spring
  generates the implementation at runtime.
- **`WorkPass`** — entity representing one employee's work pass (`employeeName`, `expiryDate`),
  many-to-one linked back to the `Business` that employs them. Same Bean Validation treatment as
  `Business` (issue #20) — a blank `employeeName` or missing `expiryDate` gets a `400`.
- **`WorkPassRepository`** — Spring Data JPA repository. Includes `findByBusinessId(Long)`,
  whose implementation Spring derives entirely from the method name (no query written by hand).
- **`RuleEngine`** — pure, unit-tested Java logic (`rules` package). Given a `Business`, its
  `WorkPass`es, and a reference date, computes the list of currently-applicable `Deadline`s
  (each an `ObligationType` + due `LocalDate`). Has no dependency on the database or HTTP layer,
  and takes the reference date as a parameter rather than calling `LocalDate.now()` internally,
  so tests are fully deterministic. Implements all three obligations: ACRA Annual Return, GST
  F5, and Employment Pass renewal (one deadline per `WorkPass`).

## Web layer

- **`BusinessController`** — exposes `POST /api/businesses` (create), `GET /api/businesses`
  (list), `PUT /api/businesses/{id}` (update), `DELETE /api/businesses/{id}` (delete, cascades
  to work passes and deadline records via `V3__cascade_delete_business_dependents.sql`, not
  application code — see "Database migrations" below), and `GET /api/businesses/{id}/deadlines`
  (compute and return that business's current deadlines via `RuleEngine`, including any
  work-pass renewals) over HTTP. `updateBusiness` never saves the client-supplied request body
  directly — only copies its mutable fields onto the already-fetched, already-owned entity, the
  same IDOR-avoidance shape as issue #66's fix on `createBusiness`.
- **`WorkPassController`** — exposes `POST`/`GET`/`DELETE` on `/api/businesses/{id}/work-passes`,
  nested under the owning business — every operation first checks the business belongs to the
  caller (same `findByIdAndOwnerId` scoping as `BusinessController`) before touching any work
  pass at all.
- **`HelloController`** — `GET /hello`, a minimal smoke-test endpoint from initial setup.

## Reminder pipeline

- **`DeadlineRecord`** — persisted counterpart to `rules.Deadline`. Adds the one thing pure
  computation can't carry: state, specifically `reminderSent`. `rules.Deadline` itself stays
  a pure in-memory value with no DB knowledge.
- **`DeadlineRecordRepository`** — Spring Data JPA repository for `DeadlineRecord`, including
  `existsByBusinessIdAndObligationTypeAndDueDate` (dedupe check) and
  `findByReminderSentFalseAndDueDateLessThanEqual` (the "what needs a reminder" query).
- **`DeadlineSyncService`** — `@Service` with a `@Scheduled` method (`syncDeadlines`, daily at
  01:00 Singapore time — `zone = "Asia/Singapore"` explicitly, issue #28, not the server's own
  default timezone) that recomputes every business's deadlines from scratch via `RuleEngine` each run and
  persists any not already stored, skipping ones that already exist so `reminderSent` isn't
  reset. Also exposes `findDueSoonAndUnreminded(referenceDate, daysAhead)`, which the dispatch
  step (`SqsDispatchService`) calls next to decide what actually gets pushed to the reminder
  queue.
- **`SqsConfig`** — `@Configuration` producing a single `SqsClient` `@Bean`. When
  `aws.sqs.endpoint` is set (local dev), it points the client at LocalStack with throwaway
  credentials; when unset (real AWS deployment), it falls back to the SDK's default credential
  chain and endpoint resolution — same code, no branching logic needed to switch environments.
- **`ReminderMessage`** — a Java `record` (concise immutable data class — auto-generates
  constructor/getters/equals/hashCode) representing one reminder's JSON payload:
  `deadlineRecordId`, `businessId`, `obligationType`, `dueDate`.
- **`SqsDispatchService`** — `@Service` with a `@Scheduled` method (`scheduledDispatch`, daily at
  01:15 Singapore time, 15 minutes after the sync job — same explicit `zone` as above) that calls `findDueSoonAndUnreminded`, serializes each
  result to JSON via Jackson, and sends it as an SQS message. Deliberately does **not** mark
  `reminderSent` — that only happens in the worker, below, after a reminder is actually sent
  successfully, so a lost/failed message can still be retried rather than silently skipped.
  Verified with a real integration test (`SqsDispatchIntegrationTest`) that boots the full app
  and confirms a message actually lands in a real SQS queue (LocalStack locally).
- **`NotificationSender`** — interface for "how a reminder actually reaches a business." Kept
  separate from the worker so the real channel can be swapped in without touching the
  queue-consuming/idempotency logic — see [notifications.md](notifications.md) for the two
  implementations and how to configure each.
- **`ReminderWorkerService`** — `@Service`, polls SQS every 30s (`pollAndProcess`,
  `@Scheduled(fixedDelay = 30_000)`). For each message: looks up the `DeadlineRecord`, and if
  it's not already `reminderSent` (the actual idempotency check — handles a message being
  redelivered after a prior successful send), calls `NotificationSender` and marks it sent.
  The SQS message is deleted **only after** the DB write succeeds — so a crash mid-processing
  leaves the message in the queue to be retried automatically once SQS's visibility timeout
  expires, rather than silently losing the reminder. Each message is processed in its own
  try/catch so one failing message doesn't block the rest of the batch — a failure is logged
  and the message is deliberately left undeleted, letting SQS's own retry/dead-letter mechanism
  (below) take over. Verified with a real integration test (`ReminderWorkerIntegrationTest`)
  covering the full sync → dispatch → worker pipeline against real Postgres + real (local) SQS.
- **`SchedulingConfig`** — `@Configuration` holding `@EnableScheduling`, gated behind
  `scheduling.enabled` (default `true`). Exists so tests can turn scheduling off entirely
  (`scheduling.enabled=false` in `application-test.properties`, via `@ActiveProfiles("test")`)
  — without this, `@SpringBootTest` boots the real background jobs, and once
  `ReminderWorkerService` existed its real poller started racing integration tests for the
  messages they'd just enqueued, a genuine (not flaky) test failure this fixed.

## Dead-letter handling

The main queue (`compliance-reminders`) has a **redrive policy**: after `maxReceiveCount: 3`
failed receives (a message repeatedly not deleted, i.e. repeatedly failing in
`ReminderWorkerService`), SQS automatically moves it to `compliance-reminders-dlq` — no
application code is involved in the move itself, it's queue configuration. This was manually
verified against LocalStack (simulating 3 failed receives via `change-message-visibility`,
confirming the message lands in the DLQ) rather than covered by an automated test, since
reproducing it end-to-end would mean waiting out real SQS visibility timeouts or adding
test-only timing hooks not worth the complexity here.

## Local development: LocalStack

No AWS account is needed for local dev. [LocalStack](https://www.localstack.cloud/) emulates
SQS on your machine — CI runs the same way. Pinned to `3.8.1`: newer LocalStack versions require
a paid license/auth token even for SQS on the free tier.

```bash
docker run --name compliance-localstack -e SERVICES=sqs -p 4566:4566 -d localstack/localstack:3.8.1

AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test aws --endpoint-url=http://localhost:4566 \
  --region us-east-1 sqs create-queue --queue-name compliance-reminders

# Dead-letter queue + redrive policy (see "Dead-letter handling" above)
AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test aws --endpoint-url=http://localhost:4566 \
  --region us-east-1 sqs create-queue --queue-name compliance-reminders-dlq

DLQ_ARN=$(AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test aws --endpoint-url=http://localhost:4566 \
  --region us-east-1 sqs get-queue-attributes \
  --queue-url http://sqs.us-east-1.localhost.localstack.cloud:4566/000000000000/compliance-reminders-dlq \
  --attribute-names QueueArn --query "Attributes.QueueArn" --output text)

AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test aws --endpoint-url=http://localhost:4566 \
  --region us-east-1 sqs set-queue-attributes \
  --queue-url http://sqs.us-east-1.localhost.localstack.cloud:4566/000000000000/compliance-reminders \
  --attributes "{\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$DLQ_ARN\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"}"
```

**Note:** LocalStack's queue state is in-memory and does **not** survive a container restart
(`docker start` after it was stopped) — `docker ps` showing it "Up" doesn't mean the queue still
exists. Re-run the `create-queue` commands (and the DLQ setup) any time LocalStack was
previously stopped, even if reusing the same container.

## Database migrations: Flyway

Schema changes are managed by Flyway (`src/main/resources/db/migration/`), not
`spring.jpa.hibernate.ddl-auto`. `ddl-auto` is set to `validate` — Hibernate checks the DB
schema matches the `@Entity` classes at startup and fails loudly if not, but never mutates
the schema itself. `update` was convenient for early solo dev but unsafe against a database
with real data (e.g. renaming a field could silently drop a column) — this had to change
before real deployment (issue #7).

**Note:** Spring Boot 4 split per-technology autoconfiguration into separate starters — plain
`flyway-core` on the classpath is no longer enough to trigger Flyway's autoconfiguration; it
needs the dedicated `spring-boot-starter-flyway` module (see `pom.xml`).

**`V3__cascade_delete_business_dependents.sql`** (issue #25): `V1`'s original `work_pass`/
`deadline_record` foreign keys were plain `REFERENCES business(id)`, no `ON DELETE` behavior
specified — Postgres's default is to reject the delete outright (a foreign key violation) if any
dependent rows still exist. Once `DELETE /api/businesses/{id}` existed, deleting a business with
any work pass or synced deadline record would have failed with an unhandled exception. This
migration drops and recreates both FKs with `ON DELETE CASCADE`, so removing a business also
removes its work passes and deadline records automatically at the DB level — correct regardless
of how a business row is ever deleted (this endpoint, a future admin tool, direct `psql`), not
dependent on the application remembering to issue the right deletes in the right order first.

## Planned (not built yet — see [open issues](https://github.com/compliance-tracker/compliance-tracker/issues))

- **DLQ monitoring/alerting** — currently, a message that lands in the dead-letter queue is
  silent; nothing surfaces it. Would need at minimum a way to inspect DLQ depth.
- **Input validation** — `spring-boot-starter-validation` has been a dependency since day one
  but is never actually used; no `@Valid` annotations exist anywhere yet.
- **API documentation** (OpenAPI/Swagger) — currently only the manually-maintained table in the
  README.
- **Cloud deployment** — AWS ECS/Fargate + RDS, replacing local Docker Postgres; switch
  `aws.sqs.endpoint` off to use real AWS.
- **Load testing** — real throughput/latency numbers against the deployed system.
