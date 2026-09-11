# Content policy

**Kotatsu-Redo community server** · last updated 11 September 2026

What gets removed, and how. This is part of [the terms](TERMS.md).

[`/rules`](../src/main/resources/rules.html) is the short version shown to users in the app. This is
the long version, written for moderators and for anyone who wants to know exactly where the line is.

Comments are published the moment you press send. Nobody reads them first. That only works if the
rules are clear and applied consistently, which is what this document is for.

---

## Removed, always

**Slurs and attacks on people for who they are** — race, ethnicity, religion, nationality, gender,
sexuality, disability. Not negotiable and not context-dependent.

**Sexual content involving minors**, described, requested or sought, in any form. Removed and banned
without discussion, and this is the one category where we act first and consider appeals afterwards.

Note the distinction the filter is built around: *discussing* a work that contains such themes is
allowed and necessary — "I dropped this because of the lolicon subplot" is a review, and a useful
one. Writing the content is not.

**Threats and targeted harassment.** Going after a person rather than an argument. Following someone
between threads. Organising others to pile on.

**Personal information** about anybody, including yourself. Real names, addresses, workplaces,
accounts on other services, photographs.

**Spam and advertising.** Anything for sale, referral links, repeated posting.

**Content that is unlawful** under French law, where the server is hosted.

---

## Removed, in ordinary moderation

**Swearing.** The bar is deliberately higher here than on most of the internet: you can say a chapter
was terrible without swearing at it, and threads read better when everyone does. This is enforced by
a word filter before the comment is ever posted.

**Insulting other users.** Disagreeing with someone is fine and expected. Calling them an idiot is
not.

**Untagged spoilers** for chapters beyond the one being discussed. Chapter threads exist so that
people who have not caught up can stay out of them; posting the ending in a chapter-3 thread defeats
that.

**Off-topic derailing** — politics, other users' reading habits, arguments imported from elsewhere.

**Low-effort noise.** The twenty-character minimum removes most of it automatically.

---

## Not removed

Stated explicitly, because over-removal is the more likely failure here and a moderator should be
able to point at this list.

**Negative opinions, however strong.** "This is the worst arc in the series and the author has lost
it" is a review. Saying a work is bad, an author is declining, a translation is poor, or a beloved
character is badly written is all ordinary discussion.

**Plot language.** Manga is full of killing, demons, war, slavery and revenge, and a serious review
says so. The word filter deliberately does not block `kill`, `demon`, `assassin`, `slave` or
`torture`.

**Content warnings.** "Content warning for the rape scene in chapter 40" is one of the most useful
things a reader can tell another reader, and it must stay sayable. A filter that eats content
warnings makes the community less safe, not more.

**Genre vocabulary.** `hentai`, `ecchi`, `yaoi`, `harem`, `doujinshi` and the rest are the working
words of the medium. They are explicitly exempted from the filter.

**Discussing works with explicit content**, including works this app can show. Describing what
happens in a work is not the same as posting it.

---

## How it is enforced

### The filter, before publication

Every comment and nickname passes a word filter as it is submitted. If it matches, the comment is
rejected with **the specific word named**, so you can rewrite that part rather than guessing. Nothing
is held for review; it either posts or it does not.

The filter matches words, not meaning, so it is occasionally wrong about a perfectly ordinary
sentence. **The rejection dialog has a "this was wrong" button.** Pressing it sends the case straight
to the moderators, and a rule that is reported wrong often enough switches itself off automatically.
It is the fastest way a bad rule gets fixed for everyone, and we would rather you pressed it than
worked around it.

### Dislikes, instead of reports

**There is no report button.** Dislikes are the signal: comments that collect them sink out of view
on their own and rise to the top of the moderators' queue.

This is a deliberate trade with a known cost. Dislikes measure *disagreement*, not harm — an
unpopular opinion collects them, and a comment that doxxes someone in a thread six people read
collects none. The thing that covers that gap is volume: every comment posted goes into a queue a
moderator reads. At this size that is a few minutes a day, and it is the actual safety net.

If something needs attention urgently, the [Discord](https://discord.gg/eu3gnd89gP) is the route.

### Structure, instead of rules

Several things that look like features are really moderation:

- **Two people get three replies each in one thread.** After that the exchange has to stop or
  somebody else has to join. A long two-person argument is unreadable for everyone else.
- **Threads are scoped to a chapter**, so people who have not caught up simply do not open them.
- **Comments sort by top, not newest**, so a hot take earns less visibility.
- **There are no @mentions and no direct messages**, so there is no mechanism for building a pile-on.

### Moderators

Moderators remove comments, ban accounts and devices, shadowban, and reset nicknames. They cannot see
your IP address or your key, because the server does not have them.

**Every action is logged** with the moderator's name and a written reason, in a log the database
itself will not let anyone edit or delete. That is what makes a decision reviewable a year later, and
a disagreement between moderators resolvable.

Consequences escalate: a removed comment, then a shadowban, then a ban, then a device ban. Severe
categories skip straight to the end.

---

## If you think we got it wrong

Say so on the [Discord](https://discord.gg/eu3gnd89gP) or the
[issue tracker](https://github.com/Kotatsu-Redo/Kotatsu-Redo/issues). Include the wording if you have
it — the moderation log makes it straightforward to find what happened and why.

For a filter rejection specifically, the **"this was wrong"** button in the app is better than either:
it is already attached to the exact rule that fired.

---

## For rights-holders and legal notices

This server holds **discussion about works and nothing else**. It stores no manga content, proxies
none, hosts none, and keeps no links to any. The app is a reader that talks to third-party sources we
have no relationship with and no control over.

If a **comment** infringes your rights or is unlawful, tell us through the contacts above with enough
detail to find it. We act on notice promptly and log having done so.

If your complaint is about the **manga itself**, it is with a source we are not connected to, and we
have nothing to remove.

---

## Self-hosters

The "removed, always" section is the part we would encourage you to keep intact. Everything else is
this instance's judgement, and the filter lists, thresholds and tiers are all configurable from the
moderation panel without touching the code.
