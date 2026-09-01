#!/usr/bin/env python3
"""Build the offline city dataset bundled with the apps.

Sources:
  - GeoNames cities (via the `geonamescache` pip package, CC-BY 4.0):
    ~34k populated places worldwide (population >= 15000), incl. Chinese
    alternate names.
  - modood/Administrative-divisions-of-China `pca.json` (vendored as
    tools/pca.json): 省→市→区县 Chinese name hierarchy, used to roll Chinese
    county-level entries up to their prefecture-level city (地级市).

Output (TSV, UTF-8, no header): lat<TAB>lng<TAB>key<TAB>display
  - key uniquely identifies a "city" for day counting (CN:杭州 / US:NY:New York City)
  - display is what the UI shows (中文优先)

The apps resolve a GPS fix by nearest neighbour over all rows and take that
row's key/display. Rows are a dense point cloud: Chinese county/district points
carry their prefecture's key, and foreign neighbourhood/suburb points carry
their parent city's key, so nearest-neighbour approximates real city extents
far better than one centroid per city.

Known limitation (v0.1): near prefecture borders a point can snap to the
neighbouring city (e.g. 燕郊 → 北京). Raw coordinates are stored by the apps so
history can be re-resolved by future dataset/algorithm upgrades.

Usage: python3 tools/build_city_dataset.py <output.tsv>
"""
import json
import math
import sys
from pathlib import Path

import geonamescache
from opencc import OpenCC

_t2s = OpenCC("t2s")

MUNICIPALITIES = {"北京市", "上海市", "天津市", "重庆市"}
ETHNIC_TOKENS = (
    "土家族", "苗族", "侗族", "布依族", "白族", "哈尼族", "彝族", "傣族", "景颇族",
    "傈僳族", "怒族", "朝鲜族", "回族", "蒙古族", "蒙古", "藏族", "羌族", "壮族",
    "哈萨克族", "哈萨克", "柯尔克孜族", "柯尔克孜", "锡伯族", "塔吉克族", "维吾尔族",
    "黎族", "满族", "达斡尔族", "仡佬族", "水族", "瑶族", "畲族", "仫佬族", "毛南族",
)
AMBIG = object()

CJK = lambda s: any("一" <= ch <= "鿿" for ch in s)


def strip_suffix(name: str) -> str:
    for suf in ("自治州", "自治县", "自治旗", "地区", "林区", "特区", "新区", "市", "区", "县", "旗"):
        if name.endswith(suf) and len(name) > len(suf):
            return name[: -len(suf)]
    return name


def prefecture_display(city_name: str) -> str:
    """地级单位显示名：XX市→XX；XX(民族)自治州→XX州；盟/地区保留全称。"""
    if city_name.endswith("自治州"):
        stem = city_name[: -len("自治州")]
        changed = True
        while changed:
            changed = False
            for tok in ETHNIC_TOKENS:
                if stem.endswith(tok) and len(stem) > len(tok):
                    stem = stem[: -len(tok)]
                    changed = True
        return stem + "州"
    if city_name.endswith("市") and len(city_name) > 1:
        return city_name[:-1]
    return city_name


def build_cn_name_index(pca: dict):
    """两套索引：全名（可信）与词干（需地理校验）；重名键一律丢弃。"""
    full: dict = {}
    stem: dict = {}

    def _put(index, k, pref):
        cur = index.get(k)
        if cur is None:
            index[k] = pref
        elif cur is not AMBIG and cur != pref:
            index[k] = AMBIG  # 通州/朝阳/金沙 之类的重名，交给地理兜底

    def put(name: str, pref: str):
        _put(full, name, pref)
        st = strip_suffix(name)
        if st != name:
            _put(stem, st, pref)

    for province, cities in pca.items():
        if province in MUNICIPALITIES:
            pref = prefecture_display(province)
            put(province, pref)
            for _group, counties in cities.items():
                for county in counties:
                    put(county, pref)
            continue
        for city, counties in cities.items():
            if city in ("省直辖县级行政区划", "自治区直辖县级行政区划"):
                for county in counties:  # 仙桃/济源/五指山… 本身即统计单元
                    put(county, prefecture_display(county))
                continue
            pref = prefecture_display(city)
            put(city, pref)
            for county in counties:
                put(county, pref)
    clean = lambda d: {k: v for k, v in d.items() if v is not AMBIG}
    return clean(full), clean(stem)


DISPLAY_OVERRIDES = {"三藩市": "旧金山", "杜拜": "迪拜", "雪梨": "悉尼"}


def _simplified_rank(s):
    try:
        s.encode("gb2312")
        return 0  # 简体优先
    except UnicodeEncodeError:
        return 1


def pick_cjk(names):
    cands = [n for n in names if CJK(n)]
    if not cands:
        return None
    best = sorted(cands, key=lambda n: (_simplified_rank(n), len(n), n))[0]
    best = _t2s.convert(best)
    if len(best) >= 3 and best.endswith("市"):
        best = best[:-1]
    return DISPLAY_OVERRIDES.get(best, best)


def haversine(lat1, lng1, lat2, lng2):
    rlat1, rlat2 = math.radians(lat1), math.radians(lat2)
    a = (math.sin((rlat2 - rlat1) / 2) ** 2
         + math.cos(rlat1) * math.cos(rlat2) * math.sin(math.radians(lng2 - lng1) / 2) ** 2)
    return 6371.0 * 2 * math.asin(math.sqrt(a))


# GeoNames 把部分澳门堂区/香港街区标成 CN；未匹配的 CN 点若落在这些框内则归还
MACAU_BOX = (22.06, 22.24, 113.51, 113.62)   # latmin, latmax, lngmin, lngmax
HK_BOX = (22.13, 22.53, 113.83, 114.45)


