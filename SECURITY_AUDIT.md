# Security audit

Scope: the upstream HCGateway code at commit `f1fb337` (the last upstream commit in this repository) and the FitStream
code that replaced it. Reviewed by reading the source; the upstream app was not run.

## Summary

No hidden backdoor, obfuscated code, credential stealer or crypto miner was found in HCGateway. The code does what its
README says. Several of those documented features, however, send your health data or telemetry to servers run by the
upstream author by default, and one feature lets a server write to and delete from Health Connect on your phone.
None of that code is in FitStream.

## Findings in upstream HCGateway

| # | Severity | Finding | Where |
|---|----------|---------|-------|
| 1 | High | **Remote write and delete of Health Connect data.** The server can send a Firebase Cloud Messaging push with `op: "PUSH"` or a delete op; the app then calls `insertRecords` / `deleteRecordsByUuids` on the phone. The app requests WRITE permission for every Health Connect type. Whoever controls the server and the Firebase project can change or erase your health records. Documented as "two-way sync". | `app/App.js` (`handlePush`, delete handler), `api/apiVersions/v2/routes.py` (`/push/<method>`, `/delete/<method>`), `AndroidManifest.xml` (`WRITE_*`) |
| 2 | High | **Data goes to the author's server by default.** `apiBase` is `https://api.hcgateway.shuchir.dev`. Self-hosting needs a rebuild. | `app/App.js` line 94 |
| 3 | High | **Firebase project belongs to the author.** `google-services.json` is committed, so push messages to installed APKs come from the author's Firebase project. | `app/android/app/google-services.json`, `app/firebase/google-services.json` |
| 4 | Medium | **Crash and performance telemetry on by default.** Sentry is initialised with hard-coded DSNs (`sentry.shuchir.dev` and a `sentry.io` project) and a trace/profiling sample rate of 1.0. It can be switched off in the app, but it starts enabled. | `app/App.js` lines 43-60, 600-620; `AndroidManifest.xml` `io.sentry.dsn`; `api/main.py` |
| 5 | Medium | **Encryption at rest is ineffective.** The Fernet key is the user's Argon2 hash padded to 32 bytes, and the hash is stored in the same database. Anyone who can read the database can decrypt all data. | `routes.py` (`key = base64.urlsafe_b64encode(hashed_password...)`) |
| 6 | Medium | **Health data and password hashes printed to logs.** `print(request.json)` logs full sync payloads; `print(user)` logs the user record including the password hash. | `routes.py` sync handler |
| 7 | Medium | **Login creates accounts automatically.** Any unknown username/password pair becomes a new account on a public instance. | `routes.py` `login()` |
| 8 | Low | `CORS(app)` allows every origin. | `api/main.py` |
| 9 | Low | Unneeded Android permissions: `SYSTEM_ALERT_WINDOW`, `READ/WRITE_EXTERNAL_STORAGE`, `POST_NOTIFICATIONS`, foreground service. | `AndroidManifest.xml` |
| 10 | Low | Missing `Authorization` header returns 400 and a malformed one raises a 500 (`split(' ')[1]`). | `routes.py` `before_request` |
| 11 | Info | `debug.keystore` is committed. This is the standard React Native debug key and only matters if used for release builds. | `app/android/app/debug.keystore` |
| 12 | Info | The `patch-package` patch only changes `startForeground(id, notification)` to pass a service type. Nothing hidden. | `app/patches/` |

Also reviewed with nothing found: `.github/workflows` (standard Expo/GHCR builds), `nixpacks.toml`, `scripts/migratedb_1.5.0.py`,
`docker-compose.yml` (note: it shipped MongoDB with the password `example` on a published port 27017).

## What was removed

Everything upstream: the React Native app, the Mongo/Fernet/FCM server, Sentry, Firebase, the hosted-instance URL,
GitHub workflows, funding and issue templates, Nix and Mongo deployment files, and support for the ~30 data types the Fit3 does not produce.

## FitStream design choices

- **Read only.** The app requests only `READ_*` Health Connect permissions plus background and history reads. There is no code path that writes to Health Connect.
- **No third parties.** No analytics, crash reporting, Firebase or push. The only network call is `POST {your server}/api/sync`. The APK contains no Sentry or Firebase classes (checked with `unzip -l`).
- **Your server only.** The server URL is entered by you and must be `https://`. Redirects are not followed, so the API key is never re-sent to another host. Cleartext traffic is disabled in the manifest.
- **API key on the device stays there.** Stored in private SharedPreferences; cloud backup and device transfer are excluded.
- **Data minimisation.** Heart rate, steps and distance are reduced to daily aggregates on the phone.
- **Server.** API key of at least 32 characters, compared in constant time. Strict payload validation (types, ranges, row limits, identifier regex for metric names) with all writes in one transaction. Parameterised SQL; table and column names come from a fixed list. 2 MB request limit. No logging of payloads. Runs as a non-root user in Docker.
- **Dashboard.** Values are HTML-escaped and the embedded JSON escapes `</`, so data cannot inject script. A test covers this.

## Known limitations

- The dashboard page has no login. Protect it at the HTTPS front end (for example Cloudflare Access), or anyone with the URL can see it.
- Health data is stored unencrypted in SQLite. Protect the host disk and the `fitstream-data` volume.
- Only a debug-signed APK is built. Sign a release build with your own key if you distribute it.
- There is no rate limiting on wrong API keys; with a 32+ character random key, brute force is not practical, but you can add `limit_req` in nginx.
- Plotly.js is loaded from `cdn.plot.ly` unless you set `PLOTLY_JS` to a local copy.
