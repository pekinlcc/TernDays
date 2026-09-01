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

v0.5: fixed severe border skew around 深港/珠澳 — the old HK/MO rescue *boxes*
swallowed Shenzhen land points (蛇口/前海 → 香港), and anchor density was wildly
asymmetric (HK 146 points vs 深圳 14, 珠海 1), so nearest-neighbour misjudged
even 深圳湾口岸/罗湖口岸/拱北. Rescue is now by comparing distance to native
HK/MO points vs CN anchors, and hand-curated border anchors (EXTRA_*_ANCHORS)
pin both sides of the boundary. Verified by 32 border probes + 12 regressions.

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

def CJK(s):
    """含汉字且不含日文假名(避免把「オオハシ上科」「アイダホ州」当中文名选中)。"""
    has_han = any("一" <= ch <= "鿿" for ch in s)
    has_kana = any("぀" <= ch <= "ヿ" for ch in s)
    return has_han and not has_kana


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


# 注意:查表发生在「去掉尾字『市』」之后,键要写去尾后的形式
DISPLAY_OVERRIDES = {
    "三藩市": "旧金山", "三藩": "旧金山", "杜拜": "迪拜", "雪梨": "悉尼",
    "泽西": "泽西城",
}


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


# 边界敏感城市的手工锚点：GeoNames 对香港街区逐一建点（146 个）而深圳只有市中心
# 十几个点、珠海仅 1 个，最近邻分界线会深压进内地一侧（曾把蛇口/前海/罗湖口岸判成
# 香港、拱北/横琴判成澳门、深圳机场判成东莞）。补齐口岸/边界侧区镇锚点拉回分界线。
EXTRA_CN_ANCHORS = [
    # 深圳（深港边界从深圳湾到沙头角，加西部机场带与东部大鹏半岛）
    ("深圳", 22.480, 113.916), ("深圳", 22.513, 113.935), ("深圳", 22.510, 113.943),
    ("深圳", 22.535, 113.890), ("深圳", 22.520, 114.067), ("深圳", 22.526, 114.057),
    ("深圳", 22.533, 114.115), ("深圳", 22.554, 114.150), ("深圳", 22.553, 114.161),
    ("深圳", 22.554, 114.229),
    ("深圳", 22.558, 114.236), ("深圳", 22.595, 114.310), ("深圳", 22.591, 114.330),
    ("深圳", 22.628, 114.418), ("深圳", 22.588, 114.476), ("深圳", 22.531, 114.505),
    ("深圳", 22.480, 114.520), ("深圳", 22.639, 113.814), ("深圳", 22.674, 113.807),
    ("深圳", 22.735, 113.814), ("深圳", 22.766, 113.848), ("深圳", 22.749, 113.917),
    ("深圳", 22.657, 114.036), ("深圳", 22.720, 114.058), ("深圳", 22.690, 114.130),
    ("深圳", 22.610, 114.110), ("深圳", 22.640, 114.200), ("深圳", 22.690, 114.350),
    ("深圳", 22.700, 114.405),
    # 珠海（珠澳边界拱北—湾仔—横琴，加西部金湾/斗门）
    ("珠海", 22.268, 113.544), ("珠海", 22.228, 113.548), ("珠海", 22.222, 113.552),
    ("珠海", 22.225, 113.517),
    ("珠海", 22.252, 113.520), ("珠海", 22.240, 113.500), ("珠海", 22.130, 113.520),
    ("珠海", 22.130, 113.543), ("珠海", 22.155, 113.535),
    ("珠海", 22.102, 113.529), ("珠海", 22.146, 113.545), ("珠海", 22.360, 113.600),
    ("珠海", 22.340, 113.570), ("珠海", 22.008, 113.377), ("珠海", 22.040, 113.410),
    ("珠海", 22.120, 113.320), ("珠海", 22.210, 113.285), ("珠海", 22.230, 113.320),
    ("珠海", 22.055, 113.290),
    # 东莞南部（与深圳宝安接壤，防止长安/滨海湾被新增的深圳锚点吸走）
    ("东莞", 22.804, 113.807), ("东莞", 22.786, 113.746), ("东莞", 22.820, 113.860),
    # 环北京卫星城（行政属廊坊三河/大厂/香河,此前整片被判北京)
    ("廊坊", 39.947, 116.800), ("廊坊", 39.982, 117.078), ("廊坊", 39.886, 116.989),
    ("廊坊", 39.761, 117.006),
    # 北京通州侧(「通州」京/苏撞名被词典丢弃,须锚点钉住,免被燕郊锚吸走)
    ("北京", 39.909, 116.656), ("北京", 39.936, 116.692), ("北京", 39.913, 116.752),
    # 花桥—安亭走廊(花桥属苏州昆山;上海嘉定西缘补锚防反向误吸)
    ("苏州", 31.257, 121.100), ("苏州", 31.310, 121.080),
    ("上海", 31.297, 121.166), ("上海", 31.375, 121.265),
    # 广佛界(南海东部黄岐/盐步/大沥/里水/桂城属佛山;广州侧金沙洲/滘口)
    ("佛山", 23.104, 113.211), ("佛山", 23.099, 113.185), ("佛山", 23.115, 113.155),
    ("佛山", 23.157, 113.194), ("佛山", 23.031, 113.147),
    ("广州", 23.160, 113.215), ("广州", 23.093, 113.230),
]

