#!/usr/bin/env python3
"""
Builds app/src/main/assets/foods.json, Meal Mapper's offline food databank, and tools/fooddb/REPORT.md.

Sources (downloaded by fetch.sh into tools/fooddb/raw/, not committed):
  INDB  Indian Nutrient Databank, 1,014 cooked Indian recipes (Vijayakumar et al., Curr Dev Nutr 2024),
        github.com/lindsayjaacks/Indian-Nutrient-Databank-INDB-  (INDB.xlsx, recipes.xlsx)
  IFCT  Indian Food Composition Tables 2017 (NIN-ICMR), 542 raw foods, via npm @ifct2017/compositions (MIT)
  CoFID UK composition of foods, the 144 rows INDB ships in UK_fct.xlsx (dairy, bread, drinks, spirits...)

Rules
  1. Eggetarian only: recipes with meat or fish ingredients are dropped; IFCT rows need the "eggetarian" tag.
  2. Deep-frying oil: INDB counts the whole pan of oil (e.g. 480 ml for 80 g of atta, so poori = 738 kcal/100 g).
     For recipes with a "for frying" oil line that is >= 25% of the recipe weight, that oil is replaced by the
     oil the food actually absorbs: ABSORBED_SHARE of the fried food's weight. Assumption, stated in REPORT.md.
  3. Checks on every row: energy vs 4/4/9 (15%), fat <= 100 g, kcal <= 900 per 100 g. Rows failing are flagged.
  4. Alcohol: energy from ethanol (0.789 g/ml x 7 kcal/g) at the stated ABV plus residual carbohydrate.
Nothing else is added by hand except typical portion weights (katori, piece), which the user can change.
"""
import csv, json, math, re, sys, collections
from pathlib import Path
import openpyxl

ROOT = Path(__file__).resolve().parent
RAW = ROOT / "raw"
OUT = ROOT.parent.parent / "app/src/main/assets/foods.json"
ABSORBED_SHARE = 0.18          # oil as a share of the fried food's weight (typical range 0.10-0.30)
DEEP_FRY_MIN_SHARE = 0.25      # a frying-oil line this large (of recipe weight) is a pan of oil, not eaten
OIL_DENSITY = 0.92

MEAT = re.compile(r"chicken|mutton|lamb|fish|prawn|shrimp|\bmeat|keema|kheema|beef|pork|crab|bacon|\bham\b|salami|"
                  r"sausage|liver|goat|lobster|squid|tuna|salmon|sardine|mackerel|pomfret|surmai|bombil|anchov|gelatin", re.I)
FRY = re.compile(r"fry|frying", re.I)
OILY = re.compile(r"^(oil|ghee|vanaspati|fat)|, oil|oil,|ghee|vanaspati", re.I)


def num(v):
    try:
        f = float(v)
        return None if math.isnan(f) else f
    except (TypeError, ValueError):
        return None


def grams(amount, unit, name):
    a = num(amount) or 0.0
    ml = {"ml": 1, "tsp": 5, "tbsp": 15, "C": 240, "drops": 0.05}.get(unit)
    if unit == "g":
        return a
    if ml is not None:
        dens = OIL_DENSITY if OILY.search(name or "") else 1.0
        return a * ml * dens
    return {"sprig": 1.0, "pinch": 0.3}.get(unit, 0.0) * a


def sheet(path):
    rows = list(openpyxl.load_workbook(path, read_only=True).active.iter_rows(values_only=True))
    head = rows[0]
    return [dict(zip(head, r)) for r in rows[1:] if any(c is not None for c in r)]


# ---------- portions ----------
KATORI = re.compile(r"\bdal\b|curry|sabzi|sabji|subji|sambar|rasam|kadhi|raita|khich|rice|pulao|biryani|poha|upma|"
                    r"halwa|kheer|curd|dahi|chole|channa|rajma|korma|paneer (curry|makhani|butter)|"
                    r"porridge|daliya|dalia|salad|chaat|chat|soup|stew|payasam|shrikhand|bhaji|usal|misal", re.I)
