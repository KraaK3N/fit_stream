FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY fitstream ./fitstream
COPY wsgi.py build.py ./

# Run as an unprivileged user; /data holds the SQLite DB, /site the generated dashboard.
RUN useradd --uid 10001 --no-create-home fitstream \
 && mkdir -p /data /site && chown fitstream:fitstream /data /site
USER fitstream

EXPOSE 8000
CMD ["gunicorn", "--bind", "0.0.0.0:8000", "--workers", "2", "--access-logfile", "-", "wsgi:app"]
