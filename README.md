# bluesteal-android

The **Android** client for bluesteal — observe and re-broadcast FIDO caBLE
(CTAP 2.2 hybrid-tunnel) BLE advertisements across devices.

- **Stack:** Kotlin / Jetpack Compose (Material 3)
- **Role:** dual-role (host **and** peer) + re-broadcast
- **Re-broadcast:** emits raw service-data and manufacturer-data, so every
  advert variant is re-emitted faithfully (HMAC tag intact).

## Build & run

```bash
git clone https://github.com/acmesecorg/bluesteal-android.git
cd bluesteal-android
# AGP needs JDK 17+. If your default `java` is older, point at Android Studio's JBR:
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew installDebug          # with a real device attached (emulators have no BLE)
```

Or open in Android Studio. No secrets or credentials needed.

## Driving host/join for testing

Launch-intent extras drive the flow over adb without tapping the UI:

- **Host:** `adb shell am start -n org.crainiate.bluesteal/.ui.MainActivity --ez host true`
- **Join:** `adb shell am start -n org.crainiate.bluesteal/.ui.MainActivity --es join <TOKEN>`

Traces live in the app's **external** files dir — read them with the adb shell
user (the hosted token is a `token.ready` event):

```bash
adb shell cat /sdcard/Android/data/org.crainiate.bluesteal/files/Logs/relay-*.jsonl
```

## Rendezvous pairing (two-rooms demo)

To pair with a device in another room without typing a token across, the peer
pulls the host's current `CODE-ROUTING` token from a small Cloudflare Worker
("rendezvous"). Deploy your own Worker from the umbrella repo's
`tools/rendezvous/`, then set these in `local.properties` (gitignored — see
`local.properties.example`):

```
rendezvous.baseUrl=https://<your-worker>.workers.dev
rendezvous.key=<bearer>
rendezvous.room=<room>
```

Rebuild, and a **Room** button appears next to Host / Join. Tapping it polls
`GET /r/<room>`, joins with the latest token, retries across the host's ~60s
routing rotations, and self-heals (re-joins) on a drop. Received adverts are
re-broadcast on air (Android is dual-role), logged as `relay.advert.recv` /
`rebroadcast.publish`. A `connectedDevice` foreground service keeps the relay
alive while backgrounded / screen-locked.

## More

Protocol, architecture, OOB-token format, and the shared specs live in the
umbrella repo: **https://github.com/acmesecorg/bluesteal**
