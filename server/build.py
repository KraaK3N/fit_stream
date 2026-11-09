#!/usr/bin/env python3
"""Build a single static index.html dashboard from the FitStream SQLite database.

Usage: python build.py [--db PATH] [--out PATH] [--days N] [--plotly-js PATH]

If --plotly-js (or PLOTLY_JS) points at a local plotly.min.js it is inlined, so the page
has no external dependencies. Otherwise the page loads Plotly from the CDN.
"""

import argparse
import html
import json
import os
import sqlite3
from datetime import date, datetime, timedelta, timezone

PLOTLY_CDN = "https://cdn.plot.ly/plotly-2.35.2.min.js"


def query(conn, sql, args=()):
    return [dict(r) for r in conn.execute(sql, args).fetchall()]


def rolling_mean(values, window=7):
    out = []
    for i in range(len(values)):
        chunk = [v for v in values[max(0, i - window + 1): i + 1] if v is not None]
        out.append(round(sum(chunk) / len(chunk), 1) if chunk else None)
    return out


def avg(values):
    values = [v for v in values if v is not None]
    return sum(values) / len(values) if values else None


def load(conn, since):
    data = {
        "steps": query(conn, "SELECT * FROM steps WHERE date >= ? ORDER BY date", (since,)),
        "sleep": query(conn, "SELECT * FROM sleep WHERE date >= ? ORDER BY date", (since,)),
        "heart_rate": query(conn, "SELECT * FROM heart_rate WHERE date >= ? ORDER BY date", (since,)),
        "workouts": query(conn, "SELECT * FROM workouts WHERE date >= ? ORDER BY start_time DESC", (since,)),
        "readings": [],
        "last_sync": None,
    }
    for r in query(conn, "SELECT metric, time, values_json FROM readings WHERE substr(time,1,10) >= ? ORDER BY time", (since,)):
        data["readings"].append({"metric": r["metric"], "time": r["time"], "values": json.loads(r["values_json"])})
    row = conn.execute("SELECT received_at FROM sync_log ORDER BY id DESC LIMIT 1").fetchone()
    data["last_sync"] = row[0] if row else None
    return data


def sleep_by_night(sleep_rows):
    """Collapse multiple sessions (naps, split nights) into one bar per date."""
    nights = {}
    for s in sleep_rows:
        n = nights.setdefault(s["date"], {"awake": 0, "light": 0, "deep": 0, "rem": 0, "total": 0})
        n["awake"] += s["awake_min"] or 0
        n["light"] += s["light_min"] or 0
        n["deep"] += s["deep_min"] or 0
        n["rem"] += s["rem_min"] or 0
        n["total"] += s["duration_min"] or 0
    return dict(sorted(nights.items()))


def kpis(data):
    steps_avg = avg([s["count"] for s in data["steps"]])
    nights = sleep_by_night(data["sleep"])
    # Asleep time excludes awake minutes; fall back to session length if no stages were recorded.
    sleep_avg = avg([(n["total"] - n["awake"]) if (n["light"] + n["deep"] + n["rem"]) else n["total"] for n in nights.values()])
    resting = [r["values"].get("bpm") for r in data["readings"] if r["metric"] == "resting_hr"]
    resting_avg = avg(resting) if resting else avg([h["min_bpm"] for h in data["heart_rate"]])
    return [
        ("Avg daily steps", f"{steps_avg:,.0f}" if steps_avg is not None else "–"),
        ("Avg sleep", f"{int(sleep_avg // 60)}h {int(sleep_avg % 60):02d}m" if sleep_avg is not None else "–"),
        ("Avg resting HR" if resting else "Avg lowest HR", f"{resting_avg:.0f} bpm" if resting_avg is not None else "–"),
        ("Workouts", str(len(data["workouts"]))),
    ]


