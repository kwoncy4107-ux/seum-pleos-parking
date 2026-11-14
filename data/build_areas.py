"""Adds private areas (apartment complexes, parking lots) to seum.db, where no-parking rules do not apply.

Usage: python -I build_areas.py <osm_pbf> <db>

Source: OpenStreetMap (© OpenStreetMap contributors, ODbL). Kept in its own tables (a separate database
in ODbL terms) so the public-data layers are unaffected.

Also adds OSM parking lots open to the public to the `parking` table (kind "OSM", or "공영" when the name
says so) as the second choice after the public-data lots: skipped when private / customers-only, inside an
apartment complex, or within 30 m of a public-data lot already there.

Also adds side-street junctions (layer JUNCTION in `feature`): nodes where three or more street branches
meet. Seoul's intersection layer only has the major ones, and the corner rule (5 m) applies at every
junction, painted or not. Skipped within 20 m of a Seoul intersection point and inside apartment complexes.

Tables:
  area(id, kind, name, geom)   kind = APT (아파트 단지) | PARKING (주차장 부지); geom = "lat,lon;..." outer ring
  area_cell(cell, aid)         0.0005 deg grid, every cell the ring's bounding box touches

Apartment complexes in Korean OSM are usually landuse=residential polygons named after the complex
(…아파트, …자이, 래미안…); plain landuse=residential without such a name can cover whole neighbourhoods
including public streets, so those are skipped.
"""
import array, json, math, re, sqlite3, sys

import numpy as np

import osmium

CELL = 0.0005
APT_NAME = re.compile(r"아파트|APT|apt|단지|자이|래미안|힐스테이트|푸르지오|아이파크|이편한|e편한|롯데캐슬|더샵|"
                      r"현대|삼성|대림|우성|한신|쌍용|극동|선경|미성|한양|럭키|두산|타워팰리스|리센츠|파크리오|"
                      r"엘스|트리지움|주공|시영|SH|LH|레미안|캐슬|하이츠|맨션|빌리지|타운")


def cell_of(lat, lon):
    return int(math.floor(lat / CELL)) * 1_000_000 + int(math.floor(lon / CELL))


class H(osmium.SimpleHandler):
    def __init__(self, db):
        super().__init__()
        self.db = db
        self.n = {"APT": 0, "PARKING": 0}
        self.lots = []   # (lat, lon, name, tags) candidates for the parking table
        # street nodes: id, branch weight (1 at a way's end, 2 in its middle), position
        self.refs = array.array("q"); self.wts = array.array("b")
        self.lat = array.array("d"); self.lon = array.array("d")
        self.apt = []    # apartment rings, to drop lots and junctions inside complexes

    def way(self, w):
        if w.tags.get("highway") not in STREETS:
            return
        nodes = w.nodes
        last = len(nodes) - 1
        try:
            for i, n in enumerate(nodes):
                loc = n.location
                self.refs.append(n.ref); self.wts.append(1 if i in (0, last) else 2)
                self.lat.append(loc.lat); self.lon.append(loc.lon)
        except osmium.InvalidLocationError:
            return

    def node(self, n):
        if n.tags.get("amenity") == "parking":
            self.lot(n.location.lat, n.location.lon, n.tags)

    def lot(self, lat, lon, t):
        if t.get("access") in ("private", "no", "customers", "permit", "residents", "delivery"):
            return
        if t.get("parking") in ("street_side", "lane", "on_kerb", "half_on_kerb"):
            return
        if not (33.0 < lat < 38.7 and 124.5 < lon < 132.0):
            return
        name = t.get("name:ko") or t.get("name") or ""
        info = {"fee": t.get("fee", ""), "capacity": t.get("capacity", ""), "parking": t.get("parking", ""),
                "operator": t.get("operator", "")}
        self.lots.append((lat, lon, name, info))

    def area(self, a):
        t = a.tags
        kind = None
        name = t.get("name:ko") or t.get("name") or ""
        if t.get("landuse") == "residential" and (t.get("residential") == "apartments" or APT_NAME.search(name)):
            kind = "APT"
        elif t.get("amenity") == "parking" and t.get("access") not in ("yes", "public"):
            kind = "PARKING"
        elif t.get("amenity") == "parking":
            kind = "PARKING"
        if not kind:
            return
        try:
            for outer in a.outer_rings():
                pts = [(n.lat, n.lon) for n in outer]
                if len(pts) < 4:
                    continue
                self.add(kind, name, pts)
                if kind == "APT":
                    self.apt.append(pts)
                elif len(pts) >= 3:
                    self.lot(sum(p[0] for p in pts) / len(pts), sum(p[1] for p in pts) / len(pts), t)
        except osmium.InvalidLocationError:
            return

    def add(self, kind, name, pts):
        lats = [p[0] for p in pts]; lons = [p[1] for p in pts]
        geom = ";".join(f"{a:.6f},{b:.6f}" for a, b in pts)
        cur = self.db.execute("INSERT INTO area(kind,name,geom) VALUES(?,?,?)", (kind, name[:40], geom))
        a0, a1 = math.floor(min(lats) / CELL), math.floor(max(lats) / CELL)
        o0, o1 = math.floor(min(lons) / CELL), math.floor(max(lons) / CELL)
        if (a1 - a0 + 1) * (o1 - o0 + 1) > 4000:  # skip absurdly large polygons (data errors)
            return
        self.db.executemany("INSERT INTO area_cell(cell,aid) VALUES(?,?)",
                            ((a * 1_000_000 + o, cur.lastrowid) for a in range(a0, a1 + 1) for o in range(o0, o1 + 1)))
        self.n[kind] += 1


