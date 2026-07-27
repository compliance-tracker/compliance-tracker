# Compliance Tracker

A compliance deadline tracker for Singapore SMEs. Tracks obligations like ACRA Annual Return,
GST filing, and work pass renewals, computes each business's actual due dates from its own
parameters (e.g. Financial Year End), and dispatches automated reminders ahead of each deadline
via a scheduled sync → SQS queue → worker pipeline. Reminders can be sent as real email (SMTP)
or, by default, just logged — see [docs/notifications.md](docs/notifications.md).

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
| Migrations | Flyway                           |
| Auth       | Spring Security + JWT (`jjwt`)   |
| CI         | GitHub Actions                   |
| Planned    | AWS ECS/Fargate + RDS (deployment) |

## Running locally

Requires Java 21, Maven, and Docker.

```bash
# 1. Start Postgres (custom port 5434 to avoid clashing with other local projects)
docker run --name compliance-postgres -e POSTGRES_PASSWORD=devpassword \
  -e POSTGRES_DB=compliance_tracker -p 5434:5432 -d postgres:16

# 2. Start LocalStack (emulates AWS SQS - see docs/architecture.md for the full setup
#    including the dead-letter queue)
docker run --name compliance-localstack -e SERVICES=sqs -p 4566:4566 -d localstack/localstack:3.8.1
AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test aws --endpoint-url=http://localhost:4566 \
  --region us-east-1 sqs create-queue --queue-name compliance-reminders

# 3. Run the app (custom port 8081)
./mvnw spring-boot:run
```

The app will be available at `http://localhost:8081`. If either container was already created
in a previous session, `docker start compliance-postgres` / `docker start compliance-localstack`
instead of `docker run` — but LocalStack's queue state does **not** survive a restart, so
re-run the `create-queue` step regardless (see docs/architecture.md for the full DLQ setup too).

`jwt.secret`/`spring.datasource.password` default to placeholder values safe for local/CI use
only — see [docs/security.md](docs/security.md) before deploying this anywhere real.

## Compliance rules

| Obligation | Rule | Source | Status |
|---|---|---|---|
| ACRA Annual Return | `financialYearEnd + 7 months`, recurring annually | [ACRA — Deadline & requirements](https://www.acra.gov.sg/manage/companies/legal-requirements-common-offences/filing-annual-returns-companies/deadline-requirements/) | Implemented. Listed-company variant (5/6 months) not modeled — SME target audience is virtually always private/non-listed |
| GST F5 filing | `calendarQuarterEnd + 1 month` | [IRAS — Due dates and extensions](https://www.iras.gov.sg/taxes/goods-services-tax-(gst)/filing-gst/due-dates-and-requests-for-extension) | Implemented. Assumes standard calendar quarters; IRAS actually assigns a per-business cycle at GST registration which may not align to calendar quarters |
| Employment Pass renewal | `= passExpiryDate` (renewal window opens 6 months prior, no grace period after expiry) | [MOM — Renew a Pass (Employment Pass)](https://www.mom.gov.sg/passes-and-permits/employment-pass/renew-a-pass) | Implemented, via `WorkPass` entity — one deadline per employee pass |

This is a reminder/tracking tool, not compliance advice — always verify against the official
source before relying on a date (see disclaimer above).

## Testing

```bash
./mvnw test
```

Requires both Postgres and LocalStack running (see "Running locally" above) —
`ComplianceTrackerApplicationTests`, `SqsDispatchIntegrationTest`, `ReminderWorkerIntegrationTest`,
and `AuthIntegrationTest` boot the real Spring context and connect to both. All run with the
`test` profile active (`scheduling.enabled=false`), so the real background `@Scheduled` jobs
don't run and race the tests' own explicit calls — see `SchedulingConfig`. The other test
classes (including `AuthControllerTest`, `JwtServiceTest`) are plain unit tests with no such
dependency.

## More docs

- [docs/api.md](docs/api.md) — full endpoint reference and a curl walkthrough.
- [docs/architecture.md](docs/architecture.md) — how the pieces fit together: domain layer, web
  layer, the sync → dispatch → worker reminder pipeline, dead-letter handling, Flyway migrations,
  what's planned but not built yet.
- [docs/security.md](docs/security.md) — auth model, login rate limiting, token revocation,
  secrets handling, and the security-relevant bugs found and fixed (including a critical IDOR).
- [docs/notifications.md](docs/notifications.md) — configuring real email (Gmail SMTP) or
  previewing emails locally with Mailpit.

## Status

Actively in development. See [open issues](https://github.com/compliance-tracker/compliance-tracker/issues)
for the current roadmap.
