# Notifications

How reminders — and, since issues #37/#36, password reset and email verification emails —
actually reach a user. See [architecture.md](architecture.md) for where `NotificationSender` sits
in the reminder pipeline.

The same `notifications.channel` setting controls both `NotificationSender` (reminders) and
`AuthEmailSender` (password reset #37, email verification #36) — one switch, two independent
interfaces (a reminder needs a `Business`/`DeadlineRecord`, an auth email just needs an email
address and a token), each with its own logging-default/email-opt-in pair of implementations.

Reminders (and reset emails) are just logged by default — nothing to configure, safe for CI/local
dev. To actually send real emails instead:

1. Generate a Gmail **app password** at [myaccount.google.com/apppasswords](https://myaccount.google.com/apppasswords)
   (requires 2-Step Verification enabled first). Don't use your real account password — Gmail
   rejects plain-password SMTP login outright once 2FA is on, and an app password can be
   revoked independently later without touching the real login.
2. Set these before starting the app:
   ```bash
   export MAIL_USERNAME=youraddress@gmail.com
   export MAIL_APP_PASSWORD=the-16-character-app-password
   export NOTIFICATIONS_CHANNEL=email
   ./mvnw spring-boot:run
   ```
3. Reminders now send to whatever email each business's owner registered with, and password
   reset requests (`POST /api/auth/forgot-password`) and new registrations (email verification)
   now email the real token instead of just logging it - all from `MAIL_USERNAME`.

Any SMTP provider works, not just Gmail — override `spring.mail.host`/`spring.mail.port` in
`application.properties` (or as env vars) if using a different one.

**The password reset email contains a real clickable link** (`{app.frontend-url}/reset-password?token=...`),
not just the bare token — the frontend's `/reset-password` page (its own issue #55) reads the
token straight from that URL's `?token=` query param. `app.frontend-url` defaults to
`http://localhost:5173` (the Vite dev server) and needs updating to the real deployed frontend
URL once one exists, same caveat as `app.cors.allowed-origin`. **The verification email still
sends the raw token as plain text, not a link** — frontend issue #56 (the verify-email UI) isn't
built yet, so a link would point nowhere real; update it the same way once #56 lands.

## Previewing emails locally without a real account (Mailpit)

To see exactly what a reminder email looks like during local dev, without touching Gmail or any
real account at all, point the app at [Mailpit](https://mailpit.axllent.org) instead — a fake
local SMTP server with a web UI showing every email it "received":

```bash
docker run --name compliance-mailpit -p 1025:1025 -p 8025:8025 -d axllent/mailpit

MAIL_HOST=localhost MAIL_PORT=1025 MAIL_SMTP_AUTH=false MAIL_SMTP_STARTTLS=false \
  MAIL_FROM=reminders@compliance-tracker.test NOTIFICATIONS_CHANNEL=email \
  ./mvnw spring-boot:run
```

Open `http://localhost:8025` to see every email the app sends land there instantly — nothing
leaves your machine, no credentials of any kind needed. Verified working end to end while
building #17: a real email arrived with the correct sender, recipient, subject, and body.

If either container was already created in a previous session, `docker start compliance-mailpit`
instead of `docker run` — otherwise `docker run` will fail with a "name already in use" error.

## Checking the active channel over the API (issue #114)

`GET /api/notifications/status` (auth required, like the rest of the API) reports which channel
is actually active right now, without needing shell/SSH access to read `application.properties`
or the process's env vars directly:

```json
{ "channel": "logging" }
```
```json
{ "channel": "email", "fromAddress": "reminders@yourdomain.com" }
```

Deliberately just current config, not a "recently sent" history — that would need persisting a
send log somewhere, a bigger feature not requested here. Built for the frontend's Notifications
status page (frontend #39/#63), the one remaining piece of its Harbour Ledger redesign.
