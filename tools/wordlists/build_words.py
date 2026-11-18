"""Build a completion word list for a letter-by-letter language (es, de, fr, it, ru, da) or English (en).

Frequencies come from FrequencyWords (OpenSubtitles 2018, pinned commit). Subtitles carry names,
English and typos, so only words the LibreOffice spelling dictionary accepts are kept;
a word it only accepts capitalized (Madrid, Haus) is stored capitalized.

    pip install spylls
    npm pack dictionary-de@3.0.0 && tar xzf dictionary-de-3.0.0.tgz     # es: dictionary-es@4.0.0, it: dictionary-it@2.0.0
    python tools/wordlists/build_words.py de package/index tools/wordlists/cyime-words-de-v1.txt

English reads the full frequency list (rarer words such as stoichiometry) and keeps up to 100 000 words;
its dictionary is SCOWL's larger en_US (oxalic, stoichiometry; license in its README), whose stems spelled
with inner capitals keep that spelling (pH, DNA, iPhone):

    curl -LO https://downloads.sourceforge.net/project/wordlist/speller/2020.12.07/hunspell-en_US-large-2020.12.07.zip
    unzip hunspell-en_US-large-2020.12.07.zip
    python tools/wordlists/build_words.py en en_US-large tools/wordlists/cyime-words-en-v1.txt

Output: one word per line, most frequent first. The lists ship as market downloads, not in the APK:
upload the file unchanged to the GitHub Release `models` and ModelScope Cyletix/cyime-model, then put its
sha256 and size into BuiltinModelCatalog (WordListCatalogTest checks they match this folder).
"""
import re
import sys
import urllib.request

from spylls.hunspell import Dictionary

SOURCE = ("https://raw.githubusercontent.com/hermitdave/FrequencyWords/"
          "525f9b560de45753a5ea01069454e72e9aa541c6/content/2018/{0}/{0}_{1}.txt")
LIMIT = 40000
# English: the full list and a larger limit, so rarer words still complete.
FULL = {"en": 100000}
LETTERS = {
    "en": "a-z",
    "es": "a-záéíóúüñ",
    "de": "a-zäöüß",
    "fr": "a-zàâæçéèêëîïôœùûüÿ",
    "it": "a-zàèéìíîòóùú",
    "ru": "а-яё",
    "da": "a-zåæøéäöüáóú",
}
# Single letters that are words; the rest are subtitle noise.
SINGLE = {
    "en": {"a", "i"},
    "es": {"a", "y", "o", "e", "u"},
    "de": set(),
    "fr": {"a", "à", "y"},
    "it": {"a", "e", "è", "i", "o"},
    "ru": {"и", "в", "с", "к", "у", "о", "а", "я"},
    "da": {"i", "å", "ø"},
}


def main(language: str, dictionary_base: str, output: str) -> None:
    pattern = re.compile(f"^[{LETTERS[language]}]+$")
    speller = Dictionary.from_files(dictionary_base)
    limit = FULL.get(language, LIMIT)
    source = SOURCE.format(language, "full" if language in FULL else "50k")
    lines = urllib.request.urlopen(source).read().decode("utf-8").splitlines()
    # A word the dictionary only knows with capitals after the first letter (pH, DNA, iPhone) keeps that spelling.
    with open(dictionary_base + ".dic", encoding="utf-8", errors="ignore") as dic:
        stems = [entry.split("/")[0].strip() for entry in dic.readlines()[1:]]
    mixed = {stem.lower(): stem for stem in stems
             if len(stem) > 1 and stem.isalpha() and any(c.isupper() for c in stem[1:])}
    seen, words = set(), []
    for line in lines:
        word, _count = line.split()
        if not pattern.match(word) or (len(word) == 1 and word not in SINGLE[language]):
            continue
        form = word if speller.lookup(word) else mixed.get(word) or (
            word.capitalize() if speller.lookup(word.capitalize()) else None)
        if form == "i":
            form = "I"
        if form is None or form in seen:
            continue
        seen.add(form)
        words.append(form)
        if len(words) >= limit:
            break
    with open(output, "w", encoding="utf-8", newline="\n") as file:
        file.write("\n".join(words) + "\n")
    print(f"{len(words)} words -> {output}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3])
