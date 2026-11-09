"""FitStream sync server: receives daily health aggregates from the phone and stores them in SQLite."""

import hmac
import json
import os
import sqlite3
from datetime import datetime, timezone

from flask import Flask, g, jsonify, request

from .validate import ValidationError, validate_payload

SCHEMA = """
CREATE TABLE IF NOT EXISTS steps (
    date TEXT PRIMARY KEY,
    count INTEGER NOT NULL,
    distance_meters REAL
);
CREATE TABLE IF NOT EXISTS sleep (
    start_time TEXT PRIMARY KEY,
    end_time TEXT NOT NULL,
    date TEXT NOT NULL,
    duration_min REAL NOT NULL,
    awake_min REAL, light_min REAL, deep_min REAL, rem_min REAL
);
CREATE TABLE IF NOT EXISTS heart_rate (
    date TEXT PRIMARY KEY,
    avg_bpm REAL, min_bpm REAL, max_bpm REAL
);
CREATE TABLE IF NOT EXISTS workouts (
    start_time TEXT PRIMARY KEY,
    end_time TEXT NOT NULL,
    date TEXT NOT NULL,
    type TEXT NOT NULL,
    duration_min REAL NOT NULL,
    calories REAL, distance_km REAL, avg_hr REAL, max_hr REAL
);
CREATE TABLE IF NOT EXISTS readings (
    metric TEXT NOT NULL,
    time TEXT NOT NULL,
    values_json TEXT NOT NULL,
    PRIMARY KEY (metric, time)
);
CREATE TABLE IF NOT EXISTS sync_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    received_at TEXT NOT NULL,
    last_sync TEXT,
    counts TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sleep_date ON sleep(date);
CREATE INDEX IF NOT EXISTS idx_workouts_date ON workouts(date);
"""

# Column order per table; the first column(s) form the primary key used for upserts.
TABLES = {
    "steps": ["date", "count", "distance_meters"],
    "sleep": ["start_time", "end_time", "date", "duration_min", "awake_min", "light_min", "deep_min", "rem_min"],
    "heart_rate": ["date", "avg_bpm", "min_bpm", "max_bpm"],
    "workouts": ["start_time", "end_time", "date", "type", "duration_min", "calories", "distance_km", "avg_hr", "max_hr"],
}


def connect(path):
    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    return conn


def init_db(path):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with connect(path) as conn:
        conn.executescript(SCHEMA)


def create_app(db_path=None, api_key=None):
    app = Flask(__name__)
    app.config["DB_PATH"] = db_path or os.environ.get("FITSTREAM_DB", "/data/fitstream.db")
    app.config["API_KEY"] = api_key or os.environ.get("FITSTREAM_API_KEY", "")
    # Daily aggregates are small; 2 MB is far above a normal payload and caps abuse.
    app.config["MAX_CONTENT_LENGTH"] = 2 * 1024 * 1024

    if len(app.config["API_KEY"]) < 32:
        raise RuntimeError("FITSTREAM_API_KEY must be set and at least 32 characters long")

    init_db(app.config["DB_PATH"])

    def db():
        if "db" not in g:
            g.db = connect(app.config["DB_PATH"])
        return g.db

    @app.teardown_appcontext
    def close_db(_exc):
        conn = g.pop("db", None)
        if conn is not None:
            conn.close()

    @app.before_request
    def require_api_key():
        if request.endpoint == "health":
            return None
        supplied = request.headers.get("X-API-Key", "")
        if not hmac.compare_digest(supplied.encode(), app.config["API_KEY"].encode()):
            return jsonify(error="unauthorized"), 401
        return None

    @app.get("/api/health")
    def health():
        return jsonify(status="ok")

    @app.post("/api/sync")
    def sync():
        body = request.get_json(silent=True)
        try:
            payload = validate_payload(body)
        except ValidationError as e:
            return jsonify(error=str(e)), 400

        conn = db()
        counts = {}
        with conn:
            for table, cols in TABLES.items():
                rows = payload.get(table, [])
                if rows:
                    placeholders = ",".join("?" * len(cols))
                    conn.executemany(
                        f"INSERT OR REPLACE INTO {table} ({','.join(cols)}) VALUES ({placeholders})",
                        [tuple(r.get(c) for c in cols) for r in rows],
                    )
                counts[table] = len(rows)
            readings = payload.get("readings", [])
            conn.executemany(
                "INSERT OR REPLACE INTO readings (metric, time, values_json) VALUES (?,?,?)",
                [(r["metric"], r["time"], json.dumps(r["values"], sort_keys=True)) for r in readings],
            )
            counts["readings"] = len(readings)
            conn.execute(
                "INSERT INTO sync_log (received_at, last_sync, counts) VALUES (?,?,?)",
                (datetime.now(timezone.utc).isoformat(), payload.get("last_sync"), json.dumps(counts)),
            )
        return jsonify(status="ok", counts=counts)

    def date_range():
        start = request.args.get("from", "0000-01-01")
        end = request.args.get("to", "9999-12-31")
        return start, end

    def read_table(table, date_col="date"):
        start, end = date_range()
        rows = db().execute(
            f"SELECT * FROM {table} WHERE {date_col} >= ? AND {date_col} <= ? ORDER BY {date_col}",
            (start, end),
        ).fetchall()
        return jsonify([dict(r) for r in rows])

    @app.get("/api/steps")
    def get_steps():
        return read_table("steps")

    @app.get("/api/sleep")
    def get_sleep():
        return read_table("sleep")

    @app.get("/api/heart_rate")
    def get_heart_rate():
        return read_table("heart_rate")

    @app.get("/api/workouts")
    def get_workouts():
        return read_table("workouts")

    @app.get("/api/readings")
    def get_readings():
        start, end = date_range()
        sql = "SELECT metric, time, values_json FROM readings WHERE substr(time,1,10) >= ? AND substr(time,1,10) <= ?"
        args = [start, end]
        metric = request.args.get("metric")
        if metric:
            sql += " AND metric = ?"
            args.append(metric)
        rows = db().execute(sql + " ORDER BY time", args).fetchall()
        return jsonify([{"metric": r["metric"], "time": r["time"], "values": json.loads(r["values_json"])} for r in rows])

    @app.get("/api/sync_log")
    def get_sync_log():
        rows = db().execute("SELECT * FROM sync_log ORDER BY id DESC LIMIT 50").fetchall()
        return jsonify([{**dict(r), "counts": json.loads(r["counts"])} for r in rows])

    return app
