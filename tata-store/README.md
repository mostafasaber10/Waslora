# Tata Store

Tata Store is an English-language Android app marketplace and developer portal. The public catalog starts with Waslora. Developers can create an account, publish an installable APK, update their listing or package, view daily download-request counts, and delete their account.

## Run it locally

Requires Node.js 20 or newer. No npm packages are needed.

```powershell
cd tata-store
./Start-Store.ps1 -AdministratorEmail owner@example.com
```

Open <http://127.0.0.1:8840>. The first run creates `storage/store.json`; uploaded app packages live under `storage/packages`. Keep this storage directory backed up and private. To use another address, set `HOST` and `PORT`. For a local phone or computer on the same network, set `HOST=0.0.0.0` and use the computer's LAN address.

## Run with Docker

From this directory, copy `.env.example` to `.env`, set a unique administrator email, and replace the setup-key placeholder with a random secret of at least 32 characters. Keep `.env` private. Then run `docker compose up --build -d` and create or claim the administrator account with the setup key. Docker persists accounts, listings, and uploaded packages in the `tata-store-data` volume. Compose binds HTTP to loopback only so it is not exposed publicly. Put a TLS reverse proxy on the host in front of `127.0.0.1:8840` and add a domain before launch. Production login cookies require HTTPS. In production mode, APK uploads also require a configured Android `apksigner` binary: set `TATA_STORE_APKSIGNER` to its full path and keep `TATA_STORE_REQUIRE_APKSIGNER=true`; optionally set `TATA_STORE_ALLOWED_CERT_SHA256` to approved certificate SHA-256 digests. The included Docker image does not contain Android build tools, so install or mount `apksigner` in the deployment image before accepting uploads.

## Store features

- Responsive storefront, app cards and detail views, text search, category filters, and sort by featured, popularity, date, or name.
- Developer registration and sign-in. Passwords are stored as salted scrypt hashes; session cookies are HTTP-only and same-site.
- Developer dashboard with owned app listings, total downloads, current-month totals, and private per-day counts.
- Android APK publishing up to 300 MB. Uploads stream to disk and must contain an APK manifest, DEX code, and APK signing metadata. New apps and updates remain private until a configured store administrator approves them. Developers may edit or remove their own apps.
- APK downloads stream with HTTP byte-range support for large files and interrupted transfers. Waslora is seeded from the public GitHub release; download clicks are counted before redirecting to the release APK. Developers can edit listing details and replace their APK while preserving download history.
- Download totals count HTTP package requests, not unique people, successful installations, or active users. A resumed/ranged download can make more than one request.
- Basic request hardening, rate limits for account creation, reports, and uploads, restrictive same-origin policy headers, path checks, request size bounds, account deletion, and password length requirements.

## Operational limits

This version is a single-instance starter service. It uses a local JSON database, in-memory login sessions, and disk-based package storage. Run one application replica with persistent storage. `Start-Store.ps1` creates a random one-time admin setup key and prints it before launching the service. For public hosting, set `TATA_STORE_ADMIN_EMAIL` and a private `TATA_STORE_ADMIN_SETUP_TOKEN` of at least 32 characters before starting the service. The account matching the email must use that key to register or claim the admin role. For a high-traffic global launch, migrate accounts and catalog data to a managed database, package files to object storage/CDN, sessions to a shared session store, and add email verification, malware scanning, backups, and monitoring before accepting arbitrary public uploads at scale. Account emails are not verified in this first version.

Only submit apps you own or have permission to distribute. The server checks APK structure and, when production signature verification is configured, verifies the APK certificate with `apksigner`. It does not scan package behavior or certify that an app is safe. The configured administrator must review each upload before it appears publicly. Store operators should remove harmful or infringing apps.

## Test

```powershell
npm test
```

The smoke test covers catalog visibility, developer and administrator accounts, authorization, app publishing and review, APK replacement, abuse reports, ranged downloads, private download analytics, and account deletion.
