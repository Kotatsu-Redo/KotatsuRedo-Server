#!/usr/bin/env python3
"""Builds the filter seed lists from the public sources, and records every judgement made.

Run from the repository root:

    python tools/curate_wordlists.py --fetch      # download the sources, then curate
    python tools/curate_wordlists.py              # curate from tools/.cache

The point of doing this as a script rather than by hand is that the *decisions* are reviewable. A
directory of word lists tells you what was blocked; this tells you why, and lets the lists be rebuilt
when the upstream sources change without redoing the argument.

Sources, per PLAN.md §6:

  - LDNOOBW (Shutterstock's original). Unmaintained, but modest and clearly built for filtering
    rather than for classification, which makes it a far better base than its successor.
  - LDNOOBW V2. Maintained and much larger - 12,996 English terms against the original's 403 - and
    that size is the problem, not the selling point. Used only for the two launch languages the
    original does not cover.

Neither is usable as shipped, for three reasons this script fixes.
"""
from __future__ import annotations

import argparse
import os
import sys
import unicodedata
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CACHE = os.path.join(ROOT, "tools", ".cache")
SEED = os.path.join(ROOT, "src", "main", "resources", "filter", "seed")
WORDS = os.path.join(ROOT, "src", "main", "resources", "filter", "words")

V1 = "https://raw.githubusercontent.com/LDNOOBW/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words/master/{lang}"
V2 = "https://raw.githubusercontent.com/LDNOOBWV2/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words_V2/main/data/{lang}.txt"

LANGUAGES = ["en", "es", "pt", "fr", "de", "it", "pl", "ru", "tr", "id", "vi", "ja", "zh", "ko", "th"]

# The original list has no Indonesian or Vietnamese, so those two come from V2 and get a heavier
# structural trim.
FROM_V2_ONLY = {"id", "vi"}

# No spaces, so "whole token" is undefined and the matcher works on substrings instead.
BOUNDARYLESS = {"ja", "zh", "ko", "th"}

# Must match FilterNormalizer.DIACRITIC_SENSITIVE. In these languages a mark is part of the letter,
# not decoration on it: stripping them folds `các` - the Vietnamese plural marker - onto a blocklist
# entry, which measured at a 31.6% false-positive rate against ordinary prose.
DIACRITIC_SENSITIVE = {"vi", "tr", "pl"}

# Must match FilterRules.MAX_GLUE_TOKENS / MAX_GLUE_TOKEN_LENGTH on the server. A phrase stored
# here that the matcher will not assemble is a rule that can never fire.
MAX_GLUE_TOKENS = 4
MAX_GLUE_TOKEN_LENGTH = 6


# --------------------------------------------------------------------------------------------------
# 1. Structural problems: terms this matcher physically cannot use.
# --------------------------------------------------------------------------------------------------

def is_letter(character: str) -> bool:
    """Letters *and* combining marks.

    `str.isalpha()` is False for a combining mark, which silently destroys Thai: twenty-six of the
    thirty-one Thai terms are spelled with a tone mark or a vowel sign, and a naive alpha check threw
    all of them away while reporting the language as covered.
    """
    return unicodedata.category(character)[0] in ("L", "M")


def is_structurally_usable(term: str, lang: str) -> tuple[bool, str]:
    if any(c.isdigit() for c in term):
        # "2g1c", "4r5e". Leetspeak variants are what the normaliser is for - the rule should be the
        # ordinary spelling and let set A and set B fold the rest.
        return False, "digits"
    if not all(is_letter(c) or c in "'- " for c in term):
        return False, "punctuation"

    words = term.split()
    if len(words) > 1:
        # A phrase cannot match a token, but it *can* match a glue window - the mechanism that exists
        # to catch `f u c k`. Storing the joined form is what makes phrases work at all, and it is not
        # optional for Vietnamese, where most of the vocabulary is two syllables and a naive phrase
        # drop loses 54% of the language.
        if len(words) > MAX_GLUE_TOKENS:
            return False, "phrase too long"
        if any(len(word) > MAX_GLUE_TOKEN_LENGTH for word in words):
            return False, "phrase word too long"

    joined = "".join(words)
    # Two characters is enough where a rule can only ever match a whole token: the boundaryless
    # scripts, and the languages that keep their marks - Vietnamese syllables are short, and a
    # three-character floor threw twenty-one real terms away.
    minimum = 2 if lang in BOUNDARYLESS or lang in DIACRITIC_SENSITIVE else 3
    if len(joined) < minimum:
        # The catastrophic case. `severe` matches as a *substring*, so a one-character rule blocks
        # every comment containing that letter. V2's English list contains "b", "c" and "f".
        return False, "too short"
    if len(joined) > 24:
        return False, "too long"
    return True, ""


