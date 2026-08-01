# API reference

Split into its own file rather than kept in the main README — the API surface is expected to
grow (admin rule endpoints are tracked/designed but not built yet).

**A generated, always-current alternative to this hand-maintained table now exists too** (issue
#21): run the app and open `http://localhost:8081/swagger-ui/index.html` for an interactive
Swagger UI (or `GET /v3/api-docs` for the raw OpenAPI 3 spec) — both public, no auth required,
generated automatically from the real controllers/DTOs so it can't drift the way a hand-written
table can. This table stays too, since a scannable overview and an interactive per-endpoint
explorer serve different purposes — not a duplicate to eventually delete.

| Method | Path                          | Auth required | Description                    |
|--------|-------------------------------|----------------|---------------------------------|
| GET    | `/hello`                      | No             | Smoke-test endpoint             |
| GET    | `/actuator/health`            | No             | Overall health — `{"status":"UP"}` with no other detail (issue #44). `/actuator/health/liveness`/`/actuator/health/readiness` sub-groups exist too, for a container orchestrator's separate liveness/readiness probes — see [architecture.md](architecture.md) |
| POST   | `/api/auth/register`          | No             | Create an account and email a verification token — returns `{ message }`, **not** usable tokens (issue #120, expanded scope) — `400` if the password is under 8 characters or missing a letter/digit. The caller must verify and then call `login` separately; see "Email verification" in [security.md](security.md) |
| POST   | `/api/auth/login`              | No             | Returns `{ token, refreshToken }` for an existing, verified account — `429` after 5 failed attempts from the same IP within a minute, `403` if the account exists and the password is correct but the email isn't verified yet (issue #120) |
| POST   | `/api/auth/resend-verification` | No            | Always returns `200` regardless of whether the email is registered or already verified (same enumeration-avoidance as `forgot-password`) — if it's registered and unverified, emails a fresh single-use verification token, replacing any previous one (issue #120) |
| POST   | `/api/auth/refresh`            | No*            | Exchanges a valid refresh token for a brand new `{ token, refreshToken }` pair — the old refresh token is revoked in the same call (single-use/rotated), so reusing it afterward gets `401`. `400` if no `Bearer` token was sent, `401` if it's missing/expired/revoked/not actually a refresh token. *Same permitAll caveat as logout below |
| POST   | `/api/auth/logout`             | No*            | Revokes the caller's token immediately — `400` if no `Bearer` token was sent. *Not gated by `SecurityConfig` like other protected routes, but functionally requires a real token to do anything |
| POST   | `/api/auth/forgot-password`    | No             | Always returns `200` regardless of whether the email is registered (avoids leaking which emails have accounts) — if it is, emails a single-use reset token valid for 1 hour |
| POST   | `/api/auth/reset-password`     | No             | Consumes a token from `forgot-password` and sets a new password — `401` if the token is missing/already used/expired, `400` if the new password is too weak (same rule as registration) |
| POST   | `/api/auth/verify-email`       | No             | Consumes the token emailed on registration, marks the account verified — `401` if the token is missing/already used/expired (valid for 7 days) |
| POST   | `/api/businesses`             | Yes            | Create a business, owned by the caller — `400` if `name` is blank, `financialYearEnd` is missing, `leadTimeDays` is present but outside 1–90, or `incorporationDate` is present and `financialYearEnd` is more than 18 months after it (issue #31, Companies Act s.198's first-year cap). `leadTimeDays` (issue #53, how many days ahead of a deadline to send its reminder), `incorporationDate`, and `gstFilingFrequency` (issue #45, `QUARTERLY` or `MONTHLY` — only meaningful when `gstRegistered` is true) are all optional and default/skip their respective checks if omitted. Accepts an optional `Idempotency-Key` header (any client-generated string, typically a UUID); resending the same key returns the original business instead of creating a duplicate |
| GET    | `/api/businesses`             | Yes            | List the caller's own businesses, paginated (issue #49) — see "Pagination" below |
| PUT    | `/api/businesses/{id}`        | Yes            | Update name/financialYearEnd/gstRegistered/leadTimeDays/incorporationDate/gstFilingFrequency — same `400` validation as create for `leadTimeDays`, `404` if it doesn't exist or isn't yours. Omitting `leadTimeDays`/`incorporationDate`/`gstFilingFrequency` leaves the business's current value unchanged rather than resetting it. The 18-month first-year check does **not** re-run here — see [architecture.md](architecture.md) for why. If `financialYearEnd` actually changes, any not-yet-reminded ACRA deadline computed from the old value is removed immediately (issue #30); the same happens for `GST_F5` if `gstFilingFrequency` actually changes (issue #45) — the next sync recomputes the correct one in either case |
| DELETE | `/api/businesses/{id}`        | Yes            | Delete a business — also deletes its work passes and computed deadlines (DB-level cascade). 404 if it doesn't exist or isn't yours |
| GET    | `/api/businesses/{id}/deadlines` | Yes         | Compute and return that business's deadlines, built-in *and* custom (issue #59), excluding any the caller has manually dismissed (issue #34) — 404 if it doesn't exist or isn't yours |
| GET    | `/api/businesses/{id}/deadlines/history` | Yes  | Every `DeadlineRecord` ever persisted for that business, past and future, paginated (issue #57) — unlike the live `deadlines` endpoint above (which only ever shows a recurring obligation's *next* occurrence), this is the real audit trail: what was actually filed/reminded and when. Each entry carries `reminderSent` and `dismissed` (cross-referenced against issue #34's dismissals) so the frontend can show a real status per row. 404 if it doesn't exist or isn't yours |
| POST   | `/api/businesses/{id}/deadlines/dismiss` | Yes | Manually mark a specific deadline (identified by `obligationType`+`dueDate`, plus `customObligationId` for `CUSTOM`) as handled — removes it from the live deadlines view above and stops it triggering an automated reminder (issue #34). Idempotent: dismissing an already-dismissed deadline returns the existing row rather than erroring or duplicating. `404` if the business, or a supplied `customObligationId`, doesn't exist or isn't the caller's |
| GET    | `/api/businesses/{id}/deadlines/dismissed` | Yes | List every deadline the caller has manually dismissed for that business (issue #34) — 404 if it doesn't exist or isn't yours |
| DELETE | `/api/businesses/{id}/deadlines/dismiss/{dismissedDeadlineId}` | Yes | Un-dismiss — the deadline reappears in the live view and dispatch pipeline again (issue #34). `204` on success, `404` if the dismissed-deadline row doesn't exist or isn't the caller's |
| POST   | `/api/businesses/{id}/work-passes` | Yes      | Create a work pass under that business — `400` if `employeeName` is blank or `expiryDate` is missing, `404` if the business doesn't exist or isn't yours |
| GET    | `/api/businesses/{id}/work-passes` | Yes      | List work passes for that business, paginated (issue #49) — 404 if it doesn't exist or isn't yours |
| DELETE | `/api/businesses/{id}/work-passes/{workPassId}` | Yes | Remove a work pass — 404 if either the business or the pass doesn't exist/belong to the caller |
| POST   | `/api/businesses/{id}/custom-obligations` | Yes | Create a custom obligation under that business (issue #59) — `400` if `name` is blank or `dueDate` is missing, `404` if the business doesn't exist or isn't yours. `recurrenceMonths` is optional (`@Min(1)`) — omitted means a one-off obligation the caller re-edits themselves once handled; set means it recurs every N months from `dueDate`, recomputed live (never mutating the stored `dueDate`), the same pattern the ACRA rule already uses |
| GET    | `/api/businesses/{id}/custom-obligations` | Yes | List custom obligations for that business, paginated (issue #49) — 404 if it doesn't exist or isn't yours |
| PUT    | `/api/businesses/{id}/custom-obligations/{customObligationId}` | Yes | Update name/dueDate/recurrenceMonths — same `400` validation as create, `404` if either doesn't exist or isn't yours. Any not-yet-reminded deadline computed from the old values is removed immediately (same #30-style cleanup as editing a business's `financialYearEnd`) — the next sync recomputes the correct one |
| DELETE | `/api/businesses/{id}/custom-obligations/{customObligationId}` | Yes | Remove a custom obligation — also removes its computed deadlines (DB-level cascade). 404 if either doesn't exist or isn't yours |
| GET    | `/api/notifications/status`   | Yes            | Read-only status of the active `NotificationSender`/`AuthEmailSender` channel (issue #114) — `{"channel":"logging"}`, `{"channel":"email","fromAddress":"..."}`, or `{"channel":"webhook"}` (issue #62 — no URL exposed, it's a credential). Reflects real server-side config, not anything per-user; requires auth like the rest of the API, not `permitAll()`'d like `/actuator/health` |
| GET    | `/api/auth/account/export`    | Yes            | PDPA Access & Correction Obligation (issue #48) — a complete, unpaginated JSON export of everything the caller's account owns: email, verification status, and every business with its work passes and custom obligations nested inside. See [privacy.md](privacy.md) |
| DELETE | `/api/auth/account`           | Yes            | PDPA Retention Limitation Obligation (issue #48) — deletes the caller's account immediately, no confirmation step (that's a frontend concern). Cascades at the DB level (`V13` migration) to every business (and transitively its work passes/deadlines/custom obligations), idempotency keys, and password-reset/email-verification tokens. `204` on success. See [privacy.md](privacy.md) |

## Errors

Every error response across the API (issue #47) has the same JSON shape:

```json
{ "error": "UNAUTHORIZED", "message": "Incorrect email or password." }
```

`error` is a short, machine-readable code — safe to branch on directly (`body.error === "UNAUTHORIZED"`)
instead of string-matching a status code or a human-readable message. Codes in use: `BAD_REQUEST`,
`UNAUTHORIZED`, `FORBIDDEN`, `CONFLICT`, `TOO_MANY_REQUESTS`, `NOT_FOUND`. `message` is for
humans/logging only, never for a client to parse.

## Pagination (issue #49)

`GET /api/businesses` and `GET /api/businesses/{id}/work-passes` both accept `?page=` (0-indexed,
default `0`) and `?size=` (default `20`, capped at `100` — an oversized request is silently
clamped, not rejected) query params, and return the same envelope shape instead of a bare array:

```json
{
  "content": [ /* the actual page of results */ ],
  "page": 0,
  "size": 20,
  "totalElements": 5,
  "totalPages": 1
}
```

## Idempotency (issue #61)

`POST /api/businesses` accepts an optional `Idempotency-Key` header. A network retry after a
timeout — the request actually succeeded server-side, the client just never saw the response —
would otherwise create a duplicate business. Generate one key per logical "create this business"
attempt (a UUID works well) and resend the same key on retry; the second request returns the
original business unchanged instead of creating a second one. Omitting the header entirely is
the default and always safe — every prior behavior is unchanged.

## Example

Register, verify (the token is only ever emailed - logged, not sent, unless
`notifications.channel=email` is configured, see [notifications.md](notifications.md)), log in,
then use the returned token for everything else:

```bash
curl -s -X POST http://localhost:8081/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email": "you@example.com", "password": "a-real-password1"}'
# {"message":"Registration successful. Check your email to verify your account, then log in."}

# Read the real token out of the logs/Mailpit/your inbox, then:
curl -X POST http://localhost:8081/api/auth/verify-email \
  -H "Content-Type: application/json" -d '{"token": "the-real-token"}'

TOKEN=$(curl -s -X POST http://localhost:8081/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "you@example.com", "password": "a-real-password1"}' | jq -r .token)

curl -X POST http://localhost:8081/api/businesses \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{"name": "Test Cafe Pte Ltd", "financialYearEnd": "2026-12-31", "gstRegistered": true}'

curl http://localhost:8081/api/businesses/1/deadlines -H "Authorization: Bearer $TOKEN"
# [{"obligationType":"ACRA_ANNUAL_RETURN","dueDate":"2027-07-31"},{"obligationType":"GST_F5","dueDate":"2026-10-30"}]
```
