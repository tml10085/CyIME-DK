"""Build Danish next-word bigram table app/src/main/assets/danish_bigrams.txt.

Sources & licenses:
- Bigram counts: Universal Dependencies Danish Dependency Treebank (UD_Danish-DDT)
  train/dev/test CoNLL-U files, CC-BY-SA 4.0:
  https://github.com/UniversalDependencies/UD_Danish-DDT
- Vocabulary filter & canonical casing: tools/wordlists/cyime-words-da-v1.txt
  (FrequencyWords da_50k OpenSubtitles 2018 CC-BY-SA 4.0 + LibreOffice Danish dictionary
  Stavekontrolden GPL-2.0/LGPL-2.1/MPL-1.1).
- Plus curated high-frequency everyday Danish conversational collocations.

Output format: one line per leading word:
    <w1_lowercase> <next1> <next2> ... (up to 12 most likely continuations)

Usage:
    python tools/wordlists/build_danish_bigrams.py
"""
import collections
import pathlib
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
WORDLIST = ROOT / "tools/wordlists/cyime-words-da-v1.txt"
OUTPUT = ROOT / "app/src/main/assets/danish_bigrams.txt"
UD_BASE = (
    "https://raw.githubusercontent.com/UniversalDependencies/UD_Danish-DDT/"
    "24b7f825b3d85d18c713c4f67121134b2cb369e4/da_ddt-ud-{split}.conllu"
)
SPLITS = ("train", "dev", "test")
MAX_NEXT = 12

# High-frequency everyday Danish conversational collocations to supplement treebank prose.
CURATED_COLLOCATIONS: dict[str, list[str]] = {
    "jeg": ["er", "har", "kan", "vil", "skal", "tror", "synes", "ved", "ikke", "kommer", "glæder", "håber"],
    "du": ["er", "har", "kan", "skal", "vil", "må", "ikke", "ved", "gør", "kommer"],
    "han": ["er", "har", "var", "ikke", "kan", "vil", "skal", "sagde", "kom", "blev"],
    "hun": ["er", "har", "var", "ikke", "kan", "vil", "skal", "sagde", "kom", "blev"],
    "vi": ["har", "er", "skal", "kan", "vil", "ses", "må", "ikke", "glæder", "kommer"],
    "de": ["er", "har", "var", "kan", "ikke", "skal", "vil", "fleste", "andre", "to"],
    "det": ["er", "var", "kan", "har", "skal", "vil", "ikke", "går", "bliver", "lyder"],
    "den": ["er", "har", "var", "kan", "skal", "første", "anden", "nye", "store", "samme"],
    "der": ["er", "var", "ikke", "har", "kan", "skal", "kommer", "sker", "står", "går"],
    "er": ["det", "du", "ikke", "der", "en", "et", "i", "på", "meget", "godt"],
    "var": ["det", "der", "en", "ikke", "et", "i", "meget", "på", "så", "helt"],
    "har": ["du", "jeg", "ikke", "været", "en", "et", "fået", "vi", "det", "godt"],
    "kan": ["du", "jeg", "ikke", "vi", "man", "det", "være", "få", "se", "godt"],
    "skal": ["vi", "du", "jeg", "være", "have", "nok", "ikke", "til", "gøre", "ud"],
    "vil": ["jeg", "du", "gerne", "have", "være", "ikke", "det", "vi", "blive", "sige"],
    "må": ["jeg", "du", "vi", "gerne", "ikke", "ske", "være", "have", "indrømme", "sige"],
    "ikke": ["er", "har", "kan", "noget", "mere", "bare", "så", "være", "det", "endnu"],
    "hvad": ["er", "skal", "med", "synes", "laver", "hedder", "siger", "tænker", "har", "kan"],
    "hvordan": ["går", "har", "er", "det", "kan", "man", "ser", "kommer", "fungerer"],
    "hvor": ["er", "bor", "mange", "langt", "gammel", "henne", "du", "jeg", "skal", "kan"],
    "hvornår": ["kommer", "er", "skal", "har", "går", "starter", "slutter", "kan", "vi"],
    "hvorfor": ["er", "har", "kan", "ikke", "skal", "det", "du", "sagde", "gjorde"],
    "hvem": ["er", "har", "skal", "kan", "var", "der", "ved"],
    "hej": ["med", "alle", "og", "du", "hvordan", "jeg", "håber", "skal"],
    "tak": ["for", "skal", "i", "fordi", "hjælpen", "igår", "sidst"],
    "mange": ["tak", "mennesker", "år", "af", "gange", "andre", "penge", "forskellige"],
    "tusind": ["tak", "fryd", "år", "kroner"],
    "velbekomme": ["og", "det", "håber"],
    "undskyld": ["jeg", "at", "det", "mig", "for", "forstyrrelsen"],
    "god": ["morgen", "dag", "aften", "weekend", "nat", "fornøjelse", "idé", "tid", "tur", "arbejdslyst"],
    "godt": ["nytår", "at", "gået", "klaret", "nok", "ud", "kan", "være", "og", "humør"],
    "gode": ["venner", "råd", "idéer", "resultater", "muligheder", "grunde"],
    "glædelig": ["jul", "påske", "overraskelse", "nyhed"],
    "tillykke": ["med", "fødselsdagen", "til", "og"],
    "ha": ["en", "det", "et"],
    "hav": ["en", "det", "et"],
    "i": ["dag", "går", "morgen", "aften", "Danmark", "København", "stedet", "en", "den", "det", "år"],
    "på": ["arbejde", "vej", "grund", "samme", "tid", "gensyn", "forhånd", "en", "det", "den"],
    "til": ["at", "dig", "mig", "os", "Danmark", "København", "sidst", "gengæld", "lykke", "den"],
    "for": ["at", "dig", "mig", "eksempel", "det", "hjælpen", "meget", "mange", "langt", "sent"],
    "med": ["dig", "mig", "os", "det", "venlig", "hilsen", "glæde", "en", "et", "at"],
    "venlig": ["hilsen", "og", "modtagelse"],
    "bedste": ["hilsner", "måde", "venner", "for"],
    "kære": ["alle", "ven", "dig", "familie"],
    "rigtig": ["god", "godt", "meget", "mange", "dejlig", "sjovt", "glad"],
    "meget": ["mere", "gerne", "godt", "glad", "tak", "større", "bedre", "vigtigt", "forskelligt"],
    "gerne": ["have", "høre", "vide", "vil", "komme", "se", "hjælpe", "være"],
    "glæder": ["mig", "os", "sig", "til"],
    "håber": ["du", "i", "det", "at", "vi", "alt"],
    "synes": ["jeg", "du", "det", "at", "ikke"],
    "tror": ["jeg", "du", "ikke", "det", "at", "han", "vi"],
    "ved": ["ikke", "du", "jeg", "hvad", "om", "det", "godt", "at"],
}


