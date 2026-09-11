# Kotatsu-Redo: Community Server + Source Scoring — Implementation Plan

Two repos, one system:

| Repo | Role |
|---|---|
| `Kotatsu-Redo` (Android, Kotlin) | client: rating/comment UI, portable pseudonymous identity, telemetry probes, score-aware ranking |
| `kotatsuredo-server` (this repo, Kotlin/Ktor/Postgres, Docker) | canonical work identity, comments, ratings, telemetry aggregation, source scores, moderation panel |

## Decisions

- **Kotlin + Ktor + Postgres**, Docker Compose, public instance, fully open source
- **Pseudonymous portable identity** — device-generated secret, no email, survives a phone change; user-chosen nickname
- **Hybrid scoring** — server-global telemetry blended with each device's own history, **with an exploration budget so new sources get seen**
- **Work catalogue resolved on demand** — Kitsu primary, MangaUpdates fallback, cached permanently; no bulk seed
- **Merge editions, split stories** — colored/official variants collapse; sequels and parts stay apart
- **Chapters matched by number + learned per-source offset**; chapter slots are language-agnostic
- **One comment thread per work/chapter**, filtered by language rather than split by it
- **Text-only comments.** No avatars, no image embeds, no file uploads — ever
- **5-star ratings** (stored at half-star granularity so the scale isn't locked in)
- **Community features are all-or-nothing** — no anonymous read-only mode; a user who doesn't want an identity turns the whole feature off and no identity is ever created
- **Strict filtering at post time** — slurs *and* ordinary swearing blocked, rejection names the term; publish is instant, moderators are the backstop
- **On-topic by construction** — chapter-scoped threads, **20-character minimum**, **reply chains capped at 3 between the same two people**, no @mentions, no DMs
- **Likes and dislikes on comments**, both counts shown; ranking by Wilson lower bound
- **No report button** — dislikes are the moderation signal, and the panel surfaces the rest
- **Device-level bans** — a banned user cannot rejoin from the same phone; reversible from the panel only
- **5-minute edit window**; nicknames changeable at will
- **Telemetry on by default**, with a follow-up prompt after onboarding to turn it off
- **No Google dependencies** — reply notifications poll on open, never FCM
- Comments and ratings **work in incognito mode**

---

## A. Deliberate simplifications

Written down because the first draft of this plan was bigger than it needed to be, and every item here is a subsystem that no longer has to be built, deployed, or debugged.

| Dropped | Replaced by | Why it's safe |
|---|---|---|
| Ed25519 request signing | **Bearer secret over TLS** (§1) | Both spikes failed: no stock Android provider registers an Ed25519 `KeyPairGenerator`, and `androidx.security:security-crypto` was deprecated in April 2025. Signing would have cost a crypto dependency on a minSdk-23 app to defend against a threat (a hostile server) that doesn't apply when you run the only instance and publish its source |
| Nonce store, replay window, clock-skew handling | nothing — TLS covers it | All three existed only to make signatures safe |
| Redis container | Postgres + in-process counters | Nothing left that needed it |
| Proof-of-work on registration | rate limits + moderation panel | Premature. Add it if abuse actually appears; the panel is the real backstop |
| pHash index (BK-tree / LSH) | **candidate-set comparison** (§2.4) | The resolver already requires title agreement, so pHash is only ever compared against 10–50 title candidates. Global hash search was never needed. This was the "is it feasible" question — it dissolves |
| MangaUpdates + MangaDex seed importers | **AniList only** for v1 | One GraphQL endpoint gives romaji/english/native titles, synonyms, relations, `idMal` and start date. The others are additive later |
| `EncryptedSharedPreferences` for the seed | plain app-private file | The seed is *deliberately* exportable — there's a recovery phrase and it rides in the backup. Encrypting at rest what you also print on screen is theatre |
| Separate trust-tier subsystem | one SQL view, three tiers | |
| Bespoke comment ranking | the Wilson function already written for §4 | Same code, two uses |
| Bespoke profanity tokenizer | the `norm()` pipeline already written for §2.2 | Same code, two uses |
| The moderation hold queue and the tier gate | **synchronous filter + instant publish** (§6) | Content is filtered on POST and published immediately. No human in the publish path, so moderator capacity never becomes the bottleneck on posting — moderators handle what the filter misses instead of everything it doesn't |
| The report flow and auto-hide-on-N-reports | **dislikes as the moderation signal** (§6) | One fewer table, endpoint, queue and abuse vector. Report buttons are themselves brigading tools, and at ~500 comments/day the recent-comments firehose is directly reviewable. See §6 for the honest limitation this accepts |
| A community-free build flavour | the runtime toggle (§1) | Toggling off means the identity is never created and no request is ever made, which is what the flavour existed to guarantee |
| Quote-downgrade for filtered terms | nothing — the over-block is accepted | Trivially gamed, and a rephrase is cheap |
| Offline comment queueing | fail with a message | A comment replayed minutes later has lost its context. Ratings still queue — they're idempotent and context-free |

Result: **two containers** (`api`, `postgres`) behind Caddy, one Kotlin service, one small extra Room database on the client, **zero new crypto dependencies**.

---

## 0. The hard problems

1. **Work identity.** `Manga.id` is a per-source hash. A comment on *Chainsaw Man* from MangaDex and one from Comick must land on the same thread, or the feature is worthless. There is no global manga ID in Kotatsu. → §2
2. **Chapter identity.** Sources disagree on numbering by an offset, a season reset, or entirely. → §2B
3. **Honest telemetry.** A source down *for this user* (geo-block, CF challenge, ISP DNS) must not poison the global score; a source globally dead must be demoted fast; and a *new* source must still get a chance to be tried. → §4

---

## 1. Identity: pseudonymous, portable, cheap

### Model

The app generates a **32-byte random secret** — that's the whole identity.

**It is created lazily**, the first time the user actually uses a community feature, never on first launch. There is no anonymous read-only mode: reads and writes both require an identity, and a user who doesn't want one turns **Community features** off in settings, at which point no secret is generated, no request is ever made, and the comment and rating UI is absent rather than empty. That's a stricter privacy posture than the usual "public reads" design and it also removes an entire unauthenticated abuse surface — every endpoint requires a bearer token, so there is no public path to rate-limit separately or to scrape.

Telemetry stays a **separate** toggle, because it carries no identity at all (§6) — turning off community features shouldn't silently also stop source scoring from working, and vice versa.

```
secret   = SecureRandom(32)                     // never leaves the device except via backup/phrase
user_id  = base64url(sha256(secret))[0:22]      // what the server stores and shows
nickname = user-chosen, + "#" + user_id[0:4]    // e.g. "raph#4f2a"
```

The server stores `sha256(secret)` and never the secret itself, exactly like a hashed API key. A database leak exposes no credentials. No email, no password, no IP retention.

**Authentication is `Authorization: Bearer <secret>` over TLS.** The server hashes it and looks up the row; an unknown hash creates one. No login endpoint, no sessions, no refresh, no expiry.

> The one operational rule that makes this safe: **never log the `Authorization` header, and never log request bodies.** Put it in the Caddy and Ktor config, and in the README.
>
> What this gives up versus request signing: an attacker with live access to the running server could harvest secrets and post as those users. The blast radius is "someone posts comments as you" — no money, no personal data, no reading history. If that ever feels too loose, signing is a drop-in upgrade, but it needs BouncyCastle or Tink to exist on minSdk 23.

### Nicknames

User-chosen, **not globally unique** — they carry a 4-character discriminator derived from `user_id`. That kills impersonation without creating a name land-grab, and it means no "that name is taken" flow. Nicknames pass the §6 word filter at set-time and are matched against a reserved list (`admin`, `mod`, `kotatsu`, `system`, …).

**Changeable at will.** Comments render the *current* nickname rather than the one in force when they were written, so there's no history to rewrite and no confusion about who said what — the discriminator is the stable identity either way.

### Portability — three paths to the same 32 bytes

1. **App backup.** Add `IDENTITY("identity")` to `BackupSection` (`backups/domain/BackupSection.kt`) plus an `IdentityBackup` model alongside `SourceBackup` / `StatisticBackup`. Default path; covers the ordinary phone swap.
2. **Recovery phrase.** Render the secret as a 24-word BIP-39 phrase under Settings → Community. Covers "I lost the backup file".
3. **Android auto-backup — included.** Decided the other way from the original recommendation. It turned out the key was *already* travelling: `backup_content.xml` and `backup_rules.xml` both include every SharedPreferences file, so excluding it would have meant adding a rule, not omitting one. Rather than leave that incidental, `AppBackupAgent` now carries the identity in its own archive too, so both restore paths agree. The trade is named rather than hidden: the key leaves the device through Google's transport, which is the price of comments surviving a phone that was lost rather than replaced on purpose.

The same secret on two phones is fine — they are the same user. Ratings are last-write-wins; comments are independent rows.

### Abuse resistance

- **Rate limits** per `user_id` and per `/24` IP bucket. The IP bucket is an **in-memory counter keyed by a hash, never persisted and never logged** — so "we don't store IPs" stays literally true.
- **Trust tiers** (one SQL view): tier 0 = under 24h old or under 3 active days; tier 1 = normal; tier 2 = long-lived and never actioned. Tiers affect **rate limits and telemetry weight only** — they never gate publication. Comments post instantly for everyone (§6).
- **Bans** by `user_id`, plus a device ban (below). **A ban removes read access too** — identity is all-or-nothing (§1), a banned device cannot create one, so the community features simply cease to exist for it. That is a harder sanction than most platforms apply, and it is deliberate.
- **A ban deletes the user's comments.** Their rows survive as tombstones with the body cleared and `state = removed`, rather than being dropped outright — hard-deleting would orphan every reply underneath and tear holes in threads other people were part of. The audit log records the sweep as one `mod_action`.
- **Shadowban** — `state = shadowed`: the comment renders normally for its author and is invisible to everyone else, and their votes stop counting. Settable per user (`is_shadowbanned`), so everything they post afterwards is shadowed on arrival. This is the right tool against someone who would otherwise just generate a new identity, because nothing signals that it happened.
- **Telemetry weighting** by tier, so fresh identities can't move a source's score.

### Device bans

A ban must survive the user simply generating a new secret, so `identity/hello` carries device values, the server stores peppered hashes, and a match in `device_ban` refuses identity creation outright — no account is made. Both values are recomputable on-device at any time, so a returning user is recognised without anything being stored on the phone. **Reversible from the panel only**; there is no in-app appeal surface.

Having looked at what's actually available in 2026, **two signals, used differently** — because the strong one is unreliable in a way that matters enormously for banning.

**Signal 1 — `ANDROID_ID` (SSAID). The hard ban.**
Scoped to app signing key + device + Android user profile. **Survives uninstall and reinstall**, which is the evasion that matters in practice. **Reset by factory reset**, differs in a work profile or second user, and differs between the F-Droid and GitHub builds because the signing keys differ. Crucially: **no collisions**. Ban on this and you never hit an innocent device.

**Signal 2 — MediaDrm / Widevine `PROPERTY_DEVICE_UNIQUE_ID`. A flag, never a ban.**
Hardware-backed, and it **often survives a factory reset** — which is exactly the gap Signal 1 leaves. But it must not be banned on directly, for two reasons found in the research:

- **It collides.** A measurable share of MediaDrm IDs are shared across different devices, especially within the same manufacturer and model. Auto-banning on a collision would ban a stranger who did nothing, with no way for them to find out why.
- **It's absent on plenty of devices.** It needs the Widevine plugin, which degoogled ROMs and some devices lack — and a meaningful slice of this userbase runs custom ROMs.

So: when a new identity presents a MediaDrm hash matching a banned device but a *different* `ANDROID_ID` — the factory-reset case — **flag it in the panel rather than blocking it**. A moderator confirms from behaviour. Collisions cost a stranger nothing, and a real evader gets caught by a human instead of by a heuristic.

**What this does not stop:** a second phone, or a determined user. That's fine. The goal is friction, not impossibility, and anything stronger means Play Integrity — a Google dependency that would break the F-Droid build and is itself bypassed by root modules within days of each Google update. Not worth it.

Also unavailable, for the record: IMEI and serial (blocked for normal apps since Android 10), the advertising ID (needs Play Services, user-resettable), and hardware key attestation (heavy, and bypassable via keybox spoofing).

**The privacy cost is real and belongs in the privacy notice.** Storing device-derived hashes makes two identities created on the same phone linkable server-side, and the MediaDrm value in particular is a near-permanent identifier that outlives a factory reset — a heavier tracking vector than anything else in this design. The mitigations: both are peppered hashes and never raw values, the pepper never leaves the server, they are sent **only at identity creation** and never on ordinary requests, and they are used for nothing but ban enforcement. This is the one place the plan trades privacy for moderation, and it should be named as such rather than buried.

---

## 2. Identity: works and titles

### 2.1 Server-side model

```
work(id, canonical_title, year, content_type, nsfw, created_at)
work_title(work_id, title_raw, title_norm, lang, kind, weight)   -- many titles per work
work_alias(source, source_key, work_id, confidence, created_at)  -- the lookup table
work_cover_hash(work_id, phash BIGINT, source)
work_external_id(work_id, provider, external_id)                 -- anilist / mal / mangaupdates / kitsu
work_relation(from_work, to_work, type, chapter_offset REAL NULL)
```

`kind` ∈ `canonical | romaji | native | english | synonym | source_observed`.
`type` ∈ `sequel | prequel | side_story | alternative_version | continuation`.

`source_key` is the parser's `url`/slug, **not** `Manga.id` — the latter changes if parser hashing ever changes.

Index `work_title.title_norm` with a **pg_trgm GIN index**. That one index is the entire search infrastructure.

### 2.2 Title normalization — the mechanism that makes "same work" work

`norm(title)` is the most load-bearing function in the system.

1. **Unicode NFKC**, then Unicode-aware casefold. Folds full-width CJK to half-width and most compat variants for free.
2. **Strip edition noise** — bracketed or trailing tokens from a fixed list: `official`, `colored`, `full color`, `fan colored`, `digital`, `raw`, `remastered`, `webtoon`, `novel`, `doujinshi`, a bare language name, a bare 4-digit year.
3. **Preserve and canonicalize sequence markers** — `part`, `season`, `arc`, `book` + numeral, and trailing numerals. Roman → arabic (`II` → `2`), spelled-out → arabic (`Second` → `2`). **Never stripped.** See §2.5.
4. **Punctuation → space**, then collapse whitespace.
5. **Transliteration variants** — index *two* keys per latin title: marks stripped (`Ōsama` → `osama`) and macron-expanded (`ousama`). Hepburn disagreement is a top cause of missed matches, and generating both is cheaper than guessing right.
6. **Native titles pass through** after step 1. An exact CJK match outweighs any latin fuzzy match.

Matching order: exact `title_norm` → `similarity() > 0.85` via pg_trgm → fail. Mirror the thresholds the app already uses in `SearchV2Helper` (`levenshteinDistance`, `almostEquals`, `MATCH_THRESHOLD_DEFAULT = 0.2f`) so both sides agree on "close enough".

**Titles accumulate.** Every alias resolution inserts any title the source used that isn't known yet, as `kind = source_observed, weight = 0.3`. The first user to hit an obscure source's odd title pays the fuzzy cost; everyone after gets an O(1) exact hit. The cross-source behaviour improves with use instead of staying as good as the seed. Low-weight observed titles never *drive* a merge — they only speed up lookups for an established work.

This same normalizer is reused by the word filter in §6.

### 2.3 Catalogue: resolved on demand, never bulk-seeded

**Changed after building it.** The original plan bulk-imported AniList up front. Two findings killed that:

- **AniList went dark mid-build**, returning `"The AniList API has been temporarily disabled due to
  severe stability issues"`. Jikan/MyAnimeList was failing too (`504`, "Jikan failed to connect to
  MyAnimeList"). A seed must not depend on a third party staying up for a long import.
- **A bulk import fetches mostly waste.** Most of any catalogue is works this userbase never opens.

So the local catalogue answers first, an external lookup happens **only for a work genuinely never
seen before**, and the result is cached permanently. External traffic is bounded by *distinct works
ever opened* — which converges fast — rather than by catalogue size or user count. A title no
catalogue knows is negative-cached, so it costs one round trip ever, not one per user who opens it.

Providers, in order:

| | why |
|---|---|
| **Kitsu** (primary) | One unauthenticated request returns the title renderings **and** the MyAnimeList, AniList and MangaUpdates ids together. For Chainsaw Man that is 15 renderings including `Chainsawman`, `CSM`, and the Cyrillic, Arabic, Korean and Chinese spellings — each of which is some source's title. |
| **MangaUpdates** (fallback) | Two round trips (search, then series detail for the `associated` names), so it goes second — but its long-tail coverage of manhwa, manhua and scanlation-only series is far better, and that is what a reader with 1200 sources is actually looking at. |

**AniList is not a dependency.** Its ids are still stored as anchors, because Kitsu hands them over
without anyone having to call it.

Anything no catalogue knows still resolves organically from the client's own fingerprint and creates
a work — the catalogue is an enrichment, never a gate. The system must work against an empty
database, because self-hosters start there.

> Check the Kitsu and MangaUpdates terms before the public launch and record the result in the README.


### 2.4 Resolution pipeline

Client fingerprint, sent the first time it needs a thread for an unresolved manga:

```json
{ "source": "MANGADEX", "key": "/title/a1b2...", "title": "Chainsaw Man",
  "alt_titles": ["チェンソーマン", "Man of Chainsaw"], "authors": ["Fujimoto Tatsuki"],
  "year": 2018, "cover_phash": "0x8f3a...", "tags": ["action"], "content_type": "MANGA",
  "external_ids": { "mal": 116778 } }
```

First hit wins:

1. **Alias hit** — `work_alias(source, source_key)`. O(1), ~99% of traffic once warm.
2. **External ID hit** — a scrobbling link is a gold-standard anchor. Kotatsu already stores MAL / AniList / Shikimori / Kitsu ids in `scrobbling/common/data/ScrobblingEntity.kt`.
3. **Exact `title_norm` hit** on any fingerprint title against any work title, with compatible year and content type.
4. **Fuzzy title, verified by cover pHash.** `alternatives/ui/covers/CoverHash.kt` already exists: DCT-based, 63-bit, `MAX_DISTANCE = 12`, edge-trimmed so padding and re-encoding score 0 while unrelated art never scored below 22.
5. **Fuzzy title alone** → `confidence = 0.7`, flagged for review. Never applied if it would merge two works that both already have comments (§2.7).
6. **No match** → create a work seeded with every title in the fingerprint.

**pHash is never searched globally.** Step 4 runs only against the 10–50 candidates step 3/5 already produced by title, so comparison is an in-memory loop over a handful of longs. No BK-tree, no LSH, no hash index — and no scaling cliff. If a future feature ever needs "find me anything that looks like this cover" with no title, *that* would need an index; nothing here does.

On success, write back observed titles and this source's cover pHash.

### 2.5 What counts as the same work

**Merge editions, split stories** — which falls almost entirely out of normalization steps 2 and 3:

| Case | Outcome | Why |
|---|---|---|
| `Chainsaw Man` / `Chainsaw Man (Colored)` / `[Official]` | **merge** | edition tokens stripped → identical `title_norm` |
| `Solo Leveling` / `나 혼자만 레벨업` | **merge** | native title is an alt title of the same work |
| `Tower of God` / `Tower of God Part 2` | **split** + `continuation` | sequence marker preserved → different `title_norm` |
| `Berserk` / `Berserk: The Prototype` | **split** + `side_story` | a subtitle is not an edition token |
| `One Punch Man` (ONE) / (Murata) | **split** + `alternative_version` | different authors; comes from AniList relations |

The hard guard: **if two candidates have the same `title_norm` after edition-stripping but differed in a sequence marker before it, never merge.** Spoilers leaking from Season 3 into the Season 1 thread is the worst thing this system can do, and it deserves the most conservative rule in the document.

`work_relation` then lets the UI offer *"Part 2 discussion →"*, recovering the discoverability that splitting costs.

### 2.6 The free signal you already have

`alternatives/domain/MigrateUseCase.kt` — when a user migrates manga A on source X to manga B on source Y, **a human just confirmed they're the same work**. Post it as `POST /v1/works/link` with `confidence = 1.0, evidence = "user_migration"`; `AutoFixUseCase` results at lower confidence. Free training data covering exactly the long-tail sources a catalogue never will.

Weight these by trust tier like everything else — a link is a write to shared state, and a malicious client posting fabricated links could merge two popular works. Tier 0 links go to the review queue rather than applying directly.

### 2.7 Merge and split

- `work_merge_log(from_work, into_work, at, reason)` — merges move aliases and re-point comments/ratings, never destroy rows.
- `POST /v1/works/{id}/dispute` feeds the moderation queue.
- **Never auto-merge two works that both already have comments.** An unmerged pair is an annoyance; a wrongly merged pair silently mixes two communities and two spoiler horizons.

---

## 2B. Chapter identity: number + learned offset

### Chapter slots are language-agnostic

The thing that needed clarifying: **MangaDex hosts one manga with a separate chapter list per language** — English might have 150 chapters, Spanish 80, French 40. Kotatsu calls these `branch`. So "align source A's chapter 45 to source B's chapter 45" is ambiguous: *which* of MangaDex's twenty lists?

The answer is that a canonical slot is simply **"chapter 45 of this work"**, with no language attached. Every branch's chapter 45 is an *alias* pointing at that one slot. Concretely:

- Alignment uses each source's **most complete branch** as its representative sequence (most chapters with non-zero `number`).
- Other branches of the same source attach by their own numbers, which in practice are identical — MangaDex's ES chapter 45 is the same chapter 45.
- A comment lives on the **slot**, so a Spanish reader on branch `es` and an English reader on branch `en` are in the same conversation, filtered by the language they *wrote in* rather than the branch they *read*.

This also removes a whole failure mode: twenty branches can no longer create twenty duplicate chapter 45s.

### Model

```
work_chapter(id, work_id, volume SMALLINT, number REAL, ordinal INT, label TEXT)
chapter_alias(source, branch, source_chapter_key, work_chapter_id, confidence, basis)
source_alignment(work_id, source, branch, offset REAL, basis, confidence, sample INT, updated_at)
```

`basis` ∈ `number | index | upload_date | manual`.

`chapter_alias` deliberately has **no unique constraint on `source_chapter_key` alone** — one source chapter can map to several slots (merged `Ch. 10-11`) and several to one (split releases).

### Alignment algorithm

Per `(work, source, branch)`, when a client uploads a chapter list or the count changes.

1. **Pick a reference** — the source/branch with the most non-zero `number` values and highest trust-weighted sample, or the seeded catalogue count.
2. **Anchor set** = chapters with `number != 0`. (Recall `0` means *unknown* in `ChapterEntity`, for both `number` and `volume`.)
3. **Search offset δ** maximising pairs where `|numA − (numB + δ)| < 0.001`. Candidates:
   - sweep −5 … +5 in 0.5 steps → the prologue/chapter-0 off-by-one, overwhelmingly the common case;
   - `firstA − firstB` and `lastA − lastB` → **season restarts and continuous-vs-split numbering**, where δ is large (e.g. +122). Without these the sweep silently finds nothing, and this is exactly the case §2.5's split policy creates.
4. **Accept** only with ≥ 5 aligned anchors and ≥ 60% of the shorter list matching.
5. **Volume as tiebreak** when both sides are non-zero; ignore entirely when either is `0`.
6. **Decimals are free** — float comparison aligns `10.5` with `10.5` and never with `10` or `11`.
7. **Graceful degradation.** Below threshold, that source's chapters get private slots and its comments stay **work-scoped with a text label**. A source you can't align must never damage a work whose other sources aligned fine.

### What the evaluation actually showed

**Everything above this line was written before any of it was measured. Most of it is wrong.**

`tools/` in the parsers repo harvested real chapter lists through the real parsers — 23 (source,
work) pairs, 15,434 chapters, 10 works present on two sources. `src/test/resources/eval/chapter-alignment.json`
is that data. Five findings, in the order they broke the design:

**1. The offset score is almost flat, so the search is meaningless on dense lists.**
Chainsaw Man, MangaDex versus WeebCentral: δ=0 matches 232 chapters, δ=±1 matches 231, δ=2 matches
230. The maximum is unique by *one chapter out of 232*. One missing chapter in either list flips the
winner. Half-step offsets score 1, because both lists are contiguous integers.

**2. When one list is a subset of the other, many offsets tie exactly.**
Five of the ten real pairs — the partially-translated ones. MangaDex's Jujutsu Kaisen has 15 chapters
(a Kazakh branch; the English was delisted) against WeebCentral's 272. *Twelve* offsets score 15/15.
The acceptance rule above — ≥5 anchors and ≥60% of the shorter list — accepts every one of them, and
the implementation picks by iteration order. **It would have shipped δ=−5 for Jujutsu Kaisen, δ=−38
for Kaguya-sama and δ=−57 for Sakamoto Days, each at "100% confidence".**

**3. No non-zero offset ever beat δ=0 on real data.** Ten pairs out of ten, ratio ≤ 1.00. Sources
copy each other's numbering, so δ=0 is nearly always the answer — and the search exists to find an
exception that this sample never contained.

**4. `uploadDate` is not a publication date, so the secondary signal below is not there.**
WeebCentral returns 75 distinct dates across One Piece's 1,193 chapters, and dates chapter 1 — serialised
in 1997 — to 2024-09-07. That is the import date. MangaDex's back catalogue is the same: chapter 1 of
One Piece "uploaded" 2026-03-19. Cross-source lag on the back catalogue has a median of 300–2,300 days
and an IQR up to 1,600, which discriminates nothing.

**5. But for a currently-serialising work the dates are real, and they agree exactly.**
Kagurabachi, the most recent twenty chapters: MangaDex and WeebCentral both date chapter 131 to
2026-09-06, 130 to 2026-08-30, 129 to 2026-08-23. Not close — identical. That is the signal, and it
lives in the recent tail rather than the archive.

Chapter *titles* would also discriminate, and cannot be relied on: WeebCentral publishes none (every
title is literally "Chapter 232"), and MangaDex's are per-language, so they only match a source in the
same language.

### The algorithm that follows from that

Inverted from the one above. Do not search for the best offset; **assume there is none, and require
evidence to believe otherwise.**

1. **δ = 0 by default.** It is right in ten of ten measured cases, and every wrong answer in the old
   design came from preferring a search result over the obvious.
2. **Take δ from date agreement on the recent tail** — the chapters whose upload dates are distinct
   and recent enough to be publication dates rather than an import stamp. Identical dates across two
   sources are near-conclusive. This is also what catches the season restart that motivated the
   `firstA − firstB` candidate: season 2 chapter 1 carries the date of the full run's chapter 596.
3. **Accept a non-zero δ from number overlap only on a landslide** — δ=0 matching almost nothing
   while δ=k matches almost everything. A one-chapter margin is not evidence.
4. **Otherwise refuse**, and fall back to work-scoped comments with a text label. Refusing is cheap;
   a wrong alignment shows a reader comments about a chapter they have not read.

The thresholds in the old design (≥5 anchors, ≥60% of the shorter list) are deleted rather than
tuned. They measure the wrong thing.

### Two more things the data changed

- **"Most complete branch" has to mean most *distinct numbers*, not most rows.** Berserk's Russian
  branch has 666 entries for 406 distinct chapters — four scanlation groups uploading the same
  chapters. Counting rows picks a branch that is half duplicates.
- **Branch counts are far higher than assumed.** Chainsaw Man has 54 branches on MangaDex, Berserk
  37, and the largest is frequently not English — Italian for Chainsaw Man, Russian for Berserk. Any
  logic that reaches for the English branch will mostly not find one.

### The two known failure modes

- **Merged chapters** (`Ch. 10-11`). Detect via a count deficit plus number-sequence gaps; parse the range from `title` where exposed. Map the one alias to *both* slots.
- **Index-only sources** (`number == 0` throughout). Fall back to `basis = index` with the same sweep, capped at `confidence = 0.5`. **`uploadDate` correlation** is a good secondary signal — chapters released within days of each other across sources are very likely the same, and `ChapterEntity.uploadDate` is already populated.

### Client side

- The list is already in hand at `details/domain/DetailsLoadUseCase.kt` / `details/ui/ChaptersMapper.kt`.
- **Upload lazily, only when the user opens a comment thread.** Bandwidth, and the app shouldn't continuously report what its user reads. The payload describes the *work*, not the person.
- Server returns a `chapter_list_hash`; the client skips re-upload while it matches, so most opens cost nothing.

---

## 3. Server: structure, schema, endpoints

### Layout

```
kotatsuredo-server/
├── docker-compose.yml          # api + postgres, behind caddy
├── Dockerfile                  # multi-stage: gradle build -> jre-alpine
├── build.gradle.kts
├── src/main/kotlin/io/kotatsuredo/server/
│   ├── Application.kt          # Ktor/Netty, ContentNegotiation, StatusPages, CallLogging (no headers/bodies)
│   ├── auth/                   # bearer hash lookup, TrustTier view, RateLimiter
│   ├── works/                  # norm(), WorkResolver, phash verify, merge/split
│   ├── chapters/               # alignment
│   ├── ratings/
│   ├── comments/               # threads, votes, word filter and language detection seams
│   ├── telemetry/              # probe ingest
│   ├── scoring/                # aggregation jobs -> source_score
│   ├── moderation/             # queue + admin API
│   ├── seed/                   # AniList importer
│   └── db/                     # Exposed DAOs, Flyway migrations
├── src/main/resources/static/  # moderation panel (served by Ktor, same container)
└── src/test/kotlin/            # norm(), WorkResolver, alignment, scoring — the tests that matter
```

**Ktor 3 + Netty**, **Exposed**, **Flyway**, **HikariCP**, **kotlinx.serialization** so DTOs copy verbatim into the app.

### Schema (beyond §2.1 and §2B)

```sql
app_user(id PK, secret_sha256 BYTEA UNIQUE, created_at, last_seen_at,
         nickname TEXT NULL, active_days INT,
         is_banned BOOL, ban_reason TEXT, is_shadowbanned BOOL)
-- ssaid_hash is the hard ban (no collisions); drm_hash only raises a panel flag
app_device(user_id, ssaid_hash BYTEA, drm_hash BYTEA NULL, first_seen_at)
device_ban(ssaid_hash PK, drm_hash NULL, banned_at, by_moderator, reason)  -- panel-reversible
moderator(id PK, handle, role, totp_secret, created_at, disabled_at NULL)

-- 5 stars in the UI, stored in half-star steps (2 = 1 star … 10 = 5 stars).
-- Costs nothing now and means adding half-stars later is a UI change, not a migration.
rating(work_id, user_id, value SMALLINT CHECK (value BETWEEN 1 AND 10),
       created_at, updated_at, PRIMARY KEY (work_id, user_id))
work_rating_agg(work_id PK, count INT, mean REAL, bayesian REAL, histogram INT[5], updated_at)

comment(id PK, work_id, origin_work_id, work_chapter_id NULL, user_id, parent_id NULL,
        depth SMALLINT, body TEXT, is_spoiler BOOL, lang TEXT,
        created_at, edited_at NULL, deleted_at NULL,
        state SMALLINT,           -- visible | shadowed | removed
        score REAL, up INT, down INT)
-- edited_at is recorded for moderation and audit but NOT surfaced in the app:
-- with a 5-minute window almost nothing accrues, so an "edited" badge is noise.
-- origin_work_id is the work this was posted against and NEVER changes.
-- A merge moves work_id; an unmerge just restores from origin_work_id. That one
-- column turns "undoing a bad merge is genuinely hard" into a one-line UPDATE.

comment_vote(comment_id, user_id, value SMALLINT, PRIMARY KEY (comment_id, user_id))
mod_action(id, moderator_id, target_type, target_id, action, note, created_at)   -- audit log

-- the filter, as tunable data rather than hardcoded lists
filter_rule(id, term, tier, lang, enabled, source_list, hit_count, fp_count)
allowlist(token, lang, added_by, created_at)
filter_block(id, user_id, lang, rule_id, created_at,
             fp_reported BOOL, reviewed_at NULL, action NULL)   -- purge with the probe sweep

-- one row per (reporter-day, source, op): needed only until the nightly rollup,
-- then aggregated into source_score inputs and purged at 7 days
source_probe_raw(source, day DATE, region TEXT, op SMALLINT, reporter_day TEXT,
                 ok INT, fail INT, empty INT, cf_blocked INT,
                 latency_p50_ms INT, latency_p90_ms INT,
                 PRIMARY KEY (source, day, region, op, reporter_day))
source_score(source, region, stability REAL, popularity REAL,
             composite REAL, sample_size INT, updated_at, PRIMARY KEY (source, region))
```

`work_rating_agg.bayesian` — never rank on a raw mean. `(C·m + Σr) / (C + n)`, `m` = global mean, `C ≈ 20`, so a single 5-star doesn't outrank a 500-vote 4.4.

Five-star scales bunch hard at 4–5 (the J-curve), which makes the raw mean nearly useless for ranking and comparison. The Bayesian value plus the histogram are what carry real information, so show the distribution, not just the average — the histogram is cheap and it's the thing that distinguishes "everyone likes it" from "half love it, half hate it", which for manga is often the interesting part.

**Brigade detection.** The Bayesian prior blunts a review-bomb but doesn't reveal one, so an hourly job flags a work when its rating rate jumps well above its own trailing baseline **and** the incoming ratings skew to an extreme (nearly all 1s or all 5s) **and** a disproportionate share come from tier-0 identities. Any one of those alone is just a manga getting popular; all three together is coordination. It lands in the panel as a flag with the histogram before and after — a moderator decides, nothing is reverted automatically.

**Every endpoint requires a bearer token**, including reads. There is no anonymous access path (§1).

### Errors are codes, not prose

The server never returns a user-facing sentence — the userbase spans 15 languages and the server has no business holding translations. Every rejection is a machine-readable code plus data, and the app renders the localised string through Weblate like every other string it owns:

```json
{ "error": "filter_blocked", "term": "…", "tier": "profanity", "rules_url": "…" }
{ "error": "rate_limited", "retry_after_s": 900, "limit": "comments_per_hour" }
{ "error": "chain_depth_exceeded" }   { "error": "too_short", "min": 20 }
{ "error": "banned" }                 { "error": "work_moved", "moved_to": "…" }
```

The blocked *term* is passed through untranslated, which is correct — it's the user's own word. Rate limiting surfaces as a plain error message naming when they can try again, not a silent failure.

### Rate limits

Per identity, sliding window. Tier 0 is under 24h old or under 3 active days; tier 2 is long-lived and never actioned.

| | tier 0 | tier 1 | tier 2 |
|---|---|---|---|
| comments | 5/hour, 15/day | 20/hour, 60/day | 40/hour, 150/day |
| replies in one thread | 5/hour | 10/hour | 15/hour |
| votes | 60/hour | 200/hour | 400/hour |
| ratings | 30/hour | 100/hour | 200/hour |
| `works/resolve` | 60/min (batched, so this is generous) | | |
| `identity/hello` | 5/hour per device hash | | |
| everything else | 300/min | | |

Plus a per-`/24` in-memory bucket at roughly 10× the single-user limit, to bound one network doing something silly. These numbers matter more than usual now that nothing is held for review — they are the only volume control in the system.

### Endpoints

```
POST   /v1/identity/hello         create-or-touch; set/change nickname
DELETE /v1/identity/me            erase ratings, tombstone comments, drop the row

POST   /v1/works/resolve          batch fingerprints -> work_ids  (hot path; always batch)
POST   /v1/works/link             user-confirmed alias (from migration)
POST   /v1/works/{id}/dispute   {kind: same_work|different_works, other_work_id?, note?}
                                  duplicates collapse into one queue item
GET    /v1/works/{id}/related     sequels, side stories, alt versions

POST   /v1/works/{id}/chapters    upload a (source, branch) chapter list -> alignment + slot ids
                                  no-op while the client's chapter_list_hash matches
GET    /v1/works/{id}/chapters    canonical slots + this source's alias mapping

GET    /v1/works/{id}/rating      aggregate + this user's own value
PUT    /v1/works/{id}/rating      {value: 1..10}
DELETE /v1/works/{id}/rating

GET    /v1/works/{id}/comments    ?sort=top|new&limit=&offset=&lang=&chapter=<slot_id>
                                  pages over root comments, replies attached, so a thread never
                                  straddles a page boundary
                                  returns per-language counts for the "also 12 in ES" affordance
POST   /v1/works/{id}/comments    {body, parent_id?, is_spoiler, lang}  ?chapter=<slot_id>
                                  `lang` is the client's locale and only a fallback: the language is
                                  detected from the text server-side, since it picks the word list
PATCH  /v1/comments/{id}          5 min edit window
DELETE /v1/comments/{id}          soft delete: blanked in place, row kept so replies survive
PUT    /v1/comments/{id}/vote     {value: 1 | -1 | 0}; idempotent, so a retry cannot double-count

GET    /v1/notifications          replies to my comments since a cursor; polled on app open
                                  fire-and-forget: delivered once, no server-side read state

POST   /v1/telemetry/probes       batched, fire-and-forget, 202
GET    /v1/sources/scores         ETag + Cache-Control; app polls daily
GET    /v1/health   /v1/version

/admin/*                          moderation API + static panel
                                  per-moderator accounts with roles + TOTP, never a user identity
```

`GET /v1/sources/scores` must be CDN-cacheable and small — ~1200 sources × a few floats ≈ 40 KB gzipped. One blob, never per-source lookups.

`sort=top` uses the **same Wilson lower bound** as §4's stability score, over `(up, up+down)`. One function, two callers.

### Ops

- `docker-compose.yml`: `api`, `postgres:16`, `caddy` (auto-TLS). That's it.
- **Logging: no `Authorization` header, no request bodies, no client IPs.** Configure it in Caddy *and* Ktor `CallLogging`, and state it in the README — otherwise the privacy claim in §6 is false.
- Config by env var; `.env.example` committed, secrets never.
- Jobs: hourly probe rollup, daily score recompute, nightly `source_probe_raw` purge (7 days), weekly AniList refresh.
- Postgres volume + `pg_dump` cron sidecar. Structured JSON logs, `/metrics`.
- **Uptime alerting**, not just metrics. A `/metrics` endpoint nobody watches tells you nothing; the app degrades silently to "community features unavailable", so an outage is invisible from the user side and will not be reported. One external uptime check hitting `/v1/health` with an alert into the Discord is enough, and it's the difference between a two-hour outage and a two-day one.
- **Restore drills.** A `pg_dump` that has never been restored is not a backup. Restore into a scratch container once before launch and once a quarter after.
- **API versioning.** Users run months-old app builds, so the server must keep `/v1` working while the app moves on. Kotatsu already sends `X-App-Version` on sync requests — do the same here and use it to soften responses for old clients rather than breaking them. Never remove a field from a `/v1` response; add `/v2` if the shape must change.

### Capacity at 25k active users

Sized from the real number, since "is this tangible" deserves an actual answer rather than a shrug. Assuming ~30% daily actives (7.5k DAU) and ~15 details-screen opens per active day:

| Call | Trigger | Per day | Avg |
|---|---|---|---|
| `works/resolve` | **first sight of a manga per device only** — the client caches the mapping forever | ~30k | 0.4/s |
| `works/{id}/rating` | every details open | ~113k | 1.3/s |
| `works/{id}/comments` | only when the user taps Comments (~5% of opens) | ~6k | 0.1/s |
| `POST comments` | | ~500 | — |
| `telemetry/probes` | one batched job per device per day | ~7.5k | 0.1/s |
| `sources/scores` | daily poll, mostly `304` | ~7.5k | 0.1/s |

**≈ 165k requests/day, under 2/s average, maybe 25/s in the evening peak.** Every one is an indexed point lookup. This is not a hard workload — it's a workload a single process ignores.

Storage after year one: AniList seed ~200k works and ~1.2M titles (the pg_trgm GIN index is the largest single object, a few hundred MB and the one thing you want resident in RAM); `work_alias` in the low millions; comments well under 100 MB; `source_probe_raw` ~1.3M rows resident given the 7-day purge. **Under 5 GB total.** Egress is dominated by the scores blob at ~9 GB/month, and ETag makes most of those `304`s.

**A Hetzner CX32 — 4 vCPU, 8 GB RAM, 80 GB NVMe, ~€7/month — runs this with room to spare.** 4 GB would work; 8 GB is the sane choice purely so the trigram index stays cached. The design should absorb 10× growth on the same box; the first things to give would be `source_probe_raw` (partition by day) and adding a cache in front of the rating aggregate.

The number that matters: **the client-side permanent `work_id` cache is what makes this cheap.** Without it, resolve moves from 30k/day to 113k/day and keeps climbing with usage rather than flattening. See §5.

---

## 4. Scoring: how a source earns its place in the queue

### Stability (server-side, per source, per region)

Per operation (`search`, `details`, `pages`):

```
successRate  = wilsonLowerBound(ok, ok + fail, z = 1.96)
latencyScore = clamp01(1 - (p50 - 800ms) / 5000ms)
cfPenalty    = cf_blocked / total
emptyPenalty = empty / max(1, ok)          // 200 OK with zero results = broken parser

stability = (0.55·successRate + 0.25·latencyScore) · (1 - 0.7·cfPenalty) · (1 - 0.4·emptyPenalty)
```

Then **exponential decay, 7-day half-life, 30-day window**.

Two details decide whether this works at all:

- **Wilson lower bound, not raw ratio.** Otherwise a source with 1 success and 0 failures scores 1.0 and jumps the queue ahead of a proven one.
- **Region bucketing.** The client declares `X-Redo-Region` derived from locale/timezone — ~8 coarse buckets, **never geo-IP**. A source geo-blocked in the EU is demoted for EU users and untouched elsewhere. Without this, one region's blocks corrupt the global number.

### Popularity (server-side)

```
popularity = log1p( Σ_days decay(day) · distinct_reporters(day) ) / log1p(max_over_sources)
```

Weight by event type (favourite > read > open > search) and count **distinct reporters**, not events, weighted by trust tier.

### Exploration — so a new source can actually get seen

Without this, §4 has a starvation bug: a new source has no telemetry → composite ≈ 0 → never queried → never gets telemetry. Same trap for any source recovering from an outage. Three mechanisms, all cheap:

1. **Optimistic prior.** A source with `sample_size < N` scores at the **global median**, not zero. Unknown means unproven, not bad.
2. **Exploration slots.** Every multi-source sweep reserves a fixed share of its workers — 1 of 8 in `AlternativesUseCase`, 1 of 4 in `SearchViewModel` — for a source drawn from the *under-sampled* set rather than the top of the ranking. Epsilon-greedy, with ε ≈ 0.15.
3. **Recency bonus.** Sources added within the last 30 days get a temporary boost so they're tried while the app still has a user willing to forgive a slow result.

Sample size is also reported to the client, so the UI can honestly say *"new source"* rather than pretending a confident score exists.

### Local personal (on-device)

Room table in the **separate community database** (§5), not the main one:

```kotlin
@Entity(tableName = "source_stats")
data class SourceStatsEntity(
    @PrimaryKey val source: String,
    val okCount: Int, val failCount: Int, val emptyCount: Int,
    val latencyEmaMs: Int, val lastOkAt: Long, val lastFailAt: Long,
    val consecutiveFailures: Int,
)
```

This is what makes scoring work offline and on first launch, and what catches "broken *for me* only".

### Blend, client-side

```kotlin
composite = 0.40 * globalStability     // is it up, for people like me
          + 0.20 * globalPopularity    // do people actually use it
          + 0.30 * localStability      // does it work for ME
          + 0.10 * affinity            // language / content-type match to the reference manga
```

`localStability` starts at the global value and gains weight as the local sample grows: `w_local = n_local / (n_local + 10)`. New installs get the crowd's wisdom; heavy users get their own history.

> These weights are **guesses**, and there will be **no analytics SDK** to tune them with — not now, not later. Validation is an offline replay harness over recorded probe data, plus shipping behind a setting and judging it honestly. That's a weaker instrument than an A/B test and it's the correct trade for this app; the plan should not pretend otherwise.

### Circuit breaker

`consecutiveFailures >= 5` → `OPEN` for `min(2^n minutes, 6h)`, skipped in sweeps, one probe allowed on expiry. Always surface it — *"3 sources skipped (unreachable)"* with tap-to-retry — so it never looks like the app silently lost sources. Exploration slots ignore the breaker once per backoff window, which is how a recovered source gets rediscovered.

---

## 5. Where this plugs into the app

All sites verified present under `app/src/main/kotlin/org/koitharu/kotatsu/`.

### Use a separate Room database

The fork tracks `upstream KotatsuApp/Kotatsu`. If upstream ships its own `Migration28To29` and you ship yours, every rebase hurts and user databases diverge. Put community data in a **separate Room database** (`CommunityDatabase`, its own version line, its own `schemas/` dir). It sidesteps the collision permanently and keeps the diff against upstream small — which matters for a fork whose stated goal is maintainability. `MangaDatabase` stays untouched at version 28.

For the same reason, keep new code in its own package tree (`org.koitharu.kotatsu.community`, `….sourcescore`) and touch upstream files as narrowly as possible.

### When the app talks to the server

This is the whole cost model, so it's worth stating as a rule rather than leaving to each call site.

```kotlin
@Entity(tableName = "work_ref", primaryKeys = ["source", "source_key"])
data class WorkRefEntity(
    val source: String,
    val sourceKey: String,
    val workId: String,
    val resolvedAt: Long,
)
```

- **Details screen opens** → look up `work_ref` locally. On a hit, no resolve call at all. On a miss, one `works/resolve`, cached indefinitely. Then `GET works/{id}/rating` for the aggregate and the user's own value.

> **Merged works must redirect, or the cache goes stale forever.** An earlier draft said a merge "is handled by the server returning the surviving id" — which is wrong, because a client with a cache hit never calls resolve again and so never hears about it. It would sit on a dead `work_id` indefinitely, silently seeing no comments.
>
> So: **every work-keyed endpoint accepts a merged-away id** and answers with `{ "moved_to": "<surviving id>" }`. The client rewrites its `work_ref` row and retries once. `work_merge_log` (§2.7) already holds exactly the mapping this needs. `resolved_at` on the cache row exists for the same reason — a slow background revalidation of old entries costs nothing and catches anything the redirect path misses.
- **Comments are resolved only when tapped** — no comment count on the details screen unless it's already in the local cache from a previous visit, so the first visit to a manga costs nothing extra and every later one is free.
- **Chapter list upload** happens on the first *chapter-thread* open for that (source, branch), gated by `chapter_list_hash`.
- **Telemetry** is one batched `WorkManager` job per day.
- **Reply notifications poll on app open** — `GET /v1/notifications` with a cursor, no push service, no FCM, no Google dependency anywhere in this feature set. A badge on the comments entry is enough.
- **Delivered exactly once, then forgotten.** The cursor lives on the device; the server just answers "replies to my comments newer than this" and keeps no read state. That means **no notification table at all** — it's a query over `comment` joined to its parent — and no unread bookkeeping to get wrong. The trade is that a reinstall or a restore loses pending notifications, which is the right side of the trade for a feature nobody should have to manage.

  Three exclusions the query must carry, because each is a bug if missed: **shadowed replies never notify** (they're invisible to everyone but their author — notifying would give the game away and break §1's shadowban outright), replies whose **parent has been removed** don't notify (there's nothing to open), and you are never notified of your own reply. Cap the lookback at ~30 days so a user returning after months gets a usable list rather than hundreds of stale entries.
- **Offline: comments fail with a message, they do not queue.** A comment replayed twenty minutes later has lost the thread it was answering. Ratings *do* queue and replay, because a rating is idempotent and carries no conversational context.
- **Local manga and external sources are never resolved proactively.** No fingerprint is sent for a CBZ import or an `ExternalMangaSource` unless the user actually opens comments on it, at which point it resolves by title like anything else. Local files aren't scored either — there's no source reliability to measure.
- Everything is best-effort: a failed call leaves the UI in its "community features unavailable" state and never blocks reading.

Ratings are per work, so a rating given on MangaDex follows the reader to Comick — which is the payoff for §2 existing at all. Rate before the work resolves (offline, or resolve failed) and the rating queues locally against `(source, source_key)` and is replayed once an id arrives.

### Telemetry capture
- **`search/domain/SearchV2Helper.kt`** — `invoke()` is *the* choke point for every per-source search. Wrap it: ok/fail/empty + latency. One decorator, no call-site churn.
- **`core/parser/MangaRepository`** implementations — wrap `getDetails` / `getPages`.
- **`alternatives/domain/AlternativesUseCase.kt`** — already logs failures under `SOURCE_REPLACEMENT_TAG`. Replace the log with a probe record; the failure path is already isolated.
- **CF blocks** — `core/network/CloudFlareInterceptor.kt` and the existing `sources.cf_state` column already track this; feed it in rather than re-detecting.
- **Sampling.** A probe is a small record — source, operation, ok/fail, latency — written every time the app touches a source. Searching and opening details happen tens of times a day; **loading pages happens on every single chapter**, which is an order of magnitude more traffic for almost no extra signal, since a source that serves pages reliably is a source that serves details reliably. So: record **100% of `search` and `details`, and 10% of `pages`**. Same statistical picture, a tenth of the rows.
- Upload: one batched `WorkManager` job, daily, on unmetered network.

### Ranking consumption
- **`core/db/dao/MangaSourcesDao.kt` → `getOrderBy()`** — add `SourcesSortOrder.SCORE` beside `ALPHABETIC / POPULARITY / MANUAL / LAST_USED`. (Today's `POPULARITY` is "rows in my local manga table"; the new one is real.)
- **`alternatives/domain/AlternativesUseCase.kt` → `getCandidateSources()`** — the `sortedWith(compareByDescending { it.priority(ref) })` chain and the private `priority()` heuristic (locale +4/+2, contentType +1) become `priority(ref) + scoreBoost(source)`. **Highest-value single edit.**
- **`alternatives/domain/AlternativesSearchOptions.kt`** — `AlternativeSortOrder.SOURCE_PRIORITY` already exists as an enum case; it finally gets real data.
- **`search/ui/multi/SearchViewModel.kt`** — `MAX_PARALLELISM = 4` workers currently stripe over sources *by index* (`for (sourceIndex in workerIndex until sources.size step workerCount)`). Change to a **shared score-ordered queue with one exploration slot**, so results surface best-source-first instead of index-first.
- **`AlternativesUseCase`** uses the same striping at `MAX_PARALLELISM = 8` — same change.
- **`explore/data/MangaSourcesRepository.kt`** — expose `observeSourceScores()`. **No new screen**: the existing source list keeps the user's current language on top and puts a small **🔥 on the top 10 most popular** sources. Cheap, legible at a glance, and it reuses the list that's already there.

  Ranked **within the user's language bucket**, not globally — otherwise every fire icon lands on English sources and the marker is useless to a French or Indonesian reader. Fall back to the global ranking when a language has too few scored sources to rank meaningfully. Suppress the icon entirely where `sample_size` is below the §4 threshold, so 🔥 never means "we have no idea".

**These three surfaces are the entire point of §4 and §5**, in priority order: **search** ordering, **alternatives** ordering, and the **source tab's popular list**. Everything else in the scoring half is plumbing that feeds them; if a change doesn't improve one of the three, it isn't worth doing.

### Comments & ratings UI
New package `org.koitharu.kotatsu.community/` mirroring the existing `data/ domain/ ui/` split:
- **`details/ui/DetailsActivity.kt` + `DetailsViewModel.kt`** — a rating row and a "Comments (N)" entry. The screen already shows external ratings via `details/ui/scrobbling/ScrobblingInfoSheet.kt`; put the community rating beside it, clearly labelled.
- **`CommentsSheet`** — follow `details/ui/pager/ChaptersPagesSheet.kt` for the bottom sheet and `adapterdelegates4` ADs (`ScrobblingInfoAD.kt` is the closest template).
- **Chapter threads** — slot id from the reader (`reader/ui/ReaderViewModel.kt`), after M2c.
- **No automatic spoiler gating by reading progress** — deliberately rejected. Comment activity peaks in the days after a chapter drops, while most readers are behind it, so progress-based blurring would hide the liveliest part of every thread from the majority of the people reading it. The cure is worse than the disease.
- The only spoiler mechanism is the **author-declared `is_spoiler` flag**, rendered as tap-to-reveal. Imperfect, but it hides only what someone deliberately marked.
- Structurally, **chapter-scoped threads are the real spoiler protection**: a chapter-45 thread contains chapter-45 discussion, so a reader on chapter 40 simply doesn't open it. That comes free with §2B and needs no gating logic at all.
- **`list/domain/ListSortOrder.kt`** already has `RATING` — extend it to community rating where a list is server-resolvable.
- New strings go through Weblate (`.weblate` at repo root).

### Plumbing
- **`core/network/NetworkModule.kt`** — a `@CommunityHttpClient` qualifier alongside `@BaseHttpClient`, with the bearer interceptor. `core/network/HttpClients.kt` shows the pattern.
- **Settings** — server host picker modelled on `sync/ui/SyncHostDialogFragment.kt`. Self-hosting stays first-class.
- **`core/AppModule.kt`** — Hilt bindings.

### Cover pHash
`CoverHash.of()` needs a decoded `Bitmap`. Compute it **once, lazily, from the cover Coil has already loaded** for the details screen, and cache it in the community DB keyed by `(source, source_key)`. Never fetch an image solely to hash it.

---

## 6. Privacy, moderation, and running a public instance

### What is and isn't collected

Stored: an opaque `user_id`, **two peppered device hashes** captured once at signup and used for nothing but ban enforcement (§1 — including the MediaDrm one, which outlives a factory reset and is the heaviest identifier in this design), a chosen nickname, comment text, ratings, votes, per-source aggregate counters, and — for up to 7 days — a `filter_block` row recording *that* a post was rejected and by which rule (the rejected text itself only for the review window, since a moderator has to see it to fix a bad rule). Not stored: email, phone, IP address, reading history, manga titles in telemetry, or timestamps finer than a day in probes.

**Telemetry is on by default**, disclosed in onboarding, with a **second prompt shortly after onboarding** offering to turn it off — deliberately, because a consent buried in a first-run flow nobody reads is not consent. The second prompt is the one that will actually be seen.

**Telemetry reporter id rotates daily** — `HMAC(secret, "YYYY-MM-DD")`. The popularity formula in §4 only ever needs `distinct_reporters(day)`, so daily rotation costs literally nothing and leaves no linkage across days at all. The id exists to keep the *score* honest — dedupe one enthusiastic user, resist brigading — not to attribute source usage to anyone.

The probe upload is still authenticated with the bearer secret, because trust-tier weighting needs to know the reporter isn't an hour-old identity. So the server sees `user_id` and the daily pseudonym in the same request, and **writes only the pseudonym**. Be straight about what that means: the unlinkability is an operational guarantee (the code doesn't persist it, the logs don't record it, the source is public), not a cryptographic one. Making it cryptographic needs blind signatures or anonymous credentials, which is a large amount of machinery for a manga app's source rankings. Not worth it.

Comments and ratings keep the stable `user_id`, because bans and "my comments" need it. Stable where identity is genuinely required, rotating everywhere else.

Being straight about the residual: a stable `user_id` on comments is *pseudonymous*, not anonymous — someone who posts identifying details in their own comments identifies themselves. That's inherent to public comments, and worth one line in the privacy notice.

**Incognito changes nothing about comments and ratings.** They are explicit, deliberate actions and stay available — this was an explicit call. Probes are unaffected too, since they never contain manga identity. Worth a line in the settings copy so the behaviour isn't surprising.

`DELETE /v1/identity/me` erases ratings, tombstones comments, drops the row. The secret *is* the proof; no email loop.

### Filter at the door, then publish instantly

Restrictive by default, with no human in the critical path:

- Every comment and nickname passes a **synchronous word filter in the POST handler**.
- **Pass → published immediately.** Visible to everyone at once. No hold queue, no approval, no tier gate, no delay for new users.
- **Fail → rejected** with `422` and a message naming the offending term, so the user rewrites deliberately instead of guessing why the app "broke".
- **Moderators are the backstop, not the gate.** They handle what a wordlist structurally cannot: evasion, context, harassment, spoilers, brigading. They never block publication.

Also still applying, since spam volume and profanity are different problems:

- **Rate limits** (§3) — what stops a script posting 10,000 filter-clean comments overnight, and now the system's only volume control.
- **Dislikes as the moderation signal.** There is **no report button**: the panel ranks recent comments by dislike count and ratio, and moderators work down from the top. One fewer table, endpoint and queue — and one fewer brigading tool, since a report button is itself a weapon.

> **The limitation this accepts, stated plainly:** dislikes measure *disagreement*, not harm. An unpopular opinion collects dislikes; a comment that doxxes someone in a thread six people read collects none, and nothing surfaces it. The mitigation that makes this workable is volume — at roughly 500 comments/day (§3 Capacity) the **recent-comments firehose in the panel is directly reviewable** by a small team in a few minutes a day. That's the actual safety net; the dislike ranking is just triage on top of it. If comment volume ever grows past what a person can skim, this decision needs revisiting before it breaks rather than after.

Implementation: an **Aho–Corasick automaton built at startup** from the lists. `O(len(text))` regardless of list size, so 50k terms match in well under a millisecond — the filter never shows up in the latency budget. Rebuildable on list update without a restart.

### Severity tiers

The posture is **strict**: ordinary swearing is blocked, not merely flagged. A reader can say a chapter was disappointing without swearing at it, and the goal is a thread about the manga rather than a fight.

| Tier | Contents | Action | Matching | Normalization | Languages |
|---|---|---|---|---|---|
| `severe` | slurs, identity attacks, sexualised minors | **block** + auto-report to the panel; repeat hits escalate to ban review | substring | set A + B | **all**, always |
| `profanity` | ordinary swearing | **block** | word boundary | set A | comment's `lang` + English |
| `watch` | borderline, drama-adjacent | publish, flag, top of queue | word boundary | set A | comment's `lang` |

### Normalization sets — specified, not adjectives

"Aggressive vs conservative" was the wrong framing. What matters is *which transforms*, because they differ enormously in false-positive risk:

**Set A — no meaningful false-positive risk, applied everywhere:**
NFKC · casefold · strip diacritics · strip zero-width and combining characters · fold Cyrillic/Greek homoglyphs to Latin (`а→a`, `е→e` — unambiguous) · collapse 3+ character repeats (`fuuuck` → `fuck`) · strip punctuation appearing *between* letters of a candidate match (`f.u.c.k`) · the unambiguous substitutions `@→a $→s 0→o 3→e`.

**Set B — real collision risk, `severe` only:**
`1→i/l  5→s  7→t  4→a` · global separator stripping · substring rather than word-boundary matching.

> **As built, one deliberate extension.** Separator evasion (`f u c k`) is checked for `profanity`
> too, not only for `severe`. It is *not* the global separator stripping this table confines to set
> B — it is a bounded window over at most four tokens of at most three characters each, and it fires
> only when the glued window is **exactly** a term. That last condition is what makes it safe: the
> containing-word check alone was not enough, because `an ass` glues to `anass`, which is not a
> dictionary word and would have blocked an ordinary sentence. Requiring the whole window removes
> that class entirely, `the rap ist` included. Without it, the single commonest evasion walks through
> a filter whose stated posture is strict.

> **And one gap left open on purpose.** `sh1t` is *not* blocked. `1→i` and `5→s` are set B, so they
> apply only to `severe`, and this table is the reason. It is a one-line change to fold them for
> `profanity` as well — the argument against is that the profanity lists are thousands of terms long
> rather than a dozen, so a digit fold has far more chances to turn an ordinary token into a match.
> Worth deciding from the block log after launch rather than guessing now.

Set B is where "assassin" and "Scunthorpe" come from, so it's confined to the tier where a miss is genuinely harmful and the term list is short and unambiguous — **and it is always gated by the containing-word check below**, which is what removes that class rather than merely limiting its blast radius. Set A is strict enough to catch essentially every casual evasion of ordinary profanity.

### The Scunthorpe class is a tokenization problem, not a context problem

Worth separating clearly, because they have completely different answers.

**"Scunthorpe" and "assassin" need no context model at all.** The rule that kills essentially the whole class:

> Block only if the match is the **whole token**, or the match is a proper substring of a token that is **not itself a known word**.

- `assassin` contains `ass`, but `assassin` is a dictionary word → **allow**
- `Scunthorpe` contains `cunt`, but `Scunthorpe` is a known place name → **allow**
- `fuck` **is** the token → **block**
- `fuuuck` → collapses to `fuck`, is the token → **block**
- `niggaaa` → collapses, not a dictionary word → **block**

The critical detail: the containing token must be a *proper superstring*. Spellcheck dictionaries contain profanity too, so "is it a dictionary word" alone would allow everything. `token != match` is what makes it correct.

**Where the dictionary comes from:** Hunspell dictionaries (LibreOffice/Mozilla) cover ~100 languages and, unlike a plain frequency list, carry affix rules — which matters enormously for agglutinative languages like Turkish and Finnish, where a plain word list fails on every inflected form. Licences vary per dictionary (LGPL/MPL/GPL); this is a **server-side** dependency, so the app's licensing is unaffected — but check each one you ship. Supplement with a place-name gazetteer and the catalogue titles from §2.3.

This is also what makes Set B's substring matching *safe enough to use at all*. Without the dictionary check, substring matching on `severe` would be unusable; with it, the residual is rare words missing from the dictionary — which the feedback loop below catches.

**Known weak spot: CJK.** Japanese and Chinese have no word boundaries, so "whole token" is undefined and the dictionary test doesn't apply. Those languages fall back to substring matching with a longer minimum term length, leaning on the catalogue allowlist. Accept a higher false-positive rate there and watch its rejection rate specifically.

### True context — the smaller, harder residual

What genuinely needs context, and honestly can't be solved by a list:

- **Quoting the work itself.** A chapter's dialogue contains a slur and someone quotes it to discuss it.
- **Plot language.** "he's going to kill the demon king" versus a threat aimed at another user. Identical tokens, opposite meanings.
- **Reclaimed and in-group usage.**

Options, in order of what I'd actually do:

1. **Accept over-blocking.** Given a strict posture, a user rephrasing is a cheap outcome. This is the default and it's fine for most of the residual.
2. **Quote downgrade** — a `profanity` match wholly inside quotation marks or a `>` blockquote downgrades from block to flag. Cheap, matches the real use case (quoting a chapter line), and trivially gamed — so it applies to `profanity` only, **never** `severe`.
3. **Do not reach for a classifier.** The obvious move is a toxicity model, and Perspective API — the free one everyone uses — shuts down after 2026. Self-hosting a model is a large scope increase for a small residual. Revisit only if the block queue shows it's actually needed.

### The feedback loop that actually fixes the list

More important than any rule above, and cheap:

- **Log every block** with the matched rule and language (the text itself need not be retained beyond the review window).
- **A "blocked" view in the panel** with **one-click allowlist** — a moderator seeing `assassin` blocked fixes it permanently in one action.
- **"This was wrong" from the rejection dialog** — the user already sees which term triggered it, so one tap files a false-positive report into that same queue.
- **Per-rule false-positive rate**, with auto-demotion of any rule whose FP reports cross a threshold. A rule that misfires repeatedly should stop blocking on its own rather than waiting for someone to notice.

That turns the wordlist from a guess made once into something tuned from real data in the first few weeks — which is what will actually determine whether this feels strict or feels broken.

### Containing false positives — the rest

1. **Never union all 75 languages.** A word innocuous in one language is profane in another; unioning across 75 would block a large amount of ordinary text. Only `severe` is global.
2. **Auto-allowlist from the catalogue.** The AniList seed (§2.3) gives ~200k titles and alt titles; any token appearing in a known work title is exempt from `profanity`. People discuss works with deliberately crude titles, and romanized Japanese collides with profanity in several European languages.
3. **A manual allowlist**, editable from the panel.
4. **Watch per-language rejection rates.** A language whose block rate is an outlier means a bad list, not a rude userbase. Without this, a broken `pt` or `tr` list quietly ruins the feature for that language and nobody finds out.

**The rejection message names the offending term** and links to the rules. Honest users fix a false positive in one try; determined users learn the list quickly — accepted, because moderators are the answer to determined users and the alternative punishes the honest majority for the minority's benefit.

The rules are a short README kept in this repo and **served by the instance at `/rules`**, so the link is versioned with the deployment and doesn't depend on GitHub being reachable — self-hosters get their own copy for free, and the app can cache it for the rejection dialog.

**English only.** Fine for v1, but worth being aware that a Vietnamese or Brazilian user whose comment was just blocked gets sent to a page they may not read — so the *rejection message itself* carries the weight, and that one is localised through Weblate (§3 Errors). If one translation is ever worth doing, do the rules page in the language with the highest block rate.

**Evasion is accepted, not fought.** Building an arms race into the filter would cost far more false positives than it prevents real ones.

### Keeping comments on-topic — the part a wordlist can't do

The goal is discussion of the manga and the chapter, not drama. Worth being clear that **profanity filtering and drama prevention are different problems**: almost no flame war needs a swear word. Ship-wars, pile-ons, spoiler fights and "first!" noise all pass a wordlist untouched. What actually moves that outcome is structure:

- **Chapter-scoped threads as the default surface** (§2B). Anchoring discussion to a specific chapter is the single strongest on-topic lever, and it's already in the plan for a different reason.
- **Cap the duel, not the discussion.** A reply is refused when the unbroken ancestor chain above it involves only two participants and already contains **3 comments by the would-be author**. Two people can exchange three rounds and then have to stop; a third person joining resets nothing but is free to reply, because that's discussion rather than a fight. Chains are short by construction, so this is a walk up `parent_id` — no recursive CTE needed.
- **Chapter scoping is the spoiler answer**, not progress-based blurring (§5) — a reader who hasn't caught up simply doesn't open the newer chapter's thread.
- **Per-thread reply throttle** (§3 rate limits) — the classic anti-flame-war lever, and cheap.
- **Sort by top, not new**, so the visibility reward for a hot take is smaller.
- **No @mentions and no DMs**, so there's no targeting mechanism to build a pile-on with.
- **20-character minimum**, which removes "first", "up", "lol" without any judgement about content — and conveniently sits right at the threshold where language detection becomes reliable (below).
- **Likes and dislikes, both counts shown.** Visible scores give readers a legible quality signal and let a weak take sink on its own instead of needing a moderator. Two guardrails keep them from becoming a weapon: ranking uses the Wilson lower bound on `(up, up + down)`, so a heavily-disliked comment falls out of view rather than sitting at the top being argued with; and **dislikes never feed auto-hide**, which stays strictly report-driven — otherwise dislike-brigading becomes a way to hide anything.
Most of these are a few hours each and they do more for the stated goal than any wordlist.

### Language detection, not locale

`comment.lang` is **detected from the text server-side**, not taken from the client's locale — a French-locale user writing in English would otherwise get the French list applied and the English one skipped, which is both a false-positive source and a trivial filter bypass.

Use **Lingua** (JVM, no native dependency) restricted to the 15 launch languages, which keeps the model small and materially improves accuracy versus running all 75. Detection is unreliable on very short text, which is exactly why the **20-character minimum** is convenient: it puts every comment above the threshold where n-gram detection works. Fall back to the client's locale when confidence is low.

This is what selects the profanity list, so it's part of the filter, not a display nicety.

Sources, having gone and looked:

- **[LDNOOBWV2](https://github.com/LDNOOBWV2/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words_V2)** — 75 languages, 50,000+ terms. This is the one to use. The [original Shutterstock LDNOOBW list](https://github.com/LDNOOBW/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words) is widely referenced but **no longer maintained**, which is exactly why V2 exists.
- **[words/profanities](https://github.com/words/profanities)** — English, maintained, severity-rated. Better quality than V2's English file; use it for `en` specifically.
- **Hate Speech Dataset Catalogue** — for the serious category (slurs, identity attacks) as opposed to ordinary profanity, which is a different queue priority. Note **Hatebase is dead**; don't build on it.
- **Do not build on Perspective API.** Google Jigsaw's free toxicity scorer is the obvious reach for this problem and it is **scheduled to shut down after 2026**. Anything depending on it would need replacing almost immediately.

Matching reuses `norm()` from §2.2 (NFKC, casefold, strip diacritics) plus collapse-repeats (`fuuuck` → `fuck`) and de-leet (`4→a 3→e 1/!→i 0→o $→s @→a`), with word-boundary matching — at the tier-appropriate aggressiveness above.

Nicknames run the same filter at set-time, plus a reserved-name list (`admin`, `mod`, `kotatsu`, `system`, …) to stop impersonation.

**Curate before shipping.** These lists are built for coarse content classification, not for gating a discussion forum — V2's per-language files vary a lot in quality and some include terms no reasonable moderator would block. Budget a pass over the languages you actually ship, and treat the list as configuration to be tuned from the panel, not a dependency to be consumed as-is.

### Languages at launch

Major Latin-script languages plus the major Asian ones. A first cut, ordered by how much of this userbase they cover:

**Dictionary check works** (word boundaries + Hunspell): `en` `es` `pt` `fr` `de` `it` `pl` `ru` `tr` `id` `vi`

**No word boundaries — substring mode** (§ the Scunthorpe section): `ja` `zh` `ko` `th`

Worth being clear about the split, because it is not a detail: **the Asian languages are exactly the ones where the containing-word check doesn't apply.** Japanese, Chinese, Korean and Thai have no spaces, so "is the match the whole token" is undefined and the Scunthorpe defence is unavailable. They run substring matching with a longer minimum term length, leaning entirely on the catalogue allowlist and the manual one. Expect a materially higher false-positive rate there, watch those four rejection rates specifically, and be readier to demote a rule than in the Latin set.

Everything else falls back to `severe`-only, which is global anyway. **Adding a language later is configuration, not code** — a curated list, a dictionary, and a row in `filter_rule` — so starting narrow costs nothing and shipping 75 uncurated lists would be strictly worse than shipping fifteen good ones.

### Moderation panel

Static SPA under `src/main/resources/static`, served by the same Ktor container — no second deployment.

- **Queues**: **most-disliked recently** (the primary triage view), **recent comments firehose** (the actual safety net — skimmable at this volume), word-list `watch` flags, **blocked-by-filter** with user-flagged false positives first, **suspected ban evasion** (MediaDrm match on a new `ANDROID_ID` — confirm by behaviour, never automatically), **rating brigades** (§3), work-link disputes.
- **Actions**: remove, restore, ban user, **ban device / reverse a device ban**, shadowban, merge/unmerge works, reset a nickname, **one-click allowlist a term or rule**, demote a misfiring rule.
- **Multiple moderator accounts with roles** — `moderator` (act on comments and users) and `admin` (manage moderators, merge works, reverse bans, see the full audit log). TOTP from the start, since the credential sits with several people.
- **Moderators are onboarded from the panel** — an admin invites, sets a role, and can disable. The very first admin is bootstrapped once from an env var or a one-shot CLI command on first run, and that path disables itself afterwards.
- **Ban reversal lives here and only here.** There is no in-app appeal surface by design; a banned user has no route back inside the app.
- **Every action writes `mod_action`** — append-only audit log, attributed to the individual moderator. Non-negotiable with a team: it's what lets you answer "who removed this and why" a year later, and what makes a moderator dispute resolvable.
- Ships as **M4b, right after comments** — not M8. With instant publish and no filter, the panel *is* the moderation system, so it can't trail the feature it moderates.

### Policy and jurisdiction

You asked me to take this one. Operational guidance, not legal advice — get a real opinion before this gets large.

**The single most important decision is one you've already made: the server holds comments, ratings and counters, and never manga content.** No images, no proxying, no mirroring, no links stored as content. Keep that line absolutely clean — it's what makes this a discussion service rather than part of a distribution chain, and it's worth refusing features that would blur it.

Then:

- **Publish, in-repo**: Terms of Service, a content policy (what gets removed), and a privacy notice matching §6 exactly. Versioned in the repo, which is itself a good-faith signal.
- **"Delete everything about me"** — a prominent in-app button on `DELETE /v1/identity/me`: ratings erased, comments tombstoned, votes dropped, user row gone. Self-service, instant, no email loop, because the secret is the proof.

- **Contact is the Discord**, linked from the rules page and the README. It gives a third party — a rights-holder, or someone being harassed in a thread they can't delete — a route to the operators, which is what matters for acting on notice. Two caveats worth knowing rather than discovering: a Discord invite is not a *designated DMCA agent* (that needs a real address filed with the Copyright Office, if you ever go that route), and Discord itself can disappear the server, so the README should carry a second durable contact — a GitHub issue tracker is enough.
- **Act on notice, promptly, and log it.** Every liability shield worth having — US DMCA safe harbour, the EU's hosting-provider protections under the DSA — is conditional on removing unlawful content once you actually know about it. The `mod_action` audit log is what demonstrates you did.
- **Register a DMCA agent** if you host in the US; it's inexpensive and it's a precondition, not a nicety.
- **GDPR is light but not zero.** No email or IP means no real personal data — but the moment your reverse proxy logs an IP, that changes. The no-logging rule in §3 Ops isn't only a privacy nicety; it's what keeps this claim true.
- ~~Age-gate NSFW threads behind the app's existing `isNsfwContentDisabled` setting~~ — **dropped, because there is nothing to gate.** The comments button lives on a manga's details page, and a user with NSFW content disabled never reaches the details page of an NSFW work: it is already filtered out of search, browsing and recommendations. The gate would guard a door nobody can get to. The community features are 18+ in any case (see `legal/TERMS.md` §2).
- **Retention**: comments until deleted, raw probes 7 days, aggregates indefinitely.
- Pick a host that will forward a complaint rather than null-route you on the first one.

---

## 7. Milestones

| # | Deliverable | Notes |
|---|---|---|
| **M0** | Ktor skeleton, Compose, Flyway, health, CI, no-log config | ~1 day; get deploy working before features |
| **M1** | Identity: secret generation, bearer interceptor, `hello`, nicknames, backup section, recovery phrase | Small now that signing is gone |
| **M5** | Telemetry: community Room DB, capture in `SearchV2Helper`, WorkManager upload, server rollup | Independent of everything in §2 |
| **M6** | Scoring: aggregation, `/v1/sources/scores`, client cache, blend, **exploration** | |
| **M7** | Ranking: `AlternativesUseCase.priority()`, score-ordered queues + exploration slot, `SourcesSortOrder.SCORE`, circuit breaker | The payoff. Ship behind a setting and measure |
| **M2a** | Catalogue providers: Kitsu + MangaUpdates, on-demand lookup with negative caching | Replaced the AniList bulk importer — see §2.3 |
| **M2b0** | **Harvest the evaluation set** — MangaDex/AniList cross-links → labelled positive pairs; same-author and same-genre pairs → hard negatives. Commit it as a fixture with a scoring script | A real deliverable with its own day of work, not a footnote to M2b. Without it the thresholds in §2.2/§2.4 are guesses wearing a confident font, and there is no way to tell a good change from a bad one |
| **M2b** | Work resolution: `norm()`, pg_trgm, fingerprints, aliases, pHash verify | **Highest-risk item.** Tuned against M2b0, precision-first |
| **M3** | Ratings: schema, endpoints, Bayesian aggregate, details UI | Needs M2b only |
| **M4a** | Comments: work threads, likes/dislikes, language detection, reply-chain cap, 20-char minimum, 5-min edit window, poll-on-open notifications, **word filter (Aho–Corasick + Hunspell containing-word check + allowlists + block log)**, instant publish, sheet UI | The filter is in the POST path, so it ships with the endpoint. The block log and one-click allowlist are part of it, not a later nicety — they're how the lists get tuned |
| **M4a.1** | *Done.* Comment domain: `comment`/`comment_vote` schema, post/edit/delete, votes with Wilson ranking, reply-chain cap, shadowban enforcement on every read path, cursor-based reply notifications, `/v1/works/{id}/comments` + `/v1/comments/{cid}` + `/v1/notifications` | Shipped with the filter behind a `ContentFilter` seam, which M4a.2 then filled |
| **M4a.2** | *Done (engine).* Aho–Corasick, set A/B normalisation, the containing-word check, three tiers, per-language scoping, catalogue and manual allowlists, block log, user dispute, auto-demotion, nickname filtering, panel queues, `/rules` | The **lists** are still a starter — curation is the outstanding task below, and `severe` catches little until it is done |
| **M4a.3** | *Done.* The app: comment sheet (thread, reply, edit, delete, vote, sort), the rejection dialog with its one-tap "this was wrong", and reply notifications polled on open | Launched from one line on the details screen, so an upstream rebase has one line to re-apply |
| **MT** | **Tests**: server-side `norm()`, WorkResolver against the M2b0 fixture, chapter alignment, scoring blend, filter (incl. containing-word check); Android-side the community Room DB, the score blend and the reply-chain cap | Not a phase at the end — each milestone lands with its own. Listed once so it isn't quietly skipped |
| **M4b** | Moderation panel: moderator accounts, roles, TOTP, dislike-ranked and firehose queues, device bans and reversal, `mod_action` audit log | **Ships with M4a, not later.** With no report button and no hold queue, the panel is the *entire* moderation system |
| **M4b.1** | *Done.* Moderator accounts (PBKDF2, TOTP, roles, one-shot bootstrap), session cookies, `mod_action` append-only in the database, five queues, comment remove/restore-from-snapshot, user and device bans, reversible work merge/unmerge, `/admin` panel served from the same container | The two filter queues followed with M4a.2, once there was a `filter_block` log for them to read |
| **M2c** | Chapter alignment + per-chapter threads: slots, offset search, merged/index-only fallbacks | After the first comment release, on purpose |

**Order: `M0 → M1 → M5 → M6 → M7 → M2a → M2b → M3 → M4a+M4b → M2c`.**

Scoring first because it needs no work identity at all, so it ships real value while the risky identity graph gets the time it needs. Chapter alignment last because work-level threads are useful alone, and alignment is the piece most likely to need a second pass once real cross-source data exists.

### The M2b evaluation, since it's the crux

Ground truth is **harvested, not hand-labelled**: MangaDex and AniList both publish cross-links between the same work across services, which yields a few thousand labelled positive pairs for free. Negatives come from same-author and same-genre pairs, which are the hard cases. Then tune the §2.2/§2.4 thresholds against it.

**Optimise for precision over recall.** A missed merge is an annoyance a user can report; a wrong merge silently mixes two communities. Target precision ≥ 0.99 and accept whatever recall that costs — the organic feedback in §2.6 will claw recall back over time, and nothing claws back a bad merge.

---

## 8. Risks

| Risk | Mitigation |
|---|---|
| Work-merge false positives collapse two series into one thread | pHash **and** title agreement; never auto-merge works that both have comments; reversible merge log; dispute endpoint; precision-first tuning |
| A sequel merges into its prequel and leaks spoilers | Sequence markers survive `norm()` and hard-block merging (§2.5) |
| Chapter alignment silently mis-maps comments | Confidence floor; per-source degradation, never global; show the label the *user's own source* uses so a mis-map is visible |
| Scoring starves new sources | Optimistic prior + exploration slots + recency bonus (§4) |
| Ranking gets *worse* — popularity crowds out niche sources | Local stability weighted heavily; never hard-exclude, only reorder; keep the "search all sources anyway" affordance (`SearchViewModel` already has the `search_disabled_sources` footer) |
| Secret leaks via a log line | No `Authorization` header, no bodies, in both Caddy and Ktor; assert it in a test |
| Instant publish means what the filter misses is briefly public | The filter blocks the bulk at the door; rate limits bound volume; the panel ships with the feature rather than after it |
| **No report button means low-visibility harm never surfaces** | The recent-comments firehose is the real net and is skimmable at ~500/day; dislike ranking is triage on top. This is the plan's weakest moderation link by design — revisit if volume outgrows a daily skim (§6) |
| Filter false positives silently cost real users their comments | Containing-word dictionary check removes the Scunthorpe class outright; Set A/B split, no cross-language union, catalogue allowlist (§6). And crucially they are **not silent**: every block is logged, the user can report it in one tap, and misfiring rules auto-demote — so the list is tuned from data in week one instead of guessed once |
| Threads become drama despite a clean word filter | Structural levers, not moderation: chapter scoping, depth cap, reply throttle, no mentions/DMs, hidden downvotes (§6). A wordlist stops swearing; it does not stop a ship war |
| Server outage breaks the app | Every community feature degrades to *absent*, never to *error*. Cached scores stay valid for days; comments simply don't render |
| Upstream rebase pain | Separate Room DB, separate packages, minimal edits to upstream files |
| Parser hash changes orphan aliases | Alias on source URL/slug, never `Manga.id` |
| Legal exposure from user content | Server never touches manga content; published policy; act-on-notice; `mod_action` audit log |

---

## 9. Status

### Nothing open

Every design question raised across this plan is decided and written into the section that owns it.
What remains is work, not choices.

### Not questions — outstanding tasks

**Before launch — curating the lists.** The filter engine is built; these are what it runs on, and `src/main/resources/filter/seed/README.md` is the same checklist next to the files:

- ~~Curate the word lists for the 15 launch languages~~ — *done*: 3,040 terms via `tools/curate_wordlists.py`, which records the judgements. The public lists needed three kinds of surgery, and the second and third are specific to this product: structurally unusable terms (V2 ships `b`, `c` and `f` as rules), **manga vocabulary** (`hentai`, `ecchi`, `harem`, `tentacle` are all in them), and **plot language** (`kill`, `demon`, `assassin`, `rape` — blocking those would eat content warnings). The native-speaker pass was then done **empirically** — `tools/fetch_audit_corpus.py` plus `FilterAuditTest` run the real filter over ordinary prose in each language and report every hit. That took the measured false-positive rate to **0.00% in 14 of 15 languages and 0.95% in English**, and found four *code* bugs on the way: Vietnamese at 31.6% because the normaliser stripped its tone marks, hyphenated compounds the dictionary could never rescue, missing inflections, and ordinary words swept in from the sources. What is left is a real speaker for `id vi th ko ja zh`, who can catch what is *missing* rather than what over-fires.
- **Check the Hunspell licence** if you ever ship one. Nothing bundled today is Hunspell-derived — `filter/words/en.txt` is hand-written — so there is currently nothing to record. Only English has a dictionary, which is why only English gets proper-substring matching on `profanity`; adding a language means adding both, or it blocks ordinary words.
- ~~Write the rules README and wire `/rules`~~ — *done*: `src/main/resources/rules.html`, served by the instance and linked from every rejection.
- ~~Add the error strings to Weblate~~ — **declined for now.** The ~20 new `community_*` strings ship English-only, which means a rejected comment is explained in English to a userbase spanning fifteen languages. The strings are ordinary resources, so this is a Weblate sync away whenever it is wanted.

**Before the public instance** ([DEPLOY.md](DEPLOY.md) walks through these in order):

- ~~Check the Kitsu and MangaUpdates terms~~ — **declined by the operator.** Both are consulted on demand and only for works this server has never seen, so the volume is negligible, but nothing has been read and recorded. Worth revisiting if either ever rate-limits or complains.
- ~~Write ToS, content policy and privacy notice~~ — *done*: [`legal/`](legal/), served at `/terms`, `/content-policy` and `/privacy`. Decisions: **France** (the LCEN lets a non-professional publisher stay unnamed provided the host holds their identity — Germany's Impressum would not have), **18+ for the community features only**, daily backups kept 7 days, and a self-service export at `GET /v1/identity/export` because the server cannot identify anyone well enough to answer an access request any other way. `PolicyClaimsTest` asserts the notice's checkable claims against the live schema, so a migration that breaks one fails the build rather than quietly making the document false.
- ~~Pick host and domain~~ — *done*: **IONOS, Paris datacentre**, at `community.kotatsuredo.app`. IONOS SARL is named as the hébergeur in the terms, which the LCEN requires even though the publisher is not. Caddy logs nothing, so the §6 privacy claims hold the moment it runs.
- ~~A second durable contact besides Discord~~ — *done*: the issue tracker is named as the record for formal notices in the terms and the content policy.
- ~~Uptime alerting and a restore drill~~ — **declined by the operator.** The backup command and the health endpoint are documented in [DEPLOY.md](DEPLOY.md) for whenever that changes; until then nobody is watching, and a backup nobody has restored is a belief rather than a backup.
