# Compliance Tracker — Backend

See `../CLAUDE.md` for overall project purpose and how I want to collaborate — this file only
covers backend/Java-specific details.

## Tech stack (decided, don't change without discussing)

- **Language:** Java 21 (LTS — deliberately not 25/26)
- **Framework:** Spring Boot 4.1.0
- **Build tool:** Maven
- **Database:** PostgreSQL 16 (Docker locally), schema managed by **Flyway** migrations (`src/main/resources/db/migration/`) — not `ddl-auto`
- **Queue:** AWS SQS — LocalStack locally/CI, real AWS at deployment
- **Auth:** Spring Security + JWT (stateless, `jjwt` library — chose JWT over session cookies since the frontend is a separate origin/app, not server-rendered)
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
- **`gh` CLI auth needs scopes added as they come up** — the default token only has `repo`/`read:org`/`gist`. Creating a GitHub Project needed `project` added (`gh auth refresh -h github.com -s project`), deleting a repo needed `delete_repo`. Each is a device-code flow requiring the user to approve in a browser — can't be done silently.
- **AWS CLI against LocalStack needs dummy credentials present** (`AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test`) even though LocalStack doesn't actually check them — the CLI itself refuses to run with none configured at all.
- **This repo moved GitHub ownership**: originally `Chrainx/compliance-tracker`, transferred to the `compliance-tracker` organization (see root CLAUDE.md). Local remote was updated (`git remote set-url origin ...`) — if a stale remote URL ever causes push/fetch confusion, that's why.
- **Spring Security's default rejection status is 403, not 401**, for a request with no authentication at all — needs an explicit `AuthenticationEntryPoint` (see `SecurityConfig`) to get the semantically-correct 401. Don't assume the default is right without checking.
- **`TestRestTemplate` moved out of `spring-boot-starter-test` in Spring Boot 4** — separate module (`spring-boot-resttestclient` + `spring-boot-restclient`, both needed), new package (`org.springframework.boot.resttestclient.TestRestTemplate`), and `@SpringBootTest` no longer auto-provides it — needs `@AutoConfigureTestRestTemplate` explicitly. Third Spring Boot 4 modularization surprise this project has hit (after Jackson's package rename and Flyway's starter requirement) — the pattern now: if something that used to "just work" silently doesn't, check whether it moved to its own module before assuming the code is wrong.
- **`@JsonIgnore` matters on any entity relationship that could reach sensitive data** — `Business.owner` (a `User`, which has a password hash) would otherwise get serialized straight into API responses. Caught this while building auth, before it shipped as a bug, not after.
- **`@RequestBody` binding straight onto a JPA entity is dangerous if the entity's `id` field isn't guarded** — a client can supply their own `id` in the body, and `JpaRepository.save()` silently does an UPDATE (not INSERT) whenever that id is non-null and exists, overwriting someone else's row. Found and fixed as a real, confirmed IDOR in `createBusiness` (issue #66) — auth alone (knowing *who* you are) doesn't protect *which row* you're allowed to touch. Same risk exists on any other `@RequestBody Entity` endpoint added later; either clear the id server-side before `save()` or (better, tracked as #46) use a dedicated request DTO with no `id` field at all.
- **A "recurring" rule computed from a single stored date needs to actually recompute from `referenceDate`, not just apply a fixed offset once** — GST F5 got this right from the start (`calendarQuarterEnd(referenceDate) + 1 month`, so it naturally tracks whatever quarter `today` is in). ACRA Annual Return didn't: `financialYearEnd.plusMonths(7)` was a one-time calculation with no dependency on `referenceDate` at all, so once that single deadline passed there was no way to get next year's. Fixed (issue #27) via `nextAcraDeadline`, which keeps adding a year until the result isn't before `referenceDate`. Worth checking any *new* rule against this same question: does it actually use `referenceDate` to find the next occurrence, or does it silently assume "today" is always before the one date it computes?
- **Spring MVC's internal forward to `/error` is itself subject to `SecurityConfig`'s rules** — a valid token hitting a malformed path param or an unmapped path was getting misreported as `401` instead of the real `400`/`404`, because `/error` wasn't in the `permitAll()` list and the forwarded dispatch didn't carry authentication through. Fixed (issue #67) by adding `/error` to `permitAll()`. Not a security hole (no response body leaked either way), but a correctness/DX trap: don't assume a 401 means "auth is broken" without checking whether the *real* failure is somewhere else entirely and just got relabeled on the way out.
- **A new `@RequestBody Entity` endpoint should apply the #66 id-clearing fix from day one, not after the fact** — `WorkPassController.createWorkPass` (#24) has the exact same `@RequestBody WorkPass` shape that caused the original IDOR, so `workPass.setId(null)` was written in from the start rather than shipping the same bug again and fixing it later.
- **Two `@ConditionalOnProperty`-gated beans implementing the same interface, one as the default** — `NotificationSender` now has `LoggingNotificationSender` (`matchIfMissing = true`, active whenever `notifications.channel` isn't explicitly `email`) and `EmailNotificationSender` (`havingValue = "email"`). This pattern (a safe zero-config default + an opt-in real implementation behind one property) is worth reusing for any future integration that needs real credentials CI/local dev shouldn't require.
- **Every `spring.mail.*` value is env-var overridable, not just username/password** — `MAIL_HOST`/`MAIL_PORT`/`MAIL_SMTP_AUTH`/`MAIL_SMTP_STARTTLS`, defaulting to real Gmail SMTP. This is what lets the exact same config point at [Mailpit](https://mailpit.axllent.org) (`docker run axllent/mailpit`, real local SMTP + web UI, zero credentials) for previewing emails locally instead of a real account — see README's "Notifications" section.
- **A singleton bean holding mutable state (`LoginRateLimiter`) is shared across every test in the same cached `@SpringBootTest` context** — and since `TestRestTemplate` calls all originate from the same loopback IP, one test's failed login attempts silently count toward another test's rate-limit budget if test order isn't controlled. Fixed in `AuthIntegrationTest` via `@TestMethodOrder` + `@Order(1)` (run the rate-limit test first, before anything else can pollute the shared IP's count) plus `@DirtiesContext(methodMode = AFTER_METHOD)` (rebuild the context, and the bean, afterward so it doesn't pollute what runs next). Worth remembering for any future stateful singleton tested via real HTTP calls.

## Running end-to-end tests / live checks

For actually driving the running app in a browser (not just `./mvnw test`), `chromium-cli` isn't installed in this environment — fall back to installing `playwright` + `playwright install chromium` in a **scratch directory** (not this repo — don't add it as a project dependency for a one-off check), and drive it with a small script. See conversation history for the working pattern (navigate, fill form, submit, screenshot, check console/network errors). Consider `/run-skill-generator` if this becomes a recurring need.

## Project status

Issues #1–4, #7–16, #19 are closed (rules engine, REST API, full reminder pipeline with SQS dispatch/worker/idempotency/dead-letter handling, Flyway migrations, CORS, auth + multi-tenancy). Note: #3 was actually done ages ago but left open by oversight — closed retroactively; watch for this kind of drift.

**Auth (#19) shipped**: real accounts (register/login), JWT-based, every `Business` scoped to its owner, enforced at the API level (not just hidden in the UI). Existing test businesses were deleted as part of this (they had no owner concept, couldn't be migrated forward) — a fresh `app_user` table backs everything now.

**Recently closed (PRs #68/#69/#70/#71/#72/#73)**: #66 (critical IDOR in `createBusiness` — client-supplied `id` let any user overwrite another user's business), #67 (401 masking legitimate 400/404 errors on `/error`), #27 (ACRA Annual Return deadline not recurring annually), #24 (WorkPass CRUD — the third of three advertised obligations is now actually reachable end to end, not just implemented in `RuleEngine`), #35 (login rate limiting — 5 failed attempts per IP per minute, then 429), #17 (real notification channel — `EmailNotificationSender` sends real SMTP email when `notifications.channel=email`; `LoggingNotificationSender` stays the zero-config default for CI/local dev).

**#5** (deploy to real AWS) and **#6** (load testing) are open but **deliberately deferred, not blocked** — see root `CLAUDE.md`'s "AWS deployment decision": Fargate has no free tier, cost (not account access) is why this is on hold. Also open: **#18** (DLQ monitoring/alerting), **#20** (input validation — `spring-boot-starter-validation` has been a dependency since day one but never actually used), **#21** (API docs/OpenAPI), plus business edit/delete (no endpoint exists for either — found while working #66/#24, may not have its own issue yet, worth checking).

See `README.md` and GitHub issues for full detail; don't duplicate that detail here.
