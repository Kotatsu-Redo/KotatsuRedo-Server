# Filter seed lists

**Generated. Do not hand-edit.** These files are produced by [`tools/curate_wordlists.py`](../../../../../tools/curate_wordlists.py),
which is where every judgement that shaped them is written down. Edit the script and regenerate:

```sh
python tools/curate_wordlists.py --fetch   # re-download the sources, then curate
python tools/curate_wordlists.py           # curate from tools/.cache
```

They are a *starting point*. They load into `filter_rule` on first boot and the database is the
source of truth from then on, so live tuning happens in the panel and a re-seed never overwrites a
moderator's decision.

## What ships

| Tier | Scope | Terms |
|---|---|---|
| `severe` | every language, substring | 47 |
| `profanity` | the comment's language + English, whole token | 2,940 across 15 languages |
| `watch` | the comment's language, publishes and flags | 31 across 6 languages |

Per language: `en` 307, `es` 73, `pt` 76, `fr` 92, `de` 68, `it` 164, `pl` 55, `ru` 142, `tr` 138,
`id` 514, `vi` 739, `ja` 173, `zh` 290, `ko` 76, `th` 33.

### Measured false-positive rate

Against ordinary prose, per [the audit](../../../../../tools/fetch_audit_corpus.py):

| | |
|---|---|
| 14 of 15 languages | **0.00%** |
| English | **0.95%** |

The English residual is foreign proper nouns (`Massimiliano`, `Syndikatsgasse`), the given name
`Dick`, and Latin binomials (`columbianus`, `phoenicum`) — an encyclopaedia is unusually full of all
three and a comment section is not. Those are for the panel's one-click allowlist.

## File naming

| File | Tier | Language |
|---|---|---|
| `severe.txt` | `severe` | global |
| `<lang>-profanity.txt` | `profanity` | that language, plus English always |
| `<lang>-watch.txt` | `watch` | that language only |

One term per line, `#` for comments, ordinary spelling. The normaliser folds case, accents, repeats,
homoglyphs and the unambiguous leetspeak, so `fuuuck`, `f.u.c.k` and `fuсk` all match `fuck` without
being listed. A new file must also be registered in `FilterService.SEED_FILES`.

## Sources

- **[LDNOOBW](https://github.com/LDNOOBW/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words)**,
  Shutterstock's original. Unmaintained, but modest and clearly built for filtering, which makes it a
  better base than its successor. Used for 13 of the 15 languages.
- **[LDNOOBW V2](https://github.com/LDNOOBWV2/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words_V2)**,
  maintained and far larger — 12,996 English terms against the original's 403. That size is the
  problem rather than the selling point, so it is used only for Indonesian and Vietnamese, which the
  original does not cover.

Neither is usable as shipped. The three reasons, all handled by the script:

1. **Structurally unusable terms.** V2's English list contains `b`, `c` and `f`. A one-character rule
   in a substring-matched tier blocks every comment containing that letter. It also carries 2,045
   multi-word phrases, which cannot match a token at all — those are stored joined instead, so the
   glue window assembles them.
2. **Manga vocabulary.** These lists are built from adult-site keywords, so they swallow the working
   vocabulary of the thing this server exists to discuss: `hentai`, `ecchi`, `yaoi`, `harem`,
   `tentacle`, `futanari` are all in them. Blocking those is not strict, it is broken.
3. **Plot language.** `kill`, `demon`, `slave`, `assassin`, `rape`. A shounen manga is four hundred
   chapters of people killing demons and a serious review says so — and "content warning for the rape
   scene in chapter 40" is among the most useful things one reader can tell another. A filter that
   eats content warnings makes the community less safe.

## The audit, and what it found

Nobody here reads Thai or Vietnamese, so the "native-speaker pass" was done empirically instead:
download a corpus of ordinary prose in each language, run the real filter over it, and treat every
hit as a suspect. A rule that fires inside an encyclopaedia article is a rule that will fire inside a
comment.

```sh
python tools/fetch_audit_corpus.py
FILTER_AUDIT_CORPUS=tools/.corpus ./gradlew test --tests '*FilterAuditTest*' -i
```

It found five things, four of which were bugs in the *code* rather than in the lists — which is
exactly why staring harder at a word list would not have worked:

1. **Vietnamese sat at a 31.6% false-positive rate.** One sentence in three. The cause was the
   normaliser stripping tone marks: `các` (the plural marker, one of the commonest words in the
   language) folded onto a blocklist entry, as did `tỉnh` (province) and `bởi` (by). Fixed by making
   diacritic-stripping language-aware — see `FilterNormalizer.DIACRITIC_SENSITIVE`. Turkish has the
   same shape (`şık`, chic) and Polish is on the list for the same reason.
2. **Hyphenated compounds could not be rescued.** `Lead-bismuth` folded into one string no dictionary
   would ever hold, so `smut` blocked a sentence about metallurgy. Tokenising now splits on hyphens,
   which costs nothing: `f-u-c-k` becomes four tokens the glue window reassembles.
3. **Inflections fell off the end of the dictionary.** `passenger` was in it and `passengers` was
   not; `classify` was and `reclassified` was not. A light suffix and prefix fallback replaced an
   endless list of plurals.
4. **Ordinary words swept in from the sources.** `negro` (the colour black) in Spanish; `ruang`
   (room), `bola` (ball) and `asing` (foreign) in Indonesian; `ratio` and `sheep` on the English
   watch list. Drug vocabulary too — not profanity, and `heroína` is also the Spanish for *heroine*.
5. **Three ordinary Vietnamese words** that no rule could rescue: `có` (to have), `phân` (to divide)
   and `tinh`, plus the phrase `vành đai` (belt, as in the asteroid belt).

Every case is now a regression assertion in `WordListTest`.

## Still worth doing

1. **A real native speaker on `id`, `vi`, `th`, `ko`, `ja`, `zh`.** The audit catches terms that are
   *also ordinary words*; it cannot catch a term that is missing, mis-spelled, or no longer used.
   Indonesian (514) and Vietnamese (739) are the largest and came from the weaker source.
2. **Dictionaries for more languages.** `filter/words/<lang>.txt` is what lets a *proper substring*
   match be overruled — it is why `assassin` and `Scunthorpe` are postable. Only English has one, so
   only English gets substring matching on `profanity`; every other language is whole-token only.
   Adding a language means Hunspell base forms plus a place-name gazetteer, then adding it to
   `WordFilter.DICTIONARY_LANGUAGES`. **Do both or neither** — a language in that set without a
   dictionary blocks ordinary words, which is exactly how `ass` came to block the Portuguese
   `passado`.
   Check each Hunspell dictionary's licence (LGPL/MPL/GPL vary) and record it; server-side only, so
   the app is unaffected.
3. **Watch lists beyond the six.** `watch` is where ship wars live and almost none of it is
   profanity, so it does not come from these sources at all — it is written by hand.
4. **Then stop and read the panel.** Filter health shows the per-language block rate and the
   per-rule false-positive rate. After a week of real traffic those numbers are worth more than any
   amount of further guessing, and a rule that misfires enough demotes itself.