def figures(data):
    figs = {}
    layout = {"margin": {"t": 30, "r": 10, "b": 40, "l": 50}, "hovermode": "x unified", "legend": {"orientation": "h", "y": -0.2}}

    if data["steps"]:
        x = [s["date"] for s in data["steps"]]
        y = [s["count"] for s in data["steps"]]
        figs["steps"] = {
            "data": [
                {"type": "bar", "x": x, "y": y, "name": "Steps", "marker": {"color": "#4c9be8"}},
                {"type": "scatter", "mode": "lines", "x": x, "y": rolling_mean(y), "name": "7-day avg", "line": {"color": "#d9534f", "width": 3}},
            ],
            "layout": layout,
        }

    nights = sleep_by_night(data["sleep"])
    if nights:
        x = list(nights)
        stages = [("awake", "Awake", "#f0ad4e"), ("light", "Light", "#9ecae1"), ("deep", "Deep", "#3182bd"), ("rem", "REM", "#756bb1")]
        traces = [{"type": "bar", "x": x, "y": [round(nights[d][k] / 60, 2) for d in x], "name": label, "marker": {"color": c}} for k, label, c in stages]
        unstaged = [round(max(0, nights[d]["total"] - sum(nights[d][k] for k, *_ in stages)) / 60, 2) for d in x]
        if any(unstaged):
            traces.append({"type": "bar", "x": x, "y": unstaged, "name": "Unstaged", "marker": {"color": "#bbbbbb"}})
        figs["sleep"] = {"data": traces, "layout": {**layout, "barmode": "stack", "yaxis": {"title": "hours"}}}

    if data["heart_rate"]:
        x = [h["date"] for h in data["heart_rate"]]
        figs["heart_rate"] = {
            "data": [
                {"type": "scatter", "mode": "lines", "x": x, "y": [h["max_bpm"] for h in data["heart_rate"]], "name": "Max", "line": {"width": 0}, "showlegend": False},
                {"type": "scatter", "mode": "lines", "x": x, "y": [h["min_bpm"] for h in data["heart_rate"]], "name": "Min–max", "fill": "tonexty", "fillcolor": "rgba(217,83,79,0.2)", "line": {"width": 0}},
                {"type": "scatter", "mode": "lines+markers", "x": x, "y": [h["avg_bpm"] for h in data["heart_rate"]], "name": "Average", "line": {"color": "#d9534f"}},
            ],
            "layout": {**layout, "yaxis": {"title": "bpm"}},
        }

    by_metric = {}
    for r in data["readings"]:
        by_metric.setdefault(r["metric"], []).append(r)
    for metric, rows in sorted(by_metric.items()):
        keys = sorted({k for r in rows for k in r["values"]})
        figs[f"reading_{metric}"] = {
            "title": metric.replace("_", " ").title(),
            "data": [{"type": "scatter", "mode": "lines+markers", "x": [r["time"] for r in rows], "y": [r["values"].get(k) for r in rows], "name": k} for k in keys],
            "layout": layout,
        }
    return figs


def fmt(v, digits=0, suffix=""):
    return "–" if v is None else f"{v:,.{digits}f}{suffix}"


def workouts_table(workouts, limit=20):
    if not workouts:
        return "<p class='empty'>No workouts in this period.</p>"
    rows = "".join(
        "<tr>"
        f"<td>{html.escape(w['date'])}</td>"
        f"<td>{html.escape(w['type'].replace('_', ' ').title())}</td>"
        f"<td>{fmt(w['duration_min'], 0, ' min')}</td>"
        f"<td>{fmt(w['calories'], 0, ' kcal')}</td>"
        f"<td>{fmt(w['distance_km'], 2, ' km')}</td>"
        f"<td>{fmt(w['avg_hr'], 0)} / {fmt(w['max_hr'], 0)}</td>"
        "</tr>"
        for w in workouts[:limit]
    )
    return ("<table><thead><tr><th>Date</th><th>Type</th><th>Duration</th><th>Calories</th>"
            f"<th>Distance</th><th>Avg / max HR</th></tr></thead><tbody>{rows}</tbody></table>")


