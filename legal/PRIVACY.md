# Privacy notice

**Kotatsu-Redo community server** · last updated 11 September 2026

This describes the **official instance** at `community.kotatsuredo.app`, operated by the Kotatsu-Redo
project and hosted in France. The server is open source, so other people run their own instances
with their own operators — see [Self-hosters](#self-hosters) at the end.

This notice covers only the community server: comments, ratings, and source telemetry. The
Kotatsu-Redo app itself sends nothing here unless you switch the community features on, and reading
manga never involves this server at all.

---

## The short version

- **There is no account in the usual sense.** No email address, no password, no phone number, no
  name. Your identity is a random key generated on your device.
- **We never store your IP address.** Not in a web-server log, not in the database, not anywhere. The
  reverse proxy has access logging disabled — permanently, and that is the whole mechanism.
- **You can erase everything from the app, instantly**, with no email confirmation and nobody to ask.
- **Nothing here is sold, shared, or used for advertising.** There is no advertising, no analytics
  product, and no third-party tracking of any kind.

---

## What the server holds

### Your identity

When you turn the community features on, the app generates **32 random bytes** on your device. That
key is your account. The server stores only its SHA-256 hash, the way a hashed API key is stored, so
a copy of the database contains no credential that could be used to log in as you.

Your public id is derived from that hash and looks like `eJrEznC1pMoflAzF5Mk1EU`. It identifies your
comments to other readers and means nothing outside this server.

If you set a **nickname**, it is stored as you typed it and shown with a four-character suffix
(`reader#ejre`) so two people can pick the same name.

**If you lose the key, it is gone.** Nobody can recover it, because nobody — including us — has it.
That is the same property that stops anyone tying your comments back to you. Back it up: the app can
export it, and it travels in Android's automatic backup if you have that switched on.

### Two device fingerprints, taken once

When your identity is first created, and **only** then, the app sends two values:

| Value | What it is | What it is for |
|---|---|---|
| `ANDROID_ID` | An identifier Android gives to each app install on each device | Enforcing a device ban |
| MediaDrm ID | A DRM identifier many Android devices have | Flagging suspected ban evasion |

Both are **hashed with a secret held only by the server** before being stored, so the stored values
cannot be matched against a guessed device id by anyone who obtains the database.

Only the `ANDROID_ID` hash is ever acted on automatically. The MediaDrm hash is never enough to block
anybody: those identifiers are shared between devices of the same model, so a match only raises a
flag for a person to look at. Banning on one would ban a stranger who did nothing.

These are sent at signup and never again. Ordinary requests do not carry them.

### Getting your account back

Your key is the only proof that an account is yours. The server never treats an `ANDROID_ID` or
MediaDrm value as a login credential and never attaches a new key to an existing account because a
device identifier matches. If Android backup restores the original key, the account continues to
work. If that key is lost, the server cannot recover the account; a new key creates a new account.

### What you write

- **Comments**: the text, the work and chapter, the language detected from the text, when it was
  posted and last edited, and its like and dislike counts.
- **Ratings**: a number from 1 to 10 (half-stars) against a work.
- **Votes**: which comments you liked or disliked.
- **The days you were active** — dates only, no times — used to work out how long you have been
  around, which sets your posting rate limits.

### Rejected comments

When the word filter stops a comment, the server records which rule matched, the language, and
**the text you were trying to post**.

That text is kept for **30 days** and then erased. The rest of the record — which rule, which
language, and whether you reported it as a mistake — is kept, because those counts are how bad rules
get found and switched off. Without them, a broken word list quietly ruins the feature for a whole
language and nobody ever finds out.

### Moderation records

Every action a moderator takes is written to an append-only log: who did it, what they did, the
reason, and when. If a comment is removed, the log keeps a copy of its text — that is what makes the
log able to answer "what did it actually say" months later, and what makes a moderator's decision
reviewable rather than final.

**This log is not erased when you delete your account.** If you were moderated, your public id and
the reason remain in it. A record that its subject can delete is not an audit trail, and being able
to show what was done and why is a condition of operating a service like this responsibly.

### Source telemetry — optional, and separate

This is switched on separately from the rest, and off unless you say otherwise.

The app measures how well each manga *source* is working — did the request succeed, how long it
took, was it blocked — and uploads counts. **It never contains which manga you looked at**, or any
title, or any search you typed. It is about the source, not about you.

Each upload is tagged with a pseudonym that is **recomputed every day** as
`HMAC(your key, today's date)`. The server never stores your key, so once a request is over nobody
can work out which pseudonym belonged to which account — not us, not somebody holding the database
and every key the server has. It is deliberately built so that even we cannot undo it.

The raw rows are **deleted after 7 days**. What survives is a score per source per region, which is
not about any person.

Your **region** is worked out on your device from your language and time zone — never from your IP
address — and is deliberately coarse: `EU`, `NA`, `APAC` and a handful more.

---

## What the server does not hold

- Your IP address.
- Your email address, phone number, or real name.
- Which manga you read, search for, or have in your library.
- Any advertising or analytics identifier.
- Any third-party tracker. The server loads nothing from anyone else's domain.

The server never stores, proxies, or serves manga content of any kind. It holds discussion about
works, and nothing else.

---

## Who can see what

- **Your comments, nickname and public id** are visible to anyone using the app.
- **Your ratings** are visible only as part of a work's average. Nobody sees what you personally gave
  a work.
- **Your votes** are never shown to anyone, including the person you voted on.
- **Moderators** can see everything above plus the moderation queues and the block log. They cannot
  see your key, your device hashes in any usable form, or your IP address, because the server does
  not have them.

---

## How long things are kept

| | |
|---|---|
| Comments | Until you delete them, or you delete your account |
| Ratings and votes | Until you change them, or you delete your account |
| Rejected comment text | 30 days |
| Rejected comment counts | Indefinitely — not linked to you after account deletion |
| Raw telemetry | 7 days |
| Telemetry scores | Indefinitely — aggregate, not about any person |
| Moderation log | Indefinitely |
| Your identity | Until you delete it |
| Database backups | 7 days |

### About backups

The database is backed up daily and each backup is kept for **seven days**, then destroyed.

This is the one place where "deleted immediately" needs a qualification, and it is better said than
discovered: when you delete a comment or your whole account, it goes from the live database at once
and stops being visible to anyone straight away — but it survives in backups until those age out. So
the honest promise is **gone immediately, and gone everywhere within seven days**.

Backups are only ever restored to recover from a failure of the whole service, never to look
something up.

---

## Deleting everything

**Settings → Community → Delete my data**, in the app. It happens immediately. There is no email
loop and nobody to ask, because your key is the only proof required.

What happens:

- Your ratings are **erased**, and every affected work's average is recalculated on the spot.
- Your votes are **erased**.
- Your comments are **blanked**. The empty rows stay so that replies other people wrote underneath
  them still have a thread to hang from — they carry no text, no nickname and no id. Nothing of yours
  remains in them.
- Your identity row, nickname, every key that spoke for it, and your device hashes are
  **deleted** - so a later install on the same phone starts over rather than finding you again.
- Moderation log entries about you, if any, remain — see above.

Uninstalling the app does not do this. Uninstalling leaves your comments up; the button removes them.

---

## Your rights

The server is operated from France, so the GDPR applies.

Two of them are buttons in the app rather than requests to us, which is deliberate:

- **Erasure** — *Settings → Community → Delete my data*. Immediate, no confirmation loop.
- **Access and portability** — *Settings → Community → Export my data*. Downloads everything the
  server holds about your account as a JSON file: your comments (including any a moderator removed),
  your ratings, your votes, your nickname and when the account was created.

For correction, restriction or objection, or to complain about how this is run, reach us through the
[Discord](https://discord.gg/eu3gnd89gP) or the
[issue tracker](https://github.com/Kotatsu-Redo/Kotatsu-Redo/issues).

Be aware of the limit built into the design: **we cannot identify you.** If you write to us asking
for your data, we have no way to tell which account is yours — there is no email address to match
against. Holding the key is the only proof of identity that exists here, which is exactly why access
and erasure are buttons authenticated by that key rather than a process run by us. That is the direct
cost of collecting nothing, and we think it is the right trade.

Two things cannot be exported even in principle, and the export file says so itself: your key (the
server only ever had its hash) and your telemetry (keyed by a pseudonym recomputed daily from that
key, so nobody can work out which rows were yours).

The legal basis for holding what is held is legitimate interest: running a discussion service, and
being able to moderate it. The telemetry is consent, given by switching it on and withdrawn by
switching it off.

You have the right to complain to a supervisory authority — in France, the
[CNIL](https://www.cnil.fr/).

---

## Children

The community features are for people aged **18 or over**. The app itself is not age-restricted, and
reading manga with the community features switched off involves this server not at all.

If we learn that an account belongs to someone under 18, it is removed.

---

## Changes

This notice is versioned in the repository alongside the code it describes, so its history is public
and any change is a visible commit. Material changes will be announced on the Discord.

---

## Self-hosters

If you run your own instance, you are the operator of it and this notice is not yours. Two things to
change before you publish your own version:

- **The operator and contact** — the Discord and issue tracker above are the official instance's.
- **The hosting location and the law that follows from it.** The GDPR section assumes an EU host and
  names the CNIL, which is the French authority. If you host in the US, DMCA safe harbour matters and
  you should register a designated agent instead.
- **The backup retention**, if yours differs from seven days.
- **Anything you put in front of the instance.** This notice describes traffic arriving directly at the server. A CDN or a tunnel in the path terminates TLS and logs addresses, and if you add one you have to say so.

Everything describing **what the software collects and for how long** is a property of the code and
is true for your instance too, provided you have not changed it and you have left access logging off
in `deploy/Caddyfile`. If you turn that on, the claim that IP addresses are never stored stops being
true for your instance, and you must say so.
