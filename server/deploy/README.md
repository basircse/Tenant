# TMS server deployment

This folder runs the TMS API and the vendor's web console as one Java process on port 8980:

| Address | What |
| --- | --- |
| `http://<server>:8980/api/…` | The API the admin and tenant apps use |
| `http://<server>:8980/console/` | The web console (vendor sign-in) |
| `http://<server>:8980/actuator/health` | Health check, shows `{"status":"UP"}` |

## What is inside

| Path | |
| --- | --- |
| `tms-api.jar` | The API |
| `web-console/` | The web console, served by the API at `/console/` |
| `config/application.properties` | **Settings: database, token secret, folders. Private, holds passwords.** |
| `config/application.properties.example` | The same settings with placeholders, for reference |
| `start.sh`, `stop.sh` | Start/stop on Linux (background, writes `tms-api.pid`) |
| `tms-api.service` | systemd service for Linux (start at boot, restart on failure) |
| `start.bat` | Start on Windows |

Created while running: `logs/` (log files) and `data/files/` (uploaded documents and photos).
Back up `data/files/` together with the database.

## First install (Linux)

1. Install Java 21 or newer: `sudo apt install openjdk-21-jre-headless` (Ubuntu/Debian), then
   `java -version`.
2. Upload this folder, for example to `/opt/tms`:
   ```bash
   scp -r deployment/* user@server:/opt/tms/
   ```
3. Start it:
   ```bash
   cd /opt/tms && chmod +x start.sh stop.sh && ./start.sh
   tail -f logs/tms-api.log     # wait for "Started TenantManagementApiApplication"
   ```
4. Open port 8980 in the server's firewall (e.g. `sudo ufw allow 8980/tcp`) and in the cloud
   provider's firewall/security group if there is one.
5. Check `http://<server>:8980/actuator/health`, then sign in at `http://<server>:8980/console/`
   with the vendor account.

### Run as a service (recommended)

Starts at boot and restarts after a crash. Use it instead of `start.sh`:

```bash
sudo useradd --system --home /opt/tms tms && sudo chown -R tms:tms /opt/tms
sudo cp /opt/tms/tms-api.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now tms-api
sudo systemctl status tms-api
```

## Updating to a new version

On the development computer run `server\deploy\package.ps1`. It rebuilds `deployment\` and keeps
your `config\application.properties`. Upload `tms-api.jar` and `web-console/`, then restart:
`./stop.sh && ./start.sh`, or `sudo systemctl restart tms-api`. Database changes (Flyway
migrations) are applied automatically at startup.

## Pointing the apps at the server

In the admin or tenant app, set the server address on the sign-in screen to
`http://<server>:8980`, or build the app with `--dart-define=TMS_API_URL=http://<server>:8980`.

## HTTPS

Logins and tokens travel unencrypted over plain `http://`. Before real users sign in, put a
reverse proxy with a certificate in front, for example Caddy with a domain name:

```
tms.example.com {
    reverse_proxy localhost:8980
}
```

Then close port 8980 to the outside and use `https://tms.example.com` in the apps and the browser.
The API already trusts the proxy's forwarded headers (`prod` profile).
