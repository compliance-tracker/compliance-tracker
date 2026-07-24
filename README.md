# Compliance Tracker

A compliance deadline tracker for Singapore SMEs. Tracks obligations like ACRA Annual Return,
GST filing, and work pass renewals, computes each business's actual due dates from its own
parameters (e.g. Financial Year End), and dispatches automated reminders ahead of each deadline
via a scheduled sync → SQS queue → worker pipeline. The actual notification channel (email/SMS)
isn't wired up yet — reminders currently land as log output via a stand-in `NotificationSender`.

> **Disclaimer:** This is a reminder/tracking tool, not compliance advice. It is not a
> substitute for consulting a qualified accountant or company secretary. Deadline rules are
> sourced from public ACRA/IRAS/MOM pages but may become outdated — always verify against the
> official source before relying on a date.

This is a portfolio project built to demonstrate backend engineering fundamentals — a rules
engine, reliable job scheduling/dispatch, and cloud deployment — rather than a production
business.

## Tech stack

| Layer      | Choice                          |
|------------|----------------------------------|
| Language   | Java 21 (LTS)                    |
| Framework  | Spring Boot 4.1.0                |
| Build tool | Maven                            |
| Database   | PostgreSQL 16 (Docker locally)   |
| Testing    | JUnit 5 (via `spring-boot-starter-test`) |
| Queue      | AWS SQS (LocalStack locally/CI, real AWS at deployment) |
| CI         | GitHub Actions                   |
| Planned    | AWS ECS/Fargate + RDS (deployment) |

## Architecture (current)

```
Client (curl/browser)   @Scheduled 01:00      @Scheduled 01:15         poll every 30s
        │                       │                    │                        │
        ▼                       ▼                    ▼                        ▼
BusinessController      DeadlineSyncService ───► SqsDispatchService      ReminderWorkerService
    │        │                 │      │                  │       │            │        │
    ▼        ▼                 ▼      ▼                  ▼       ▼            ▼        ▼
BusinessRepo  RuleEngine ◄──────┘   DeadlineRecordRepo ◄──────────┴── AWS SQS ─┘   NotificationSender
    │        (pure logic,                  │                    (LocalStack           │
    ▼         no DB/HTTP dep)              ▼                     locally,             ▼
PostgreSQL (business, work_pass, deadline_record)              real AWS prod)   (logs only for now)
```

- **`Business`** — entity representing an SME and the parameters its compliance deadlines are
  computed from (`name`, `financialYearEnd`, `gstRegistered`).
- **`BusinessRepository`** — Spring Data JPA repository interface. Extending `JpaRepository`
  gives `save`/`findAll`/`findById`/etc. for free, with no method bodies written — Spring
  generates the implementation at runtime.
- **`WorkPass`** — entity representing one employee's work pass (`employeeName`, `expiryDate`),
  many-to-one linked back to the `Business` that employs them.
- **`WorkPassRepository`** — Spring Data JPA repository. Includes `findByBusinessId(Long)`,
  whose implementation Spring derives entirely from the method name (no query written by hand).
- **`RuleEngine`** — pure, unit-tested Java logic (`rules` package). Given a `Business`, its
  `WorkPass`es, and a reference date, computes the list of currently-applicable `Deadline`s
  (each an `ObligationType` + due `LocalDate`). Has no dependency on the database or HTTP layer,
  and takes the reference date as a parameter rather than calling `LocalDate.now()` internally,
  so tests are fully deterministic. Implements all three obligations: ACRA Annual Return, GST
  F5, and Employment Pass renewal (one deadline per `WorkPass`).
- **`BusinessController`** — exposes `POST /api/businesses` (create), `GET /api/businesses`
  (list), and `GET /api/businesses/{id}/deadlines` (compute and return that business's current
  deadlines via `RuleEngine`, including any work-pass renewals) over HTTP.
- **`HelloController`** — `GET /hello`, a minimal smoke-test endpoint from initial setup.
- **`DeadlineRecord`** — persisted counterpart to `rules.Deadline`. Adds the one thing pure
  computation can't carry: state, specifically `reminderSent`. `rules.Deadline` itself stays
  a pure in-memory value with no DB knowledge.
- **`DeadlineRecordRepository`** — Spring Data JPA repository for `DeadlineRecord`, including
  `existsByBusinessIdAndObligationTypeAndDueDate` (dedupe check) and
  `findByReminderSentFalseAndDueDateLessThanEqual` (the "what needs a reminder" query).
- **`DeadlineSyncService`** — `@Service` with a `@Scheduled` method (`syncDeadlines`, daily at
  01:00) that recomputes every business's deadlines from scratch via `RuleEngine` each run and
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
  01:15, 15 minutes after the sync job) that calls `findDueSoonAndUnreminded`, serializes each
  result to JSON via Jackson, and sends it as an SQS message. Deliberately does **not** mark
  `reminderSent` — that only happens in the worker, below, after a reminder is actually sent
  successfully, so a lost/failed message can still be retried rather than silently skipped.
  Verified with a real integration test (`SqsDispatchIntegrationTest`) that boots the full app
  and confirms a message actually lands in a real SQS queue (LocalStack locally).
- **`NotificationSender`** — interface for "how a reminder actually reaches a business."
  Kept separate from the worker so a real channel (email via AWS SES, SMS, etc.) can be swapped
  in later without touching the queue-consuming/idempotency logic.
- **`LoggingNotificationSender`** — the only implementation so far: logs the reminder instead
  of really sending one. No email provider is wired up yet — flagged here as a real scope gap,
  not a silently-cut corner. The rest of the pipeline is genuinely end-to-end functional with
  this as a stand-in.
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

