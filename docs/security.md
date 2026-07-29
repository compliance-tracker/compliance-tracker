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

## Password strength on registration (issue #43)

`AuthController.register` rejects a password under 8 characters or missing a letter/digit with a
plain `400`, before ever checking whether the email is already taken. Deliberately minimal — not
a full complexity ruleset (no forced special characters or mixed case) — enough to stop trivially
weak passwords without frustrating real users over rules that don't meaningfully improve security
here. Checked in `register` only, not `login` — `AuthRequest` is shared between the two endpoints,
and this must never reject a login attempt for an existing account whose password predates this
check.

## Password reset (issue #37)

`POST /api/auth/forgot-password` always returns `200`, whether or not the email actually belongs
to an account — same enumeration-avoidance reasoning as login's identical `401` for "no such
user" and "wrong password" above. If the account does exist, a single-use token
(`UUID.randomUUID()`, same idiom as the JWT `jti` claim) is generated, valid for 1 hour
(`auth.password-reset-expiration-ms`), and emailed via `AuthEmailSender` — logged, not really
sent, unless `notifications.channel=email` is configured (see [notifications.md](notifications.md)).
Requesting a reset again before using the first token invalidates it - only the most recently
issued token for a given user is ever valid.

`POST /api/auth/reset-password` consumes the token: `401` if it's missing, already used, or
expired (again, the same code for all three - not a distinct "expired" vs "invalid" response,
which would let a client probe which raw token strings once existed). A successful reset applies
the same password strength check as registration (issue #43) and deletes every reset token for
that user, not just the one used, so a reset genuinely ends the token's usability rather than
leaving a second still-valid one from an earlier request.

**Session invalidation on reset (issue #96):** `User.tokenValidAfter` is set to `Instant.now()` on
a successful `reset-password`. Unlike `TokenBlocklist` (which revokes by exact token string, and
only ever sees a token this app itself explicitly revoked, e.g. via logout), this is a per-user
floor checked against every token's own `iat` claim — so it also catches tokens `TokenBlocklist`
never had a reference to, without needing to enumerate and revoke each one individually.
`JwtAuthenticationFilter.isValidForUser` enforces it for access tokens, `AuthController.refresh`
enforces the same check for refresh tokens (otherwise a pre-reset refresh token could just mint a
fresh access token forever, defeating the point). `NULL` (every account that's never reset its
password) means no floor at all, the previous unrestricted behavior.

**Known limitation of the check itself:** a JWT `iat` claim only has *second* precision (the JWT
numeric-date format), while `tokenValidAfter` is a sub-second `Instant`. The comparison floors
`tokenValidAfter` down to the second it falls in before comparing, so a token minted in the exact
same second as the reset — even a fraction *before* it — is still accepted. This is a deliberate,
narrow (sub-one-second) trade-off in favor of not locking a user out of the very session they just
created by logging back in immediately after a reset, not an oversight.

## Email verification (issue #36)

`register` now also generates a single-use token (valid 7 days, `auth.email-verification-expiration-ms`
— deliberately longer than the password reset token above, since verifying an email is much
lower stakes and a new user might not check their inbox right away) and emails it via the same
`AuthEmailSender` used for password reset, marking the new account `emailVerified = false` until
`POST /api/auth/verify-email` consumes it.

**Enforced at both registration and login (issue #120).** `register` no longer returns any tokens -
just `{ message }` confirming the account was created and that a verification email was sent.
`POST /api/auth/login` is the actual enforcement point: correct credentials against an unverified
account get a `403 FORBIDDEN` ("Please verify your email before logging in."), not the `200` a
verified account gets - a deliberately distinct response from the `401` "wrong email or password"
case, since these credentials genuinely are correct. Not counted against `LoginRateLimiter` either
- that exists to slow down credential-guessing, not to penalize a real, correctly-authenticated
user.

(An earlier version of this enforcement kept `register`'s auto-login and only gated `login` -
reconsidered almost immediately, before it shipped further, once it was clear that auto-logging
into an account that then couldn't log back in again without verifying first was a confusing
half-measure, not a real feature. The frontend's registration flow needs a matching update -
tracked as its own cross-linked issue, frontend#75 - since it currently expects `register` to log
the user straight into the dashboard.)

Since the original verification token is single-use and only valid 7 days, `POST
/api/auth/resend-verification` (email in, always `200` whether or not the account exists or is
already verified - same enumeration-avoidance shape as `forgot-password`) issues a fresh one,
replacing any previous unconsumed token - added alongside the enforcement itself specifically to
avoid permanently locking out anyone whose original token expired or never arrived.

Gating other real functionality on verification (e.g. requiring it before creating a business) was
considered and deliberately not done here - login is the one meaningful checkpoint a real user
actually passes through, and blocking individual actions afterward would be considerably more
design surface for limited extra benefit.

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
