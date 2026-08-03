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
engine, real regulatory sourcing, and reliable job scheduling/dispatch — rather than a production
business. Real AWS deployment is deliberately deferred, not built (see Status below).

## Tech stack

| Layer      | Choice                          |
|------------|----------------------------------|
| Language   | Java 21 (LTS)                    |
| Framework  | Spring Boot 4.1.0                |
| Build tool | Maven                            |
| Database   | PostgreSQL 16 (Docker locally)   |
| Testing    | JUnit 5 (via `spring-boot-starter-test`), coverage via JaCoCo (issue #55) |
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

### Test coverage (issue #55)

`./mvnw test` produces a full JaCoCo coverage report at `target/site/jacoco/index.html` (open it
directly in a browser) — no separate `mvn verify` needed, no third-party account. CI uploads the
same report as a build artifact on every run (downloadable from the workflow run's own page),
whether or not the tests themselves passed. Informational only — no coverage percentage is
enforced as a merge gate.

### Running with Docker (issue #52)

A `Dockerfile` builds and runs the app as a container — real prep work for eventual ECS/Fargate
deployment (#5, deliberately on hold for cost reasons, not this), fully testable right now with
nothing but Docker itself, no AWS account needed:

```bash
docker build -t compliance-tracker-backend .

# Postgres/LocalStack (steps 1-2 above) still need to be reachable - host.docker.internal is
# Docker Desktop's own hostname for "the host machine", since the containerized app can't reach
# localhost:5434/4566 the way a directly-run ./mvnw process can.
docker run --rm -p 8081:8081 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5434/compliance_tracker \
  compliance-tracker-backend
```

Multi-stage (a JDK build stage, a much smaller JRE-only runtime stage), runs as a non-root user.
No `HEALTHCHECK` baked into the image itself — a real deployment's own task definition/pod spec
should point its health check at `GET /actuator/health` (issue #44) directly, not a second,
possibly-drifting copy of that same logic.

## Compliance rules

| Obligation | Rule | Source | Status |
|---|---|---|---|
| ACRA Annual Return | `financialYearEnd + 7 months`, recurring annually | [ACRA — Deadline & requirements](https://www.acra.gov.sg/manage/companies/legal-requirements-common-offences/filing-annual-returns-companies/deadline-requirements/) | Implemented. Listed-company variant (5/6 months) not modeled — SME target audience is virtually always private/non-listed |
| First-financial-year validation | A first-year `financialYearEnd` more than 18 months after `incorporationDate` is rejected (`400`) at business creation | Companies Act 1967 s.198; [ACRA — Choosing a company's FYE](https://www.acra.gov.sg/register/business/registering-different-business-structures/local-company/choosing-a-companys-financial-year-end/) (cites the same 18-month threshold for a changed FYE) | Implemented (issue #31). `incorporationDate` is optional — a business without one just skips the check, same as before this existed. The AR deadline formula itself doesn't change for a first year (always `FYE + 7 months`) — only the *allowed length* of that first year differs, which is what this validates |
| GST F5 filing | `accountingPeriodEnd + 1 month`, per `gstFilingFrequency` (`QUARTERLY` default or `MONTHLY`) | [IRAS — Due dates and extensions](https://www.iras.gov.sg/taxes/goods-services-tax-(gst)/filing-gst/due-dates-and-requests-for-extension) | Implemented (issue #45 added `MONTHLY`). Still assumes standard calendar months/quarters; IRAS actually assigns a per-business cycle at GST registration which may not align to calendar boundaries, and six-monthly filing (a real third IRAS-supported frequency) isn't modeled at all |
| Employment Pass renewal | `= passExpiryDate` (renewal window opens 6 months prior, no grace period after expiry) | [MOM — Renew a Pass (Employment Pass)](https://www.mom.gov.sg/passes-and-permits/employment-pass/renew-a-pass) | Implemented, via `WorkPass` entity — one deadline per employee pass |
| S Pass renewal | `= passExpiryDate` (renewal window opens 6 months prior, no grace period after expiry) — identical formula to Employment Pass | [MOM — Renew a Pass (S Pass)](https://www.mom.gov.sg/passes-and-permits/s-pass/renew-a-pass) | Implemented (issue #32), via `WorkPass.passType` |
| Work Permit renewal | `= passExpiryDate` (recommended application window 7–12 weeks before expiry, no grace period after expiry) — same due-date formula, only the advisory application window differs, which this app doesn't model | [MOM — Renew a Work Permit](https://www.mom.gov.sg/passes-and-permits/work-permit-for-foreign-worker/renew-a-work-permit) | Implemented (issue #32), via `WorkPass.passType` |
| Corporate Income Tax filing (Form C-S/C-S (Lite)/C) | `30 November` of the Year of Assessment (`financialYearEnd`'s own year + 1, under IRAS's preceding-year-basis rule — the calendar date is fixed regardless of which month the FYE itself falls in), recurring annually | [IRAS — Corporate Income Tax Filing Season](https://www.iras.gov.sg/taxes/corporate-income-tax/basics-of-corporate-income-tax/corporate-income-tax-filing-season-2026); [IRAS — Year of Assessment](https://www.iras.gov.sg/taxes/corporate-income-tax/basics-of-corporate-income-tax/basic-guide-to-corporate-income-tax-for-companies) | Implemented (issue #33), applies to every business unconditionally (same footing as ACRA). The separate, earlier Estimated Chargeable Income (ECI) filing (due 3 months after FYE) is a distinct real IRAS obligation not modeled here |

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

## Dependency vulnerability scanning

[Dependabot](https://docs.github.com/en/code-security/dependabot) is configured
(`.github/dependabot.yml`) to check Maven dependencies (`pom.xml`, including transitive ones) and
the CI workflow's own GitHub Actions weekly, opening a PR automatically when one has a known
vulnerability or a newer version available. Repo-level vulnerability alerts and automated
security-fix PRs are both enabled too — visible under the repo's Security tab.

## API documentation

Interactive, always-current API docs are generated automatically from the real controllers/DTOs
(issue #21, [springdoc-openapi](https://springdoc.org)) — run the app and open
`http://localhost:8081/swagger-ui/index.html` (or `GET /v3/api-docs` for the raw OpenAPI 3 spec).
Both are public, no auth required — it's documentation, not an endpoint acting on anyone's
behalf. Try-it-out against a real protected endpoint by registering/logging in via
`docs/api.md`'s curl walkthrough, then pasting the returned token into Swagger UI's own
"Authorize" button.

## More docs

- [docs/api.md](docs/api.md) — full endpoint reference and a curl walkthrough.
- [docs/architecture.md](docs/architecture.md) — how the pieces fit together: domain layer, web
  layer, the sync → dispatch → worker reminder pipeline, dead-letter handling, Flyway migrations,
  what's planned but not built yet.
- [docs/security.md](docs/security.md) — auth model, login rate limiting, token revocation,
  secrets handling, and the security-relevant bugs found and fixed (including a critical IDOR).
- [docs/notifications.md](docs/notifications.md) — configuring real email (Gmail SMTP) or
  previewing emails locally with Mailpit.
- [docs/privacy.md](docs/privacy.md) — what's collected and why, how to export or delete your
  data, and known gaps against a full PDPA compliance program (no DPO appointed, no formal breach
  process).

## Status

Feature-complete for what this project set out to demonstrate. All five compliance obligations
(ACRA Annual Return, GST F5, Employment Pass/S Pass/Work Permit renewal, corporate income tax)
are implemented and sourced from real gov.sg/IRAS/MOM pages, reachable end to end via the REST
API — not just modeled in the rules engine. The full reminder pipeline (scheduled sync → SQS
dispatch → worker, idempotent, with dead-letter handling) is real and tested, including under
genuine concurrency. Auth is JWT-based with email verification, password reset, rate limiting,
and encryption at rest for sensitive fields (PDPA-researched, not invented). CI runs the full
test suite plus JaCoCo coverage reporting on every push.

**Deliberately not done**: real AWS deployment (issue #5) and load testing (#6) — not blocked on
anything, a considered call not to spend money keeping infrastructure running for a project that
already demonstrates everything it needs to without one (Fargate has no free tier, unlike RDS).
See [open issues](https://github.com/compliance-tracker/compliance-tracker/issues) for anything
still tracked.