PIECES = [  # (pattern, label, grams)  typical piece weights
    (r"phulka", "1 phulka", 30), (r"chapati|roti(?!.*roll)", "1 roti", 40), (r"parantha|paratha", "1 paratha", 80),
    (r"poori|puri\b", "1 poori", 25), (r"\bnaan", "1 naan", 90), (r"dosa|uttapam|pesarattu", "1 dosa", 100),
    (r"idli", "1 idli", 40), (r"vada|vadai", "1 vada", 50), (r"samosa", "1 samosa", 60), (r"dhokla", "1 piece", 30),
    (r"pakora|pakoda|bhajiya", "1 piece", 20), (r"ladoo|laddu", "1 ladoo", 35), (r"gulab jamun", "1 piece", 40),
    (r"jalebi", "1 piece", 30), (r"burfi|barfi", "1 piece", 25), (r"thepla", "1 thepla", 40),
    (r"bhakri|bhakhri", "1 bhakri", 60), (r"puranpoli|puran poli", "1 puran poli", 70), (r"cheela|chilla", "1 cheela", 70),
    (r"cutlet|patties|tikki", "1 piece", 50), (r"sandwich", "1 sandwich", 120), (r"omelette|omelet", "1 omelette (1 egg)", 60),
    (r"^egg, (poultry|country hen|duck), whole", "1 egg", 50),
    (r"bread|toast", "1 slice", 30), (r"biscuit|cookie", "1 biscuit", 10), (r"kachori", "1 kachori", 50),
    (r"thalipeeth", "1 piece", 70), (r"modak", "1 modak", 40), (r"appam", "1 appam", 60),
]
CUP = re.compile(r"\btea\b|chai|coffee", re.I)
GLASS = re.compile(r"milk(?! powder)|lassi|chaas|buttermilk|juice|sharbat|shake|smoothie|jal jeera|nimbu pani|lemonade|cola|squash|coconut water", re.I)


def portions(name):
    units = []
    for pat, label, g in PIECES:
        if re.search(pat, name, re.I):
            units.append([label, g])
            break
    if CUP.search(name):
        units.append(["1 cup", 150])
    if GLASS.search(name):
        units.append(["1 glass", 250])
    return units


# ---------- aliases ----------
def aliases_from_name(name):
    out = []
    for inner in re.findall(r"\(([^)]*)\)", name):
        out += [p.strip() for p in re.split(r"/|,", inner) if p.strip()]
    base = re.sub(r"\([^)]*\)", "", name).strip()
    out += [p.strip() for p in base.split("/") if p.strip() and p.strip() != base]
    return out


def ifct_local(lang):
    out = []
    for part in (lang or "").split(";"):
        m = re.match(r"\s*(H|Mar|Guj|Hin)\.\s*(.+)", part)
        if m:
            out += [x.strip() for x in m.group(2).split(",") if x.strip()]
    return out


def check(k, p, c, f, alcohol_g=0.0):
    flags = []
    macro = 4 * p + 4 * c + 9 * f + 7 * alcohol_g
    big = max(k, macro)
    if big >= 20 and abs(k - macro) / big > 0.15:
        flags.append("energy-mismatch")
    if f > 101 or k > 900 or p > 100 or c > 106:
        flags.append("implausible")
    return flags


