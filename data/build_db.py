"""Build the on-device SQLite bundle (seum.db) from public datasets.

Usage: python -I build_db.py <raw_dir> <out_db>

raw_dir must contain:
  lane/A058_L.*            Seoul T-GIS lane lines (OA-15537), EPSG:5186
  cw/A004_A.*              Seoul T-GIS crosswalk polygons (OA-15554), EPSG:5186
  enf_*.csv                Seoul illegal-parking enforcement records (OA-22190), cp949
  std_15034538.csv         national fire hydrants (data.go.kr standard)
  std_15028200.csv         national unmanned enforcement cameras
  std_15012891.csv         national child protection zones
  std_15012896.csv         national parking lots
  std_bus.csv (optional)   bus stops: columns name,lat,lon

Tables (all coordinates WGS84):
  feature(id, layer, cell, lat, lon, geom, info)   geom = "lat,lon;lat,lon;..." for lines/polygons
  enf_cell(cell, total, hours)                     hours = 24 comma-separated counts
  enf_pt(cell, total)                              same records on a 0.00003 deg grid (cell = lat_i*10^8 + lon_i)
  parking(id, cell, lat, lon, name, kind, info)
  meta(key, value)

cell = grid index of 0.0005 deg (about 55 m x 44 m); the engine scans the 3x3 block around the car.
Enforcement cells use the same grid.
"""
import csv, glob, io, json, math, os, sqlite3, sys, collections

import shapefile
from pyproj import Transformer

CELL = 0.0005
T5186 = Transformer.from_crs("EPSG:5186", "EPSG:4326", always_xy=True)


def cell_of(lat, lon):
    return int(math.floor(lat / CELL)) * 1_000_000 + int(math.floor(lon / CELL))


def fnum(s):
    try:
        v = float(str(s).strip())
        return v if math.isfinite(v) else None
    except (TypeError, ValueError):
        return None


def valid_kr(lat, lon):
    return lat is not None and lon is not None and 33.0 < lat < 39.0 and 124.5 < lon < 132.0


def read_std(path):
    with io.open(path, encoding="utf-8", newline="") as f:
        r = csv.reader(f)
        codes = next(r)
        next(r)  # Korean header row
        for row in r:
            yield dict(zip(codes, row))


class Writer:
    def __init__(self, db):
        self.db = db
        self.n = collections.Counter()

    def point(self, layer, lat, lon, info):
        cur = self.db.execute("INSERT INTO feature(layer,lat,lon,geom,info) VALUES(?,?,?,?,?)",
                              (layer, round(lat, 6), round(lon, 6), None, json.dumps(info, ensure_ascii=False)))
        self.db.execute("INSERT INTO feature_cell(cell,fid) VALUES(?,?)", (cell_of(lat, lon), cur.lastrowid))
        self.n[layer] += 1

    def shape(self, layer, pts, info):
        """Store a line/polygon once and index it in every grid cell it crosses, so a 3x3 scan always finds it."""
        geom = ";".join(f"{a:.6f},{b:.6f}" for a, b in pts)
        cells = set()
        for (a1, b1), (a2, b2) in zip(pts, pts[1:] or pts):
            steps = max(1, int(max(abs(a2 - a1), abs(b2 - b1)) / (CELL / 2)))
            for k in range(steps + 1):
                t = k / steps
                cells.add(cell_of(a1 + (a2 - a1) * t, b1 + (b2 - b1) * t))
        clat = sum(p[0] for p in pts) / len(pts)
        clon = sum(p[1] for p in pts) / len(pts)
        cur = self.db.execute("INSERT INTO feature(layer,lat,lon,geom,info) VALUES(?,?,?,?,?)",
                              (layer, round(clat, 6), round(clon, 6), geom, json.dumps(info, ensure_ascii=False)))
        self.db.executemany("INSERT INTO feature_cell(cell,fid) VALUES(?,?)", ((c, cur.lastrowid) for c in cells))
        self.n[layer] += 1


def to_wgs(points):
    out = []
    for x, y in points:
        lon, lat = T5186.transform(x, y)
        out.append((lat, lon))
    return out


# Seoul T-GIS lane codes (A058_KND_CDE), from the dataset's table definition PDF
LANE_LAYERS = {
    "010": ("NO_STOP_LINE", "주정차금지선(황색 실선)"),
    "009": ("NO_PARK_LINE", "주차금지선(황색 점선)"),
    "014": ("NO_STOP_BOX", "정차금지지대"),
}


