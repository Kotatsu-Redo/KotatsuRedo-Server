#!/usr/bin/env python3
"""Downloads a corpus of ordinary prose, for auditing the word lists against it.

    python tools/fetch_audit_corpus.py
    FILTER_AUDIT_CORPUS=tools/.corpus ./gradlew test --tests '*FilterAuditTest*'

This is the closest thing we have to the native-speaker pass the lists want. Nobody on this side
reads Thai or Vietnamese, but a rule that fires inside an encyclopaedia article is a rule that will
fire inside a comment, and that is the entire false-positive class worth chasing: a word list is
dangerous exactly when one of its entries is also an ordinary word.

It works. Run against Vietnamese it put the false-positive rate at **31.6%** - one sentence in three -
and named the cause: `các`, the plural marker and one of the commonest words in the language, folded
onto a blocklist entry once the normaliser stripped its tone marks. That produced a fix to the
normaliser rather than to the list, which no amount of staring at the word list would have found.

Wikipedia is the source because it is broad, dull, free of profanity, and available in every launch
language. It is also *adversarial* in a useful way: it is full of Latin binomials and foreign proper
nouns, which is where the long tail of substring collisions lives. A rate measured here is a
pessimistic bound on what a comment section will see.
"""
from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TARGET = os.path.join(ROOT, "tools", ".corpus")

LANGUAGES = ["en", "es", "pt", "fr", "de", "it", "pl", "ru", "tr", "id", "vi", "ja", "zh", "ko", "th"]

# Wikipedia asks for a descriptive agent and will rate-limit an anonymous flood.
AGENT = {"User-Agent": "kotatsuredo-filter-audit/1.0 (https://github.com/Kotatsu-Redo)"}
TARGET_CHARS = 40_000


def fetch(lang: str) -> str:
    pages: list[str] = []
    for _ in range(12):
        url = (
            "https://%s.wikipedia.org/w/api.php?action=query&generator=random"
            "&grnnamespace=0&grnlimit=25&prop=extracts&explaintext=1"
            "&format=json&formatversion=2" % lang
        )
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=AGENT), timeout=40) as response:
                data = json.load(response)
            for page in data.get("query", {}).get("pages", []):
                if page.get("extract"):
                    pages.append(page["extract"])
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError):
            time.sleep(5)
            continue
        if sum(len(p) for p in pages) >= TARGET_CHARS:
            break
        time.sleep(2.5)
    return "\n\n".join(pages)


def main() -> int:
    os.makedirs(TARGET, exist_ok=True)
    for lang in LANGUAGES:
        body = fetch(lang)
        path = os.path.join(TARGET, "%s.txt" % lang)
        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(body)
        print("%-3s %7d chars" % (lang, len(body)))
    print("\nNow run:\n  FILTER_AUDIT_CORPUS=tools/.corpus ./gradlew test --tests '*FilterAuditTest*' -i")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