### Dead-letter handling

The main queue (`compliance-reminders`) has a **redrive policy**: after `maxReceiveCount: 3`
failed receives (a message repeatedly not deleted, i.e. repeatedly failing in
`ReminderWorkerService`), SQS automatically moves it to `compliance-reminders-dlq` — no
application code is involved in the move itself, it's queue configuration. This was manually
verified against LocalStack (simulating 3 failed receives via `change-message-visibility`,
confirming the message lands in the DLQ) rather than covered by an automated test, since
reproducing it end-to-end would mean waiting out real SQS visibility timeouts or adding
test-only timing hooks not worth the complexity here.

### Local development: LocalStack

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

### Planned (not built yet — see [open issues](https://github.com/Chrainx/compliance-tracker/issues))

- **Real notification channel** — replace `LoggingNotificationSender` with an actual email
  (e.g. AWS SES) or SMS provider behind the existing `NotificationSender` interface.
- **DLQ monitoring/alerting** — currently, a message that lands in the dead-letter queue is
  silent; nothing surfaces it. Would need at minimum a way to inspect DLQ depth.
- **Cloud deployment** — AWS ECS/Fargate + RDS, replacing local Docker Postgres; switch
  `aws.sqs.endpoint` off to use real AWS.
- **Load testing** — real throughput/latency numbers against the deployed system.

### Compliance rules

| Obligation | Rule | Source | Status |
|---|---|---|---|
| ACRA Annual Return | `financialYearEnd + 7 months` | [ACRA — Deadline & requirements](https://www.acra.gov.sg/manage/companies/legal-requirements-common-offences/filing-annual-returns-companies/deadline-requirements/) | Implemented. Listed-company variant (5/6 months) not modeled — SME target audience is virtually always private/non-listed |
| GST F5 filing | `calendarQuarterEnd + 1 month` | [IRAS — Due dates and extensions](https://www.iras.gov.sg/taxes/goods-services-tax-(gst)/filing-gst/due-dates-and-requests-for-extension) | Implemented. Assumes standard calendar quarters; IRAS actually assigns a per-business cycle at GST registration which may not align to calendar quarters |
| Employment Pass renewal | `= passExpiryDate` (renewal window opens 6 months prior, no grace period after expiry) | [MOM — Renew a Pass (Employment Pass)](https://www.mom.gov.sg/passes-and-permits/employment-pass/renew-a-pass) | Implemented, via `WorkPass` entity — one deadline per employee pass |

This is a reminder/tracking tool, not compliance advice — always verify against the official
source before relying on a date (see disclaimer above).

## Running locally

Requires Java 21, Maven, and Docker.

```bash
# 1. Start Postgres (custom port 5434 to avoid clashing with other local projects)
docker run --name compliance-postgres -e POSTGRES_PASSWORD=devpassword \
  -e POSTGRES_DB=compliance_tracker -p 5434:5432 -d postgres:16

# 2. Start LocalStack (emulates AWS SQS - see "Local development: LocalStack" below for the
#    full setup including the dead-letter queue)
docker run --name compliance-localstack -e SERVICES=sqs -p 4566:4566 -d localstack/localstack:3.8.1
AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test aws --endpoint-url=http://localhost:4566 \
  --region us-east-1 sqs create-queue --queue-name compliance-reminders

# 3. Run the app (custom port 8081)
./mvnw spring-boot:run
```

The app will be available at `http://localhost:8081`.

If either container was already created in a previous session, `docker start compliance-postgres`
/ `docker start compliance-localstack` instead of `docker run` — otherwise `docker run` will
fail with a "name already in use" error.

**Note:** LocalStack's queue state is in-memory and does **not** survive a container restart
(`docker start` after it was stopped) — `docker ps` showing it "Up" doesn't mean the queue still
exists. Re-run the `create-queue` command (and the DLQ setup below) any time LocalStack was
previously stopped, even if reusing the same container.

## API

| Method | Path                          | Description                                |
|--------|-------------------------------|---------------------------------------------|
| GET    | `/hello`                      | Smoke-test endpoint                         |
| POST   | `/api/businesses`             | Create a business                           |
| GET    | `/api/businesses`             | List all businesses                         |
| GET    | `/api/businesses/{id}/deadlines` | Compute and return that business's current compliance deadlines |

Example:

```bash
curl -X POST http://localhost:8081/api/businesses \
  -H "Content-Type: application/json" \
  -d '{"name": "Test Cafe Pte Ltd", "financialYearEnd": "2026-12-31", "gstRegistered": true}'

curl http://localhost:8081/api/businesses/1/deadlines
# [{"obligationType":"ACRA_ANNUAL_RETURN","dueDate":"2027-07-31"},{"obligationType":"GST_F5","dueDate":"2026-10-30"}]
```

## Testing

```bash
./mvnw test
```

Requires both Postgres and LocalStack running (see "Running locally" above) —
`ComplianceTrackerApplicationTests`, `SqsDispatchIntegrationTest`, and
`ReminderWorkerIntegrationTest` boot the real Spring context and connect to both. All three
run with the `test` profile active (`scheduling.enabled=false`), so the real background
`@Scheduled` jobs don't run and race the tests' own explicit calls — see `SchedulingConfig`.
The other test classes are plain unit tests with no such dependency.

## Status

Actively in development. See [open issues](https://github.com/Chrainx/compliance-tracker/issues)
for the current roadmap.
