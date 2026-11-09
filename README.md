# FitStream

A personal health dashboard for a Samsung Galaxy Fit3. It follows the design in Sidharth Shambu's post
[Building a Personal Health Dashboard from Samsung Health Data](https://shambu2k.hashnode.dev/extract-samsung-health-data).
The repository started as a fork of [HCGateway](https://github.com/ShuchirJ/HCGateway); all of the original code has been replaced.

```
Galaxy Fit3 → Samsung Health → Health Connect → FitStream app ──HTTPS + API key──▶ nginx ─▶ Flask ─▶ SQLite
                                                                                       │
                                                         build.py (every 8 h) ─▶ index.html (static dashboard)
```

| Layer       | What it uses                                              |
|-------------|-----------------------------------------------------------|
| Phone app   | Kotlin, Jetpack Compose, Health Connect SDK, WorkManager  |
| Sync server | Python, Flask, SQLite, gunicorn                           |
| Dashboard   | `build.py` → single static HTML page with Plotly.js       |
| Serving     | nginx, Docker Compose                                     |
| Auth        | One API key (`X-API-Key` header)                          |

## Repository layout

```
android/            Kotlin app (package app.fitstream) + Dockerfile to build the APK without a local SDK
server/fitstream/   Flask app: /api/sync, read endpoints, payload validation
server/build.py     Builds the static dashboard from SQLite
server/tests/       pytest suite
docker-compose.yml  api + dashboard rebuilder + nginx
nginx.conf          Serves the dashboard and proxies /api/
SECURITY_AUDIT.md   Review of the original HCGateway code and of FitStream
```

## Data collected

The app only requests **read** permissions, for the types Samsung Health writes from the Fit3:

| Data                         | Health Connect record                            | Sent as                              |
|------------------------------|--------------------------------------------------|--------------------------------------|
| Steps and distance           | `StepsRecord`, `DistanceRecord`                  | daily totals                         |
| Heart rate                   | `HeartRateRecord`                                | daily min / avg / max                |
| Sleep with stages            | `SleepSessionRecord`                             | one row per session                  |
| Workouts                     | `ExerciseSessionRecord` (+ calories, distance, HR during the session) | one row per session |
| SpO₂, resting HR             | `OxygenSaturationRecord`, `RestingHeartRateRecord` | daily averages                     |
| Weight, body fat, BMR        | `WeightRecord`, `BodyFatRecord`, `BasalMetabolicRateRecord` | each reading            |

Raw per-sample heart rate is never uploaded. Each sync re-sends the last 2 days, because the band reaches
Samsung Health late; the server upserts, so re-sends do not create duplicates. The first sync covers 30 days.

## Server setup

Requirements: Docker with the Compose plugin.

```bash
cp .env.example .env
python3 -c "import secrets; print(secrets.token_urlsafe(32))"   # paste into FITSTREAM_API_KEY in .env
docker compose up -d --build
curl http://127.0.0.1:8080/api/health                           # {"status":"ok"}
```

- The dashboard is at `http://127.0.0.1:8080/` and is rebuilt every `FITSTREAM_REBUILD_SECONDS` (default 8 hours).
  Rebuild immediately with `docker compose exec dashboard python build.py`.
- Data lives in the `fitstream-data` Docker volume (`/data/fitstream.db`). Back it up with
  `docker compose exec api python -c "import sqlite3; sqlite3.connect('/data/fitstream.db').backup(sqlite3.connect('/data/backup.db'))"`
  and then `docker compose cp api:/data/backup.db .`.

### HTTPS and access control

The app refuses plain `http://` URLs, and nginx binds to `127.0.0.1` by default. Put an HTTPS front end in front of it,
for example Cloudflare Tunnel, Caddy or Tailscale Funnel, pointing at `http://127.0.0.1:8080`.

`/api/*` needs the API key. **The dashboard page itself has no login**, so protect `/` at the front end
(the blog uses Cloudflare Access with Google sign-in). Without that, anyone with the URL can see your health data.

### Running without Docker

```bash
cd server
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
export FITSTREAM_API_KEY=...  FITSTREAM_DB=./fitstream.db
gunicorn --bind 127.0.0.1:8000 wsgi:app
python build.py --db ./fitstream.db --out ./site/index.html    # e.g. from cron: 0 8,14,20 * * *
```

Tests: `pip install pytest && python -m pytest -q` (from `server/`).

## Phone setup

1. Build the APK (no Android SDK needed on the host):
   ```bash
   docker build -f android/Dockerfile -t fitstream-android android
   docker run --rm -v "$PWD/android:/src" -v fitstream-gradle:/root/.gradle fitstream-android
   ```
   Output: `android/app/build/outputs/apk/debug/app-debug.apk`. Install it with `adb install` or by copying it to the phone.
   Android 9 (API 28) or newer is required.
2. In Samsung Health: **Settings → Health Connect**, and allow Samsung Health to write the data types above.
3. Open FitStream, tap **Grant permissions**, and allow everything, including background reads.
4. Enter the server URL (`https://…`) and the API key, tap **Save**, then **Sync now**.
5. Turn on automatic sync (default every 12 hours).

## API

All endpoints except `/api/health` need the header `X-API-Key: <key>`.

| Method | Path                | Description                                                       |
|--------|---------------------|-------------------------------------------------------------------|
| GET    | `/api/health`       | Liveness check                                                    |
| POST   | `/api/sync`         | Upload a payload; returns row counts per table                    |
| GET    | `/api/steps`        | Optional `from` / `to` (`YYYY-MM-DD`)                             |
| GET    | `/api/sleep`        | Same filters                                                      |
| GET    | `/api/heart_rate`   | Same filters                                                      |
| GET    | `/api/workouts`     | Same filters                                                      |
| GET    | `/api/readings`     | Same filters plus `metric` (`spo2`, `resting_hr`, `weight`, `body_fat`, `bmr`) |
| GET    | `/api/sync_log`     | Last 50 syncs                                                     |

Sync payload (all lists optional):

```json
{
  "last_sync": "2026-10-30T08:00:00Z",
  "steps":      [{"date": "2026-10-30", "count": 8000, "distance_meters": 6100.5}],
  "heart_rate": [{"date": "2026-10-30", "avg_bpm": 72, "min_bpm": 51, "max_bpm": 141}],
  "sleep":      [{"start_time": "...", "end_time": "...", "date": "2026-10-30", "duration_min": 450,
                  "awake_min": 20, "light_min": 250, "deep_min": 90, "rem_min": 90}],
  "workouts":   [{"start_time": "...", "end_time": "...", "date": "2026-10-30", "type": "running",
                  "duration_min": 45, "calories": 410, "distance_km": 6.2, "avg_hr": 148, "max_hr": 171}],
  "readings":   [{"metric": "weight", "time": "2026-10-30T05:00:00Z", "values": {"kg": 70.2}}]
}
```

## License

GPL-3.0, inherited from HCGateway. See `LICENSE`.