def build_lanes(w, raw):
    r = shapefile.Reader(os.path.join(raw, "lane", "A058_L"), encoding="cp949")
    names = [f[0] for f in r.fields[1:]]
    for sr in r.iterShapeRecords():
        rec = dict(zip(names, sr.record))
        kind = rec["A058_KND_C"]
        if kind not in LANE_LAYERS or rec["STAT_CDE"] == "":
            continue
        layer, label = LANE_LAYERS[kind]
        pts = sr.shape.points
        parts = list(sr.shape.parts) + [len(pts)]
        for a, b in zip(parts, parts[1:]):
            seg = to_wgs(pts[a:b])
            if len(seg) >= 2:
                w.shape(layer, seg, {})


def build_crosswalks(w, raw):
    r = shapefile.Reader(os.path.join(raw, "cw", "A004_A"), encoding="cp949")
    names = [f[0] for f in r.fields[1:]]
    for sr in r.iterShapeRecords():
        rec = dict(zip(names, sr.record))
        pts = to_wgs(sr.shape.points)
        if len(pts) >= 3:
            w.shape("CROSSWALK", pts, {"x": 1} if str(rec.get("CSS_CDE", "")).strip() else {})


def build_hydrants(w, raw):
    for r in read_std(os.path.join(raw, "std_15034538.csv")):
        lat, lon = fnum(r.get("LATITUDE")), fnum(r.get("LONGITUDE"))
        if valid_kr(lat, lon):
            w.point("HYDRANT", lat, lon, {"detail": (r.get("DESC_LC", "") or "")[:30]})


def build_cctv(w, raw):
    for r in read_std(os.path.join(raw, "std_15028200.csv")):
        se = r.get("REGLT_SE", "")
        # 단속구분 4/04 = 불법주정차 (standard data code table)
        if "4" not in se.replace("04", "4").split("+"):
            continue
        lat, lon = fnum(r.get("LATITUDE")), fnum(r.get("LONGITUDE"))
        if valid_kr(lat, lon):
            w.point("CCTV", lat, lon, {"label": "고정 단속 CCTV", "place": r.get("ITLPC", ""),
                                       "ref": r.get("REFERENCE_DATE", "")})


def build_school_zones(w, raw):
    for r in read_std(os.path.join(raw, "std_15012891.csv")):
        # every row is a protection-zone facility; the Y/N column is blank for ~95% of rows, so only drop explicit N
        if r.get("CHLD_PRTN_ZON_YN", "").strip().upper() == "N":
            continue
        lat, lon = fnum(r.get("LATITUDE")), fnum(r.get("LONGITUDE"))
        if valid_kr(lat, lon):
            w.point("SCHOOL_ZONE", lat, lon, {"label": "어린이보호구역", "name": r.get("TRGET_FCLTY_NM", ""),
                                              "ref": r.get("REFERENCE_DATE", "")})


def build_bus(w, raw):
    """국토교통부 전국 버스정류장 위치정보 (data.go.kr 15067528), cp949 CSV:
    정류장번호,정류장명,위도,경도,정보수집일,모바일단축번호,도시코드,도시명,관리도시명"""
    p = os.path.join(raw, "bus_tago.csv")
    if not os.path.exists(p):
        return
    with io.open(p, encoding="cp949", errors="replace", newline="") as f:
        r = csv.reader(f)
        next(r)
        for row in r:
            if len(row) < 4:
                continue
            lat, lon = fnum(row[2]), fnum(row[3])
            if valid_kr(lat, lon):
                w.point("BUS_STOP", lat, lon, {"n": row[1][:20]})


def build_national_crosswalks(w, raw):
    """전국횡단보도표준데이터 points, used outside Seoul (Seoul has T-GIS polygons)."""
    p = os.path.join(raw, "std_15028201.csv")
    if not os.path.exists(p):
        return
    for r in read_std(p):
        if "서울" in (r.get("CTPRVN_NM", "") or ""):
            continue
        lat, lon = fnum(r.get("LATITUDE")), fnum(r.get("LONGITUDE"))
        if valid_kr(lat, lon):
            w.point("CROSSWALK_PT", lat, lon, {})


def build_intersections(w, raw):
    """서울시 교차로 (T-GIS A008_P, OA-15534), EPSG:5186 points."""
    base = os.path.join(raw, "isec", "A008_P", "A008_P")
    if not os.path.exists(base + ".shp"):
        return
    r = shapefile.Reader(base, encoding="cp949")
    names = [f[0] for f in r.fields[1:]]
    for sr in r.iterShapeRecords():
        rec = dict(zip(names, sr.record))
        (lat, lon), = to_wgs(sr.shape.points[:1])
        w.point("INTERSECTION", lat, lon, {"n": str(rec.get("CSS_NAM", ""))[:20]})


