# Privacy notice

This is a plain-language description of what this app collects, why, how long it's kept, and how
to get it back or get rid of it — written to actually be read by a real business owner using the
app, not a legal document. See "Known gap" at the bottom before treating anything here as a
finished compliance story.

**This is a reminder/tracking tool, not compliance or legal advice.** Deadline calculations are
based on public rules published by ACRA, IRAS, and MOM, but you're responsible for verifying your
own actual obligations — see the disclaimer shown in the app itself.

## What's collected, and why

| Data | Why it's collected |
|------|---------------------|
| Your email address | Your account identifier — login, password reset, verification, and (if email notifications are configured) where reminders are sent |
| Your password | Hashed (BCrypt) before storage — never kept or logged in plain text, and never recoverable, only resettable |
| Business name, Financial Year End, GST registration status, incorporation date | The inputs the deadline rules engine needs to compute *your* actual ACRA/GST/work-pass due dates — nothing here is collected for any purpose beyond that calculation |
| Work pass employee names and expiry dates | Same reasoning — needed to compute and remind about work pass renewal deadlines |
| Custom obligation names and dates | Whatever you choose to track yourself — the app doesn't interpret or validate these against any real regulation, they're your own reminders |

Nothing here is sold, shared with third parties, or used for anything beyond running the app's
own deadline-tracking and reminder features.

## How it's protected

- Your password is BCrypt-hashed, never stored in a reversible form.
- Your email, business names, and work pass employee names are encrypted at rest (AES-256-GCM) —
  see [security.md](security.md#encryption-at-rest-issue-63). A stolen database backup alone
  doesn't reveal these values.
- All API access requires a JWT tied to your account — see [security.md](security.md) for the
  full authentication model.

## How long it's kept

Indefinitely, until you delete your account. There's currently no separate automatic data
retention/expiry policy beyond that — see "Known gap" below.

## Your access, correction, export, and deletion rights

- **Correct your data**: update a business's or work pass's own fields any time via the normal
  edit endpoints (`PUT /api/businesses/{id}`, etc.) — see [api.md](api.md).
- **Export everything**: `GET /api/auth/account/export` returns a single JSON document containing
  your account email, verification status, and every business you own with its work passes and
  custom obligations nested inside — a complete, unpaginated copy of everything the app holds
  about you.
- **Delete your account**: `DELETE /api/auth/account` deletes your account immediately, no
  confirmation step at the API level (a confirmation dialog is the frontend's job, same as
  business deletion already has). This cascades at the database level to every business you own
  (and, transitively, its work passes, computed deadlines, and custom obligations), any
  idempotency keys, and any outstanding password-reset/email-verification tokens — nothing is left
  behind. There is no "soft delete" or recovery grace period: deletion is immediate and permanent.

Both endpoints require being logged in as the account in question — there's no way to export or
delete anyone else's data, by construction (they act on whichever account the caller's own token
belongs to, never an id supplied in the request).

## Known gap — read before assuming this is a complete compliance program

Building the two endpoints above (data export and account deletion) addresses PDPA's Access &
Correction and Retention Limitation obligations specifically. It does **not**, by itself,
constitute a complete Singapore PDPA compliance program. In particular, two things are
deliberately **not** resolved by this document or any code in this repository, and are flagged
here rather than silently glossed over:

- **No Data Protection Officer (DPO) has been appointed.** PDPA requires every organization,
  regardless of size, to appoint one, with contact details publicly available. This is a
  business/organizational decision, not something an engineering task can resolve on its own.
- **No formal data breach incident-response process exists.** PDPA requires assessing a
  suspected breach (generally within 30 days of becoming aware) and notifying the PDPC within 3
  calendar days of determining it's notifiable (likely to cause significant harm, or affecting
  500+ individuals). No such process is documented or automated here.

Both are real, deliberate gaps — the same "deliberately deferred, not an oversight" framing this
project already uses for its [AWS deployment decision](../../CLAUDE.md) — appropriate to flag
clearly given this project's own standing rule against overstating its actual completeness, and
consistent with the app not yet being deployed anywhere handling real user data (see
[architecture.md](architecture.md)).
