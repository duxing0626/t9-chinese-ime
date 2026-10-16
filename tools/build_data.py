#!/usr/bin/env python3
"""Build the IME lookup tables (app/assets/*.txt) from the open-source dictionaries in data/raw.

Sources:
  essay-cantonese.txt, jyut6ping3.*.dict.yaml  -- rime-cantonese (CC BY 4.0)
  Cangjie5_HK.txt                               -- Jackchows/Cangjie5 (MIT)
  stroke.dict.yaml                              -- rime-stroke (LGPL-3.0)

Output formats (UTF-8, tab separated, sorted by the first column):
  cangjie.txt / quick.txt : digits  code  char  score
  stroke.txt              : digits  char  score
  jyutping.txt            : digits  syllable-lengths  reading  text  lazy-flags  score
  jyutping_abbr.txt       : initials-digits  reading  text  score
  assoc.txt               : char  continuation1 continuation2 ...
  emoji_words.txt         : keyword  emoji1 emoji2 ...   (CLDR annotations, Unicode license)
  emoji_groups.txt        : group-name  emoji1 emoji2 ...
"""
import os
import re
import sys
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW = os.path.join(ROOT, "data", "raw")
OUT = os.path.join(ROOT, "app", "assets")

T9 = {}
for d, letters in {"2": "abc", "3": "def", "4": "ghi", "5": "jkl", "6": "mno",
                   "7": "pqrs", "8": "tuv", "9": "wxyz"}.items():
    for ch in letters:
        T9[ch] = d


def t9(s):
    return "".join(T9[c] for c in s)


def raw(name):
    return os.path.join(RAW, name)


def rime_body(path):
    """Yield the tab-separated rows of a Rime dict.yaml after the '...' header terminator."""
    started = False
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not started:
                started = line == "..."
                continue
            if not line or line.startswith("#"):
                continue
            yield line.split("\t")


def load_essay():
    freq = {}
    with open(raw("essay-cantonese.txt"), encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) == 2 and parts[1].isdigit():
                freq[parts[0]] = int(parts[1])
    return freq


def displayable(ch, freq):
    """Keep characters an Android 5 phone is likely to render: BMP and (Big5-HKSCS or seen in the corpus)."""
    if len(ch) != 1 or ord(ch) > 0xFFFF:
        return False
    if freq.get(ch, 0) > 0:
        return True
    try:
        ch.encode("big5hkscs")
        return True
    except UnicodeEncodeError:
        return False


def write(name, rows):
    rows.sort(key=lambda r: (r[0], -int(r[-1])))
    with open(os.path.join(OUT, name), "w", encoding="utf-8") as f:
        for r in rows:
            f.write("\t".join(str(x) for x in r) + "\n")
    print(f"{name}: {len(rows)} rows", file=sys.stderr)


