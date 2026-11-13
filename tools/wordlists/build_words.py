"""Build a completion word list for a letter-by-letter language (es, de, fr, it, ru, da).

Frequencies come from FrequencyWords (OpenSubtitles 2018, pinned commit). Subtitles carry names,
English and typos, so only words the LibreOffice spelling dictionary accepts are kept;
a word it only accepts capitalized (Madrid, Haus) is stored capitalized.

    pip install spylls
    npm pack dictionary-de@3.0.0 && tar xzf dictionary-de-3.0.0.tgz     # es: dictionary-es@4.0.0, it: dictionary-it@2.0.0
    python tools/wordlists/build_words.py de package/index tools/wordlists/cyime-words-de-v1.txt

Output: one word per line, most frequent first. The lists ship as market downloads, not in the APK:
upload the file unchanged to the GitHub Release `models` and ModelScope Cyletix/cyime-model, then put its
sha256 and size into BuiltinModelCatalog (WordListCatalogTest checks they match this folder).
"""
import re
import sys
import urllib.request

from spylls.hunspell import Dictionary

SOURCE = ("https://raw.githubusercontent.com/hermitdave/FrequencyWords/"
          "525f9b560de45753a5ea01069454e72e9aa541c6/content/2018/{0}/{0}_50k.txt")
LIMIT = 40000
LETTERS = {
    "es": "a-záéíóúüñ",
    "de": "a-zäöüß",
    "fr": "a-zàâæçéèêëîïôœùûüÿ",
    "it": "a-zàèéìíîòóùú",
    "ru": "а-яё",
    "da": "a-zåæøéäöüáóú",
}
# Single letters that are words; the rest are subtitle noise.
SINGLE = {
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
    lines = urllib.request.urlopen(SOURCE.format(language)).read().decode("utf-8").splitlines()
    seen, words = set(), []
    for line in lines:
        word, _count = line.split()
        if not pattern.match(word) or (len(word) == 1 and word not in SINGLE[language]):
            continue
        form = word if speller.lookup(word) else word.capitalize() if speller.lookup(word.capitalize()) else None
        if form is None or form in seen:
            continue
        seen.add(form)
        words.append(form)
        if len(words) >= LIMIT:
            break
    with open(output, "w", encoding="utf-8", newline="\n") as file:
        file.write("\n".join(words) + "\n")
    print(f"{len(words)} words -> {output}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3])