STREETS = {"primary", "secondary", "tertiary", "unclassified", "residential", "living_street"}


def apt_index(h):
    apt = {}
    for ring in h.apt:
        lats = [p[0] for p in ring]; lons = [p[1] for p in ring]
        a0, a1 = math.floor(min(lats) / CELL), math.floor(max(lats) / CELL)
        o0, o1 = math.floor(min(lons) / CELL), math.floor(max(lons) / CELL)
        if (a1 - a0 + 1) * (o1 - o0 + 1) > 4000:
            continue
        for a in range(a0, a1 + 1):
            for o in range(o0, o1 + 1):
                apt.setdefault(a * 1_000_000 + o, []).append(ring)
    return apt


def add_junctions(db, h, apt):
    """Street nodes with three or more branches, merged within ~10 m, as JUNCTION points."""
    db.execute("DELETE FROM feature_cell WHERE fid IN (SELECT id FROM feature WHERE layer='JUNCTION')")
    db.execute("DELETE FROM feature WHERE layer='JUNCTION'")
    refs = np.frombuffer(h.refs, dtype=np.int64)
    if refs.size == 0:
        return 0, 0, 0
    uniq, first, inv = np.unique(refs, return_index=True, return_inverse=True)
    branches = np.bincount(inv, weights=np.frombuffer(h.wts, dtype=np.int8).astype(np.float64))
    pick = first[branches >= 3]
    lat = np.frombuffer(h.lat, dtype=np.float64)[pick]; lon = np.frombuffer(h.lon, dtype=np.float64)[pick]
    # merge nodes of one junction (dual carriageways, offset side streets) on a ~10 m grid
    groups = {}
    for a, b in zip(lat, lon):
        groups.setdefault((int(a / 0.0001), int(b / 0.000125)), []).append((a, b))
    seoul = {}
    for a, b in db.execute("SELECT lat, lon FROM feature WHERE layer='INTERSECTION'"):
        seoul.setdefault(cell_of(a, b), []).append((a, b))
    n = near = inapt = 0
    for pts in groups.values():
        a = sum(p[0] for p in pts) / len(pts); b = sum(p[1] for p in pts) / len(pts)
        c = cell_of(a, b); ca, co = divmod(c, 1_000_000)
        cand = [q for da in (-1, 0, 1) for do in (-1, 0, 1) for q in seoul.get((ca + da) * 1_000_000 + co + do, [])]
        if any(math.hypot((q[0] - a) * 111_000, (q[1] - b) * 88_000) < 20 for q in cand):
            near += 1; continue
        if any(inside(a, b, r) for r in apt.get(c, [])):
            inapt += 1; continue
        cur = db.execute("INSERT INTO feature(layer,lat,lon,geom,info) VALUES('JUNCTION',?,?,NULL,'{\"src\":\"osm\"}')",
                         (round(a, 6), round(b, 6)))
        db.execute("INSERT INTO feature_cell(cell,fid) VALUES(?,?)", (c, cur.lastrowid))
        n += 1
    return n, near, inapt


