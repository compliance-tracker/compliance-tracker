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

## Token refresh (issue #26)

Before this, the only way to get a new token after the access token's 24h expiry was logging in
again with a password — reasonable for a demo, annoying for a real user. Now `register`/`login`
return **two** tokens: `token` (short-lived, 24h, attached to every normal API request — same
name/lifetime as before, kept for backward compatibility with the existing frontend, which
already reads `response.token`) and `refreshToken` (long-lived, 7 days, usable for exactly one
thing: `POST /api/auth/refresh`).

They're distinguished by a `type` claim inside the JWT payload itself — a refresh token carries
`type=refresh`, an access token doesn't — checked in two places: `JwtAuthenticationFilter` rejects
a refresh token presented as if it were an access token (so a stolen refresh token can't be used
to actually call the API), and `AuthController.refresh` rejects an access token presented as if it
were a refresh token (so a short-lived token can't be used to mint itself an indefinite chain of
replacements).

`POST /api/auth/refresh` implements **rotation**: exchanging a refresh token immediately revokes
it (via the same `TokenBlocklist` logout already uses) and returns a brand new access/refresh
pair. This makes each refresh token single-use — reusing one that's already been exchanged
(whether by the legitimate client retrying, or an attacker who intercepted it after it was already
used) gets a `401`, not a second valid pair.

**A real bug found live, not hypothetically**: standard JWT `iat`/`exp` claims only have *second*
precision. Two tokens generated for the same email within the same wall-clock second (e.g. two
refresh calls fired back-to-back) were byte-identical signed strings — which silently broke
rotation, since revoking "the old token" by exact string match also revoked the "new" one just
issued to replace it, because they were the same string. Fixed by adding a random `jti` (JWT ID,
via `UUID.randomUUID()`) to every generated token, access or refresh, guaranteeing two tokens are
never identical even if issued in the same second. Regression-tested in `JwtServiceTest`, and
reproduced/re-verified live via curl both before and after the fix.

**Access token lifetime (24h) was deliberately left unchanged**, not shortened now that refresh
exists — shortening it before the frontend actually implements silent/automatic refresh would
just make the current UI log users out more often, a regression with no offsetting benefit yet.
Tightening it is a natural follow-up once the frontend half of this is built.

## CORS on every response, including rejections (issue #83)

CORS is configured via a `CorsConfigurationSource` bean, wired into `SecurityConfig` via
`.cors(Customizer.withDefaults())` rather than a plain `WebMvcConfigurer.addCorsMappings(...)`.
That distinction matters: MVC-level CORS only applies to requests that reach a controller through
the normal dispatch path, so a request Spring Security rejects early (a 401 for a missing/expired
token, committed directly by the `AuthenticationEntryPoint`) never picked up CORS headers.
`.cors(...)` registers Spring Security's own `CorsFilter` at the very front of the chain instead,
so every response — success or rejection — carries `Access-Control-Allow-Origin`. Without this, a
401 in a real browser doesn't surface as a readable status at all, just an opaque "blocked by CORS
policy" network failure, which silently broke the frontend's ability to tell "your session expired"
apart from "the backend is unreachable."

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