# --------------------------------------------------------------------------------------------------
# 2. The judgement that only someone building *this* product would make.
# --------------------------------------------------------------------------------------------------

# Manga vocabulary. These lists are assembled from adult-site keywords, so they swallow the ordinary
# working vocabulary of the thing this server exists to discuss. Blocking `hentai` in a manga app is
# not strict, it is broken: "this one has too much ecchi" is a review, not an obscenity.
GENRE_VOCABULARY = {
    "hentai", "ecchi", "yaoi", "yuri", "harem", "isekai", "mecha", "manga", "anime", "otaku",
    "doujin", "doujinshi", "dojinshi", "oppai", "waifu", "husbando", "senpai", "sensei",
    "shounen", "shonen", "shoujo", "shojo", "seinen", "josei", "bishounen", "bishoujo",
    "futanari", "ahegao", "bara", "tentacle", "tentacles", "omegaverse", "yandere", "tsundere",
    "fanservice", "nsfw", "lewd", "onee", "onii", "imouto", "ero", "eroge", "otome",
    # Genre labels for content the tier system handles as *content*, not as a word. "I dropped it,
    # too much lolicon" is a criticism of the work and has to be sayable.
    "loli", "lolicon", "shota", "shotacon", "lolita",
}

# Plot language. A shounen manga is four hundred chapters of people killing demons, and a serious
# review says so. The plan calls this out as the context residual a wordlist cannot solve
# (PLAN.md §6, "True context"), and over-blocking it would gut exactly the discussion this is for.
#
# `rape` is the hard one and it is deliberately here: "the rape scene in chapter 40 was handled
# badly" is among the most important things a reader can warn another reader about. A filter that
# eats content warnings makes the community less safe, not more.
PLOT_VOCABULARY = {
    "kill", "killer", "killing", "kills", "murder", "murderer", "death", "dead", "die", "died",
    "blood", "bloody", "gore", "gory", "violence", "violent", "demon", "demons", "devil",
    "slave", "slaves", "slavery", "master", "assassin", "assassins", "torture", "suicide",
    "corpse", "execution", "war", "weapon", "hell", "damn", "damned",
    "rape", "raped", "rapist", "incest", "abuse", "cannibal", "necromancer",
}

# Brand and site names. LDNOOBW was built to keep adult-site traffic out of search suggestions, so it
# carries a pile of trade names that mean nothing here and will never appear in a comment.
BRAND_NOISE = {
    "babeland", "bangbros", "bangbus", "cialis", "viagra", "brazzers", "youporn", "pornhub",
    "redtube", "xhamster", "xvideos", "camgirl", "camslut", "camwhore", "escort", "escorts",
    "milf", "santorum", "birdlock", "bunghole", "blumpkin",
}

# Drugs. Not profanity, and this is not a drug-policy filter - but the public lists are full of them
# because they were built to keep adult-site traffic out of search suggestions. `heroína` is also the
# Spanish for *heroine*, which is a word that comes up in manga discussion rather a lot.
DRUG_VOCABULARY = {
    "drogas", "droga", "heroina", "heroína", "cocaina", "cocaína", "marihuana", "marijuana",
    "cannabis", "crack", "meth", "methamphetamine", "lsd", "cocaine", "heroin", "opium", "opio",
    "drogue", "drogen", "droghe", "narkotik", "narkoba", "ganja",
}

# Ordinary words the audit caught firing on encyclopaedic prose. Each was measured, not guessed - see
# FilterAuditTest, which is how a list nobody here can read gets checked at all.
#
# Vietnamese is absent from this set on purpose: its false positives were caused by the normaliser
# stripping tone marks rather than by the list, and are fixed in FilterNormalizer instead.
MEASURED_FALSE_POSITIVES = {
    # es - `negro` is simply the colour black.
    "negro", "negra", "negros", "negras",
    # id - LDNOOBW V2's Indonesian list is the weakest of the fifteen and sweeps in common nouns.
    "ruang", "bola", "asing", "bau", "bikin", "hantam", "kasar", "kurang", "main", "masuk",
    "muka", "nakal", "pantat", "rusak", "tahi", "telur",
    # pt
    "mama", "mamas",
    # vi - all three are among the commonest words in the language. `có` is "to have", `phân` is
    # "to divide" (phân bố, to distribute), and `tinh` is the second half of `hành tinh`, planet.
    "có", "phân", "tinh", "co", "phan", "vành đai", "vànhđai",
    # en - `mong` inside `among`, `smut` inside `bismuth`, and `tit` inside half the dictionary. The
    # dictionary catches most of it; these are the ones not worth the risk.
    "mong", "tit", "tits",
}