def main():
    report = collections.Counter()
    notes = []
    foods = []

    # ----- INDB recipes -----
    lines = collections.defaultdict(list)
    for r in sheet(RAW / "recipes.xlsx"):
        lines[r["recipe_code"]].append(r)
    for r in sheet(RAW / "INDB.xlsx"):
        code, name = r["food_code"], str(r["food_name"]).strip()
        ing = lines.get(code, [])
        if any(MEAT.search(f"{i['food_name']} {i['ingredient_name_org']}") for i in ing) or MEAT.search(name):
            report["INDB dropped: meat/fish"] += 1
            continue
        k, p, c, f = (num(r[x]) or 0.0 for x in ("energy_kcal", "protein_g", "carb_g", "fat_g"))
        su, fi = num(r["freesugar_g"]), num(r["fibre_g"])
        sf = (num(r["sfa_mg"]) or 0) / 1000 if num(r["sfa_mg"]) is not None else None
        na = num(r["sodium_mg"])
        flags = []
        fry = [i for i in ing if FRY.search(f"{i['ingredient_name_org']} {i['amount_org']}") and OILY.search(str(i["food_name"]))]
        total = sum(grams(i["amount"], i["unit"], i["food_name"]) for i in ing)
        oil = sum(grams(i["amount"], i["unit"], i["food_name"]) for i in fry)
        if fry and total > 0 and oil / total >= DEEP_FRY_MIN_SHARE:
            fat_total = f * total / 100
            if fat_total < 0.8 * oil:
                flags.append("fry-basis-unclear")
                report["INDB fried: basis unclear, left as is"] += 1
            else:
                dough = total - oil
                absorbed = ABSORBED_SHARE / (1 - ABSORBED_SHARE) * dough
                w = dough + absorbed
                before = k
                k = (k * total / 100 - oil * 9 + absorbed * 9) / w * 100
                f = (fat_total - oil + absorbed) / w * 100
                scale = total / w
                p, c = p * scale, c * scale
                su = su * scale if su is not None else None
                fi = fi * scale if fi is not None else None
                na = na * scale if na is not None else None
                sf = max(0.0, (sf * total / 100 - (oil - absorbed) * 0.11)) / w * 100 if sf is not None else None
                flags.append("fry-corrected")
                report["INDB fried: oil corrected"] += 1
                notes.append((name, round(before), round(k)))
        flags += check(k, p, c, f)
        # A wet dish (dal, khichdi, curry) above 250 kcal/100 g is probably on a dry-ingredient basis in INDB.
        if (KATORI.search(name) and k > 250 and re.search(r"curry|\bdal\b|khich|raita|sabzi|soup|kadhi|sambar", name, re.I)
                and not re.search(r"vada|cutlet|burfi|roti|parantha|paratha|poori|powder|premix|masala\)", name, re.I)):
            flags.append("check-basis")
        if k <= 0:
            report["INDB dropped: no energy"] += 1
            continue
        foods.append(dict(id=code, n=name, a=aliases_from_name(name), s="INDB", x=flags, b="g",
                          k=k, p=p, c=c, f=f, sf=sf, su=su, fi=fi, na=na,
                          u=portions(name), kt=bool(KATORI.search(name))))
        report["INDB kept"] += 1

    # ----- IFCT 2017 raw foods -----
    with open(RAW / "ifct/index.csv", newline="") as fh:
        rd = csv.reader(fh)
        head = next(rd)
        col = {h.split("; ")[-1]: i for i, h in enumerate(head)}
        for row in rd:
            if "eggetarian" not in row[col["tags"]]:
                report["IFCT dropped: not eggetarian"] += 1
                continue
            v = lambda key: num(row[col[key]]) if key in col else None
            k = (v("enerc") or 0) / 4.184
            p, c, f = v("protcnt") or 0, v("choavldf") or 0, v("fatce") or 0
            extra = []
            if k <= 0 and f >= 99:  # IFCT leaves energy blank for oils and ghee: pure fat is 9 kcal/g
                k = 9 * f
                extra.append("energy-from-fat")
            name = row[col["name"]].strip()
            group = row[col["grup"]]
            raw = group in ("Cereals and Millets", "Grain Legumes") and "raw" not in name.lower()
            display = f"{name} (raw)" if raw else name
            sat = v("fasat")
            foods.append(dict(id=row[col["code"]], n=display, a=ifct_local(row[col["lang"]]), s="IFCT", x=check(k, p, c, f) + extra,
                              b="g", k=k, p=p, c=c, f=f, sf=sat / 1000 if sat is not None else None,
                              su=v("fsugar"), fi=v("fibtg"), na=v("na"), u=portions(name), kt=False, g=group))
            report["IFCT kept"] += 1

    # ----- CoFID rows shipped with INDB -----
    for r in sheet(RAW / "UK_fct.xlsx"):
        name = str(r.get("food_name") or "").strip()
        if not name or MEAT.search(name) or re.search(r"stock cubes, chicken|agar|bicarbonate|baking powder|cream of tartar|water, distilled", name, re.I):
            continue
        k, p, c, f = (num(r.get(x)) or 0.0 for x in ("energy_kcal", "protein_g", "carb_g", "fat_g"))
        if k <= 0 and "spirits" not in name.lower():
            continue
        basis = "ml" if re.search(r"juice|cola|drink|spirits|tea,|milk|squash", name, re.I) and "powder" not in name.lower() and "condensed" not in name.lower() else "g"
        alcohol = 0.0
        if "spirits" in name.lower():  # CoFID energy includes alcohol; macros do not
            alcohol = (k - 4 * p - 4 * c - 9 * f) / 7
        sfa = num(r.get("sfa_mg"))
        foods.append(dict(id="UK" + str(r["food_code"]), n=name, a=[], s="CoFID", x=check(k, p, c, f, alcohol), b=basis,
                          k=k, p=p, c=c, f=f, sf=sfa / 1000 if sfa is not None else None, su=num(r.get("freesugar_g")),
                          fi=num(r.get("fibre_g")), na=num(r.get("sodium_mg")), u=portions(name), kt=False))
        report["CoFID kept"] += 1

    # ----- Alcohol by ABV -----
    for id_, name, abv, carbs, protein, units in [
        ("ALC1", "Beer, lager (5% alcohol)", 5, 3.6, 0.5, [["1 bottle 330 ml", 330], ["1 pint/can 500 ml", 500], ["1 bottle 650 ml", 650]]),
        ("ALC2", "Beer, strong (8% alcohol)", 8, 3.6, 0.5, [["1 bottle 330 ml", 330], ["1 can 500 ml", 500], ["1 bottle 650 ml", 650]]),
        ("ALC3", "Wine, red (13% alcohol)", 13, 2.6, 0.1, [["1 glass 150 ml", 150]]),
        ("ALC4", "Wine, white (12% alcohol)", 12, 2.6, 0.1, [["1 glass 150 ml", 150]]),
        ("ALC5", "Whisky / rum / vodka / gin (42.8% alcohol)", 42.8, 0, 0, [["1 small peg 30 ml", 30], ["1 large peg 60 ml", 60]]),
    ]:
        alcohol_g = abv * 0.789  # per 100 ml
        k = alcohol_g * 7 + carbs * 4 + protein * 4
        foods.append(dict(id=id_, n=name, a=["daru", "drink", "alcohol"], s="ABV", x=[], b="ml", k=k, p=protein, c=carbs, f=0.0,
                          sf=None, su=None, fi=0.0, na=None, u=units, kt=False))
        report["Alcohol by ABV"] += 1

    for fd in foods:
        for key in ("k", "p", "c", "f", "sf", "su", "fi", "na"):
            if fd.get(key) is not None:
                fd[key] = round(fd[key], 2)
        fd["x"] = sorted(set(fd["x"]))
        fd.pop("g", None)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({"v": 1, "foods": foods}, ensure_ascii=False, separators=(",", ":")))

    flagged = collections.Counter(x for fd in foods for x in fd["x"])
    with open(ROOT / "REPORT.md", "w") as out:
        out.write("# Food databank report\n\nGenerated by `tools/fooddb/build.py`.\n\n")
        out.write(f"Foods in the app: **{len(foods)}**\n\n| Step | Rows |\n|---|---|\n")
        for key, n in sorted(report.items()):
            out.write(f"| {key} | {n} |\n")
        out.write("\n| Flag | Rows |\n|---|---|\n")
        for key, n in sorted(flagged.items()):
            out.write(f"| {key} | {n} |\n")
        out.write(f"\nDeep-fry correction: frying oil replaced by absorbed oil = {int(ABSORBED_SHARE * 100)}% of the fried food's weight "
                  "(assumption; published values for Indian deep-fried foods range roughly 10-30%). Water lost in frying is not modelled, "
                  "so corrected values are, if anything, slightly low.\n\n")
        out.write("| Recipe | kcal/100 g before | after |\n|---|---|---|\n")
        for name, b, a in sorted(notes)[:400]:
            out.write(f"| {name} | {b} | {a} |\n")
    print(f"{len(foods)} foods -> {OUT}")
    for key, n in sorted(report.items()):
        print(f"  {key}: {n}")
    print("  flags:", dict(flagged))


if __name__ == "__main__":
    sys.exit(main())
