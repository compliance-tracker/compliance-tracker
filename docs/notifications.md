# Notifications

How reminders — and, since issues #37/#36, password reset and email verification emails —
actually reach a user. See [architecture.md](architecture.md) for where `NotificationSender` sits
in the reminder pipeline.

The same `notifications.channel` setting controls both `NotificationSender` (reminders) and
`AuthEmailSender` (password reset #37, email verification #36) — one switch, two independent
interfaces (a reminder needs a `Business`/`DeadlineRecord`, an auth email just needs an email
address and a token). `NotificationSender` has three implementations (logging default, email,
webhook — see below); `AuthEmailSender` only ever has two (logging default, email) — there's no
sane way to deliver a password-reset link via a generic webhook, so `LoggingAuthEmailSender`
stays the fallback for *any* non-`email` channel value, `notifications.channel=webhook` included.
This isn't just a design note — it was a real bug found live (issue #62): `LoggingAuthEmailSender`
used to only activate for the literal value `"logging"`, so setting `channel=webhook` left no
`AuthEmailSender` bean at all and the whole app failed to start. Fixed via
`@ConditionalOnExpression` instead of a literal `havingValue` match.

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

**Both the password reset and verification emails contain a real, styled, clickable button** —
`{app.frontend-url}/reset-password?token=...` and `{app.frontend-url}/verify-email?token=...`
respectively, matching `ResetPasswordPage.tsx`/`VerifyEmailPage.tsx`'s own `?token=` query param
convention exactly, with the same link repeated as plain text underneath for any client that
strips the button's styling. `app.frontend-url` defaults to `http://localhost:5173` (the Vite dev
server) and needs updating to the real deployed frontend URL once one exists, same caveat as
`app.cors.allowed-origin`. Both emails share a small inline-styled HTML template
(`EmailTemplate`) loosely echoing the frontend's own teal/brass palette — deliberately
table-based, inline-CSS-only HTML (no `<style>` block, no external assets), since most email
clients (Outlook especially) strip or ignore anything else.

## Webhook/Slack reminders (issue #62)

A cheap alternative to email for anyone who'd rather see reminders land in a channel they already
monitor. `WebhookNotificationSender` posts a plain `{"text": "..."}` JSON body — Slack's own
incoming-webhook format, and one plenty of other tools (Mattermost, several generic "Slack-
compatible" webhook receivers) accept too, so this isn't Slack-specific code, just a
Slack-*shaped* request.

```bash
export WEBHOOK_URL=https://hooks.slack.com/services/T000/B000/xxxxxxxxxxxxxxxxxxxxxxxx
export NOTIFICATIONS_CHANNEL=webhook
./mvnw spring-boot:run
```

To get a real Slack incoming-webhook URL: a Slack workspace admin creates one at
[api.slack.com/apps](https://api.slack.com/apps) → "Incoming Webhooks" → "Add New Webhook to
Workspace", picks a channel, and copies the generated URL. Treat it as a secret — anyone with the
URL can post to that channel, no further auth needed, which is also why `GET
/api/notifications/status` never echoes it back (see below), the same reasoning it never echoes
`spring.mail.*` credentials for the email channel either.

The app **fails fast at startup**, not on the first real reminder, if `notifications.channel=webhook`
is set with no `notifications.webhook-url` configured — a blank URL would otherwise surface as a
confusing connection error deep inside the reminder worker's own retry logic instead of an
obvious, immediate config error.

Password reset and email verification still just log (via `LoggingAuthEmailSender`) when
`channel=webhook` — there's no webhook equivalent for those, by design, and both interfaces share
the one `notifications.channel` switch (see above), so there's no separate setting to make auth
emails go somewhere else while reminders go to a webhook.

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
```json
{ "channel": "webhook" }
```

The webhook channel's response deliberately carries no URL field at all — unlike `fromAddress`
(an identifier, safe to show), a webhook URL *is* the credential.

Deliberately just current config, not a "recently sent" history — that would need persisting a
send log somewhere, a bigger feature not requested here. Built for the frontend's Notifications
status page (frontend #39/#63), the one remaining piece of its Harbour Ledger redesign.