DOMAIN_ALLOW = GENRE_VOCABULARY | PLOT_VOCABULARY | BRAND_NOISE | DRUG_VOCABULARY | MEASURED_FALSE_POSITIVES


# --------------------------------------------------------------------------------------------------
# 3. The severe tier, chosen by hand because the cost of being wrong is different in kind.
# --------------------------------------------------------------------------------------------------

# `severe` is the only global tier and the only one matched as a substring, so the plan's rule is
# absolute: a term with any innocent reading does not belong here.
#
# Two well-known slurs are deliberately *absent* for exactly that reason:
#
#   kike  - the ordinary Spanish nickname for Enrique. LATAM is one of this app's largest regions.
#   chink - an ordinary English noun ("a chink of light"). It sits in en-profanity instead, where
#           whole-token matching still blocks the standalone slur without the substring risk.
#
# Every term here that has an innocent superstring is listed in SEVERE_SUPERSTRINGS below, and the
# test suite fails if one of those is not in the dictionary.
SEVERE = {
    # Racial and ethnic slurs.
    "nigger", "niggers", "nigga", "niggas", "coon", "coons", "wetback", "wetbacks",
    "spic", "spics", "gook", "gooks", "paki", "pakis", "beaner", "beaners",
    "raghead", "towelhead", "zipperhead", "halfbreed",
    # Sexual-orientation and gender slurs.
    "faggot", "faggots", "fagot", "tranny", "trannies", "shemale", "dyke", "dykes",
    # Disability slurs.
    "retard", "retards", "retarded", "mongoloid",
    # Sexualised minors. Content, not genre: these are the words for the act.
    "pedophile", "paedophile", "pedophiles", "pedophilia", "paedophilia", "childporn",
    # Cyrillic. Written unfolded, because the normaliser leaves pure-Cyrillic text alone.
    "жид", "жиды", "ниггер", "пидорас", "педофил",
    # Other launch languages, restricted to the genuinely unambiguous.
    "negrata", "sudaca", "bougnoule", "czarnuch",
}

# Removed from SEVERE after testing, and worth recording so nobody puts them back.
#
# This tier is global and matches as a substring, so the plan's rule is absolute: a term with any
# innocent reading does not belong here. Applying that honestly is stricter than it first looks.
#
#   negro, negre, neger, crioulo  - `negro` is simply the colour black in Spanish, Portuguese and
#                                   Italian. As a global substring rule it blocks `el gato negro`.
#   kike                          - the ordinary Spanish nickname for Enrique. LATAM is one of this
#                                   app's largest regions.
#   chink                         - an ordinary English noun ("a chink of light"). Moved to
#                                   en-profanity, where whole-token matching still blocks the
#                                   standalone slur without the substring risk.
#   pedał                         - Polish for "pedal" as well as a slur.
#   нигер                         - Niger, the country.
#   чурка                         - also a block of wood.
#   zenci, kanake, spastic        - descriptive or mild in some registers; left to per-language lists
#                                   where whole-token matching applies.
#
# Anything in this comment that a moderator decides is worth blocking should go in that language's
# profanity list, not here.
AMBIGUOUS_NOT_SEVERE = {
    "negro", "negre", "neger", "crioulo", "kike", "chink", "pedał", "нигер", "чурка",
    "zenci", "kanake", "spastic",
}

# Whole-token blocking is safe for these where substring matching is not, so they stay in the
# language lists they belong to.
EXTRA_SEVERE_AS_PROFANITY = {
    "en": ["chink", "chinks", "spastic"],
    "es": ["negrata", "sudaca"],
    "pl": ["pedał", "pedaly"],
    "tr": ["zenci"],
    "de": ["kanake", "neger"],
    "ru": ["чурка", "хохол", "москаль"],
}

# Innocent words that *contain* a severe term. Substring matching finds them; the containing-word
# check is the only thing that saves them, and it can only save what the dictionary knows.
SEVERE_SUPERSTRINGS = {
    "coon": ["raccoon", "raccoons", "cocoon", "cocoons", "tycoon", "tycoons"],
    "spic": ["suspicion", "suspicions", "suspicious", "despicable", "auspicious", "conspicuous",
             "spice", "spices", "spicy", "perspicacious"],
    "paki": ["pakistan", "pakistani", "pakistanis"],
    "retard": ["retardant", "retardants", "retardation"],
    "dyke": ["dykes"],  # a dyke is also an embankment; the plural is the same word
    "gook": [],
    "fagot": ["fagoting"],
}