def inside(lat, lon, ring):
    c = False
    j = len(ring) - 1
    for i in range(len(ring)):
        yi, xi = ring[i]; yj, xj = ring[j]
        if (yi > lat) != (yj > lat) and lon < (xj - xi) * (lat - yi) / (yj - yi + 1e-15) + xi:
            c = not c
        j = i
    return c


def add_lots(db, h):
    """OSM lots as the second choice; public-data rows (already in `parking`) stay first."""
    db.execute("DELETE FROM parking WHERE kind IN ('OSM') OR json_extract(info,'$.src')='osm'")
    have = {}
    for lat, lon in db.execute("SELECT lat, lon FROM parking"):
        have.setdefault(cell_of(lat, lon), []).append((lat, lon))
    apt = {}
    for ring in h.apt:
        lats = [p[0] for p in ring]; lons = [p[1] for p in ring]
        a0, a1 = math.floor(min(lats) / CELL), math.floor(max(lats) / CELL)
        o0, o1 = math.floor(min(lons) / CELL), math.floor(max(lons) / CELL)
        if (a1 - a0 + 1) * (o1 - o0 + 1) > 4000:
            continue
        for a in range(a0, a1 + 1):
            for o in range(o0, o1 + 1):
                apt.setdefault(a * 1_000_000 + o, []).append(ring)
    n = dup = inapt = 0
    for lat, lon, name, info in h.lots:
        c = cell_of(lat, lon)
        a, o = divmod(c, 1_000_000)
        near = [q for da in (-1, 0, 1) for do in (-1, 0, 1) for q in have.get((a + da) * 1_000_000 + o + do, [])]
        if any(math.hypot((q[0] - lat) * 111_000, (q[1] - lon) * 88_000) < 30 for q in near):
            dup += 1; continue
        if any(inside(lat, lon, r) for r in apt.get(c, [])):
            inapt += 1; continue
        kind = "공영" if "공영" in name else "OSM"
        info = dict(info, src="osm")
        if info["fee"] == "no":
            info["PARKINGCHRGE_INFO"] = "무료"
        db.execute("INSERT INTO parking(cell,lat,lon,name,kind,info) VALUES(?,?,?,?,?,?)",
                   (c, lat, lon, name[:40], kind, json.dumps(info, ensure_ascii=False)))
        n += 1
    return n, dup, inapt


def main(pbf, out):
    db = sqlite3.connect(out)
    db.executescript("""
        DROP TABLE IF EXISTS area; DROP TABLE IF EXISTS area_cell;
        CREATE TABLE area(id INTEGER PRIMARY KEY, kind TEXT NOT NULL, name TEXT, geom TEXT NOT NULL);
        CREATE TABLE area_cell(cell INTEGER NOT NULL, aid INTEGER NOT NULL);
    """)
    h = H(db)
    h.apply_file(pbf, locations=True, idx="flex_mem")
    db.execute("CREATE INDEX area_cell_idx ON area_cell(cell)")
    nl, dup, inapt = add_lots(db, h)
    h.n.update({"PARKING_OSM": nl, "osm_lot_dup": dup, "osm_lot_in_apt": inapt})
    nj, jnear, japt = add_junctions(db, h, apt_index(h))
    h.n.update({"JUNCTION": nj, "junction_near_seoul": jnear, "junction_in_apt": japt})
    db.executemany("INSERT OR REPLACE INTO meta VALUES(?,?)",
                   [(f"n_AREA_{k}", str(v)) for k, v in h.n.items()] + [("area_source", "© OpenStreetMap contributors (ODbL)"),
                    ("parking_osm_source", "© OpenStreetMap contributors (ODbL)")])
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(json.dumps(h.n))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
