# Tenda MF6 widget for Android

A home screen widget showing the modem's battery level, signal strength,
network type (4G/3G/EDGE), unread SMS count and data usage. Plus a message
list screen and notifications about new SMS.

The modem is driven by the same HTTP API its web interface at 192.168.0.1 uses.
The scheme was recovered from a HAR capture and verified against the real device
with `tenda_probe.py`.

## Modem API

Firmware: MI-FI MF3 (model MF6), Vue-based web interface.

### Login

```
POST /login/Auth
{"userName":"admin","password":"<MD5(password) in UPPERCASE>"}

-> {"errCode":0}
   Set-Cookie: password=<that same MD5><6 random chars>
```

Every later request is authorized by that cookie. The hash is a plain MD5
uppercased (`chunk-common.js`: `MD5(e).toString().toUpperCase()`).

### Data

```
GET /goform/getModules?rand=<float>&modules=batteryInfo,networkStatus,simStatus,unreadMessage,usedFlow,flowData
```

```json
{"batteryInfo":{"isCharge":false,"battery":30},
 "networkStatus":{"status":0,"mode":"4G","signal":3,"profileName":"Turkcell internet"},
 "simStatus":{"isMatchApn":1,"status":0},
 "unreadMessage":{"count":0},
 "usedFlow":{"usedData":"2145787775"},
 "flowData":{"mode":1,"monthData":"18253611008","unit":"GB","initialTime":1}}
```

| Field | Meaning |
|---|---|
| `batteryInfo.battery` | 0..100 |
| `batteryInfo.isCharge` | charging |
| `networkStatus.signal` | 0..3 — four steps, same as the web icon |
| `networkStatus.mode` | display string as is: `4G`, `3G`, `EDGE` |
| `networkStatus.status` | `0` — online; `1/2/5/6` — the web shows "No Service" and hides `mode` |
| `unreadMessage.count` | unread SMS |
| `usedFlow.usedData` | bytes used, **as a string** (does not fit in an int) |
| `flowData.mode` | `0` no limit, `1` limit set, `2` statistics only |
| `flowData.monthData` | limit in bytes as a string; meaningful when `mode == 1` |

The divisor for "GB" is 1024, as in the web UI (`18253611008` is exactly 17 GiB).

### SMS

```
GET /goform/getModules?modules=smsList
```

Returns threads grouped by sender; `content` is a **UTF-16BE** hex string,
`time` is unix seconds, plus `id` and `isRead`. Reading the list does **not**
change the read state.

Marking as read is a separate explicit call (the web sends it when a thread is
opened, see `smsDetail.js`):

```
POST /goform/setModules?modules=viewSms
{"viewSms":{"id":["3","4"]}}

-> {"errCode":"0"}
```

## Firmware quirks

Everything below was verified against the real device — without handling it a
client works only every other time.

1. **Redirects instead of JSON.** The modem answers `302 -> /login.html`, and an
   HTTP client that follows redirects automatically gets the login page HTML
   instead of data. Redirect following must be disabled and handled manually.

2. **A redirect does not mean the session is gone.** `/login/loginInfo` returns
   302 simply because a session is already active; `/login/Auth` sometimes
   redirects for no visible reason and an immediate retry succeeds. Cure it with
   a retry (3 attempts) and treat only a persistent redirect as "log in again".

3. **A new login kills the previous session.** Verified: after a second
   `/login/Auth` the old session starts answering `errCode:1000`. So the client
   keeps one session and re-logins only on `errCode:1000`. Logging in on every
   poll would keep kicking you out of the web interface.

4. **`errCode` is sometimes a number, sometimes a string.** `getModules` returns
   a number (`1000`), `setModules` returns a string (`"0"`). Compare after
   coercing the type.

5. **`errCode:1000`** in any response means "session is not authorized".

6. **`viewSms` does not validate ids** — a non-existent id still returns
   `errCode:"0"`.

7. **The session limit is 3** (`loginInfo.maxLimit`), counted per client.

8. **The web interface is plain HTTP only.** Android blocks cleartext by default
   since Android 9, which shows up as "Cleartext HTTP traffic not permitted", so
   the app ships a `network_security_config.xml` that allows it. The modem host
   is user-configurable, so the permission is set on the base config rather than
   pinned to one address.

## API probe script

```bash
python3 tenda_probe.py status      # summary
python3 tenda_probe.py sms         # message list, leaves the read state alone
python3 tenda_probe.py mark-read 3 4
python3 tenda_probe.py raw batteryInfo,networkStatus
python3 tenda_probe.py watch --interval 5
```

The password comes from `--password`, the `TENDA_PASSWORD` environment variable
or a prompt. `-v` shows requests, redirects and cookies.

## Application

```
net/TendaClient.kt   HTTP, login, redirect retry, re-login on errCode=1000
net/Models.kt        response parsing, UTF-16BE decoding, byte formatting
net/WifiNetwork.kt   picking the Wi-Fi interface for requests
data/Prefs.kt        settings; password in EncryptedSharedPreferences
work/PollWorker.kt   one poll: cache, widget redraw, notification
work/Scheduler.kt    periodic WorkManager job
widget/              RemoteViews widget, 4x2
ui/MainActivity.kt   settings, a "Test" button and 5-second live refresh
ui/SmsActivity.kt    message list and explicit mark-as-read
```

Decisions worth knowing about:

