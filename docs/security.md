# Security

Auth model, secrets handling, and the security-relevant fixes made along the way. See
[architecture.md](architecture.md) for the rest of the system.

## Authentication

- **`User`** — entity for a registered account (`email`, `passwordHash`). Table name is
  `app_user`, not `user` — a reserved word in Postgres.
- **`Business.owner`** — every business belongs to exactly one `User` (`@ManyToOne`,
  `@JsonIgnore` so the owner — including their password hash — never gets serialized into an
  API response).
- **`JwtService`** — generates and verifies signed tokens (HMAC-SHA256, via `jjwt`). A JWT's
  payload (the user's email) is readable by anyone, not encrypted — the signature is what
  makes it trustworthy, since only the server holding the signing secret can produce one that
  verifies.
- **`JwtAuthenticationFilter`** — runs once per request, reads `Authorization: Bearer <token>`,
  and if valid and not revoked (see "Logout / token revocation" below), populates Spring
  Security's context with the corresponding `User` as the authenticated principal.
- **`SecurityConfig`** — stateless (`SessionCreationPolicy.STATELESS`, no cookies/sessions at
  all), CSRF disabled (irrelevant for a token-based API), `/hello`, `/api/auth/**`, and `/error`
  open, everything else requires a valid token. Returns `401` (not Spring Security's 403
  default) for missing/invalid auth via a custom `AuthenticationEntryPoint`.
- **`AuthController`** — `POST /api/auth/register` and `POST /api/auth/login`, both returning a
  JWT. Passwords are hashed with BCrypt, never stored or compared in plain text. Login returns
  the same `401` whether the email doesn't exist or the password is wrong — revealing which one
  it was would let an attacker enumerate registered emails. Registration checks for an existing
  email up front, but also catches the DB's own unique-constraint violation around the actual
  save — two concurrent registrations for the same email resolve to exactly one `200` and one
  clean `409`, never an unhandled `500` (issue #42, a genuine reproduced race condition, not a
  theoretical one).
- **`BusinessController`** — every method scopes to `@AuthenticationPrincipal User`:
  `createBusiness` sets the owner automatically; `getAllBusinesses`/`getDeadlines` only ever
  return the current user's own businesses (`findByOwnerId`/`findByIdAndOwnerId`). A business
  that exists but belongs to someone else returns a plain `404`, not `403` — confirming "this ID
  exists, it's just not yours" leaks more than a flat "not found."

Verified two ways: `BusinessControllerTest`/`AuthControllerTest`/`JwtServiceTest` at the Java
method level (mocked dependencies), and `AuthIntegrationTest` at the real HTTP level (boots the
actual app, makes real requests via `TestRestTemplate`) — the latter is what actually proves
`SecurityConfig`'s rules work, since calling a controller method directly bypasses the security
filter chain entirely.

## Login rate limiting (issue #35)

`LoginRateLimiter` — an in-memory, per-IP fixed-window counter (5 failed attempts per minute,
then `429 Too Many Requests`, including for the correct password until the window resets).
Deliberately per-IP rather than per-email: rate-limiting by email would leak the same "does this
account exist" signal login's identical-401 already hides. Single-instance, in-memory by
design — no Redis/Bucket4j — a real multi-instance deployment behind a load balancer would need
a distributed store instead.

## Logout / token revocation (issue #41)

A JWT is stateless by design — the server never remembers which tokens exist, so without this,
"logout" did nothing server-side and a cleared-locally token stayed valid until its natural 24h
expiry. `TokenBlocklist` (modeled on `LoginRateLimiter` — same in-memory, single-instance
approach) holds revoked token strings; `POST /api/auth/logout` revokes the caller's own token by
exact string match, and `JwtAuthenticationFilter` checks the blocklist alongside the normal
signature/expiry check on every request. Revocation is by exact token string, not by user/email,
which makes it naturally per-session: each login produces a different token (a fresh issued-at
timestamp before signing), so logging out on one device doesn't invalidate others.

## The critical IDOR in `createBusiness` (issue #66)

Authentication (above) answers "is this a real logged-in user?" It doesn't answer "is this user
allowed to touch *this specific row*?" — `createBusiness` had exactly that second gap, and it
was a real, confirmed, exploitable bug: `@RequestBody Business business` bound the entire JSON
body onto the entity, including `id`. Spring Data JPA's `save()` does an `UPDATE` (not `INSERT`)
whenever the entity's `id` is non-null and already exists — so a logged-in, fully legitimate
user could `POST /api/businesses` with someone else's real business `id` and silently take
ownership of it. Fixed by clearing the id server-side (`business.setId(null)`) before saving,
forcing every create to always be an insert regardless of client input. The same defensive
pattern was applied to `WorkPassController.createWorkPass` from day one, since it has the
identical `@RequestBody Entity` shape.

## Secrets

`jwt.secret` and `spring.datasource.password` both default to placeholder values checked into
`application.properties` (`devpassword` / a generated-but-committed JWT key) — these only ever
match the local Docker/CI Postgres containers, but they're still in this public repo's git
history, so treat them as permanently public, not actually secret. Both are overridable via env
var (`JWT_SECRET` / `DB_PASSWORD`) without touching the defaults — a real deployment **must**
set fresh values this way (ideally via a real secret store like AWS Secrets Manager, not another
committed property), generated the same way as the placeholders:

```bash
openssl rand -base64 32
```

**Important nuance:** setting the env vars stops *new* deployments from reusing the committed
default, but does **not** erase the old value from git history — that requires deliberately
rewriting history (destructive, requires a force-push, breaks other clones), not something to
do without a real reason. Since nothing has ever been deployed with these values protecting
anything real, the practical exposure today is effectively zero — but a real deployment must
generate genuinely fresh values, never reuse the placeholders (issue #40).