def render(data, days, plotly_js=None):
    figs = figures(data)
    cards = "".join(f"<div class='card'><div class='label'>{html.escape(k)}</div><div class='value'>{html.escape(v)}</div></div>" for k, v in kpis(data))

    sections = []
    for key, title in (("steps", "Steps"), ("sleep", "Sleep"), ("heart_rate", "Heart rate")):
        body = f"<div id='fig-{key}' class='chart'></div>" if key in figs else "<p class='empty'>No data yet.</p>"
        sections.append(f"<section><h2>{title}</h2>{body}</section>")
    sections.append(f"<section><h2>Recent workouts</h2>{workouts_table(data['workouts'])}</section>")
    readings = [k for k in figs if k.startswith("reading_")]
    if readings:
        sections.append("<section><h2>Body metrics</h2>" + "".join(
            f"<h3>{html.escape(figs[k]['title'])}</h3><div id='fig-{k}' class='chart small'></div>" for k in readings) + "</section>")

    # json.dumps output is embedded in a <script>; escape "</" so data can never close the tag.
    fig_json = json.dumps({k: {"data": v["data"], "layout": v["layout"]} for k, v in figs.items()}).replace("</", "<\\/")
    if plotly_js:
        plotly_tag = "<script>" + plotly_js.replace("</script", "<\\/script") + "</script>"
    else:
        plotly_tag = f"<script src='{PLOTLY_CDN}' charset='utf-8'></script>"

    built = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    synced = html.escape(data["last_sync"][:16].replace("T", " ") + " UTC") if data["last_sync"] else "never"
    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>FitStream</title>
<style>
body{{font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;margin:0;background:#f5f6f8;color:#222}}
header{{padding:20px 24px;background:#1f2937;color:#fff}} header h1{{margin:0;font-size:22px}}
header p{{margin:4px 0 0;color:#cbd5e1;font-size:13px}}
main{{max-width:1100px;margin:0 auto;padding:16px}}
.cards{{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:12px}}
.card,section{{background:#fff;border-radius:10px;padding:16px;box-shadow:0 1px 3px rgba(0,0,0,.08)}}
.label{{font-size:13px;color:#666}} .value{{font-size:28px;font-weight:600;margin-top:4px}}
section{{margin-top:16px}} h2{{margin:0 0 8px;font-size:17px}} h3{{font-size:14px;color:#555}}
.chart{{height:340px}} .chart.small{{height:240px}} .empty{{color:#888}}
table{{width:100%;border-collapse:collapse;font-size:14px}} th,td{{text-align:left;padding:6px 8px;border-bottom:1px solid #eee}}
</style>{plotly_tag}</head>
<body><header><h1>FitStream</h1><p>Last {days} days · last sync {synced} · built {built}</p></header>
<main><div class="cards">{cards}</div>{''.join(sections)}</main>
<script>
const FIGS = {fig_json};
for (const [k, f] of Object.entries(FIGS)) {{
  Plotly.newPlot('fig-' + k, f.data, f.layout, {{responsive: true, displaylogo: false}});
}}
</script></body></html>
"""


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--db", default=os.environ.get("FITSTREAM_DB", "/data/fitstream.db"))
    p.add_argument("--out", default=os.environ.get("FITSTREAM_OUT", "/site/index.html"))
    p.add_argument("--days", type=int, default=int(os.environ.get("FITSTREAM_DAYS", "90")))
    p.add_argument("--plotly-js", default=os.environ.get("PLOTLY_JS"))
    a = p.parse_args()

    if not os.path.exists(a.db):
        raise SystemExit(f"database not found: {a.db}")
    conn = sqlite3.connect(f"file:{a.db}?mode=ro", uri=True)
    conn.row_factory = sqlite3.Row
    since = (date.today() - timedelta(days=a.days)).isoformat()
    data = load(conn, since)
    conn.close()

    plotly_js = None
    if a.plotly_js and os.path.exists(a.plotly_js):
        with open(a.plotly_js, encoding="utf-8") as f:
            plotly_js = f.read()

    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    tmp = a.out + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(render(data, a.days, plotly_js))
    os.replace(tmp, a.out)  # atomic, so nginx never serves a half-written file
    print(f"wrote {a.out} ({len(data['steps'])} step days, {len(data['sleep'])} sleep sessions, {len(data['workouts'])} workouts)")


if __name__ == "__main__":
    main()
