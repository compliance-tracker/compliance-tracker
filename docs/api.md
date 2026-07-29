# API reference

Split into its own file rather than kept in the main README — the API surface is expected to
grow (admin rule endpoints are tracked/designed but not built yet), and issue #21 already
anticipates this manually-maintained table eventually being replaced by generated OpenAPI docs.
Better here than moved twice.

| Method | Path                          | Auth required | Description                    |
|--------|-------------------------------|----------------|---------------------------------|
| GET    | `/hello`                      | No             | Smoke-test endpoint             |
| POST   | `/api/auth/register`          | No             | Create an account, returns `{ token, refreshToken }` — `400` if the password is under 8 characters or missing a letter/digit. Also emails a verification token (informational only right now — see "Email verification" in [security.md](security.md); nothing currently checks it) |
| POST   | `/api/auth/login`              | No             | Returns `{ token, refreshToken }` for an existing account — `429` after 5 failed attempts from the same IP within a minute |
| POST   | `/api/auth/refresh`            | No*            | Exchanges a valid refresh token for a brand new `{ token, refreshToken }` pair — the old refresh token is revoked in the same call (single-use/rotated), so reusing it afterward gets `401`. `400` if no `Bearer` token was sent, `401` if it's missing/expired/revoked/not actually a refresh token. *Same permitAll caveat as logout below |
| POST   | `/api/auth/logout`             | No*            | Revokes the caller's token immediately — `400` if no `Bearer` token was sent. *Not gated by `SecurityConfig` like other protected routes, but functionally requires a real token to do anything |
| POST   | `/api/auth/forgot-password`    | No             | Always returns `200` regardless of whether the email is registered (avoids leaking which emails have accounts) — if it is, emails a single-use reset token valid for 1 hour |
| POST   | `/api/auth/reset-password`     | No             | Consumes a token from `forgot-password` and sets a new password — `401` if the token is missing/already used/expired, `400` if the new password is too weak (same rule as registration) |
| POST   | `/api/auth/verify-email`       | No             | Consumes the token emailed on registration, marks the account verified — `401` if the token is missing/already used/expired (valid for 7 days) |
| POST   | `/api/businesses`             | Yes            | Create a business, owned by the caller — `400` if `name` is blank, `financialYearEnd` is missing, `leadTimeDays` is present but outside 1–90, or `incorporationDate` is present and `financialYearEnd` is more than 18 months after it (issue #31, Companies Act s.198's first-year cap). `leadTimeDays` (issue #53, how many days ahead of a deadline to send its reminder) and `incorporationDate` are both optional and simply skip their respective checks if omitted. Accepts an optional `Idempotency-Key` header (any client-generated string, typically a UUID); resending the same key returns the original business instead of creating a duplicate |
| GET    | `/api/businesses`             | Yes            | List the caller's own businesses, paginated (issue #49) — see "Pagination" below |
| PUT    | `/api/businesses/{id}`        | Yes            | Update name/financialYearEnd/gstRegistered/leadTimeDays/incorporationDate — same `400` validation as create for `leadTimeDays`, `404` if it doesn't exist or isn't yours. Omitting `leadTimeDays`/`incorporationDate` leaves the business's current value unchanged rather than resetting it. The 18-month first-year check does **not** re-run here — see [architecture.md](architecture.md) for why. If `financialYearEnd` actually changes, any not-yet-reminded ACRA deadline computed from the old value is removed immediately (issue #30) — the next sync recomputes the correct one |
| DELETE | `/api/businesses/{id}`        | Yes            | Delete a business — also deletes its work passes and computed deadlines (DB-level cascade). 404 if it doesn't exist or isn't yours |
| GET    | `/api/businesses/{id}/deadlines` | Yes         | Compute and return that business's deadlines — 404 if it doesn't exist or isn't yours |
| POST   | `/api/businesses/{id}/work-passes` | Yes      | Create a work pass under that business — `400` if `employeeName` is blank or `expiryDate` is missing, `404` if the business doesn't exist or isn't yours |
| GET    | `/api/businesses/{id}/work-passes` | Yes      | List work passes for that business, paginated (issue #49) — 404 if it doesn't exist or isn't yours |
| DELETE | `/api/businesses/{id}/work-passes/{workPassId}` | Yes | Remove a work pass — 404 if either the business or the pass doesn't exist/belong to the caller |

## Errors

Every error response across the API (issue #47) has the same JSON shape:

```json
{ "error": "UNAUTHORIZED", "message": "Incorrect email or password." }
```

`error` is a short, machine-readable code — safe to branch on directly (`body.error === "UNAUTHORIZED"`)
instead of string-matching a status code or a human-readable message. Codes in use: `BAD_REQUEST`,
`UNAUTHORIZED`, `CONFLICT`, `TOO_MANY_REQUESTS`, `NOT_FOUND`. `message` is for humans/logging only,
never for a client to parse.

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

Register, then use the returned token for everything else:

```bash
TOKEN=$(curl -s -X POST http://localhost:8081/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email": "you@example.com", "password": "a-real-password1"}' | jq -r .token)

curl -X POST http://localhost:8081/api/businesses \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{"name": "Test Cafe Pte Ltd", "financialYearEnd": "2026-12-31", "gstRegistered": true}'

curl http://localhost:8081/api/businesses/1/deadlines -H "Authorization: Bearer $TOKEN"
# [{"obligationType":"ACRA_ANNUAL_RETURN","dueDate":"2027-07-31"},{"obligationType":"GST_F5","dueDate":"2026-10-30"}]
```
