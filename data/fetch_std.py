"""Download data.go.kr standard datasets through the same public JSON endpoints the site's CSV button uses."""
import json, sys, os, time, urllib.request, urllib.parse, csv, io

BASE = "https://www.data.go.kr"
UA = {"User-Agent": "Mozilla/5.0", "X-Requested-With": "XMLHttpRequest"}


def get(url):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read().decode("utf-8"))


def fetch(pk, outdir):
    h = get(f"{BASE}/download/columList.json?pk={pk}&ext=CSV")
    cols = h["columList"]
    tv = h["tableVO"]
    total = h["totalCount"]
    per = 10000
    out = os.path.join(outdir, f"std_{pk}.csv")
    with io.open(out, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow([c["columCode"] for c in cols])
        w.writerow([c["columNm"] for c in cols])
        n = 0
        # the site reports at most 50,000 rows, but later pages still return data: page until a short page
        for page in range(1, 1000):
            params = [("svcTableNm", tv["svcTableNm"]), ("perPage", per), ("page", page), ("totalCount", max(total, 10_000_000))]
            params += [("colNmList", c) for c in tv["colNmList"]]
            url = f"{BASE}/download/standard.json?publicDataPk={pk}&" + urllib.parse.urlencode(params)
            rows = get(url)
            for r in rows:
                w.writerow([r.get(c["columCode"], "") for c in cols])
            n += len(rows)
            time.sleep(0.5)
            if len(rows) < per:
                break
    print(pk, h.get("fileName"), "total", total, "written", n, flush=True)


if __name__ == "__main__":
    outdir = sys.argv[1]
    os.makedirs(outdir, exist_ok=True)
    for pk in sys.argv[2:]:
        try:
            fetch(pk, outdir)
        except Exception as e:
            print(pk, "ERROR", repr(e), flush=True)
