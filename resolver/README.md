# Waslora Resolver Service

Waslora sends a public platform URL here and receives the downloaded video without opening the platform in a browser. The service runs `yt-dlp` plus FFmpeg, keeps temporary files in a private job directory, and deletes finished jobs after a TTL. It is intended for public links and content the user has permission to save; it does not use cookies, passwords, browser sessions, or DRM keys.

## API contract

`GET /health` (with the bearer token when one is configured) returns:

```json
{"ok":true,"service":"waslora-resolver","version":"0.2.0"}
```

`POST /v1/jobs` accepts:

```json
{"url":"https://www.youtube.com/watch?v=example"}
```

It returns a queued job:

```json
{"ok":true,"id":"…","status":"downloading","statusUrl":"/v1/jobs/…"}
```

`GET /v1/jobs/:id` returns progress. When `status` becomes `ready`, download `GET /v1/jobs/:id/file`. `DELETE /v1/jobs/:id` cancels a running job and removes its files. Jobs are bounded by duration, file size, and concurrent-job limits. The default file-size ceiling is 100 GB; lower it with `WASLORA_MAX_FILE_BYTES` when the server has less free storage. The default concurrent-job limit is four and can be raised to 16 with `WASLORA_MAX_JOBS` when the server can handle it.

Example request from the laptop:

```powershell
$headers = @{ Authorization = 'Bearer replace-with-your-token' }
$job = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8787/v1/jobs -Headers $headers -ContentType 'application/json' -Body '{"url":"https://www.facebook.com/share/v/example"}'
$job
Invoke-WebRequest -Uri ("http://127.0.0.1:8787" + $job.statusUrl) -Headers $headers
```

The current Android app uses an embedded yt-dlp/FFmpeg engine, so ordinary platform downloads do not use this Resolver service. This folder remains an optional standalone server for development and testing only.

## Run locally

Requires Node.js 18 or newer. No npm package is required for the scaffold:

```powershell
node .\server.js
```

Set `WASLORA_RESOLVER_PORT` to choose another port. The default is `8787`. Docker listens on its container interface but publishes the host port to `127.0.0.1` by default, so the service is not reachable from other devices. Use `./start.ps1 -ExposeLan` only on a trusted private Wi-Fi network; it publishes the port to the LAN and prints a strong bearer token. For any public or untrusted network, place an HTTPS reverse proxy in front of the service, keep the backend private, and do not publish the plain HTTP port. A non-loopback bind also requires a token of at least 32 characters.

## Docker

From this folder, set a long random token and start the service:

```powershell
$env:WASLORA_RESOLVER_TOKEN = 'replace-with-a-long-random-value'
docker compose up --build -d
```

On Windows, `.\start.ps1` generates a strong token, starts Docker, and prints the laptop addresses and token to paste into the Android app:

```powershell
powershell -ExecutionPolicy Bypass -File .\start.ps1 -ExposeLan
```

The image includes FFmpeg and the latest `yt-dlp` at build time. Update the image periodically because platform extractors can change.

Support is best-effort: platform extractors can stop working when the platforms change, and a private, login-only, DRM-protected, or unavailable video will fail. A generic “every link always works” promise cannot be made.
