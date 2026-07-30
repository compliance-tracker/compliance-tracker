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
| `auth` | `AuthController`/`AuthRequest`/`AuthResponse`, `JwtService`, `JwtAuthenticationFilter`, `LoginRateLimiter`, `TokenBlocklist`, `User`, `UserRepository`, `PasswordResetToken`/`PasswordResetTokenRepository`, `ForgotPasswordRequest`/`ResetPasswordRequest`, `EmailVerificationToken`/`EmailVerificationTokenRepository`, `VerifyEmailRequest` |
| `business` | `Business`/`BusinessRequest`/`BusinessResponse`/`BusinessController`/`BusinessRepository`, `WorkPass`/`WorkPassRequest`/`WorkPassResponse`/`WorkPassController`/`WorkPassRepository`, `CustomObligation`/`CustomObligationRequest`/`CustomObligationResponse`/`CustomObligationController`/`CustomObligationRepository` (issue #59), `DeadlineRecord`/`DeadlineRecordRepository`, `DeadlineSyncService`, `IdempotencyKey`/`IdempotencyKeyRepository`, `PageResponse` (pagination, issue #49) |
| `config` | `SecurityConfig`, `CorsConfig`, `SchedulingConfig`, `SqsConfig`, `OpenApiConfig` (issue #21), `LoggingConfig` (issue #51) — cross-cutting `@Configuration` classes, not owned by any one feature |
| `error` | `ApiError`, `GlobalExceptionHandler` — the consistent structured error response format (issue #47), also cross-cutting |
| `logging` | `CorrelationIdFilter`, `CorrelationIdSupport` (issue #51) — request/scheduled-run correlation IDs, see "Request correlation IDs" below |
| `notifications` | `NotificationSender` interface (reminders), `EmailNotificationSender`, `LoggingNotificationSender`; `AuthEmailSender` interface (password reset #37, email verification #36), `EmailAuthEmailSender`, `LoggingAuthEmailSender` |
| `queue` | `SqsDispatchService`, `ReminderWorkerService`, `ReminderMessage`, `DlqMonitorService` (issue #18) |
| `rules` | Pure rules-engine logic (`RuleEngine`, `Deadline`, `ObligationType`) — predates this split, was already its own package |
| `security` | `EncryptedStringConverter` (column-level AES-256-GCM encryption at rest), `EmailHasher` (deterministic HMAC-SHA256 lookup hash for `User.email`) — issue #63 |
| *(root)* | `ComplianceTrackerApplication` (entry point), `HelloController` (smoke test) |

Test packages mirror this exactly (`src/test/.../auth`, `.../business`, etc.) - standard Maven/
Gradle convention, and it kept import parity easy to check while doing the split.

## Domain layer

- **`Business`** — entity representing an SME and the parameters its compliance deadlines are
  computed from (`name`, `financialYearEnd`, `gstRegistered`, `leadTimeDays`, `incorporationDate`).
  Never bound directly from a request or serialized directly into a response (issue #46) —
  `BusinessRequest`/`BusinessResponse` are the API's actual contract; this is purely the
  persistence shape.
- **`BusinessRequest`** / **`BusinessResponse`** — the API's actual contract for a business
  (issue #46), separate from the JPA entity. `BusinessRequest` carries the Bean Validation
  annotations (`@NotBlank`/`@NotNull`, issue #20) and deliberately has no `id`/`owner` field at
  all — not just fields a controller has to remember to clear — so the #66-style IDOR (a client
  supplying their own `id`, JPA's `save()` silently doing an `UPDATE` instead of an `INSERT`) is
  structurally impossible, not just defended against. `leadTimeDays` (issue #53) is a boxed
  `Integer` on the request specifically so it can be optional (`@Min(1) @Max(90)` when present,
  no `@NotNull`) — `createBusiness` defaults a missing value to `14`, `updateBusiness` leaves the
  business's existing value untouched rather than resetting it, so a client that doesn't know
  about the field yet (the current frontend) never has to send it.
- **`BusinessRepository`** — Spring Data JPA repository interface. Extending `JpaRepository`
  gives `save`/`findAll`/`findById`/etc. for free, with no method bodies written — Spring
  generates the implementation at runtime.
- **`WorkPass`** — entity representing one employee's work pass (`employeeName`, `expiryDate`),
  many-to-one linked back to the `Business` that employs them. Same DTO separation as `Business`
  above — `WorkPassRequest`/`WorkPassResponse` are the real contract.
- **`WorkPassRepository`** — Spring Data JPA repository. Includes `findByBusinessId(Long)`,
  whose implementation Spring derives entirely from the method name (no query written by hand).
- **`RuleEngine`** — pure, unit-tested Java logic (`rules` package). Given a `Business`, its
  `WorkPass`es, its `CustomObligation`s, and a reference date, computes the list of
  currently-applicable `Deadline`s (each an `ObligationType` + due `LocalDate`, plus a
  `customName`/`customObligationId` pair, both null except for `ObligationType.CUSTOM`). Has no
  dependency on the database or HTTP layer, and takes the reference date as a parameter rather
  than calling `LocalDate.now()` internally, so tests are fully deterministic. Implements the
  three built-in obligations — ACRA Annual Return, GST F5, and Employment Pass renewal (one
  deadline per `WorkPass`) — plus a business's own custom obligations (issue #59): a one-off
  (`recurrenceMonths` null) uses its stored `dueDate` as-is, even once overdue (same "an overdue
  deadline stays visible" behavior as work pass renewal); a recurring one recomputes the actual
  next occurrence live from its fixed anchor `dueDate` every time, the same
  never-mutate-the-stored-date pattern `nextAcraDeadline` already uses for ACRA, just with a
  configurable month step (`nextRecurringDeadline`) instead of a fixed 12. Also exposes
  `firstFinancialYearExceedsAcraLimit(incorporationDate, financialYearEnd)` (issue #31, sourced
  from Companies Act 1967 s.198) — a validation helper, not a deadline computation; a plain
  literal-date comparison (`financialYearEnd.isAfter(incorporationDate.plusMonths(18))`), only
  ever called from `BusinessController.createBusiness`. It's deliberately *not* called from
  `updateBusiness`: `financialYearEnd` is stored as a single date but stands for "this month/day,
  every year" once a business has real history (see `nextAcraDeadline`'s own comment) — comparing
  an *existing* business's current `financialYearEnd` against its `incorporationDate` on every
  future edit would eventually flag a perfectly normal multi-year-old business's routine update
  as an illegal first year. The check only means something at the one moment a first FYE is
  actually being declared for a business that doesn't exist yet. An earlier implementation tried
  to search for the "nearest occurrence" of the FYE month/day instead, specifically to dodge that
  same false positive on updates — that version was itself wrong (the nearest occurrence of any
  month/day is always within ~12 months of any date, so it could never register an 18-month
  violation at all) and was caught by its own test suite failing, not spotted by inspection. See
  `RuleEngineTest`'s comment above that test for the full story.

## Web layer

- **`BusinessController`** — exposes `POST /api/businesses` (create), `GET /api/businesses`
  (list, paginated — see `PageResponse` below), `PUT /api/businesses/{id}` (update),
  `DELETE /api/businesses/{id}` (delete, cascades to work passes and deadline records via
  `V3__cascade_delete_business_dependents.sql`, not application code — see "Database migrations"
  below), and `GET /api/businesses/{id}/deadlines` (compute and return that business's current
  deadlines via `RuleEngine`, including any work-pass renewals) over HTTP. `updateBusiness` never
  saves the client-supplied request DTO directly — only copies its fields onto the already-fetched,
  already-owned entity. `createBusiness` also accepts an optional `Idempotency-Key` header
  (issue #61) — see `IdempotencyKey` below — and is the only place the first-year 18-month check
  (issue #31, see `RuleEngine` above) ever runs.
- **`PageResponse<T>`** — a small custom pagination envelope (issue #49; `content`, `page`,
  `size`, `totalElements`, `totalPages`), used by `GET /api/businesses` and
  `GET /api/businesses/{id}/work-passes`. Deliberately not Spring Data's own `Page<T>` serialized
  directly — its default JSON shape leaks Spring-internal fields (`pageable`, `sort`, etc.) that
  aren't part of this API's actual contract, the same reasoning behind `BusinessResponse`/
  `WorkPassResponse` existing at all (issue #46). `PageResponse.pageable(page, size)` centralizes
  clamping (page floored at `0`, size clamped to `1`–`100`) so both controllers share the exact
  same rules.
- **`IdempotencyKey`** / **`IdempotencyKeyRepository`** — records which business a given
  `(idempotencyKey, ownerId)` pair already created, so a retried `POST /api/businesses` (a client
  resending after a timeout, not knowing whether the first attempt actually succeeded) returns
  the original business instead of creating a duplicate. The unique constraint on
  `(idempotency_key, owner_id)` (`V4` migration) is the real enforcement point under concurrency —
  same "check first, DB constraint is the actual guarantee" shape as issue #42's registration
  race, extended to also delete the loser's already-created `Business` row rather than leaving it
  behind as an orphaned duplicate.
- **`WorkPassController`** — exposes `POST`/`GET`/`DELETE` on `/api/businesses/{id}/work-passes`,
  nested under the owning business — every operation first checks the business belongs to the
  caller (same `findByIdAndOwnerId` scoping as `BusinessController`) before touching any work
  pass at all.
- **`CustomObligationController`** (issue #59) — exposes `POST`/`GET`/`PUT`/`DELETE` on
  `/api/businesses/{id}/custom-obligations`, same nested-under-the-owning-business scoping as
  `WorkPassController`. `PUT` also clears any stale, not-yet-reminded `DeadlineRecord` tied to the
  obligation (`deleteByCustomObligationIdAndReminderSentFalse`) — the same #30 lesson as a
  business's FYE changing: without it, the next sync would insert the newly-correct deadline
  alongside the old, now-wrong one instead of replacing it. `DELETE` relies on the DB's own
  `ON DELETE CASCADE` (`custom_obligation_id` FK, `V11` migration) to clean up its
  `DeadlineRecord`s, the same pattern `V3` already established for deleting a business.
- **`HelloController`** — `GET /hello`, a minimal smoke-test endpoint from initial setup.

## API documentation (issue #21)

`springdoc-openapi-starter-webmvc-ui` generates an OpenAPI 3 spec (`GET /v3/api-docs`) and an
interactive Swagger UI (`/swagger-ui/index.html`) directly from the existing controllers/DTOs —
no annotations needed for a working baseline, since the request/response shape is already
explicit (`BusinessRequest`/`BusinessResponse` etc., issue #46). `OpenApiConfig` adds the two
things springdoc can't infer on its own: human-readable title/description (echoing this app's
own disclaimer — it's a reminder tool, not compliance advice), and a `bearerAuth` HTTP security
scheme, without which Swagger UI's own "Authorize" button would have nothing to attach a JWT to
when trying a protected endpoint from the docs UI itself. Both the spec and the UI are
`permitAll()`'d in `SecurityConfig` — they're documentation, not an endpoint acting on anyone's
behalf, same reasoning as the health endpoints above being public. **springdoc-openapi 3.0.x
specifically** — the 2.x line only supports Spring Boot 3; this project is on Spring Boot 4.1.0
(Spring Framework 7), and 3.0.x is the first line with real Spring Boot 4 support, confirmed via
springdoc's own docs before adding the dependency (checked live, not assumed, given this project
has already hit several real Spring Boot 4 compatibility surprises — see backend/CLAUDE.md's
gotchas).

## Health/readiness (issue #44)

`GET /actuator/health` (plus the Kubernetes-style `/actuator/health/liveness` and
`/actuator/health/readiness` sub-groups, `management.endpoint.health.probes.enabled=true`) is
what a load balancer or container orchestrator would point its health/readiness checks at once
this is ever actually deployed (#5) — `permitAll()`'d in `SecurityConfig`, since infrastructure
checking "is this instance alive" can't attach a JWT. `management.endpoints.web.exposure.include=
health` deliberately exposes nothing else over HTTP (no `/actuator/env`, `/actuator/beans`, etc.),
and `show-details=never` keeps even the health response itself to a bare `{"status":"UP"}` for an
unauthenticated caller — no leaking which DB it's checking or connection-pool internals.

**A real bug found live while verifying this endpoint, not assumed:** Spring Boot
auto-configures a `mail` health indicator the instant `spring-boot-starter-mail` is on the
classpath (needed for `EmailNotificationSender`/`AuthEmailSender`) — it tries an actual SMTP
connection to `smtp.gmail.com` on every health check. With this app's deliberate, safe
zero-config default (`notifications.channel=logging`, no real Gmail credentials), that connection
always fails, which dragged the *entire aggregate* `/actuator/health` to `DOWN` even though the
app itself was completely healthy — exactly the false-positive a real load balancer must never
see, since it would kill perfectly fine instances. Real email is an explicitly opt-in feature this
app is designed to work without (see [notifications.md](notifications.md)); health/readiness has
to reflect that, not assume every optional integration is actually configured. Fixed with
`management.health.mail.enabled=false`.

The DB connectivity check *is* left enabled and does mean something real — Spring Boot
auto-configures it from the `DataSource` already on the classpath (`spring-boot-starter-data-jpa`),
so readiness genuinely reflects whether the app can currently reach Postgres, not just that the
JVM process is up.

## Request correlation IDs (issue #51)

`CorrelationIdFilter` puts a per-request ID into SLF4J's MDC (a thread-local map every log line
on that thread can read from) — reused from an incoming `X-Correlation-Id` header if the caller
already has one, otherwise a fresh `UUID.randomUUID()`. Echoed back as a response header too, so
a user/client reporting an issue can hand back the exact ID to search logs for.
`logging.pattern.level=%5p [%X{correlationId:-}]` (`application.properties`) is what actually
makes it show up in log output — Spring Boot's own documented hook for injecting MDC content
into the default pattern without a full custom `logback-spring.xml`.

**Registered at `Ordered.HIGHEST_PRECEDENCE` via `LoggingConfig`, not left to Spring Boot's
default filter auto-registration.** A plain `@Component` filter would otherwise register at
`LOWEST_PRECEDENCE` (last) — meaning Spring Security's own filter chain (registered separately,
much earlier) could reject a request outright (401/403) before it ever reached the correlation
filter at all, leaving exactly the requests most worth tracing with no ID on their log lines.
`LoggingConfig` wraps the same `CorrelationIdFilter` bean in an explicit `FilterRegistrationBean`
instead, forcing it to run first — verified live that a genuinely `401`-rejected request still
carries the header.

**The reminder pipeline (`DeadlineSyncService.syncDeadlines`, `SqsDispatchService.scheduledDispatch`,
`ReminderWorkerService.pollAndProcess`) needed a second, different mechanism** — these all run on
their own `@Scheduled` trigger, never inside an HTTP request, so `CorrelationIdFilter` has
nothing to run through for them. `CorrelationIdSupport.runWithNewCorrelationId(Runnable)` gives
each one's own invocation a fresh correlation ID the same way, covering every business/message
that one run touches. Each of the three `@Scheduled` methods now delegates its actual body to a
private method, wrapped in `runWithNewCorrelationId` — `ReminderWorkerService.pollAndProcess`
specifically keeps `@Transactional` on the outer `@Scheduled` method (Spring's `@Transactional`
proxy doesn't intercept self-invocation, an existing gotcha this project already hit once) and
wraps around the delegation to its private impl, not the other way around.

## Reminder pipeline

- **`DeadlineRecord`** — persisted counterpart to `rules.Deadline`. Adds the one thing pure
  computation can't carry: state, specifically `reminderSent`. `rules.Deadline` itself stays
  a pure in-memory value with no DB knowledge.
- **`DeadlineRecordRepository`** — Spring Data JPA repository for `DeadlineRecord`, including
  `existsByBusinessIdAndObligationTypeAndDueDate` (dedupe check for the 3 built-in obligation
  types), `existsByCustomObligationIdAndDueDate` (the same dedupe check for `ObligationType.CUSTOM`
  — issue #59: a business can have several custom obligations that happen to share the same due
  date, which the plain `(business, obligationType, dueDate)` key can't tell apart, so a custom
  obligation's own id is the real disambiguator instead), `findByReminderSentFalse`
  (the starting point for the "what needs a reminder" query — see `findDueSoonAndUnreminded`
  below for why the actual due-date cutoff isn't part of this query anymore), and
  `deleteByBusinessIdAndObligationTypeAndReminderSentFalse`/`deleteByCustomObligationIdAndReminderSentFalse`
  (issue #30, and its issue #59 counterpart) — called from `BusinessController.updateBusiness`
  whenever `financialYearEnd` actually changes, and from `CustomObligationController.updateCustomObligation`
  whenever a custom obligation's own `dueDate`/`recurrenceMonths` changes, to remove the
  now-stale, not-yet-reminded deadline the *old* value produced. `DeadlineSyncService`'s own
  dedupe check only ever prevents re-inserting a deadline that's already correct; it has no way
  to remove one that's become wrong because the value it was computed from changed underneath
  it, so without this cleanup an edited business/obligation would end up with the stale record
  still sitting in the reminder queue right alongside the newly-synced correct one.
- **`DeadlineSyncService`** — `@Service` with a `@Scheduled` method (`syncDeadlines`, daily at
  01:00 Singapore time — `zone = "Asia/Singapore"` explicitly, issue #28, not the server's own
  default timezone) that recomputes every business's deadlines (built-in *and* custom, issue #59)
  from scratch via `RuleEngine` each run and persists any not already stored, skipping ones that
  already exist so `reminderSent` isn't reset. Also exposes `findDueSoonAndUnreminded(referenceDate)`, which the dispatch step
  (`SqsDispatchService`) calls next to decide what actually gets pushed to the reminder queue —
  "due soon" is evaluated per-record against that record's own `business.leadTimeDays` (issue
  #53), not a single global window, so it's `@Transactional(readOnly = true)`: `Business` is a
  lazy relationship on `DeadlineRecord`, and reading `leadTimeDays` off it during the filtering
  needs the Hibernate session kept open past the initial repository call.
- **`SqsConfig`** — `@Configuration` producing a single `SqsClient` `@Bean`. When
  `aws.sqs.endpoint` is set (local dev), it points the client at LocalStack with throwaway
  credentials; when unset (real AWS deployment), it falls back to the SDK's default credential
  chain and endpoint resolution — same code, no branching logic needed to switch environments.
- **`ReminderMessage`** — a Java `record` (concise immutable data class — auto-generates
  constructor/getters/equals/hashCode) representing one reminder's JSON payload:
  `deadlineRecordId`, `businessId`, `obligationType`, `dueDate`.
- **`SqsDispatchService`** — `@Service` with a `@Scheduled` method (`scheduledDispatch`, daily at
  01:15 Singapore time, 15 minutes after the sync job — same explicit `zone` as above) that calls
  `findDueSoonAndUnreminded` (no longer takes a `daysAhead` parameter, issue #53 — there's no
  single global lookahead left to pass), serializes each result to JSON via Jackson, and sends it
  as an SQS message. Deliberately does **not** mark
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

**`DlqMonitorService`** (issue #18) closes the gap that redrive policy left open: once a message
actually landed in the DLQ, nothing surfaced it. Every 5 minutes (`fixedDelay = 300_000` — far
slower than `ReminderWorkerService`'s own 30s poll, since DLQ depth only ever changes after a
message has already failed 3 times), it checks the DLQ's `ApproximateNumberOfMessages` attribute
and logs a `WARN` (with the current correlation ID, issue #51) if it's non-zero. Deliberately
silent when the queue is empty — the overwhelmingly common case — so the log line stays a real
signal, not noise an operator learns to tune out.

**Deliberately does not add a new HTTP endpoint to expose this.** The app has no admin/role
concept at all yet — every endpoint just means "authenticated as *some* user" — and DLQ contents
span every business/user in the system, not just whichever caller happened to hit the endpoint.
Building a real admin-auth model just to expose one read-only number would be a much bigger,
separate scope decision than this issue asks for (see issues #65/#39, both of which would need
the same groundwork, both still open). Log-based alerting is the honest MVP instead — a line an
operator (or, on real AWS, a CloudWatch Logs metric filter/alarm — not set up here, this project
isn't deployed anywhere real yet) can act on — plus `aws sqs get-queue-attributes` for manual
inspection, the same technique already used to investigate issue #75's own queue-depth problem.

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

**`V4__add_idempotency_keys.sql`** (issue #61): a new `idempotency_key` table recording which
`Business` a given `(idempotency_key, owner_id)` pair already created, with a unique constraint
on that pair — the actual concurrency guarantee, not just an application-level lookup before
insert. See `IdempotencyKey` above.

**`V5__add_password_reset_tokens.sql`** (issue #37): a new `password_reset_token` table — a
single-use, expiring token generated by `POST /api/auth/forgot-password` and consumed by
`POST /api/auth/reset-password`. See "Password reset" in [security.md](security.md).

**`V6__add_email_verification.sql`** (issue #36): adds `email_verified` (default `false`) to
`app_user`, plus a new `email_verification_token` table — same single-use/expiring shape as
password reset tokens, generated on `register` and consumed by `POST /api/auth/verify-email`. See
"Email verification" in [security.md](security.md).

**`V7__add_token_valid_after.sql`** (issue #96): adds `token_valid_after` (nullable `TIMESTAMP`)
to `app_user` — a per-user floor set on a successful password reset, checked against every JWT's
`iat` claim so a token issued before the reset stops working. See "Token refresh"/session
invalidation in [security.md](security.md).

**`V8__add_business_lead_time_days.sql`** (issue #53): adds `lead_time_days` (`INTEGER NOT NULL
DEFAULT 14`) to `business` — replaces the single hardcoded 14-day reminder lookahead every
business used to share with a per-business value.

**`V9__add_business_incorporation_date.sql`** (issue #31): adds `incorporation_date` (nullable
`DATE`, no default) to `business` — lets `createBusiness` validate a first-year
`financialYearEnd` against the Companies Act's 18-month cap; a business that never sets it just
skips the check.

**`V10__add_indexes_for_common_queries.sql`** (issue #50) — see "Database indexes" below.

## Database indexes (issue #50)

Postgres does **not** automatically index a foreign key column on the referencing side (only the
referenced side gets one, implicitly, from its own primary key) — every `REFERENCES ...` column
added across `V1`–`V9` had been an unindexed sequential-scan target the whole time. Not a real
problem at this project's current tiny data volume, but each of these backs a query that runs on
every real request (or, for `deadline_record`, every sync/dispatch cycle across every business),
so worth getting right before real volume ever exists rather than after:

- `idx_business_owner_id` — `BusinessRepository.findByOwnerId`/`findByIdAndOwnerId`, on almost
  every authenticated business-related request.
- `idx_work_pass_business_id` — `WorkPassRepository.findByBusinessId`/`findByIdAndBusinessId`.
- `idx_deadline_record_business_obligation_due` (composite, `business_id, obligation_type,
  due_date`) — matches `DeadlineRecordRepository.existsByBusinessIdAndObligationTypeAndDueDate`
  exactly, the sync job's own dedupe check, run once per computed `Deadline` for every business
  on every daily sync — arguably the single hottest query in the app once real data volume
  exists. Its leading two columns also cover
  `deleteByBusinessIdAndObligationTypeAndReminderSentFalse`'s (issue #30) own `WHERE` clause as a
  prefix match.
- `idx_deadline_record_unreminded` — a **partial** index (`WHERE NOT reminder_sent`), not a plain
  one on the whole column, backing `findByReminderSentFalse` (the dispatcher's own
  table-wide "what might need a reminder" query). `reminder_sent` only ever flips false→true and
  stays true forever, so in real usage the `true` rows are pure dead weight for this specific
  query and only grow over time — a partial index tracks only the small, shrinking subset this
  query actually cares about.
- `idx_password_reset_token_user_id` / `idx_email_verification_token_user_id` —
  `deleteByUserId` on each. Lower volume in practice (a user has zero or one of each at a time)
  but still an unindexed FK lookup otherwise.

Deliberately **not** added: `app_user.email` (already `UNIQUE`, `V2`, Postgres backs a unique
constraint with its own index automatically), `idempotency_key`'s `(idempotency_key, owner_id)`
(already covered the same way by its own `UNIQUE` constraint, `V4`, which
`IdempotencyKeyRepository.findByKeyAndOwnerId`'s `WHERE` clause matches exactly).

**Verified live, not just "the migration ran cleanly":** `EXPLAIN` against the real local
Postgres confirmed the planner actually chooses `idx_business_owner_id` and
`idx_deadline_record_business_obligation_due` for their respective queries. The partial
`idx_deadline_record_unreminded` index, on this local dev/test database specifically, is *not*
chosen — worth understanding why rather than treating as a failure: this table has accumulated
~2,000 rows from repeated local test runs, and roughly 90% of them are unreminded (synthetic test
data never gets marked "sent" for real), so the partial index doesn't usefully narrow anything
against *this* table's current, test-skewed shape. In real usage the ratio inverts —
`reminder_sent` stays `true` forever once set, so unreminded is a small, shrinking minority, and
the planner would choose the index once the table's real shape reflects that. The planner is
making the right call for what's actually in the table right now; the index exists for the shape
the table will actually have in production.

## Planned (not built yet — see [open issues](https://github.com/compliance-tracker/compliance-tracker/issues))

- **Cloud deployment** — AWS ECS/Fargate + RDS, replacing local Docker Postgres; switch
  `aws.sqs.endpoint` off to use real AWS.
- **Load testing** — real throughput/latency numbers against the deployed system.
