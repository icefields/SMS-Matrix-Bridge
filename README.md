# SMS ↔ Matrix Bridge

A lightweight, fully open-source Android app that bridges your phone's SMS messages to a Matrix room and back. No middleman server, no cloud dependency. Your phone talks directly to your Matrix homeserver over HTTPS.

---

## Table of Contents

- [What Does This App Do? (For Non-Developers)](#what-does-this-app-do-for-non-developers)
- [Getting Started — Step by Step Setup Guide](#getting-started--step-by-step-setup-guide)
  - [Step 1: Create a Matrix Account for the Bridge](#step-1-create-a-matrix-account-for-the-bridge)
  - [Step 2: Get the Access Token](#step-2-get-the-access-token)
  - [Step 3: Create a Room for the Bridge](#step-3-create-a-room-for-the-bridge)
  - [Step 4: Invite Users to the Room](#step-4-invite-users-to-the-room)
  - [Step 5: Install and Configure the App](#step-5-install-and-configure-the-app)
- [Usage](#usage)
- [Modes of Operation](#modes-of-operation)
- [How It Works — Technical Architecture](#how-it-works--technical-architecture)
- [Code Walkthrough](#code-walkthrough)
  - [Config.kt — Persistent Configuration](#configkt--persistent-configuration)
  - [SmsReceiver.kt — SMS Interception](#smsreceiverkt--sms-interception)
  - [MatrixClient.kt — Matrix API Wrapper](#matrixclientkt--matrix-api-wrapper)
  - [MatrixSyncService.kt — Long-Polling Foreground Service](#matrixsyncservicekt--long-polling-foreground-service)
  - [SmsSender.kt — SMS Dispatch](#smssenderkt--sms-dispatch)
  - [BootReceiver.kt — Auto-Start on Boot](#bootreceiverkt--auto-start-on-boot)
  - [MainActivity.kt — Configuration UI](#mainactivitykt--configuration-ui)
- [AndroidManifest.xml — Permissions & Components](#androidmanifestxml--permissions--components)
- [Dependencies](#dependencies)
- [Building](#building)
- [Security Considerations](#security-considerations)
- [Troubleshooting](#troubleshooting)
- [License](#license)

---

## What Does This App Do?

1. **Someone sends you an SMS** → The app intercepts it and posts it to a Matrix room you choose. You see it on your computer, tablet, or any device connected to Matrix.

2. **You reply from Matrix** → Type `!sms +1234567890 Your reply here` in that Matrix room → The app picks it up and sends it as an SMS from your phone.

**Key points:**
- No third-party server is involved. Your phone talks directly to your Matrix homeserver.
- The app runs in the background on your phone. It uses very little battery in receive-only mode.
- You need a Matrix account, it can be on any homeserver, including one you self-host.

**Two modes:**
- **Receive-only (default):** SMS → Matrix only. Low battery impact. No foreground service needed.
- **Full bridge:** SMS ↔ Matrix. The app also listens for `!sms` commands from Matrix and sends SMS. Requires a persistent foreground service (shows a notification).

---

## Getting Started — Step by Step Setup Guide

You need three pieces of information to configure the app:
1. **Homeserver URL** - the address of your Matrix server
2. **Access Token** - a secret key that lets the app authenticate as a Matrix user
3. **Room ID** - the internal ID of the Matrix room where SMS messages will appear

### Step 1: Create a Matrix Account for the Bridge

You can use an existing Matrix account, but **it's strongly recommended to create a dedicated account for the bridge.** This way:
- Your personal messages stay separate from SMS messages
- You can control exactly which room the bridge has access to
- If the token is compromised, you only lose the bridge account

**On a Matrix client (e.g., Element):**
1. Open Element (or your preferred Matrix client)
2. Create a new account — e.g., username `smsbot` on your homeserver
3. Remember the username and password — you'll need them to get the access token

**If you self-host with Synapse (bare-metal):**

1. Find your `homeserver.yaml` configuration file. Common locations:
   - `/etc/synapse/homeserver.yaml`
   - `/opt/synapse/homeserver.yaml`
   - Whatever path you specified during setup

2. Check if registration is enabled. Open `homeserver.yaml` and look for:
   ```yaml
   enable_registration: false
   ```
   If it's `false`, you have two options:

   **Option A — Use the `register_new_matrix_user` CLI tool (recommended, no need to enable registration):**
   ```bash
   register_new_matrix_user -c /path/to/homeserver.yaml -u smsbot -p 'YourSecurePassword'
   ```
   This script ships with the `matrix-synapse` package. It talks directly to the database, bypassing the registration toggle. You'll be prompted:
   - "Make admin?" → answer **no** (the bridge bot does not need admin privileges)
   - If your Synapse is configured to require a registration shared secret, you'll also be prompted for it — find it in `homeserver.yaml` under `registration_shared_secret`

   If the command is not found, it may be installed elsewhere:
   ```bash
   # Check if it's on PATH:
   which register_new_matrix_user

   # Common locations depending on install method:
   # pip install (virtualenv):
   /path/to/synapse/env/bin/register_new_matrix_user -c /path/to/homeserver.yaml -u smsbot -p 'YourSecurePassword'

   # Debian/Ubuntu package:
   /usr/bin/register_new_matrix_user -c /etc/synapse/homeserver.yaml -u smsbot -p 'YourSecurePassword'
   ```

   **Option B — Temporarily enable public registration, sign up via Element, then disable it:**
   1. Edit `homeserver.yaml`:
      ```yaml
      enable_registration: true
      enable_registration_without_verification: true
      ```
   2. Restart Synapse:
      ```bash
      sudo systemctl restart matrix-synapse
      # Or, depending on your setup:
      sudo systemctl restart synapse
      ```
   3. Open Element → create a new account with username `smsbot` and your chosen password
   4. **Immediately disable registration again** — edit `homeserver.yaml` back to:
      ```yaml
      enable_registration: false
      ```
   5. Restart Synapse again:
      ```bash
      sudo systemctl restart matrix-synapse
      ```

   > ⚠️ **Security warning:** Leaving `enable_registration: true` open means anyone can create accounts on your server. Only enable it temporarily and disable it immediately after.

**If you self-host with Synapse (Docker):**

1. Find your Synapse container name:
   ```bash
   docker ps | grep synapse
   ```
   Note the container name or ID (e.g., `synapse`, `matrix-synapse`, `synapse-1`).

2. Find the config path inside the container. Check your `docker-compose.yml` for volume mounts:
   ```bash
   cat docker-compose.yml | grep -A5 synapse
   ```
   Typical volume mapping:
   ```yaml
   volumes:
     - ./synapse-data:/data
   # or
     - /opt/synapse:/data
   ```
   Inside the container, the config is at `/data/homeserver.yaml`.

3. **Option A — Use `register_new_matrix_user` inside the container (recommended):**
   ```bash
   docker exec -it synapse register_new_matrix_user -c /data/homeserver.yaml -u smsbot -p 'YourSecurePassword'
   ```
   Replace `synapse` with your actual container name and `/data/homeserver.yaml` with the config path inside the container.

   You'll be prompted:
   - "Make admin?" → answer **no**
   - If a registration shared secret is required, find it by reading the config inside the container:
     ```bash
     docker exec synapse cat /data/homeserver.yaml | grep registration_shared_secret
     ```

   If `register_new_matrix_user` is not found inside the container, it may be at a different path depending on the Docker image:
   ```bash
   # Try the default location:
   docker exec -it synapse which register_new_matrix_user

   # Try the virtualenv path (common in matrixdotorg/synapse image):
   docker exec -it synapse /usr/local/bin/python -m synapse.tools.register_new_matrix_user -c /data/homeserver.yaml -u smsbot -p 'YourSecurePassword'

   # Or explicitly via the synapse venv:
   docker exec -it synapse /usr/local/bin/register_new_matrix_user -c /data/homeserver.yaml -u smsbot -p 'YourSecurePassword'
   ```

4. **Option B — Temporarily enable public registration, sign up via Element, then disable it:**

   1. Edit the config file **on the host** (not inside the container). Find the host path from your volume mounts:
      ```bash
      # If docker-compose has: volumes: - ./data:/data
      # Then edit on the host:
      vim ./data/homeserver.yaml

      # If it's: volumes: - /opt/synapse:/data
      vim /opt/synapse/homeserver.yaml
      ```
   2. Find and change these lines (or add them if missing):
      ```yaml
      enable_registration: true
      enable_registration_without_verification: true
      ```
   3. Restart the container to pick up the config change:
      ```bash
      docker restart synapse
      ```
   4. Open Element → create a new account with username `smsbot` and your chosen password
   5. **Immediately disable registration again** — edit `homeserver.yaml` back to:
      ```yaml
      enable_registration: false
      ```
   6. Restart the container again:
      ```bash
      docker restart synapse
      ```

5. **Option C — Use the Synapse Admin API (if you have admin access):**

   If you already have an admin account, you can create users via the admin API without touching the config:
   ```bash
   curl -X PUT "https://YOUR_HOMESERVER/_synapse/admin/v2/users/@smsbot:example.com" \
     -H "Authorization: Bearer YOUR_ADMIN_TOKEN" \
     -H "Content-Type: application/json" \
     -d '{
       "password": "YourSecurePassword",
       "displayname": "SMS Bridge Bot",
       "admin": false
     }'
   ```

   Replace:
   - `YOUR_HOMESERVER` — your homeserver's public URL
   - `@smsbot:example.com` — the full Matrix user ID (replace `example.com` with your server name)
   - `YOUR_ADMIN_TOKEN` — access token of an admin user (get it from Element → Settings → Help & About → Access Token)
   - `YourSecurePassword` — a strong password for the bridge account

   A successful response looks like:
   ```json
   {
     "name": "@smsbot:example.com"
   }
   ```

   > **Note:** This works the same whether Synapse is bare-metal or Docker — it goes through the public API, not the container. Just make sure your reverse proxy forwards `/_synapse/admin/` paths.

   > ⚠️ **Security warning:** The admin API endpoint `/_synapse/admin/` should only be accessible to authenticated admins. Make sure your reverse proxy doesn't accidentally expose it to the public internet without auth.

### Step 2: Get the Access Token

The access token is a long string that authenticates the app to your Matrix homeserver. **Treat it like a password — anyone with it can read and send messages as that user.**

#### Method A: Using `curl` (recommended — most reliable)

This works regardless of whether your homeserver is bare-metal or Docker — you just need the URL.

```bash
curl -X POST "https://YOUR_HOMESERVER_URL/_matrix/client/v3/login" \
  -H "Content-Type: application/json" \
  -d '{
    "type": "m.login.password",
    "identifier": {
      "type": "m.id.user",
      "user": "smsbot"
    },
    "password": "YourSecurePassword"
  }'
```

Replace:
- `YOUR_HOMESERVER_URL` — your actual homeserver URL (e.g., `https://matrix.example.com`)
- `smsbot` — the username you created
- `YourSecurePassword` — the password you set

**Response** (example):
```json
{
  "user_id": "@smsbot:example.com",
  "access_token": "syt_c21zYm90_XxXxXxXxXxXxXxXxXxXxXxXxXxXxXxXx",
  "device_id": "ABCDEFGHIJK",
  "well_known": {
    "m.homeserver": {
      "base_url": "https://matrix.example.com"
    }
  }
}
```

**Copy the `access_token` value** — that's what you paste into the app.

> **If your server uses a custom or self-signed certificate**, add `-k` to curl to skip verification:
> ```bash
> curl -k -X POST "https://YOUR_HOMESERVER_URL/_matrix/client/v3/login" ...
> ```
> Note: The Android app will also need to trust this certificate. For self-signed certs, you may need to install the CA certificate on your Android device (Settings → Security → Install certificate).

> **Docker-specific:** If your Synapse is behind a reverse proxy (Caddy, Nginx, Traefik) in Docker, use the **public** URL (e.g., `https://matrix.example.com`), not the internal container URL. The reverse proxy handles routing to the container.

> **If the request fails with a connection error**, make sure:
> - The homeserver URL is correct and accessible from outside your network
> - Your reverse proxy (if any) is forwarding `/.well-known/matrix/client` and `/_matrix/` paths
> - Your firewall allows HTTPS (port 443) traffic

#### Method B: Using Element (easier but token may expire)

1. Log in to Element as the bridge user (`smsbot`)
2. Go to **Settings → Help & About → Advanced**
3. Scroll down to the **Access Token** field
4. Click to reveal and copy it

> ⚠️ **Warning:** Tokens obtained through Element are tied to your Element session. If you log out of Element, the token is invalidated and the bridge stops working. The `curl` method creates an independent device token that persists until you explicitly revoke it. **Prefer the curl method.**

#### Method C: Using `curl` with admin API (Docker / Synapse admin)

If you have admin access to Synapse and want to create a token without knowing the password:

```bash
# For bare-metal:
curl -X POST "http://localhost:8008/_synapse/admin/v1/users/@smsbot:example.com/login" \
  -H "Authorization: Bearer YOUR_ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{}'

# For Docker (through reverse proxy):
curl -X POST "https://matrix.example.com/_synapse/admin/v1/users/@smsbot:example.com/login" \
  -H "Authorization: Bearer YOUR_ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{}'

# For Docker (direct to container, if exposed):
curl -X POST "http://localhost:8008/_synapse/admin/v1/users/@smsbot:example.com/login" \
  -H "Authorization: Bearer YOUR_ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{}'
```

You can get your admin token by logging in as an admin user via curl (same as Method A) or from Element's Settings → Help & About → Access Token.

### Step 3: Create a Room for the Bridge

You need a dedicated Matrix room where SMS messages will be posted. **Don't use an existing personal chat** — create a new room specifically for the SMS bridge.

#### Via Element:

1. Click **"+" (Add room) → New Room**
2. Set the room name (e.g., "SMS Bridge")
3. Set visibility to **Private** (recommended)
4. Click **Create Room**
5. Once created, click the room name at the top → **Settings**
6. Scroll to the very bottom — you'll see the **Room ID** (format: `!AbCdEfGhIjKlMn:example.com`)
7. **Copy the Room ID** — this is what you paste into the app

> ⚠️ The Room ID is **not** the room name or alias. It starts with `!` and looks like `!XqUaLmNpQr:matrix.example.com`. The alias (like `#sms-bridge:example.com`) won't work — you need the internal ID.

#### Via curl:

```bash
curl -X POST "https://YOUR_HOMESERVER_URL/_matrix/client/v3/createRoom" \
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "SMS Bridge",
    "visibility": "private",
    "preset": "private_chat"
  }'
```

The response includes the `room_id`:
```json
{
  "room_id": "!AbCdEfGhIjKlMn:example.com"
}
```

### Step 4: Invite Users to the Room

The bridge user (e.g., `@smsbot:example.com`) needs to be **in the room**. If you created the room with your personal account, invite the bridge user. If you created the room with the bridge account, invite your personal account.

#### Via Element:

1. Open the SMS Bridge room
2. Click the **people icon** (top right) or click the room name → **Invite people**
3. Type the bridge user's name (e.g., `@smsbot:example.com`) and select it
4. The bridge user will appear in the room once it accepts the invite

> **The bridge user must accept the invite.** Since the bridge user is the one the app logs in as, the app will automatically see the invite when it syncs. However, the app doesn't handle room invites automatically — you should **accept the invite manually** first:
>
> The easiest way: Log in to Element **as the bridge user** (`smsbot`), go to the room, and accept the invite. Then log back in as yourself.
>
> Or via curl:
> ```bash
> curl -X POST "https://YOUR_HOMESERVER_URL/_matrix/client/v3/rooms/!ROOM_ID:example.com/join" \
>     -H "Authorization: Bearer BRIDGE_USER_TOKEN" \
>     -H "Content-Type: application/json" \
>     -d '{}'
> ```

#### Via curl (invite the bridge user):

```bash
curl -X POST "https://YOUR_HOMESERVER_URL/_matrix/client/v3/rooms/!ROOM_ID:example.com/invite" \
  -H "Authorization: Bearer YOUR_PERSONAL_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "user_id": "@smsbot:example.com"
  }'
```

Then accept the invite (as the bridge user):
```bash
curl -X POST "https://YOUR_HOMESERVER_URL/_matrix/client/v3/rooms/!ROOM_ID:example.com/join" \
  -H "Authorization: Bearer BRIDGE_USER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{}'
```

#### Adding other people:

You can invite any Matrix user to the room — they'll see incoming SMS messages and (if full bridge mode is enabled) can send SMS by typing `!sms` commands.

1. In Element: Open the room → Click room name → Invite people → type their Matrix ID
2. Or via curl:
   ```bash
   curl -X POST "https://YOUR_HOMESERVER_URL/_matrix/client/v3/rooms/!ROOM_ID:example.com/invite" \
     -H "Authorization: Bearer YOUR_TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"user_id": "@friend:otherserver.com"}'
   ```

> ⚠️ **Privacy note:** Anyone in the room can see all SMS messages that come through the bridge. Only invite people you trust. If you only want to see SMS yourself, just have your personal account and the bridge account in the room.

### Step 5: Install and Configure the App

1. **Build and install** the APK on your Android phone (see [Building](#building))
2. **Open the app** — you'll see the configuration screen
3. **Grant permissions** — the app will prompt for SMS and notification permissions. Grant all of them.
4. **Fill in the fields:**
   - **Homeserver URL:** Your Matrix server URL (e.g., `https://matrix.example.com`). Do **not** include a trailing slash.
   - **Access Token:** The token you obtained in Step 2
   - **Room ID:** The room ID you obtained in Step 3 (format: `!xxx:example.com`)
5. **Choose your mode:**
   - **Receive only** (checked by default): SMS → Matrix only. Low battery impact.
   - **Uncheck for full bridge**: SMS ↔ Matrix. The app will also listen for `!sms` commands. This requires a foreground service (a persistent notification).
6. **Tap "Test Connection"** — verifies the homeserver URL and access token. Shows your Matrix user ID if successful.
7. **Enable the bridge** using the toggle switch
8. **Tap "Save & Apply"**

The bridge is now active. Send a test SMS to your phone — it should appear in the Matrix room within seconds.

---

## Usage

### Incoming SMS (automatic)

When someone sends an SMS to your phone, it appears in the Matrix room like this:

> 📱 SMS from +1234567890:
> Hey, are you coming tonight?

The sender's phone number and the full message body are included.

### Sending SMS from Matrix (full bridge mode only)

Type in the Matrix room:

```
!sms +1234567890 Your message here
```

- `!sms` — the command prefix (not case-sensitive — `!SMS` works too)
- `+1234567890` — the phone number to send to (include country code with `+`)
- `Your message here` — the SMS text

After sending, the bridge confirms in the room:

> ✅ SMS sent to +1234567890

> **Note:** Sending SMS from Matrix only works in **full bridge mode** (the "Receive only" checkbox must be unchecked). In receive-only mode, the app doesn't listen for Matrix messages.

---

## Modes of Operation

| Feature | Receive Only | Full Bridge |
|---------|-------------|-------------|
| SMS → Matrix | ✅ | ✅ |
| Matrix → SMS | ❌ | ✅ (`!sms` command) |
| Foreground service | Not needed | Required (shows notification) |
| Battery impact | Minimal | Low (long-poll with 30s timeout) |
| Auto-start on boot | No service to start | Starts sync service |
| Use case | Just want to read SMS on desktop | Want to reply from desktop too |

---

## How It Works — Technical Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                         ANDROID PHONE                            │
│                                                                  │
│  ┌──────────┐    SMS_RECEIVED    ┌──────────────┐                │
│  │  Android  │─────────────────► │  SmsReceiver  │               │
│  │   SMS     │   (BroadcastReceiver)              │              │
│  │  System   │                   └──────┬────────┘               │
│  └──────────┘                          │                         │
│                                  sendSmsNotice()                 │
│                                        │                         │
│                                        ▼                         │
│                                ┌──────────────┐                  │
│                                │ MatrixClient  │                 │
│                                │  (OkHttp)     │                 │
│                                └──────┬────────┘                 │
│                                       │ PUT /send                │
│  ┌────────────┐    sendTextMessage()  │                          │
│  │  SmsSender │◄──────────────────────┤                          │
│  │  (SmsManager)                      │                          │
│  └─────┬──────┘                       │                          │
│        │                              │                          │
└────────┼──────────────────────────────┼──────────────────────────┘
         │                              │
    SMS to recipient            HTTPS to Matrix homeserver
         │                              │
         ▼                              ▼
   ┌──────────┐               ┌────────────────────┐
   │  Carrier │               │  Matrix Room       │
   │  Network │               │  (!xxx:example.com)│
   └──────────┘               └────────┬───────────┘
                                        │
                                        │ Long-poll /sync
                                        │ (30s timeout)
                                        ▼
                               ┌───────────────────┐
                               │ MatrixSyncService │
                               │ (Foreground Svc)  │
                               └────────┬──────────┘
                                        │
                               Parse "!sms" commands
                                        │
                                        ▼
                               ┌──────────────────┐
                               │   SmsSender      │
                               │  (SmsManager)    │
                               └──────────────────┘
```

**Key design decisions:**

1. **No web server on the phone.** The phone is always a client — it makes outbound HTTPS requests to the Matrix homeserver. This means no port forwarding, no firewall issues, and no attack surface from incoming connections.

2. **Long-polling, not WebSockets.** The app uses Matrix's `/sync` endpoint with a 30-second timeout. When new messages arrive, the server responds immediately. If nothing happens, the server responds after 30 seconds and the client reconnects. This is battery-efficient and works reliably on mobile networks.

3. **SharedPreferences for config.** No database overhead — just a simple key-value store for the three settings (URL, token, room ID) plus the enable/receive-only flags.

4. **Thread-safe configuration.** `Config` uses `@Volatile` fields and `synchronized` blocks so the background service and UI thread don't race.

---

## Code Walkthrough

### `Config.kt` — Persistent Configuration

**Purpose:** Stores and retrieves app configuration using Android's `SharedPreferences`.

**How it works:**
- `Config` is a Kotlin `object` (singleton) — accessible from anywhere in the app
- Fields are `@Volatile` for thread safety (read from UI thread, background service, and BroadcastReceiver)
- `load()` and `save()` are `synchronized` to prevent race conditions
- `isConfigured()` checks that all three required fields (homeserver URL, access token, room ID) are non-empty

**Stored values:**
| Key | Type | Default | Purpose |
|-----|------|---------|---------|
| `homeserver_url` | String | `""` | Matrix homeserver URL |
| `access_token` | String | `""` | Matrix API access token |
| `room_id` | String | `""` | Target Matrix room ID |
| `enabled` | Boolean | `false` | Bridge on/off |
| `receive_only` | Boolean | `true` | SMS → Matrix only (no Matrix → SMS) |

### `SmsReceiver.kt` — SMS Interception

**Purpose:** BroadcastReceiver that fires when an SMS arrives on the phone.

**How it works:**
1. Registered in the manifest with `android.provider.Telephony.SMS_RECEIVED` intent filter at **priority 999** (high priority to catch SMS early)
2. When an SMS arrives, `onReceive()` is called by the Android system
3. Loads config — if the bridge is disabled or not configured, returns immediately
4. Extracts SMS messages from the intent using `Telephony.Sms.Intents.getMessagesFromIntent()`
5. Groups messages by sender (multi-part SMS from the same number get concatenated)
6. For each sender, calls `matrixClient.sendSmsNotice(sender, body)` which posts to Matrix

**Why GlobalScope?** `BroadcastReceiver.onReceive()` is short-lived — the system can kill it as soon as `onReceive()` returns. But the network call to Matrix takes time. `GlobalScope.launch(Dispatchers.IO)` ensures the coroutine outlives the receiver. This is a deliberate trade-off — in production, a better pattern would be to enqueue work via WorkManager, but for this lightweight app it's sufficient.

### `MatrixClient.kt` — Matrix API Wrapper

**Purpose:** Wraps the Matrix Client-Server API using OkHttp.

**Key components:**

- **`MatrixClientHolder`** (object): Shared singleton holding the `OkHttpClient`, `Gson`, and JSON media type. Shared across all `MatrixClient` instances to avoid connection pool leaks.

- **`MatrixClient`** (class): Instance that takes a `Context` for config access. Methods:

  - **`sendSmsNotice(sender, body)`** — Fire-and-forget coroutine that sends a formatted SMS notification to the Matrix room. Message format: `📱 SMS from +1234567890:\nHello world`

  - **`sendTextMessage(text)`** — Core send method. Uses `PUT /_matrix/client/v3/rooms/{roomId}/send/m.room.message/{txnId}`. Transaction IDs are unique (timestamp + counter) to prevent duplicates on retry.

  - **`pollForMessages(sinceBatch)`** — Long-polls `GET /_matrix/client/v3/sync` with a 30-second timeout. Uses a filter to only receive `m.room.message` events from the configured room (limit 10). Parses the response for `!sms` commands and dispatches them to `SmsSender`. Returns the `next_batch` token for subsequent calls.

  - **`getUserId()`** — Calls `GET /_matrix/client/v3/account/whoami` to get the bot's own Matrix user ID. Caches it in `cachedUserId` so the sync loop can skip the bot's own messages (prevents infinite loops: bot sends "✅ SMS sent" → sync sees it → tries to process it as an `!sms` command).

  - **`createFilter()`** — Builds a Matrix sync filter JSON that limits results to the configured room and `m.room.message` events only. Reduces bandwidth by not syncing unrelated rooms.

**Self-message filtering:** The sync loop must skip messages sent by the bot itself. Without this, the bot would see its own "✅ SMS sent to +1234567890" message, try to parse it as an `!sms` command, and potentially loop. The `cachedUserId` check prevents this.

**!sms command parsing:** Uses a regex `^\+?\d+\s+` to split the command into phone number and message. The phone number must start with `+` (international format) or digits. Everything after the phone number is the message body.

### `MatrixSyncService.kt` — Long-Polling Foreground Service

**Purpose:** Android foreground service that keeps the Matrix `/sync` long-poll loop running.

**How it works:**

1. **`onStartCommand()`** — Entry point. Checks config: if disabled, not configured, or receive-only mode, stops immediately. Otherwise:
   - Creates a persistent notification (required for foreground services on Android 8+)
   - Calls `startForeground()` with the notification — this prevents Android from killing the service
   - Launches a coroutine that runs the sync loop

2. **Sync loop:**
   - First calls `getUserId()` to cache the bot's user ID (for self-message filtering)
   - Does an initial sync with `sinceBatch=null` — this skips all historical messages (we only care about new ones)
   - Enters an infinite loop: calls `pollForMessages(sinceBatch)`, updates `sinceBatch`, repeats
   - Each `/sync` call blocks for up to 30 seconds waiting for new messages
   - On error: waits 10 seconds and retries
   - Re-checks config each iteration — if the user disables the bridge in the app's UI, the loop exits

3. **`onDestroy()`** — Cancels the coroutine and the service scope

4. **`START_STICKY`** — If Android kills the service (memory pressure), it restarts it automatically

**Why a foreground service?** Android aggressively kills background services to save battery. A foreground service (with its persistent notification) tells Android "this is actively doing work, don't kill it." The notification says "Listening for Matrix messages" and can't be swiped away.

**Why not WorkManager?** WorkManager has minimum interval of 15 minutes — way too slow for a chat bridge. Long-polling with a foreground service gives near-instant message delivery.

### `SmsSender.kt` — SMS Dispatch

**Purpose:** Thin wrapper around Android's `SmsManager` to send SMS messages.

**How it works:**
- On Android 12+ (API 31): uses `context.getSystemService(SmsManager::class.java)` — the modern API
- On older versions: falls back to the deprecated `SmsManager.getDefault()`
- Handles multipart SMS automatically: if the message is too long for a single SMS, `divideMessage()` splits it into parts and `sendMultipartTextMessage()` sends them

**No delivery reports.** The `sentIntent` and `deliveryIntent` parameters are null — the app fires and forgets. If SMS sending fails, it logs the error but doesn't notify the user. This could be improved in a future version.

### `BootReceiver.kt` — Auto-Start on Boot

**Purpose:** Restarts the Matrix sync service after the phone reboots.

**How it works:**
- Listens for `BOOT_COMPLETED` and `QUICKBOOT_POWERON` (HTC devices) broadcasts
- Loads config — if the bridge is enabled, configured, and NOT in receive-only mode, starts `MatrixSyncService`
- In receive-only mode, there's no service to start — the `SmsReceiver` is always active (it's registered in the manifest)

### `MainActivity.kt` — Configuration UI

**Purpose:** The app's only screen — configuration form with test and save functionality.

**Layout:**
- Switch: Enable/Disable bridge
- Checkbox: Receive-only mode (default: checked)
- EditText: Homeserver URL
- EditText: Access Token (masked as password)
- EditText: Room ID
- Button: Save & Apply
- Button: Test Connection
- TextView: Status display

**Key behaviors:**

- **`requestPermissions()`** — On create, requests `RECEIVE_SMS`, `SEND_SMS`, `INTERNET`, and (Android 13+) `POST_NOTIFICATIONS`
- **`saveAndApply()`** — Saves config to SharedPreferences, then starts or stops the sync service based on the new settings
- **`testConnection()`** — Calls `MatrixClient.getUserId()` on a background coroutine. If successful, shows "✅ Connected as @smsbot:example.com". If failed, shows "❌ Connection failed — check URL and token"
- **`updateStatus()`** — Shows the current bridge state:
  - `⚠️ Not configured` — missing required fields
  - `⏸ Bridge disabled` — toggle is off
  - `📥 Receive-only mode (SMS → Matrix)` — receive-only, no sync service
  - `🔄 Full bridge (SMS ↔ Matrix)` — full bridge, sync service running

---

## AndroidManifest.xml — Permissions & Components

| Permission | Purpose | Required For |
|-----------|---------|--------------|
| `RECEIVE_SMS` | Intercept incoming SMS | Both modes |
| `SEND_SMS` | Send SMS from Matrix commands | Full bridge mode |
| `READ_SMS` | Read SMS inbox (future use) | Reserved |
| `INTERNET` | Matrix API HTTP calls | Both modes |
| `FOREGROUND_SERVICE` | Run sync service in background | Full bridge mode |
| `FOREGROUND_SERVICE_DATA_SYNC` | Type declaration for foreground service | Full bridge mode |
| `RECEIVE_BOOT_COMPLETED` | Auto-start after reboot | Full bridge mode |
| `POST_NOTIFICATIONS` | Foreground service notification | Android 13+ |

**Registered components:**
- `MainActivity` — Launcher activity
- `SmsReceiver` — SMS BroadcastReceiver (priority 999, exported with `BROADCAST_SMS` permission)
- `BootReceiver` — Boot completed BroadcastReceiver
- `MatrixSyncService` — Foreground service (type: `dataSync`, not exported)

---

## Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| OkHttp | 4.12.0 | HTTP client for Matrix API |
| Gson | 2.12.1 | JSON serialization/deserialization |
| Kotlin Coroutines | 1.9.0 | Async HTTP calls, sync loop |
| Material Components | 1.12.0 | UI widgets (SwitchMaterial) |
| AndroidX Core KTX | 1.15.0 | Kotlin extensions for Android APIs |
| AndroidX Preference KTX | 1.2.1 | SharedPreferences Kotlin extensions |
| JUnit | 4.13.2 | Unit testing |
| Mockito | 5.14.2 | Mocking for unit tests |
| mockito-kotlin | 5.4.0 | Kotlin-friendly Mockito wrappers |

**Notably absent:** No Matrix SDK. The app talks directly to the Matrix Client-Server API using raw HTTP calls. This keeps the app tiny and avoids pulling in the heavyweight matrix-sdk-android dependency (~2MB). The trade-off is that the app only implements the few endpoints it needs (`/login`, `/sync`, `/send`, `/whoami`) rather than the full Matrix protocol.

---

## Building

### Prerequisites

- JDK 21
- Android SDK 35 (compile and target)
- Android device running API 26+ (Android 8.0 Oreo)

### Build from CLI

```bash
cd sms-matrix-bridge
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

### Build release APK

```bash
./gradlew assembleRelease
```

The release APK is minified with ProGuard (R8). You'll find it at `app/build/outputs/apk/release/app-release-unsigned.apk`. Sign it with your keystore:

```bash
jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 \
  -keystore /path/to/keystore.jks \
  app/build/outputs/apk/release/app-release-unsigned.apk \
  your-key-alias
```

### Build with Android Studio

1. Open the project directory in Android Studio
2. Let Gradle sync
3. Run → Run 'app' (or Build → Build APK)

---

## Security Considerations

1. **Access token storage.** The token is stored in SharedPreferences as plaintext. On rooted devices, any app with root access can read it. For better security, consider using Android's EncryptedSharedPreferences (from the `androidx.security` library). **Treat the token like a password.**

2. **SMS permissions.** The app requires `RECEIVE_SMS` and `SEND_SMS` — these are dangerous permissions. Android prompts the user to grant them. The app cannot function without them.

3. **No end-to-end encryption.** The app uses the Matrix Client-Server API directly (no E2EE support). Messages in the bridge room should be sent unencrypted, or the bot won't be able to read `!sms` commands. **Do not put sensitive SMS content in an E2EE room** — the bridge account has access to the room's keys, but the app itself doesn't implement the Megolm decryption.

4. **Self-signed certificates.** If your homeserver uses a self-signed certificate, you'll need to install the CA certificate on your Android device. Go to Settings → Security → Install certificate → CA certificate.

5. **Rate limiting.** Synapse rate-limits API calls by default. If you receive a flood of SMS, the bridge may hit the rate limit and some messages could be delayed. The 30-second sync timeout naturally throttles outbound checks.

6. **Token revocation.** To revoke a compromised token:
   ```bash
   # List all devices (admin API):
   curl "https://YOUR_HOMESERVER/_synapse/admin/v1/users/@smsbot:example.com/devices" \
     -H "Authorization: Bearer ADMIN_TOKEN"
   
   # Delete a specific device:
   curl -X DELETE "https://YOUR_HOMESERVER/_synapse/admin/v1/users/@smsbot:example.com/devices/DEVICE_ID" \
     -H "Authorization: Bearer ADMIN_TOKEN"
   ```
   Or log out all devices via Element (Settings → Security → Devices → Log out all other sessions).

---

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| SMS not appearing in Matrix | Bridge disabled or not configured | Open app → check status, enable bridge, verify fields |
| SMS not appearing in Matrix | App killed by battery optimization | Disable battery optimization for this app: Settings → Apps → SMS Matrix Bridge → Battery → Unrestricted |
| SMS not appearing in Matrix | No internet connection | Phone needs network access to reach the homeserver |
| `!sms` commands not working | Receive-only mode enabled | Uncheck "Receive only" in the app |
| `!sms` commands not working | Sync service not running | Toggle bridge off/on, or reboot phone |
| Connection test fails | Wrong homeserver URL | Must be the base URL (e.g., `https://matrix.example.com`), not `/_matrix/` or a room URL |
| Connection test fails | Wrong access token | Re-generate via curl (see Step 2) |
| Connection test fails | Self-signed cert | Install CA certificate on Android device |
| Bot's own messages trigger `!sms` | Self-message filtering failed | The app caches the user ID on first sync. Restart the app (or reboot) to re-fetch it. |
| Duplicate SMS messages | Another SMS app is forwarding | Check that no other app (like a call/SMS blocker or IFTTT) is also bridging SMS |
| Sync service stops after a while | Android Doze mode | Disable battery optimization for the app (see above) |
| Messages delayed by 10+ seconds | Network latency or rate limiting | Normal behavior — the 30s sync timeout means worst-case 30s delay. Persistent delays may indicate a slow homeserver. |

---

## License

AGPL-3.0