def main() -> None:
    words = WORDLIST.read_text(encoding="utf-8").splitlines()
    canonical = {w.lower(): w for w in words if w.strip()}
    allowed = set(canonical.keys())

    counts: dict[str, collections.Counter[str]] = collections.defaultdict(collections.Counter)
    for split in SPLITS:
        text = urllib.request.urlopen(UD_BASE.format(split=split)).read().decode("utf-8")
        prev: str | None = None
        for line in text.splitlines():
            if not line or line.startswith("#"):
                prev = None
                continue
            cols = line.split("\t")
            if len(cols) >= 2 and "-" not in cols[0] and "." not in cols[0]:
                w = cols[1].lower()
                if w in allowed:
                    if prev is not None and w != prev:
                        counts[prev][w] += 1
                    prev = w
                else:
                    prev = None

    for w1, next_list in CURATED_COLLOCATIONS.items():
        if w1 not in allowed:
            continue
        for rank, raw_w2 in enumerate(next_list):
            w2 = raw_w2.lower()
            if w2 in allowed and w2 != w1:
                counts[w1][w2] += 500 - rank * 30

    rows: list[str] = []
    for w1 in sorted(counts.keys()):
        top = [canonical[w2] for w2, _ in counts[w1].most_common(MAX_NEXT) if w2 in canonical and w2 != w1]
        if top:
            rows.append(f"{w1} {' '.join(top)}")

    OUTPUT.write_text("\n".join(rows) + "\n", encoding="utf-8", newline="\n")
    print(f"{len(rows)} bigram rows -> {OUTPUT} ({OUTPUT.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
