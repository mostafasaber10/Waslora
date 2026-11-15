FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    WASLORA_RESOLVER_HOST=0.0.0.0 \
    WASLORA_RESOLVER_PORT=8787 \
    WASLORA_JOB_DIR=/var/lib/waslora/jobs

RUN apt-get update \
    && apt-get install --no-install-recommends -y ffmpeg nodejs ca-certificates \
    && pip install --no-cache-dir --upgrade yt-dlp \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY server.js /app/server.js

RUN mkdir -p /var/lib/waslora/jobs \
    && chown -R nobody:nogroup /var/lib/waslora/jobs \
    && chmod 700 /var/lib/waslora/jobs

EXPOSE 8787
USER nobody
CMD ["node", "/app/server.js"]
