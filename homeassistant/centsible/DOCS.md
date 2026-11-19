# Centsible

Runs your Actual Budget server and the Centsible bridge (what the Android app talks to) together, on versions that are tested together. When a new Centsible release is out, Home Assistant offers it as an update, and Actual moves with it.

Everything lives in the app's data, so **Home Assistant's backups include your budget, the household (members and paired phones) and the Teller certificate**. Keep backups encrypted (Home Assistant's default) since they hold financial data.

## First install

1. **Configuration** tab: set **Public address** to the https address the phones will use, e.g. `https://budget-api.example.com`. If that address is behind Cloudflare Access, also fill in the service token.
2. Start the app and open the **Log** tab. It shows a one-time **setup code**.
3. In the Centsible app, choose **Set up a new bridge**, enter the public address and the setup code, and pick Actual's password (a new Actual server asks you to choose one).

## Reaching it from your phones

The app listens on port **8787**. Point your Cloudflare tunnel's public hostname at it:

- With the **Cloudflared** app in Home Assistant: add the hostname, with the service set to `http://<hostname>:8787`. The hostname is on this app's **Info** tab (it looks like `abcd1234-centsible`).
- With a tunnel managed in the Cloudflare dashboard: set the hostname's service the same way, or to `http://<your Home Assistant's address>:8787`.

Actual's own web app is on port 5006 inside the app. It's off by default; turn it on under **Network** to use it on your network, or give your tunnel a second hostname pointing at `http://<hostname>:5006`.

## Moving from Docker Compose

Your old setup has two folders next to `docker-compose.yml`: `actual-data` and `bridge-data`.

1. On the old server: `docker compose down` (so nothing changes while you copy).
2. Install this app, but don't start it yet. Fill in **Public address** (same as `BRIDGE_PUBLIC_URL` in your `.env`) and the Cloudflare Access fields (the `CF_ACCESS_*` values).
3. Copy both folders into this app's config folder, inside a folder named `import`:
   `/addon_configs/<id>_centsible/import/actual-data` and `.../import/bridge-data`.
   The Studio Code Server, File editor or Samba apps can all reach `/addon_configs`.
4. Start the app. The log says `Imported actual-data` and `Imported bridge-data`; the originals are kept as `*.imported` (delete them once you're happy).
5. Move your tunnel hostnames to this app (above). Phones keep working: they're already paired with this household.

The import only runs while the app has no data of its own, so it can't overwrite a running household.

## Updates

**Settings → Apps → Centsible → Update** (or turn on auto-update). Check **Back up before updating**: the app stops for a moment during backups and updates. The Centsible app's **Settings → Server** shows when an update is available and points here.

## Options

| Option | What it does |
|---|---|
| Public address | The https address phones use. Goes into pairing QR codes. Required. |
| Cloudflare Access client ID / secret | The service token protecting the public address; phones get it when pairing. |
| Setup code | A fixed first-run code instead of a generated one. |
| Actual password | Usually empty: the app asks during setup and the bridge remembers it. |
| Encrypted budget passwords | Only for budgets with Actual's end-to-end encryption, by budget sync id. |
| Log level | How much the bridge logs. |
