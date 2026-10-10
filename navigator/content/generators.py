"""Savol generatorlari.

* "Cheksiz" generatorlar (matematika) har chaqiruvda yangi savol yaratadi —
  baza yetishmasa, bot foydalanuvchi so'rovi bilan yangi savollar qo'sha oladi.
* "Cheklangan" generatorlar (poytaxtlar, lug'at, fe'llar) ro'yxatdagi barcha
  savollarni bir marta qaytaradi.
"""
from __future__ import annotations

import random
from dataclasses import dataclass
from typing import Callable, Iterable


@dataclass(frozen=True)
class Draft:
    text: str
    options: list[str]
    correct_index: int
    explanation: str


def fmt(n: int | float) -> str:
    """12345 -> '12 345' (o'zbekcha raqam yozuvi)."""
    if isinstance(n, float) and not n.is_integer():
        return f"{n:.2f}".rstrip("0").rstrip(".").replace(".", ",")
    return f"{int(n):,}".replace(",", " ")


def build_options(correct: str, distractors: Iterable[str], rng: random.Random, n: int = 4) -> tuple[list[str], int] | None:
    seen = {correct.strip().lower()}
    picked: list[str] = []
    for d in distractors:
        key = d.strip().lower()
        if key and key not in seen:
            seen.add(key)
            picked.append(d)
        if len(picked) == n - 1:
            break
    if len(picked) < n - 1:
        return None
    idx = rng.randrange(n)
    picked.insert(idx, correct)
    return picked, idx


def numeric_distractors(correct: int, rng: random.Random, allow_negative: bool = False,
                        extra: Iterable[int] = ()) -> list[str]:
    # Qadam son "yumaloqligiga" moslanadi: 14 000 uchun ±1 000, 85 uchun ±1.
    step = 1
    while correct and correct % (step * 10) == 0 and step * 10 <= abs(correct) // 10:
        step *= 10
    deltas = [1, -1, 2, -2, 10, -10, 5, -5, 3, -3]
    rng.shuffle(deltas)
    cands = [*extra] + [correct + d * step for d in deltas]
    s = str(abs(correct))
    if len(s) >= 2 and s[-1] != s[-2] and s[-1] != "0":  # raqamlarni almashtirish — tipik xato
        swapped = int(s[:-2] + s[-1] + s[-2]) * (1 if correct >= 0 else -1)
        cands.insert(len(extra), swapped)
    return [fmt(c) for c in cands if (allow_negative or c >= 0) and c != correct]


def _num_draft(text: str, correct: int, explanation: str, rng: random.Random, allow_negative=False,
               extra: Iterable[int] = ()) -> Draft | None:
    built = build_options(fmt(correct), numeric_distractors(correct, rng, allow_negative, list(extra)), rng)
    if not built:
        return None
    opts, idx = built
    return Draft(text, opts, idx, explanation)


# ---------------------------------------------------------------- matematika
def gen_arithmetic(level: int, rng: random.Random) -> Draft | None:
    if level == 1:
        op = rng.choice("+-×")
        if op == "+":
            a, b = rng.randint(11, 99), rng.randint(11, 99)
            r = a + b
        elif op == "-":
            a = rng.randint(30, 99)
            b = rng.randint(11, a - 5)
            r = a - b
        else:
            a, b = rng.randint(2, 9), rng.randint(11, 25)
            r = a * b
        expr = f"{a} {op} {b}"
        return _num_draft(f"Hisoblang: {expr} = ?", r, f"{expr} = {fmt(r)}", rng)
    if level == 2:
        op = rng.choice("+-×:")
        if op == "+":
            a, b = rng.randint(120, 999), rng.randint(120, 999)
            r = a + b
        elif op == "-":
            a = rng.randint(400, 999)
            b = rng.randint(101, a - 50)
            r = a - b
        elif op == "×":
            a, b = rng.randint(12, 39), rng.randint(12, 39)
            r = a * b
        else:
            b, r = rng.randint(6, 19), rng.randint(12, 49)
            a = b * r
        expr = f"{a} {op} {b}"
        return _num_draft(f"Hisoblang: {expr} = ?", r, f"{expr} = {fmt(r)}", rng)
    # level 3 — amallar tartibi
    a, b, c, d = rng.randint(10, 60), rng.randint(3, 15), rng.randint(3, 12), rng.randint(2, 30)
    form = rng.randrange(3)
    if form == 0:
        r = a + b * c - d
        expr = f"{a} + {b} × {c} − {d}"
        expl = f"Avval ko‘paytirish: {b} × {c} = {b * c}; so‘ng {a} + {b * c} − {d} = {fmt(r)}"
    elif form == 1:
        r = (a + b) * c - d
        expr = f"({a} + {b}) × {c} − {d}"
        expl = f"Avval qavs: {a} + {b} = {a + b}; {a + b} × {c} = {(a + b) * c}; − {d} = {fmt(r)}"
    else:
        q = rng.randint(2, 9)
        prod = b * q
        r = a - prod // b + c * d
        expr = f"{a} − {prod} : {b} + {c} × {d}"
        expl = f"Bo‘lish va ko‘paytirish avval: {prod} : {b} = {q}, {c} × {d} = {c * d}; {a} − {q} + {c * d} = {fmt(r)}"
    return _num_draft(f"Ifodaning qiymatini toping: {expr}", r, expl, rng, allow_negative=True)


