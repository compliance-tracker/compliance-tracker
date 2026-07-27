# Notifications

How reminders actually reach a business owner — see [architecture.md](architecture.md) for
where `NotificationSender` sits in the reminder pipeline.

Reminders are just logged by default — nothing to configure, safe for CI/local dev. To actually
send real emails instead:

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
3. Reminders now send to whatever email each business's owner registered with, from
   `MAIL_USERNAME`.

Any SMTP provider works, not just Gmail — override `spring.mail.host`/`spring.mail.port` in
`application.properties` (or as env vars) if using a different one.

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
