import json
import os
import sqlite3
import subprocess
import sys
from datetime import date, timedelta

import pytest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

from fitstream import create_app  # noqa: E402

KEY = "k" * 40
TODAY = date.today().isoformat()
YESTERDAY = (date.today() - timedelta(days=1)).isoformat()


def sample_payload():
    return {
        "last_sync": "2026-10-29T08:00:00Z",
        "steps": [{"date": YESTERDAY, "count": 8000, "distance_meters": 6100.5}, {"date": TODAY, "count": 4200}],
        "heart_rate": [{"date": TODAY, "avg_bpm": 72.4, "min_bpm": 51, "max_bpm": 141}],
        "sleep": [{
            "start_time": f"{YESTERDAY}T23:10:00Z", "end_time": f"{TODAY}T06:40:00Z", "date": TODAY,
            "duration_min": 450, "awake_min": 20, "light_min": 250, "deep_min": 90, "rem_min": 90,
        }],
        "workouts": [{
            "start_time": f"{TODAY}T07:00:00Z", "end_time": f"{TODAY}T07:45:00Z", "date": TODAY,
            "type": "running", "duration_min": 45, "calories": 410, "distance_km": 6.2, "avg_hr": 148, "max_hr": 171,
        }],
        "readings": [{"metric": "spo2", "time": f"{TODAY}T03:00:00Z", "values": {"percent": 96.0}}],
    }


@pytest.fixture
def ctx(tmp_path):
    db = str(tmp_path / "fs.db")
    app = create_app(db_path=db, api_key=KEY)
    return app.test_client(), db


def auth(extra=None):
    return {"X-API-Key": KEY, **(extra or {})}


def test_rejects_short_api_key(tmp_path):
    with pytest.raises(RuntimeError):
        create_app(db_path=str(tmp_path / "x.db"), api_key="short")


def test_health_is_public(ctx):
    client, _ = ctx
    assert client.get("/api/health").status_code == 200


@pytest.mark.parametrize("headers", [{}, {"X-API-Key": "wrong"}, {"X-API-Key": KEY + "x"}])
def test_auth_required(ctx, headers):
    client, _ = ctx
    assert client.post("/api/sync", json=sample_payload(), headers=headers).status_code == 401
    assert client.get("/api/steps", headers=headers).status_code == 401


def test_sync_and_read(ctx):
    client, _ = ctx
    r = client.post("/api/sync", json=sample_payload(), headers=auth())
    assert r.status_code == 200, r.get_json()
    assert r.get_json()["counts"] == {"steps": 2, "heart_rate": 1, "sleep": 1, "workouts": 1, "readings": 1}

    steps = client.get("/api/steps", headers=auth()).get_json()
    assert [s["count"] for s in steps] == [8000, 4200]
    assert client.get(f"/api/steps?from={TODAY}", headers=auth()).get_json()[0]["count"] == 4200
    assert client.get("/api/workouts", headers=auth()).get_json()[0]["type"] == "running"
    assert client.get("/api/readings?metric=spo2", headers=auth()).get_json()[0]["values"] == {"percent": 96.0}
    assert len(client.get("/api/sync_log", headers=auth()).get_json()) == 1


def test_resync_upserts_without_duplicates(ctx):
    client, _ = ctx
    client.post("/api/sync", json=sample_payload(), headers=auth())
    p = sample_payload()
    p["steps"] = [{"date": TODAY, "count": 9999}]
    client.post("/api/sync", json=p, headers=auth())
    steps = client.get("/api/steps", headers=auth()).get_json()
    assert len(steps) == 2
    assert {s["date"]: s["count"] for s in steps}[TODAY] == 9999
    assert len(client.get("/api/workouts", headers=auth()).get_json()) == 1


@pytest.mark.parametrize("mutate", [
    lambda p: p.update(steps="nope"),
    lambda p: p["steps"][0].update(date="2026-13-40"),
    lambda p: p["steps"][0].update(count="8000"),
    lambda p: p["steps"][0].update(count=True),
    lambda p: p["heart_rate"][0].update(avg_bpm=9000),
    lambda p: p["sleep"][0].update(start_time="yesterday"),
    lambda p: p["workouts"][0].update(type=""),
    lambda p: p["readings"][0].update(metric="Robert'); DROP TABLE steps;--"),
    lambda p: p["readings"][0].update(values={"x": "y"}),
])
def test_validation_rejects_bad_input(ctx, mutate):
    client, db = ctx
    p = sample_payload()
    mutate(p)
    assert client.post("/api/sync", json=p, headers=auth()).status_code == 400
    # Nothing should have been written.
    assert sqlite3.connect(db).execute("SELECT COUNT(*) FROM steps").fetchone()[0] == 0


def test_non_json_body(ctx):
    client, _ = ctx
    r = client.post("/api/sync", data="not json", headers=auth({"Content-Type": "application/json"}))
    assert r.status_code == 400


def test_oversized_body(ctx):
    client, _ = ctx
    r = client.post("/api/sync", data="x" * (3 * 1024 * 1024), headers=auth({"Content-Type": "application/json"}))
    assert r.status_code == 413


def test_build_dashboard(ctx, tmp_path):
    client, db = ctx
    p = sample_payload()
    p["workouts"][0]["type"] = "<script>alert(1)</script>"
    client.post("/api/sync", json=p, headers=auth())
    out = tmp_path / "site" / "index.html"
    res = subprocess.run([sys.executable, os.path.join(os.path.dirname(HERE), "build.py"), "--db", db, "--out", str(out), "--days", "30"],
                         capture_output=True, text=True)
    assert res.returncode == 0, res.stderr
    page = out.read_text()
    for needle in ("fig-steps", "fig-sleep", "fig-heart_rate", "fig-reading_spo2", "Avg daily steps"):
        assert needle in page
    assert "<script>alert(1)" not in page.lower()
    assert "&lt;script&gt;alert(1)" in page.lower()
    # KPI: (8000 + 4200) / 2 = 6100 steps; sleep 450 - 20 awake = 7h 10m
    assert "6,100" in page and "7h 10m" in page


def test_build_dashboard_empty_db(ctx, tmp_path):
    _, db = ctx
    out = tmp_path / "index.html"
    res = subprocess.run([sys.executable, os.path.join(os.path.dirname(HERE), "build.py"), "--db", db, "--out", str(out)],
                         capture_output=True, text=True)
    assert res.returncode == 0, res.stderr
    assert "No data yet." in out.read_text()