def gen_percent(level: int, rng: random.Random) -> Draft | None:
    if level == 1:
        p = rng.choice([10, 20, 25, 50])
        n = rng.randint(2, 40) * 20
        r = n * p // 100
        return _num_draft(f"{fmt(n)} ning {p}% i nechaga teng?", r,
                          f"{fmt(n)} × {p} : 100 = {fmt(r)}", rng)
    if level == 2:
        p = rng.choice([5, 15, 30, 40, 60, 75])
        price = rng.randint(2, 60) * 2000
        disc = price * p // 100
        r = price - disc
        return _num_draft(
            f"Mahsulot narxi {fmt(price)} so‘m. {p}% chegirmadan keyin narxi qancha bo‘ladi?", r,
            f"Chegirma: {fmt(price)} × {p}% = {fmt(disc)} so‘m; {fmt(price)} − {fmt(disc)} = {fmt(r)} so‘m", rng,
            extra=[disc, price + disc])
    form = rng.randrange(2)
    if form == 0:
        p = rng.choice([10, 20, 25, 30, 40, 60])
        x = rng.randint(2, 50) * 10
        part = x * p // 100
        if x * p % 100:
            return None
        return _num_draft(f"Qaysi sonning {p}% i {fmt(part)} ga teng?", x,
                          f"x = {fmt(part)} : {p} × 100 = {fmt(x)}", rng)
    up, down = rng.choice([(20, 10), (10, 20), (25, 20), (50, 20), (20, 25), (30, 10)])
    base = rng.randint(1, 40) * 10000
    mid = base * (100 + up) // 100
    r = mid * (100 - down) // 100
    if base * (100 + up) % 100 or mid * (100 - down) % 100:
        return None
    return _num_draft(
        f"Narx {fmt(base)} so‘m edi. Avval {up}% oshirildi, keyin {down}% kamaytirildi. Yakuniy narx qancha?",
        r, f"{fmt(base)} × {100 + up}% = {fmt(mid)}; {fmt(mid)} × {100 - down}% = {fmt(r)} so‘m", rng,
        extra=[base * (100 + up - down) // 100, mid])


def gen_equation(level: int, rng: random.Random) -> Draft | None:
    if level == 1:
        x = rng.randint(2, 60)
        if rng.random() < 0.5:
            a = rng.randint(5, 90)
            return _num_draft(f"Tenglamani yeching: x + {a} = {x + a}", x,
                              f"x = {x + a} − {a} = {x}", rng)
        a = rng.randint(2, 9)
        return _num_draft(f"Tenglamani yeching: {a}x = {a * x}", x, f"x = {a * x} : {a} = {x}", rng)
    if level == 2:
        x, a, b = rng.randint(-15, 25), rng.randint(2, 12), rng.randint(1, 40)
        if x == 0:
            return None
        sign = rng.choice("+-")
        c = a * x + b if sign == "+" else a * x - b
        return _num_draft(f"Tenglamani yeching: {a}x {sign} {b} = {c}", x,
                          f"{a}x = {c} {'−' if sign == '+' else '+'} {b} = {a * x}; x = {a * x} : {a} = {x}",
                          rng, allow_negative=True)
    x, a, c = rng.randint(-12, 15), rng.randint(3, 9), rng.randint(1, 8)
    b = rng.randint(1, 12)
    if a == c or x == 0:
        return None
    d = a * (x + b) - c * x
    return _num_draft(
        f"Tenglamani yeching: {a}(x + {b}) = {c}x {'+' if d >= 0 else '−'} {abs(d)}", x,
        f"{a}x + {a * b} = {c}x {'+' if d >= 0 else '−'} {abs(d)} ⇒ {a - c}x = {d - a * b} ⇒ x = {x}",
        rng, allow_negative=True)


PY_TRIPLES = [(3, 4, 5), (5, 12, 13), (8, 15, 17), (7, 24, 25), (20, 21, 29), (9, 40, 41)]


def gen_geometry(level: int, rng: random.Random) -> Draft | None:
    if level == 1:
        a = rng.randint(2, 40)
        if rng.random() < 0.5:
            return _num_draft(f"Tomoni {a} sm bo‘lgan kvadratning perimetri necha sm?", 4 * a,
                              f"P = 4a = 4 × {a} = {4 * a} sm", rng)
        return _num_draft(f"Tomoni {a} sm bo‘lgan kvadratning yuzi necha sm²?", a * a,
                          f"S = a² = {a}² = {a * a} sm²", rng)
    if level == 2:
        a, b = rng.randint(3, 30), rng.randint(3, 30)
        if a == b:
            return None
        form = rng.randrange(3)
        if form == 0:
            return _num_draft(f"Tomonlari {a} sm va {b} sm bo‘lgan to‘g‘ri to‘rtburchakning yuzi necha sm²?",
                              a * b, f"S = a × b = {a} × {b} = {a * b} sm²", rng)
        if form == 1:
            return _num_draft(f"Tomonlari {a} sm va {b} sm bo‘lgan to‘g‘ri to‘rtburchakning perimetri necha sm?",
                              2 * (a + b), f"P = 2(a + b) = 2 × ({a} + {b}) = {2 * (a + b)} sm", rng)
        if a * b % 2:
            return None
        return _num_draft(f"Asosi {a} sm, balandligi {b} sm bo‘lgan uchburchakning yuzi necha sm²?",
                          a * b // 2, f"S = (a × h) : 2 = ({a} × {b}) : 2 = {a * b // 2} sm²", rng)
    k = rng.randint(1, 6)
    p, q, h = (v * k for v in rng.choice(PY_TRIPLES))
    if rng.random() < 0.5:
        return _num_draft(f"To‘g‘ri burchakli uchburchakning katetlari {p} va {q} ga teng. Gipotenuzani toping.",
                          h, f"c = √({p}² + {q}²) = √{p * p + q * q} = {h}", rng)
    return _num_draft(f"To‘g‘ri burchakli uchburchakning gipotenuzasi {h}, bir kateti {p}. Ikkinchi katetni toping.",
                      q, f"b = √({h}² − {p}²) = √{h * h - p * p} = {q}", rng)


# ---------------------------------------------------------------- geografiya
CAPITALS: dict[int, list[tuple[str, str]]] = {
    1: [
        ("O‘zbekiston", "Toshkent"), ("Qozog‘iston", "Astana"), ("Qirg‘iziston", "Bishkek"),
        ("Tojikiston", "Dushanbe"), ("Turkmaniston", "Ashxobod"), ("Rossiya", "Moskva"),
        ("Xitoy", "Pekin"), ("Yaponiya", "Tokio"), ("Fransiya", "Parij"), ("Germaniya", "Berlin"),
        ("Italiya", "Rim"), ("Ispaniya", "Madrid"), ("Buyuk Britaniya", "London"), ("Turkiya", "Anqara"),
        ("AQSH", "Vashington"), ("Misr", "Qohira"), ("Hindiston", "Nyu-Dehli"), ("Janubiy Koreya", "Seul"),
        ("Afg‘oniston", "Kobul"), ("Eron", "Tehron"),
    ],
    2: [
        ("Kanada", "Ottava"), ("Avstraliya", "Kanberra"), ("Braziliya", "Braziliya"), ("Argentina", "Buenos-Ayres"),
        ("Ozarbayjon", "Boku"), ("Gruziya", "Tbilisi"), ("Armaniston", "Yerevan"), ("Ukraina", "Kiyev"),
        ("Belarus", "Minsk"), ("Polsha", "Varshava"), ("Avstriya", "Vena"), ("Gretsiya", "Afina"),
        ("Portugaliya", "Lissabon"), ("Niderlandiya", "Amsterdam"), ("Shvetsiya", "Stokgolm"),
        ("Norvegiya", "Oslo"), ("Pokiston", "Islomobod"), ("Saudiya Arabistoni", "Ar-Riyod"),
        ("Indoneziya", "Jakarta"), ("Meksika", "Mexiko"),
    ],
    3: [
        ("Shveytsariya", "Bern"), ("Finlandiya", "Xelsinki"), ("Daniya", "Kopengagen"), ("Vengriya", "Budapesht"),
        ("Ruminiya", "Buxarest"), ("Bolgariya", "Sofiya"), ("Chexiya", "Praga"), ("Litva", "Vilnyus"),
        ("Latviya", "Riga"), ("Estoniya", "Tallin"), ("Mo‘g‘uliston", "Ulan-Bator"), ("Vyetnam", "Xanoy"),
        ("Malayziya", "Kuala-Lumpur"), ("Tailand", "Bangkok"), ("Keniya", "Nayrobi"), ("Efiopiya", "Addis-Abeba"),
        ("Nigeriya", "Abuja"), ("Yangi Zelandiya", "Vellington"), ("Chili", "Santyago"), ("Peru", "Lima"),
    ],
}


def all_capitals(level: int) -> list[Draft]:
    rng = random.Random(f"capitals-{level}")
    pairs = CAPITALS[level]
    out: list[Draft] = []
    for country, city in pairs:
        others = [c for _, c in pairs if c != city]
        rng.shuffle(others)
        built = build_options(city, others, rng)
        if built:
            out.append(Draft(f"{country} davlatining poytaxti qaysi shahar?", built[0], built[1],
                             f"{country} davlatining poytaxti — {city}."))
        others_c = [c for c, _ in pairs if c != country]
        rng.shuffle(others_c)
        built = build_options(country, others_c, rng)
        if built:
            out.append(Draft(f"{city} shahri qaysi davlatning poytaxti?", built[0], built[1],
                             f"{city} — {country} davlatining poytaxti."))
    return out


# ---------------------------------------------------------------- ingliz tili
WORDS: dict[int, list[tuple[str, str]]] = {
    1: [
        ("apple", "olma"), ("book", "kitob"), ("water", "suv"), ("house", "uy"), ("dog", "it"),
        ("cat", "mushuk"), ("sun", "quyosh"), ("school", "maktab"), ("bread", "non"), ("friend", "do‘st"),
        ("mother", "ona"), ("father", "ota"), ("car", "mashina"), ("tree", "daraxt"), ("milk", "sut"),
        ("red", "qizil"), ("big", "katta"), ("small", "kichik"), ("window", "deraza"), ("door", "eshik"),
    ],
    2: [
        ("weather", "ob-havo"), ("journey", "sayohat"), ("kitchen", "oshxona"), ("neighbour", "qo‘shni"),
        ("healthy", "sog‘lom"), ("expensive", "qimmat"), ("cheap", "arzon"), ("invite", "taklif qilmoq"),
        ("careful", "ehtiyotkor"), ("village", "qishloq"), ("library", "kutubxona"), ("island", "orol"),
        ("river", "daryo"), ("mountain", "tog‘"), ("answer", "javob"), ("question", "savol"),
        ("enough", "yetarli"), ("forget", "unutmoq"), ("remember", "eslamoq"), ("choose", "tanlamoq"),
    ],
    3: [
        ("achievement", "yutuq"), ("environment", "atrof-muhit"), ("opportunity", "imkoniyat"),
        ("reliable", "ishonchli"), ("consequence", "oqibat"), ("significant", "muhim"), ("improve", "yaxshilamoq"),
        ("require", "talab qilmoq"), ("knowledge", "bilim"), ("ancient", "qadimiy"), ("purpose", "maqsad"),
        ("encourage", "rag‘batlantirmoq"), ("government", "hukumat"), ("population", "aholi"),
        ("survive", "omon qolmoq"), ("determine", "aniqlamoq"), ("generous", "saxiy"), ("attempt", "urinish"),
        ("decrease", "kamaymoq"), ("obvious", "ochiq-oydin"),
    ],
}

VERBS: dict[int, list[tuple[str, str, str]]] = {
    1: [
        ("go", "went", "gone"), ("see", "saw", "seen"), ("eat", "ate", "eaten"), ("come", "came", "come"),
        ("do", "did", "done"), ("have", "had", "had"), ("make", "made", "made"), ("take", "took", "taken"),
        ("give", "gave", "given"), ("get", "got", "got"), ("know", "knew", "known"), ("say", "said", "said"),
        ("write", "wrote", "written"), ("drink", "drank", "drunk"), ("sleep", "slept", "slept"),
        ("run", "ran", "run"),
    ],
    2: [
        ("buy", "bought", "bought"), ("bring", "brought", "brought"), ("think", "thought", "thought"),
        ("teach", "taught", "taught"), ("catch", "caught", "caught"), ("find", "found", "found"),
        ("leave", "left", "left"), ("meet", "met", "met"), ("speak", "spoke", "spoken"),
        ("break", "broke", "broken"), ("choose", "chose", "chosen"), ("forget", "forgot", "forgotten"),
        ("begin", "began", "begun"), ("swim", "swam", "swum"), ("sing", "sang", "sung"),
        ("drive", "drove", "driven"),
    ],
    3: [
        ("arise", "arose", "arisen"), ("bear", "bore", "borne"), ("bite", "bit", "bitten"),
        ("bleed", "bled", "bled"), ("forbid", "forbade", "forbidden"), ("freeze", "froze", "frozen"),
        ("hide", "hid", "hidden"), ("lay", "laid", "laid"), ("lie", "lay", "lain"), ("seek", "sought", "sought"),
        ("shake", "shook", "shaken"), ("steal", "stole", "stolen"), ("swear", "swore", "sworn"),
        ("tear", "tore", "torn"), ("weave", "wove", "woven"), ("withdraw", "withdrew", "withdrawn"),
    ],
}


def _regularize(v: str) -> str:
    if v.endswith("e"):
        return v + "d"
    if v.endswith("y") and v[-2:-1] not in "aeiou":
        return v[:-1] + "ied"
    return v + "ed"


def all_words(level: int) -> list[Draft]:
    rng = random.Random(f"words-{level}")
    pairs = WORDS[level]
    out: list[Draft] = []
    for en, uz in pairs:
        others = [u for _, u in pairs if u != uz]
        rng.shuffle(others)
        built = build_options(uz, others, rng)
        if built:
            out.append(Draft(f"«{en}» so‘zining o‘zbekcha tarjimasi qaysi?", built[0], built[1],
                             f"{en} — {uz}."))
        others_en = [e for e, _ in pairs if e != en]
        rng.shuffle(others_en)
        built = build_options(en, others_en, rng)
        if built:
            out.append(Draft(f"«{uz}» so‘zi ingliz tilida qanday bo‘ladi?", built[0], built[1],
                             f"{uz} — {en}."))
    return out


def all_verbs(level: int) -> list[Draft]:
    rng = random.Random(f"verbs-{level}")
    verbs = VERBS[level]
    out: list[Draft] = []
    for v1, v2, v3 in verbs:
        pool = [f for (a, b, c) in verbs if a != v1 for f in (b, c)]
        rng.shuffle(pool)
        for form_name, correct, other in (("Past Simple (V2)", v2, v3), ("Past Participle (V3)", v3, v2)):
            built = build_options(correct, [other, _regularize(v1), *pool], rng)
            if built:
                out.append(Draft(f"«{v1}» fe’lining {form_name} shakli qaysi?", built[0], built[1],
                                 f"{v1} — {v2} — {v3}. Bu noto‘g‘ri (irregular) fe’l, «-ed» qo‘shilmaydi."))
    return out


# ---------------------------------------------------------------- registry
@dataclass(frozen=True)
class Generator:
    key: str
    infinite: bool
    make: Callable[[int, random.Random], Draft | None] | None = None
    make_all: Callable[[int], list[Draft]] | None = None

    def produce(self, level: int, count: int, rng: random.Random | None = None) -> Iterable[Draft]:
        """Cheksiz generatordan ``count`` tagacha (urinishlar bilan) yoki cheklangan ro'yxatning hammasi."""
        if not self.infinite:
            yield from self.make_all(level)  # type: ignore[misc]
            return
        rng = rng or random.Random()
        made, attempts = 0, 0
        while made < count and attempts < count * 30:
            attempts += 1
            d = self.make(level, rng)  # type: ignore[misc]
            if d is not None:
                made += 1
                yield d


GENERATORS: dict[str, Generator] = {
    "math.arithmetic": Generator("math.arithmetic", True, make=gen_arithmetic),
    "math.percent": Generator("math.percent", True, make=gen_percent),
    "math.equation": Generator("math.equation", True, make=gen_equation),
    "math.geometry": Generator("math.geometry", True, make=gen_geometry),
    "geo.capitals": Generator("geo.capitals", False, make_all=all_capitals),
    "en.words": Generator("en.words", False, make_all=all_words),
    "en.verbs": Generator("en.verbs", False, make_all=all_verbs),
}