def in_box(lat, lng, box):
    return box[0] <= lat <= box[1] and box[2] <= lng <= box[3]


def cluster_foreign(entries):
    """把街区/近郊点合并进同国 15km 内、人口 >= 其 10/3 倍的最近大城。"""
    entries = sorted(entries, key=lambda c: -c["population"])
    grid = {}
    for i, c in enumerate(entries):
        grid.setdefault((c["countrycode"], round(c["latitude"] / 0.2), round(c["longitude"] / 0.2)), []).append(i)
    parent = list(range(len(entries)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    for i, c in enumerate(entries):
        best, bestd = None, 1e18
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                for j in grid.get((c["countrycode"], round(c["latitude"] / 0.2) + dy,
                                   round(c["longitude"] / 0.2) + dx), []):
                    o = entries[j]
                    if o["population"] < c["population"] * (10 / 3):
                        continue
                    d = haversine(c["latitude"], c["longitude"], o["latitude"], o["longitude"])
                    if d <= 15 and d < bestd:
                        bestd, best = d, j
        if best is not None:
            parent[i] = find(best)
    return entries, [find(i) for i in range(len(entries))]


def main(out_path: str):
    pca = json.loads((Path(__file__).parent / "pca.json").read_text(encoding="utf-8"))
    cn_full, cn_stem = build_cn_name_index(pca)
    gc = geonamescache.GeonamesCache()
    cities = list(gc.get_cities().values())

    rows = []
    cn_matched, cn_unmatched, foreign = [], [], []

    for c in cities:
        cc, lat, lng, names = c["countrycode"], c["latitude"], c["longitude"], c["alternatenames"]
        if cc == "CN":
            pref, by_stem = None, False
            cands = sorted([n for n in names if CJK(n)], key=lambda n: (-len(n), n))
            for cand in cands:
                pref = cn_full.get(cand)
                if pref:
                    break
            if not pref:
                for cand in cands:
                    if cand.endswith(("镇", "乡", "街道")):
                        continue
                    pref = cn_stem.get(cand)
                    if pref:
                        by_stem = True
                        break
            if pref:
                cn_matched.append([lat, lng, pref, by_stem])
            else:
                cn_unmatched.append((lat, lng, None, c))
        elif cc == "HK":
            rows.append((lat, lng, "HK:香港", "香港"))
        elif cc == "MO":
            rows.append((lat, lng, "MO:澳门", "澳门"))
        else:
            foreign.append(c)

    # 词干匹配的点做地理校验：距离该地级市（全名匹配点的中位坐标）>250km 视为撞名，降级
    import statistics
    med = {}
    for lat, lng, pref, by_stem in cn_matched:
        if not by_stem:
            med.setdefault(pref, ([], []))
            med[pref][0].append(lat)
            med[pref][1].append(lng)
    med = {p: (statistics.median(v[0]), statistics.median(v[1])) for p, v in med.items()}
    kept = []
    for lat, lng, pref, by_stem in cn_matched:
        if by_stem and pref in med and haversine(lat, lng, *med[pref]) > 250:
            cn_unmatched.append((lat, lng, pref, None))  # 保留词干标签兜底
        else:
            kept.append([lat, lng, pref, by_stem])
    cn_matched = kept

    # 未匹配 CN 点：先做港澳矩形救援，再按最近已匹配点归属地级市
    anchors = [(lat, lng, pref) for lat, lng, pref, _ in cn_matched]
    dropped = 0
    for lat, lng, stem_pref, c in cn_unmatched:
        if in_box(lat, lng, MACAU_BOX):
            rows.append((lat, lng, "MO:澳门", "澳门"))
            continue
        if in_box(lat, lng, HK_BOX):
            rows.append((lat, lng, "HK:香港", "香港"))
            continue
        best, bestd = None, 1e18
        for mlat, mlng, pref in anchors:
            d = haversine(lat, lng, mlat, mlng)
            if d < bestd:
                bestd, best = d, pref
        if best is not None and bestd <= 120:
            cn_matched.append([lat, lng, best, False])
        elif stem_pref:
            cn_matched.append([lat, lng, stem_pref, False])
        else:
            dropped += 1

    for lat, lng, pref, _ in cn_matched:
        rows.append((lat, lng, f"CN:{pref}", pref))

    # 境外：先聚类（代々木→東京、Paris 04→Paris），再产出行
    entries, roots = cluster_foreign(foreign)
    for i, c in enumerate(entries):
        root = entries[roots[i]]
        zh = pick_cjk(root["alternatenames"])
        display = zh or root["name"]
        key = f"{root['countrycode']}:{root.get('admin1code') or ''}:{root['name']}"
        rows.append((c["latitude"], c["longitude"], key, display))

    rows.sort(key=lambda r: (r[2], r[0], r[1]))
    with open(out_path, "w", encoding="utf-8") as f:
        for lat, lng, key, display in rows:
            f.write(f"{lat:.5f}\t{lng:.5f}\t{key}\t{display}\n")

    prefs = {r[3] for r in rows if r[2].startswith("CN:")}
    fkeys = {r[2] for r in rows if not r[2].startswith(("CN:", "HK:", "MO:"))}
    merged = sum(1 for i, r in enumerate(roots) if r != i)
    print(f"rows={len(rows)} cn_prefectures={len(prefs)} cn_dropped={dropped} "
          f"foreign_cities={len(fkeys)} foreign_merged={merged}")
    print(f"wrote {out_path} ({Path(out_path).stat().st_size} bytes)")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "cities.tsv")