# --------------------------------------------------------------------------------------------------
# 4. Terms the sources miss.
# --------------------------------------------------------------------------------------------------

# The original list is thin outside English, and a few of the commonest swears in each language are
# simply not in it. These are the words people actually type.
EXTRA_PROFANITY = {
    "en": ["ass", "arse", "wanker", "twat", "prick", "bellend", "knobhead", "douchebag"],
    "es": ["gilipollas", "cabron", "coño", "joder", "puta", "puto", "mierda", "pendejo",
           "chinga", "chingar", "verga", "culero", "boludo", "pelotudo", "pajero", "maricon"],
    "pt": ["caralho", "foda", "foder", "merda", "porra", "puta", "puto", "cuzao", "buceta",
           "viado", "corno", "bosta", "otario", "arrombado"],
    "fr": ["putain", "merde", "connard", "connasse", "salope", "enculé", "enculer", "batard",
           "bordel", "foutre", "pute", "couille", "chiant"],
    "de": ["scheisse", "arschloch", "fotze", "wichser", "hurensohn", "schlampe", "verpiss",
           "fick", "ficken", "miststück", "drecksau"],
    "it": ["cazzo", "stronzo", "stronza", "merda", "troia", "puttana", "coglione", "vaffanculo",
           "figa", "bastardo", "porcodio"],
    "pl": ["kurwa", "chuj", "pierdol", "pierdolić", "jebać", "jebany", "skurwysyn", "spierdalaj",
           "cipa", "dupek", "gówno", "zjeb"],
    "ru": ["хуй", "хуя", "пизда", "ебать", "ебал", "блядь", "бля", "сука", "мудак", "гандон",
           "долбоёб", "пиздец", "нахуй", "охуеть", "мразь", "говно"],
    "tr": ["amk", "amına", "sikerim", "siktir", "orospu", "piç", "yarrak", "göt", "salak",
           "aptal", "gerizekalı"],
    "id": ["anjing", "bangsat", "kontol", "memek", "ngentot", "bajingan", "goblok", "tolol",
           "keparat", "brengsek"],
    "vi": ["địt", "lồn", "cặc", "đụ", "đéo", "chó", "ngu", "khốn", "đĩ", "cứt"],
    "ja": ["くそ", "クソ", "ちんこ", "まんこ", "きちがい", "しね", "ばか", "あほ", "やりまん"],
    "zh": ["傻逼", "操你妈", "草泥马", "婊子", "王八蛋", "他妈的", "去死", "白痴", "贱人"],
    "ko": ["씨발", "시발", "개새끼", "병신", "지랄", "좆", "년", "존나", "미친놈"],
    "th": ["เหี้ย", "สัส", "ควย", "หี", "แม่ง", "ไอ้เหี้ย", "เย็ด"],
}

# Borderline and drama-adjacent: publishes, flags, and goes to the top of the panel's queue. This is
# where ship wars live, and almost none of it is profanity - which is the point.
WATCH = {
    "en": ["cringe", "trash", "garbage", "cope", "seethe", "overrated",
           "overhyped", "delusional", "braindead", "shill", "chud"],
    "es": ["basura", "sobrevalorado", "ridiculo", "patetico"],
    "pt": ["lixo", "superestimado", "patetico", "ridiculo"],
    "fr": ["nul", "surcote", "pathetique", "ridicule"],
    "de": ["müll", "überbewertet", "erbärmlich", "lächerlich"],
    "it": ["spazzatura", "sopravvalutato", "patetico", "ridicolo"],
}


def fetch(lang: str) -> None:
    os.makedirs(CACHE, exist_ok=True)
    for name, url in (("v1", V1.format(lang=lang)), ("v2", V2.format(lang=lang))):
        target = os.path.join(CACHE, "%s-%s.txt" % (name, lang))
        try:
            with urllib.request.urlopen(url, timeout=30) as response:
                data = response.read().decode("utf-8", "replace")
            with open(target, "w", encoding="utf-8", newline="\n") as handle:
                handle.write(data)
        except Exception as error:  # noqa: BLE001 - a missing language is normal, not fatal
            print("  %s/%s unavailable (%s)" % (name, lang, error.__class__.__name__))


def load(name: str, lang: str) -> list[str]:
    path = os.path.join(CACHE, "%s-%s.txt" % (name, lang))
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8") as handle:
        return [line.strip() for line in handle if line.strip()]