def build_enforcement(db, raw):
    cells = collections.defaultdict(lambda: [0] * 24)
    files = sorted(glob.glob(os.path.join(raw, "enf_*.csv")))
    days = set()
    for path in files:
        # OA-22190 files are UTF-8 with BOM (decoding them as cp949 garbles the address columns)
        with io.open(path, encoding="utf-8-sig", errors="replace", newline="") as f:
            r = csv.reader(f)
            next(r)
            for row in r:
                if len(row) < 6:
                    continue
                lon, lat = fnum(row[4]), fnum(row[5])
                if not valid_kr(lat, lon):
                    continue
                try:
                    hour = int(row[1].strip().split(":")[0])
                except ValueError:
                    continue
                if 0 <= hour < 24:
                    cells[cell_of(lat, lon)][hour] += 1
                    days.add(row[0].strip('"'))
    db.executemany("INSERT INTO enf_cell(cell,total,hours) VALUES(?,?,?)",
                   ((c, sum(h), ",".join(map(str, h))) for c, h in cells.items()))
    span = f"{min(days)}~{max(days)}" if days else ""
    return len(cells), sum(sum(h) for h in cells.values()), span


PT = 0.00003  # ~3.3 m x 2.6 m


def build_enforcement_points(db, raw):
    """Where each ticket was written, on a ~3 m grid: drawn on the map and counted for "tickets right here"."""
    db.executescript("DROP TABLE IF EXISTS enf_fine; DROP TABLE IF EXISTS enf_pt;"
                     "CREATE TABLE enf_pt(cell INTEGER PRIMARY KEY, total INTEGER NOT NULL);")
    cells = collections.Counter()
    for path in sorted(glob.glob(os.path.join(raw, "enf_*.csv"))):
        with io.open(path, encoding="utf-8-sig", errors="replace", newline="") as f:
            r = csv.reader(f)
            next(r)
            for row in r:
                if len(row) < 6:
                    continue
                lon, lat = fnum(row[4]), fnum(row[5])
                if valid_kr(lat, lon):
                    cells[int(math.floor(lat / PT)) * 100_000_000 + int(math.floor(lon / PT))] += 1
    db.executemany("INSERT INTO enf_pt(cell,total) VALUES(?,?)", cells.items())
    return len(cells)


def build_parking(db, raw):
    n = 0
    for r in read_std(os.path.join(raw, "std_15012896.csv")):
        lat, lon = fnum(r.get("LATITUDE")), fnum(r.get("LONGITUDE"))
        if not valid_kr(lat, lon):
            continue
        info = {k: r.get(k, "") for k in ("PRKCMPRT", "BASIC_TIME", "BASIC_CHARGE", "ADD_UNIT_TIME", "ADD_UNIT_CHARGE",
                                           "PARKINGCHRGE_INFO", "WEEKDAY_OPER_OPEN_HHMM", "WEEKDAY_OPER_COLSE_HHMM",
                                           "RDNMADR", "LNMADR")}
        db.execute("INSERT INTO parking(cell,lat,lon,name,kind,info) VALUES(?,?,?,?,?,?)",
                   (cell_of(lat, lon), lat, lon, r.get("PRKPLCE_NM", ""), r.get("PRKPLCE_SE", ""),
                    json.dumps(info, ensure_ascii=False)))
        n += 1
    return n


def main(raw, out):
    if os.path.exists(out):
        os.remove(out)
    db = sqlite3.connect(out)
    db.executescript("""
        CREATE TABLE feature(id INTEGER PRIMARY KEY, layer TEXT NOT NULL,
                             lat REAL NOT NULL, lon REAL NOT NULL, geom TEXT, info TEXT);
        CREATE TABLE feature_cell(cell INTEGER NOT NULL, fid INTEGER NOT NULL);
        CREATE TABLE enf_cell(cell INTEGER PRIMARY KEY, total INTEGER NOT NULL, hours TEXT NOT NULL);
        CREATE TABLE parking(id INTEGER PRIMARY KEY, cell INTEGER NOT NULL, lat REAL, lon REAL,
                             name TEXT, kind TEXT, info TEXT);
        CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
    """)
    w = Writer(db)
    build_lanes(w, raw)
    build_crosswalks(w, raw)
    build_hydrants(w, raw)
    build_cctv(w, raw)
    build_school_zones(w, raw)
    build_bus(w, raw)
    build_national_crosswalks(w, raw)
    build_intersections(w, raw)
    ncell, nenf, span = build_enforcement(db, raw)
    npt = build_enforcement_points(db, raw)
    npark = build_parking(db, raw)
    db.executescript("""
        CREATE INDEX feature_cell_idx ON feature_cell(cell);
        CREATE INDEX parking_cell ON parking(cell);
    """)
    meta = {"cell_deg": str(CELL), "enf_span": span, "enf_records": str(nenf), "enf_cells": str(ncell),
            "parking": str(npark), "enf_points": str(npt), **{f"n_{k}": str(v) for k, v in w.n.items()}}
    db.executemany("INSERT INTO meta VALUES(?,?)", meta.items())
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(json.dumps(meta, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