# 港澳一侧同样补口岸/边界锚点（GeoNames 街区点集中在市区，口岸带稀疏），
# 让深圳河/关闸两侧各有近锚，分界线落回真实边界线
EXTRA_HKMO_ANCHORS = [
    ("HK:香港", "香港", 22.511, 114.066),  # 落马洲管制站
    ("HK:香港", "香港", 22.528, 114.117),  # 罗湖站
    ("HK:香港", "香港", 22.529, 114.129),  # 文锦渡（港侧）
    ("HK:香港", "香港", 22.546, 114.164),  # 香园围口岸
    ("HK:香港", "香港", 22.545, 114.222),  # 沙头角（港侧）
    ("HK:香港", "香港", 22.487, 114.005),  # 尖鼻咀（深圳湾对岸）
    ("MO:澳门", "澳门", 22.213, 113.549),  # 关闸
    ("MO:澳门", "澳门", 22.158, 113.560),  # 氹仔
    ("MO:澳门", "澳门", 22.144, 113.565),  # 路氹城
    ("MO:澳门", "澳门", 22.109, 113.557),  # 路环
]


CLUSTER_MAX_POP = 200_000  # 大城不并入他城(泽西城 29 万曾被并进纽约)


def cluster_foreign(entries):
    """把街区/近郊点合并进同国同一级行政区 15km 内、人口 >= 其 10/3 倍的最近大城。
    跨 admin1 不并(泽西城 NJ ≠ 纽约 NY),人口 >= 20 万的城市不并。"""
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
        if c["population"] >= CLUSTER_MAX_POP:
            continue
        best, bestd = None, 1e18
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                for j in grid.get((c["countrycode"], round(c["latitude"] / 0.2) + dy,
                                   round(c["longitude"] / 0.2) + dx), []):
                    o = entries[j]
                    if o["population"] < c["population"] * (10 / 3):
                        continue
                    if (o.get("admin1code") or "") != (c.get("admin1code") or ""):
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
    hkmo_native = []  # 原生 HK/MO 点，供未匹配 CN 点的边界救援比较

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
            hkmo_native.append((lat, lng, "HK:香港", "香港"))
        elif cc == "MO":
            rows.append((lat, lng, "MO:澳门", "澳门"))
            hkmo_native.append((lat, lng, "MO:澳门", "澳门"))
        elif cc == "SG":
            # 城市国家整体记一城:住兀兰/裕廊/义顺都是「在新加坡」
            rows.append((lat, lng, "SG:新加坡", "新加坡"))
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

    # 手工锚点先入列，参与后续未匹配点的吸附
    for pref, lat, lng in EXTRA_CN_ANCHORS:
        cn_matched.append([lat, lng, pref, False])
    for key, disp, lat, lng in EXTRA_HKMO_ANCHORS:
        rows.append((lat, lng, key, disp))
        hkmo_native.append((lat, lng, key, disp))

    # 未匹配 CN 点：GeoNames 把部分港澳街区/堂区标成 CN，先比较「最近原生 HK/MO 点」
    # 与「最近内地锚点」谁更近来救援（比矩形框贴合真实边界，不会吞掉蛇口/前海等
    # 深圳陆地点），再按最近已匹配点归属地级市
    anchors = [(lat, lng, pref) for lat, lng, pref, _ in cn_matched]
    dropped = 0
    for lat, lng, stem_pref, c in cn_unmatched:
        best, bestd = None, 1e18
        for mlat, mlng, pref in anchors:
            d = haversine(lat, lng, mlat, mlng)
            if d < bestd:
                bestd, best = d, pref
        hbest, hbestd = None, 1e18
        for hlat, hlng, key, disp in hkmo_native:
            d = haversine(lat, lng, hlat, hlng)
            if d < hbestd:
                hbestd, hbest = d, (key, disp)
        if hbest is not None and hbestd < bestd and hbestd <= 25:
            rows.append((lat, lng, hbest[0], hbest[1]))
            continue
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

    # 同 key 撞名消解：同 (cc:admin1:name) 下的不同聚类根是两座真实不同的城市
    # （如 JP:12:Shibetsu 的标津町与士别市），次要根的 key 追加 #geonameid 区分
    def root_key(root):
        return f"{root['countrycode']}:{root.get('admin1code') or ''}:{root['name']}"

    key_roots: dict = {}
    for i in range(len(entries)):
        root = entries[roots[i]]
        key_roots.setdefault(root_key(root), {})[root["geonameid"]] = root["population"]
    key_suffix = {}
    dedup_keys = 0
    for key, gids in key_roots.items():
        if len(gids) > 1:
            dedup_keys += 1
            main_gid = max(gids, key=lambda g: gids[g])
            for gid in gids:
                if gid != main_gid:
                    key_suffix[(key, gid)] = f"{key}#{gid}"

    for i, c in enumerate(entries):
        root = entries[roots[i]]
        zh = pick_cjk(root["alternatenames"])
        display = zh or root["name"]
        key = root_key(root)
        key = key_suffix.get((key, root["geonameid"]), key)
        rows.append((c["latitude"], c["longitude"], key, display))

    rows = sorted(set(rows), key=lambda r: (r[2], r[0], r[1]))  # set 去掉完全重复行

    # 中文城市补拼音别名列(第 5 列,可选),让 beijing/shenzhen 也能搜到
    from pypinyin import lazy_pinyin

    pinyin_cache: dict = {}

    def alias(key, display):
        if not (key.startswith("CN:") or key.startswith("HK:") or key.startswith("MO:") or key.startswith("SG:")):
            return ""
        if display not in pinyin_cache:
            pinyin_cache[display] = "".join(lazy_pinyin(display))
        return pinyin_cache[display]

    with open(out_path, "w", encoding="utf-8") as f:
        for lat, lng, key, display in rows:
            a = alias(key, display)
            if a:
                f.write(f"{lat:.5f}\t{lng:.5f}\t{key}\t{display}\t{a}\n")
            else:
                f.write(f"{lat:.5f}\t{lng:.5f}\t{key}\t{display}\n")

    prefs = {r[3] for r in rows if r[2].startswith("CN:")}
    fkeys = {r[2] for r in rows if not r[2].startswith(("CN:", "HK:", "MO:"))}
    merged = sum(1 for i, r in enumerate(roots) if r != i)
    print(f"rows={len(rows)} cn_prefectures={len(prefs)} cn_dropped={dropped} "
          f"foreign_cities={len(fkeys)} foreign_merged={merged} key_collisions_resolved={dedup_keys}")
    print(f"wrote {out_path} ({Path(out_path).stat().st_size} bytes)")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "cities.tsv")