def curate(lang: str, report: list[str]) -> list[str]:
    source = "v2" if lang in FROM_V2_ONLY else "v1"
    raw = load(source, lang) or load("v2", lang)
    dropped: dict[str, int] = {}
    kept: set[str] = set()

    for term in raw:
        term = unicodedata.normalize("NFC", term).strip().lower()
        usable, why = is_structurally_usable(term, lang)
        if not usable:
            dropped[why] = dropped.get(why, 0) + 1
            continue
        if term in DOMAIN_ALLOW or any(word in DOMAIN_ALLOW for word in term.split()):
            dropped["domain"] = dropped.get("domain", 0) + 1
            continue
        if term in SEVERE:
            # Global already; listing it per-language as well would double-count every block.
            dropped["severe"] = dropped.get("severe", 0) + 1
            continue
        # Stored joined: `son of a bitch` becomes `sonofabitch`, which is exactly what the glue
        # window assembles from those four tokens.
        kept.add("".join(term.split()))

    for term in EXTRA_PROFANITY.get(lang, []) + EXTRA_SEVERE_AS_PROFANITY.get(lang, []):
        term = unicodedata.normalize("NFC", term).strip().lower()
        if term not in SEVERE and term not in DOMAIN_ALLOW:
            kept.add("".join(term.split()))

    summary = ", ".join("%s %d" % (why, count) for why, count in sorted(dropped.items()))
    report.append("  %-3s %5d in -> %4d kept   (dropped: %s)" % (lang, len(raw), len(kept), summary))
    return sorted(kept)


HEADER = """\
# {tier} ({lang}){scope}
#
# Generated by tools/curate_wordlists.py from {source}, then curated - see that script for every
# judgement that produced this file. Edit the script, not this file: the panel is where live tuning
# happens, and a hand-edit here is lost on the next regeneration.
#
# Terms are written in ordinary spelling. The normaliser folds case, accents, repeats, homoglyphs and
# the unambiguous leetspeak, so `fuuuck`, `f.u.c.k` and `fuсk` all match `fuck` without being listed.
"""


def write(path: str, header: str, terms: list[str]) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(header)
        handle.write("\n")
        handle.write("\n".join(terms))
        handle.write("\n")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fetch", action="store_true", help="download the sources first")
    args = parser.parse_args()

    if args.fetch:
        print("Fetching sources...")
        for lang in LANGUAGES:
            fetch(lang)

    if not os.path.isdir(CACHE):
        print("No cached sources. Run with --fetch first.", file=sys.stderr)
        return 1

    report: list[str] = []
    print("Curating:")
    for lang in LANGUAGES:
        terms = curate(lang, report)
        scope = "" if lang not in BOUNDARYLESS else "\n# No word boundaries in this script, so these match as substrings."
        write(
            os.path.join(SEED, "%s-profanity.txt" % lang),
            HEADER.format(
                tier="profanity",
                lang=lang,
                scope=scope,
                source="LDNOOBW V2" if lang in FROM_V2_ONLY else "LDNOOBW",
            ),
            terms,
        )
    print("\n".join(report))

    for lang, terms in WATCH.items():
        write(
            os.path.join(SEED, "%s-watch.txt" % lang),
            HEADER.format(tier="watch", lang=lang, scope="", source="hand-written"),
            sorted(set(terms)),
        )

    severe_header = HEADER.format(tier="severe", lang="global", scope="", source="hand-picked")
    severe_header += (
        "#\n"
        "# Applies in EVERY language and matches as a SUBSTRING, so a term with any innocent reading\n"
        "# does not belong here. `kike` (a Spanish nickname) and `chink` (an English noun) are\n"
        "# deliberately absent for that reason; see the script.\n"
    )
    write(os.path.join(SEED, "severe.txt"), severe_header, sorted(SEVERE))
    print("\n  severe (global): %d terms" % len(SEVERE))

    # The dictionary entries that keep the substring tier survivable.
    extra_dictionary = sorted({w for words in SEVERE_SUPERSTRINGS.values() for w in words})
    print("  dictionary guards for severe terms: %d" % len(extra_dictionary))
    with open(os.path.join(WORDS, "severe-guards.txt"), "w", encoding="utf-8", newline="\n") as handle:
        handle.write(
            "# Innocent words that contain a `severe` term.\n"
            "#\n"
            "# `severe` matches as a substring, so each of these would be blocked if the dictionary\n"
            "# did not know it. Generated from SEVERE_SUPERSTRINGS in tools/curate_wordlists.py, and\n"
            "# asserted by WordFilterTest - adding a severe term without its superstrings fails the\n"
            "# build rather than quietly blocking `raccoon`.\n\n"
        )
        handle.write("\n".join(extra_dictionary))
        handle.write("\n")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