def build_cangjie(freq):
    seen, quick_seen = set(), set()
    cj, qk = [], []
    with open(raw("Cangjie5_HK.txt"), encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 2:
                continue
            ch, code = parts[0], parts[1]
            if not re.fullmatch(r"[a-z]{1,5}", code) or not displayable(ch, freq):
                continue
            score = freq.get(ch, 0)
            if (code, ch) not in seen:
                seen.add((code, ch))
                cj.append((t9(code), code, ch, score))
            q = code if len(code) <= 2 else code[0] + code[-1]
            if (q, ch) not in quick_seen:
                quick_seen.add((q, ch))
                qk.append((t9(q), q, ch, score))
    write("cangjie.txt", cj)
    write("quick.txt", qk)


def build_stroke(freq):
    m = {"h": "1", "s": "2", "p": "3", "n": "4", "z": "5"}
    rows, seen = [], set()
    for parts in rime_body(raw("stroke.dict.yaml")):
        if len(parts) < 2:
            continue
        ch, code = parts[0], parts[1]
        if not re.fullmatch(r"[hspnz]+", code) or not displayable(ch, freq):
            continue
        digits = "".join(m[c] for c in code)
        if (digits, ch) in seen:
            continue
        seen.add((digits, ch))
        rows.append((digits, ch, freq.get(ch, 0)))
    write("stroke.txt", rows)


def build_jyutping(freq):
    best = {}  # (text, toneless reading) -> score

    def add(text, reading, score):
        key = (text, reading)
        if score > best.get(key, -1):
            best[key] = score

    for parts in rime_body(raw("jyut6ping3.chars.dict.yaml")):
        if len(parts) < 2:
            continue
        ch, reading = parts[0], parts[1]
        syl = re.sub(r"[1-6]", "", reading)
        if not re.fullmatch(r"[a-z]+", syl) or not displayable(ch, freq):
            continue
        pct = 100.0
        if len(parts) >= 3 and parts[2].endswith("%"):
            pct = max(float(parts[2][:-1]), 0.5)
        add(ch, syl, int(freq.get(ch, 0) * pct / 100))

    # Most likely toneless reading per character, used to spell corpus words missing from the dictionaries.
    char_readings = defaultdict(dict)
    for (text, reading), score in best.items():
        char_readings[text][reading] = score

    def add_word(word, syls):
        if len(word) < 2 or len(syls) != len(word) or not all(re.fullmatch(r"[a-z]+", s) for s in syls):
            return False
        if not all(displayable(c, freq) for c in word):
            return False
        add(word, " ".join(syls), freq.get(word, 0))
        return True

    known = set()
    for parts in rime_body(raw("jyut6ping3.words.dict.yaml")):
        if len(parts) >= 2 and add_word(parts[0], [re.sub(r"[1-6]", "", s) for s in parts[1].split(" ")]):
            known.add(parts[0])
    for parts in rime_body(raw("jyut6ping3.phrase.dict.yaml")):
        if len(parts) >= 2 and len(parts[0]) <= 4 and freq.get(parts[0], 0) > 0:
            if add_word(parts[0], [re.sub(r"[1-6]", "", s) for s in parts[1].split(" ")]):
                known.add(parts[0])

    composed = 0
    for word, n in sorted(freq.items(), key=lambda x: -x[1]):
        if n < 1500 or not 2 <= len(word) <= 4 or word in known:
            continue
        syls = []
        for c in word:
            readings = char_readings.get(c)
            if not readings:
                break
            ranked = sorted(readings.items(), key=lambda x: -x[1])
            # skip characters whose pronunciation is genuinely ambiguous
            if len(ranked) > 1 and ranked[1][1] * 3 > ranked[0][1]:
                break
            syls.append(ranked[0][0])
        else:
            if add_word(word, syls):
                composed += 1
    print(f"composed corpus words: {composed}", file=sys.stderr)

    rows, lazy_rows, abbr_rows = [], 0, []
    for (text, reading), score in best.items():
        syls = reading.split(" ")
        rows.append(jyutping_row(syls, reading, text, 0, score))
        for mask in range(1, 1 << len(LAZY_RULES)):
            variant = lazy_variant(syls, mask)
            if variant:
                rows.append(jyutping_row(variant, reading, text, mask, score))
                lazy_rows += 1
        if 2 <= len(syls) <= 6 and score > 0:
            abbr_rows.append((t9("".join(x[0] for x in syls)), reading, text, score))
    print(f"lazy-sound rows: {lazy_rows}", file=sys.stderr)
    write("jyutping.txt", rows)
    write("jyutping_abbr.txt", abbr_rows)


def jyutping_row(syls, reading, text, flags, score):
    lengths = "".join(str(len(s)) for s in syls)
    return (t9("".join(syls)), lengths, reading, text, flags, score)


# Lazy pronunciations (懶音), one bit each: n- -> l-, ng- dropped (ng -> m), gwo/kwo -> go/ko.
def _lazy_nl(s):
    return "l" + s[1:] if s.startswith("n") and not s.startswith("ng") else s


def _lazy_ng(s):
    if s == "ng":
        return "m"
    return s[2:] if s.startswith("ng") else s


def _lazy_gwo(s):
    return s[0] + s[2:] if s.startswith(("gwo", "kwo")) else s


LAZY_RULES = [_lazy_nl, _lazy_ng, _lazy_gwo]


def lazy_variant(syls, mask):
    """Spelling with every rule in mask applied, or None if some rule in mask changes nothing."""
    out = list(syls)
    for bit, rule in enumerate(LAZY_RULES):
        if mask & (1 << bit):
            changed = [rule(x) for x in out]
            if changed == out:
                return None
            out = changed
    return out


def build_assoc(freq):
    by_first = defaultdict(list)
    for w, n in freq.items():
        if 2 <= len(w) <= 4 and n > 0 and all(displayable(c, freq) for c in w):
            by_first[w[0]].append((n, w[1:]))
    rows = []
    for ch, conts in by_first.items():
        conts.sort(key=lambda x: -x[0])
        rows.append((ch, " ".join(c for _, c in conts[:18])))
    rows.sort()
    with open(os.path.join(OUT, "assoc.txt"), "w", encoding="utf-8") as f:
        for r in rows:
            f.write("\t".join(r) + "\n")
    print(f"assoc.txt: {len(rows)} rows", file=sys.stderr)


def emoji_allowed(version, cps):
    """Emoji an Android 5.0 phone can draw: Unicode 6.0 emoji plus the faces added in 6.1."""
    if version == "E0.6":
        return True
    return version == "E1.0" and len(cps) == 1 and cps[0] in UNICODE_61_FACES


UNICODE_61_FACES = {0x1F600, 0x1F617, 0x1F619, 0x1F61B, 0x1F61F, 0x1F626, 0x1F627, 0x1F62C,
                    0x1F62E, 0x1F62F, 0x1F634, 0x1F636}
EMOJI_GROUPS = {"Smileys & Emotion": "表情", "People & Body": "人物", "Animals & Nature": "動植物",
                "Food & Drink": "食物", "Travel & Places": "地方", "Activities": "活動",
                "Objects": "物件", "Symbols": "符號", "Flags": "旗幟"}


# Everyday Hong Kong words that CLDR's keywords miss; listed emoji come first.
CURATED_EMOJI = {
    "開心": "😄 😊 😁 😆", "高興": "😄 😊", "笑": "😂 😄 😆", "哈哈": "😂 😆 😄", "好笑": "😂 😆",
    "喊": "😭 😢", "傷心": "😢 😭 💔", "嬲": "😠 😡 💢", "激氣": "😤 😡", "驚": "😱 😨 😰",
    "攰": "😩 😫 😪", "瞓覺": "😴 💤", "瞓": "😴 💤", "晚安": "😴 🌙 💤", "早晨": "☀️ 😊 🌞",
    "愛": "❤️ 😍 😘 💕", "鍾意": "😍 ❤️ 👍", "錫": "😘 💋",
    "讚": "👍 👏", "唔得": "🙅 ❌",
    "多謝": "🙏 😊", "唔該": "🙏", "對唔住": "🙇 🙏", "拜託": "🙏", "加油": "💪 🔥",
    "生日": "🎂 🎉 🎁", "生日快樂": "🎂 🎉 🎁", "恭喜": "🎉 👏 🎊", "新年": "🧧 🎉 🎆", "聖誕": "🎄 🎅 🎁",
    "食飯": "🍚 🍴", "麵": "🍜", "咖啡": "☕", "茶": "🍵", "飲茶": "🍵 🥟", "啤酒": "🍺 🍻",
    "雪糕": "🍦 🍨", "蛋糕": "🍰 🎂", "錢": "💰 💵", "落雨": "☔ 🌧", "太陽": "☀️",
    "車": "🚗 🚌", "巴士": "🚌", "地鐵": "🚇", "飛機": "✈️",
    "屋企": "🏠", "返工": "💼 🏢", "電話": "📱 📞", "手機": "📱", "音樂": "🎵 🎶",
    "貓": "🐱 😺", "狗": "🐶 🐕", "豬": "🐷 🐖", "鬼": "👻", "屎": "💩", "汗": "😅 😓",
    "冇問題": "👌 👍", "OK": "👌",
}


# Everyday function words that CLDR lists as keywords but which should not pull in emoji.
EMOJI_STOPWORDS = {"可以", "唔好", "個人", "問題", "完全", "當然", "打開", "方向", "接受", "發出", "好的",
                   "表情", "大聲", "雙手", "身體", "聲音", "夜晚", "紅色", "怪物", "手指"}


def build_emoji():
    groups, order, group = defaultdict(list), [], None
    with open(raw("emoji-test.txt"), encoding="utf-8") as f:
        for line in f:
            if line.startswith("# group:"):
                group = EMOJI_GROUPS.get(line.split(":", 1)[1].strip())
                continue
            m = re.match(r"^([0-9A-F ]+?)\s*; fully-qualified\s*# \S+ (E\d+\.\d+) ", line)
            if not m or group is None:
                continue
            cps = [int(x, 16) for x in m.group(1).split()]
            if not emoji_allowed(m.group(2), [c for c in cps if c != 0xFE0F]):
                continue
            e = "".join(chr(c) for c in cps)
            groups[group].append(e)
            order.append(e)
    rank = {e: i for i, e in enumerate(order)}
    bare = {e.replace("\ufe0f", ""): e for e in order}

    keywords = defaultdict(dict)  # keyword -> {emoji: priority}
    for name in ("cldr-yue.xml", "cldr-zh_Hant.xml"):
        with open(raw(name), encoding="utf-8") as f:
            for m in re.finditer(r'<annotation cp="([^"]+)"( type="tts")?>([^<]+)</annotation>', f.read()):
                e = bare.get(m.group(1).replace("\ufe0f", ""))
                if not e:
                    continue
                prio = 0 if m.group(2) else 1
                for kw in m.group(3).split("|"):
                    kw = kw.strip()
                    # Single characters (人, 手, 大 …) are typed too often to carry emoji; curated ones are added below.
                    if 2 <= len(kw) <= 6 and all(ord(c) <= 0xFFFF for c in kw):
                        keywords[kw][e] = min(prio, keywords[kw].get(e, 9))
    for kw, emojis in CURATED_EMOJI.items():
        for i, e in enumerate(emojis.split()):
            if e in rank:
                keywords[kw][e] = -10 + i
    for kw in EMOJI_STOPWORDS:
        keywords.pop(kw, None)
    rows = []
    for kw, emojis in keywords.items():
        best = sorted(emojis, key=lambda e: (emojis[e], rank[e]))[:5]
        rows.append((kw, " ".join(best)))
    rows.sort(key=lambda r: r[0].encode("utf-16-be"))
    with open(os.path.join(OUT, "emoji_words.txt"), "w", encoding="utf-8") as f:
        for r in rows:
            f.write("\t".join(r) + "\n")
    with open(os.path.join(OUT, "emoji_groups.txt"), "w", encoding="utf-8") as f:
        for name in EMOJI_GROUPS.values():
            if groups[name]:
                f.write(name + "\t" + " ".join(groups[name]) + "\n")
    print(f"emoji: {len(order)} emoji, {len(rows)} keywords", file=sys.stderr)


def main():
    os.makedirs(OUT, exist_ok=True)
    freq = load_essay()
    build_cangjie(freq)
    build_stroke(freq)
    build_jyutping(freq)
    build_assoc(freq)
    build_emoji()


if __name__ == "__main__":
    main()
