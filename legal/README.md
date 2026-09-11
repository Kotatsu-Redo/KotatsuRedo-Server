# Policies

The documents governing the **official instance** at `community.kotatsuredo.app`, operated by the
Kotatsu-Redo project and hosted in France.

| | | Served at |
|---|---|---|
| [Terms of service](TERMS.md) | What you agree to by using it | `/terms` |
| [Content policy](CONTENT-POLICY.md) | What gets removed, and how | `/content-policy` |
| [Privacy notice](PRIVACY.md) | What is held, for how long, and how to erase it | `/privacy` |
| [Community rules](../src/main/resources/rules.html) | The short version, shown in the app | `/rules` |

## Why these are Markdown, in the repository

Versioned next to the code they describe, so every change to a privacy notice is a public commit
with a date and a diff against it. That is both the honest way to publish a policy and a good-faith
signal if anyone ever asks when something changed.

They are copied into the jar at build time and rendered by the instance, so what a user opens is the
text that was actually committed — a notice nobody can reach is not published.

## They are tested

`PolicyClaimsTest` asserts the privacy notice's specific, checkable promises against the live schema:
no column anywhere could hold an IP address, `app_user` has no email or password, raw telemetry is
deleted at seven days, blocked text at thirty, `comment.user_id` is `SET NULL` so deleting an account
cannot take other people's replies with it, and the moderation log is append-only in the database.

A migration that breaks one of those fails the build. That is the point: the failure mode for a
privacy notice is not going stale, it is quietly describing a service that no longer exists.

`MarkdownTest` covers the renderer, including against these three documents.

## Decisions behind them

| | |
|---|---|
| Jurisdiction | France. GDPR (CNIL) and the DSA apply; no DMCA agent is needed |
| Minimum age | 18+, for the community features only — the app itself is unrestricted |
| Operator | The Kotatsu-Redo project, as a **non-professional publisher** under the LCEN — which is what lets it stay unnamed publicly, provided the host holds its identity |
| Backups | Daily, kept 7 days |
| Host | IONOS SARL, Paris datacentre — named in the terms as the LCEN requires |
| Scope | Written for the official instance, with what a self-hoster must change marked in each document |

## If you run your own instance

Each document ends with a **Self-hosters** section naming exactly what to change — in every case the
operator, the contact, and the law that follows from where you host.

Everything describing *what the software collects and for how long* is a property of the code and is
true of your instance too, with one exception you control: the claim that IP addresses are never
stored holds only while access logging stays off in `deploy/Caddyfile`. If you turn it on, that
sentence stops being true for your instance and you must say so.
