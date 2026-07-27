# API reference

Split into its own file rather than kept in the main README — the API surface is expected to
grow (business update/delete, admin rule endpoints, password reset, email verification are all
tracked/designed but not built yet), and issue #21 already anticipates this manually-maintained
table eventually being replaced by generated OpenAPI docs. Better here than moved twice.

| Method | Path                          | Auth required | Description                    |
|--------|-------------------------------|----------------|---------------------------------|
| GET    | `/hello`                      | No             | Smoke-test endpoint             |
| POST   | `/api/auth/register`          | No             | Create an account, returns `{ token, refreshToken }` — `400` if the password is under 8 characters or missing a letter/digit |
| POST   | `/api/auth/login`              | No             | Returns `{ token, refreshToken }` for an existing account — `429` after 5 failed attempts from the same IP within a minute |
| POST   | `/api/auth/refresh`            | No*            | Exchanges a valid refresh token for a brand new `{ token, refreshToken }` pair — the old refresh token is revoked in the same call (single-use/rotated), so reusing it afterward gets `401`. `400` if no `Bearer` token was sent, `401` if it's missing/expired/revoked/not actually a refresh token. *Same permitAll caveat as logout below |
| POST   | `/api/auth/logout`             | No*            | Revokes the caller's token immediately — `400` if no `Bearer` token was sent. *Not gated by `SecurityConfig` like other protected routes, but functionally requires a real token to do anything |
| POST   | `/api/businesses`             | Yes            | Create a business, owned by the caller |
| GET    | `/api/businesses`             | Yes            | List the caller's own businesses (not everyone's) |
| PUT    | `/api/businesses/{id}`        | Yes            | Update name/financialYearEnd/gstRegistered — 404 if it doesn't exist or isn't yours |
| DELETE | `/api/businesses/{id}`        | Yes            | Delete a business — also deletes its work passes and computed deadlines (DB-level cascade). 404 if it doesn't exist or isn't yours |
| GET    | `/api/businesses/{id}/deadlines` | Yes         | Compute and return that business's deadlines — 404 if it doesn't exist or isn't yours |
| POST   | `/api/businesses/{id}/work-passes` | Yes      | Create a work pass under that business — 404 if the business doesn't exist or isn't yours |
| GET    | `/api/businesses/{id}/work-passes` | Yes      | List work passes for that business — 404 if it doesn't exist or isn't yours |
| DELETE | `/api/businesses/{id}/work-passes/{workPassId}` | Yes | Remove a work pass — 404 if either the business or the pass doesn't exist/belong to the caller |

## Example

Register, then use the returned token for everything else:

```bash
TOKEN=$(curl -s -X POST http://localhost:8081/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email": "you@example.com", "password": "a-real-password"}' | jq -r .token)

curl -X POST http://localhost:8081/api/businesses \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{"name": "Test Cafe Pte Ltd", "financialYearEnd": "2026-12-31", "gstRegistered": true}'

curl http://localhost:8081/api/businesses/1/deadlines -H "Authorization: Bearer $TOKEN"
# [{"obligationType":"ACRA_ANNUAL_RETURN","dueDate":"2027-07-31"},{"obligationType":"GST_F5","dueDate":"2026-10-30"}]
```