- **Requests go through the Wi-Fi network explicitly**
  (`Network.openConnection`), otherwise, with mobile data up, traffic to
  192.168.0.1 can leave through the cellular interface. We take the `Network`
  directly rather than using `bindProcessToNetwork` — a process-wide binding is
  easy to forget to undo.
- **The work constraint is `CONNECTED`, not `UNMETERED`**: Android often flags a
  mobile modem's Wi-Fi as metered, which would stop the job from running.
- **The widget draws the last successful snapshot** and appends the reason when
  a fresh poll failed — otherwise it would flash an error every time the phone
  is away from the modem's Wi-Fi.
- **The session cookie is persisted** in the encrypted store and shared by the
  worker, the settings screen and the SMS screen. Without that every poll would
  log in from scratch, and since a new login kills the previous session that is
  one dead session per poll — enough to keep kicking you out of the modem's web
  interface.
- **Three polling tiers.** Background: WorkManager, 15 minutes, its own floor.
  Foreground: while the settings screen is on top it refreshes every 5 seconds,
  which costs nothing extra because the phone is awake anyway. On demand: the ⟳
  button on the widget.
- **Sub-minute background polling is not worth attempting.** WorkManager's floor
  is 15 minutes and a widget's own `updatePeriodMillis` floor is 30; anything
  faster needs a foreground service with a permanent notification, and on
  Android 15+ `dataSync` services are capped at roughly six hours a day.
- **The modem's subnet is checked before any socket is opened.** On a foreign
  Wi-Fi a request to 192.168.0.1 leaves through an interface with no route to it
  and burns the whole connect timeout; the error even names the source address,
  `failed to connect to /192.168.0.1 from /192.168.1.105`. Comparing the modem's
  address against the interface's own `LinkAddress` prefix catches that
  instantly and needs no location permission, unlike reading the SSID. A
  hostname instead of a literal address skips the check rather than blocking.
- **Refreshing on unlock is not possible from a manifest receiver.** A receiver
  for `ACTION_USER_PRESENT` is registered by the system but never delivered:
  the broadcast log says `skipped by policy at enqueue: Background execution not
  allowed`, the Android 8+ implicit-broadcast restriction. Google's own
  `UserPresentReceiver`s are skipped alongside it. In practice the gap is
  covered anyway, because leaving Doze flushes the deferred periodic job.
- **Even 15 minutes is a lower bound.** With the screen off the device dozes and
  defers the job — observed on a Pixel, where a queued poll simply did not run
  until the screen came back on.
- **`OPTION_APPWIDGET_MIN_HEIGHT` is the height in landscape** and
  `OPTION_APPWIDGET_MAX_HEIGHT` the one in portrait — not a range. Measuring by
  the wrong one hid the operator line on a 3x1 widget that had plenty of room.
- **The unread-SMS envelope is a vector too** (`ic_sms.xml`), at the same 9dp
  height and 1.2 stroke as the battery. It used to be the text glyph `✉`, which
  rendered smaller than the vectors around it and sat on the text baseline
  rather than the centre of the row.
- **The battery icon is generated too**, as `ic_battery_0/20/40/60/80/100`: a
  status-bar style outline with a terminal nub and a proportional fill. The
  charge is rounded to the nearest 20% because the icon is nine dp tall and
  finer steps are not visible. The lowest two steps fill in red — the modem
  runs on its own battery, so a low charge is worth flagging.
- **The signal staircase is drawn by this app.** Android exposes no signal icon
  to apps: there is nothing matching signal/cellular/wifi among the 114 public
  `android.R.drawable` entries, and the status bar's own indicator is
  SystemUI-internal (`com.android.settingslib.graph.SignalDrawable`). So
  `ic_signal_none` and `ic_signal_0..3` are a generated vector set — four
  ascending bars, one dp apart, lit ones in the accent colour and the rest
  dimmed. The modem reports 0..3, so level N lights N+1 bars and a full four
  means the best signal; no service dims all of them.
- **Background opacity is a setting**, a slider in the app. The background moved
  off the root view's `background` onto an `ImageView` behind the content,
  because `RemoteViews` can set an ImageView's alpha on every API level while
  tinting a background drawable needs API 31. The shape's own colour is opaque
  and all the transparency comes from the setting. It is applied when the slider
  is released, not during the drag, so the widget is redrawn once.
- **The widget resizes from 3x1 down to 2x1.** Measured on a Pixel: 3x1 reports
  276x94dp and 2x1 reports 179x94dp, so a cell is about 92dp wide and a row 94dp
  tall. Both keep all three rows; only the width changes. Below 220dp the
  provider scales the type down (15sp to 13sp, 11sp to 10sp) and the padding
  with it, because `RemoteViews` cannot change margins before API 31 but can
  change text size and padding. The refresh button sits on the first row —
  at the bottom it was the first thing to get clipped.
- **The SSID is deliberately not checked**: reading the network name requires
  the location permission. We just try the request instead.

## Building

The Gradle wrapper (`gradlew`, `gradle-wrapper.jar`) is not in the repository —
its binary cannot be produced as text. Either:

- open the project in Android Studio, which offers its own Gradle and creates
  the wrapper; or
- run `gradle wrapper --gradle-version 8.7` once locally.

JDK 17 is required:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Only the debug variant is installable as is — it is signed with the local debug
key. `release` has no `signingConfig`, so `assembleRelease` produces an unsigned
APK that `adb install` refuses.
