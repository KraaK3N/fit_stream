"""Strict validation of the /api/sync payload. Unknown fields are dropped, wrong types are rejected."""

import re
from datetime import datetime

MAX_ROWS = 5000
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
METRIC_RE = re.compile(r"^[a-z][a-z0-9_]{0,31}$")


class ValidationError(ValueError):
    pass


def _date(v, field):
    if not isinstance(v, str) or not DATE_RE.match(v):
        raise ValidationError(f"{field}: expected YYYY-MM-DD")
    try:
        datetime.strptime(v, "%Y-%m-%d")
    except ValueError:
        raise ValidationError(f"{field}: invalid date") from None
    return v


def _timestamp(v, field):
    if not isinstance(v, str) or len(v) > 40:
        raise ValidationError(f"{field}: expected ISO-8601 timestamp")
    try:
        datetime.fromisoformat(v.replace("Z", "+00:00"))
    except ValueError:
        raise ValidationError(f"{field}: invalid ISO-8601 timestamp") from None
    return v


def _num(v, field, required=False, lo=0, hi=1e9):
    if v is None:
        if required:
            raise ValidationError(f"{field}: required")
        return None
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        raise ValidationError(f"{field}: expected number")
    if not lo <= v <= hi:
        raise ValidationError(f"{field}: out of range")
    return v


def _text(v, field, max_len=64):
    if not isinstance(v, str) or not 0 < len(v) <= max_len:
        raise ValidationError(f"{field}: expected non-empty string (max {max_len})")
    return v


def _rows(payload, key):
    rows = payload.get(key, [])
    if not isinstance(rows, list):
        raise ValidationError(f"{key}: expected list")
    if len(rows) > MAX_ROWS:
        raise ValidationError(f"{key}: too many rows (max {MAX_ROWS})")
    for i, r in enumerate(rows):
        if not isinstance(r, dict):
            raise ValidationError(f"{key}[{i}]: expected object")
    return rows


def validate_payload(payload):
    if not isinstance(payload, dict):
        raise ValidationError("body must be a JSON object")
    out = {}
    if payload.get("last_sync") is not None:
        out["last_sync"] = _timestamp(payload["last_sync"], "last_sync")

    out["steps"] = [
        {
            "date": _date(r.get("date"), f"steps[{i}].date"),
            "count": int(_num(r.get("count"), f"steps[{i}].count", required=True, hi=1e6)),
            "distance_meters": _num(r.get("distance_meters"), f"steps[{i}].distance_meters", hi=1e6),
        }
        for i, r in enumerate(_rows(payload, "steps"))
    ]

    out["heart_rate"] = [
        {
            "date": _date(r.get("date"), f"heart_rate[{i}].date"),
            **{k: _num(r.get(k), f"heart_rate[{i}].{k}", hi=300) for k in ("avg_bpm", "min_bpm", "max_bpm")},
        }
        for i, r in enumerate(_rows(payload, "heart_rate"))
    ]

    out["sleep"] = [
        {
            "start_time": _timestamp(r.get("start_time"), f"sleep[{i}].start_time"),
            "end_time": _timestamp(r.get("end_time"), f"sleep[{i}].end_time"),
            "date": _date(r.get("date"), f"sleep[{i}].date"),
            "duration_min": _num(r.get("duration_min"), f"sleep[{i}].duration_min", required=True, hi=2880),
            **{k: _num(r.get(k), f"sleep[{i}].{k}", hi=2880) for k in ("awake_min", "light_min", "deep_min", "rem_min")},
        }
        for i, r in enumerate(_rows(payload, "sleep"))
    ]

    out["workouts"] = [
        {
            "start_time": _timestamp(r.get("start_time"), f"workouts[{i}].start_time"),
            "end_time": _timestamp(r.get("end_time"), f"workouts[{i}].end_time"),
            "date": _date(r.get("date"), f"workouts[{i}].date"),
            "type": _text(r.get("type"), f"workouts[{i}].type"),
            "duration_min": _num(r.get("duration_min"), f"workouts[{i}].duration_min", required=True, hi=2880),
            "calories": _num(r.get("calories"), f"workouts[{i}].calories", hi=1e5),
            "distance_km": _num(r.get("distance_km"), f"workouts[{i}].distance_km", hi=1e4),
            "avg_hr": _num(r.get("avg_hr"), f"workouts[{i}].avg_hr", hi=300),
            "max_hr": _num(r.get("max_hr"), f"workouts[{i}].max_hr", hi=300),
        }
        for i, r in enumerate(_rows(payload, "workouts"))
    ]

    readings = []
    for i, r in enumerate(_rows(payload, "readings")):
        metric = r.get("metric")
        if not isinstance(metric, str) or not METRIC_RE.match(metric):
            raise ValidationError(f"readings[{i}].metric: expected lowercase identifier")
        values = r.get("values")
        if not isinstance(values, dict) or not 0 < len(values) <= 8:
            raise ValidationError(f"readings[{i}].values: expected object with 1-8 numeric fields")
        clean = {}
        for k, v in values.items():
            if not isinstance(k, str) or not METRIC_RE.match(k):
                raise ValidationError(f"readings[{i}].values: bad key")
            clean[k] = _num(v, f"readings[{i}].values.{k}", required=True, lo=-1e6, hi=1e6)
        readings.append({"metric": metric, "time": _timestamp(r.get("time"), f"readings[{i}].time"), "values": clean})
    out["readings"] = readings
    return out
