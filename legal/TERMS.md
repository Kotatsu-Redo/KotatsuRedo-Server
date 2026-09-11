# Terms of service

**Kotatsu-Redo community server** · last updated 11 September 2026

These terms cover the **official instance** at `community.kotatsuredo.app`, operated by the
Kotatsu-Redo project and hosted in France. Other people run their own instances under their own
terms — see [Self-hosters](#self-hosters).

They cover the community features only: comments, ratings, and source telemetry. They do not cover
the Kotatsu-Redo app, which is separate, free software, and works with none of this switched on.

---

## 1. What this is

A place to talk about manga. You can comment on works and chapters, rate them, like and dislike other
people's comments, and optionally contribute measurements that help the app decide which sources to
try first.

It is run by volunteers, for free, with no guarantee that it will be running tomorrow.

## 2. Who can use it

You must be **18 or over** to use the community features.

There is no age verification and we are not going to pretend otherwise — this is a statement of who
the service is for, and grounds for removing an account when we learn otherwise. The app itself has
no age requirement; only these features do.

You may not use the service if you have previously been banned from it.

## 3. Your account

Your account is a random key generated on your device. There is no password to reset and no email to
recover from.

**If you lose the key, the account is gone.** We cannot restore it — not as a policy, but because the
server never had it. That is the same property that stops anyone connecting your comments to you.
Back it up.

Anyone holding your key *is* you as far as the server is concerned. Keep it to yourself.

## 4. What you post

You keep ownership of what you write. By posting, you grant the operators of this instance a
non-exclusive, worldwide, royalty-free licence to store and display it as part of the service, and to
keep serving it to other users. This lasts until you delete the comment or your account.

Do not post anything you do not have the right to post.

Everything you write is **published immediately**, without review. That is a deliberate choice, and
it is why [the content policy](CONTENT-POLICY.md) matters: it is the rulebook that makes instant
publication workable, and it is part of these terms.

## 5. What we will do

- Remove content that breaks the content policy.
- Ban accounts, and in serious or repeated cases the devices behind them.
- Act on reports from rights-holders and from people being harmed, promptly, and keep a record of
  having done so.

Moderation is done by people, who will sometimes be wrong. Every action is logged with a reason and
can be reviewed and reversed.

## 6. Bans

A ban removes your access to the community features and deletes your comments. It does not affect the
app, your library, or your ability to read anything.

**There is no appeal inside the app**, by design — there is no mechanism for a banned account to
contact anybody, because that mechanism would be a harassment surface. If you think a ban was a
mistake, say so on the [Discord](https://discord.gg/eu3gnd89gP).

Device bans are reversible and are reversed when they turn out to be wrong. A device identifier can
be shared between unrelated devices, so we never ban on the weaker of the two identifiers we hold.

## 7. What we do not promise

The service is provided **as is**. No uptime guarantee, no promise your data will still be there
tomorrow, no warranty of any kind, express or implied.

This is a hobby project run at cost on one small server. It may go down, lose data, or be
discontinued. Keep nothing here that you cannot afford to lose, and back up your key.

To the fullest extent the law allows, the operators are not liable for any loss arising from your
use of the service. Nothing here limits liability that cannot be limited under French law — including
liability for gross negligence (*faute lourde*), intentional fault (*faute dolosive*), or injury to
life or person.

## 8. Content on the service

Comments are written by users, not by us. We do not endorse them and we do not check them before
they appear.

**This server never stores, hosts, proxies or links to manga content.** It holds discussion about
works and nothing else. If you believe a *comment* infringes your rights or is unlawful, tell us —
contact is below — and we will act on it. If your complaint concerns the manga itself, it is with a
source we have no relationship with, and we cannot help; the app is a reader, not a host.

## 9. Your data, and ending it

Two buttons in the app, both authenticated by your key because that is the only proof of identity
this service has:

- **Settings → Community → Export my data** — a JSON copy of everything the server holds about your
  account.
- **Settings → Community → Delete my data** — immediate, no confirmation loop.

[The privacy notice](PRIVACY.md) describes exactly what each covers, including what a deletion leaves
behind and why.

We can suspend or end your access if you break these terms or the content policy.

We can also shut the instance down. If that happens we will say so on the Discord with as much notice
as we can manage.

## 10. Law, contact, and who runs this

These terms are governed by French law. If you are a consumer, this does not deprive you of the
protections of the law of your own country of residence.

### Publisher

This instance is published on a **non-professional** basis by the Kotatsu-Redo project. It is free to
use, carries no advertising, and sells nothing.

Under the LCEN (loi n° 2004-575, art. 6 III 2), a non-professional publisher may keep their identity
from being published, provided the hosting provider holds it. That is the case here: our identity is
held by the host named below and will be disclosed to the judicial authority on request.

### Hébergeur

**IONOS SARL**
7, place de la Gare, BP 70109
57200 Sarreguemines Cedex, France
RCS Sarreguemines B 431 303 775 · SARL au capital de 100 000 EUR
Téléphone : 0970 808 911

The servers themselves are in IONOS's **Paris** datacentre.

### Contact

For anything — a legal notice, a ban appeal, a complaint, a question:

- **[Discord](https://discord.gg/eu3gnd89gP)** — the fastest route
- **[Issue tracker](https://github.com/Kotatsu-Redo/Kotatsu-Redo/issues)** — durable, and public

For formal notices, the issue tracker is the record.

## 11. Changes

These terms live in the repository, so every change is a public commit with a date against it.
Material changes will be announced on the Discord before they take effect.

---

## Self-hosters

If you run your own instance you are its operator, and these are not your terms. Change before
publishing:

- **§10** — you are the publisher, with your contact, your host and your governing law. If you are
  outside France, the LCEN provision that lets a non-professional publisher stay unnamed does not
  apply to you; Germany, for instance, requires an Impressum with a real name and postal address.
- **§2** — the age requirement, if you choose differently.
- **§7** — your own liability position.

Sections 1, 3, 4, 5, 6, 8 and 9 describe how the software behaves and are true of your instance too.
