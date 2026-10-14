# Пульт — voice remote for Android TV

**English** | [Русский](README.ru.md)

An old Android tablet next to the TV becomes a hands-free remote for an
Android TV / Google TV box. Say «Пульт, пауза» from the couch and the video
stops. Speech is recognized offline on the tablet (Vosk, Russian), and the box
is driven over Wi-Fi with the same protocol the Google TV phone app speaks.
No cloud, no Google Assistant, no root.

```
«Пульт, пауза»                        ⏸  Пауза
«Пульт, перемотай назад на минуту»    ⏪  −1 мин
«Пульт, вниз… вправо… окей»           ⬇  ➡  ⏺  OK
«Пульт, выключи через час»            ⏻  Выключу в 23:40
```

## Features

- **Hears you over the movie.** Tested from the couch, about 3 m away, with
  YouTube playing at normal volume: 16 of 16 commands recognized, no false
  triggers from the TV. Just the tablet's built-in microphones.
- **Offline and private.** Recognition runs on the tablet against a fixed
  grammar of about 350 Russian phrases. Audio never leaves the device.
- **Playback.** Pause and play, volume, mute, seeking by any amount, start
  over, skip the intro, next and previous episode, «что играет» and «сколько
  осталось».
- **Navigation.** OK, back, the arrows and home. For 6 seconds after a
  navigation key, the next ones need no wake word: «Пульт, вниз… вниз… окей».
- **Power.** Turns the TV off and on (the box switches the TV over HDMI-CEC).
  Sleep timer: «через час» or «после этой серии».
- **Apps and search.** Opens YouTube, Кинопоиск, Okko, Plex and VK Видео, and
  searches YouTube by voice.
- **Two-step mode for loud scenes.** Say «Пульт», pause, and the TV mutes for
  a moment so the command lands in silence.
- **Quiet feedback.** A line drawn over whatever is on the tablet's screen
  (a clock app, say). No sounds.
- **Quiet mode.** A new install only logs what it would have done until you
  switch control on.

## How it works

```
tablet mic ─▶ Vosk (grammar of commands) ─▶ Android TV Remote v2 (TLS, paired once) ─▶ TV box
                                        └─▶ helper app on the box (optional, HTTP in the LAN) ─▶ media sessions
```

Two APKs:

| APK | Runs on | What it does |
| --- | --- | --- |
| `tvvoice.apk` («Пульт») | the tablet | Listens all the time as a foreground service, recognizes «Пульт, …» and sends keys to the box. |
| `tvhelper.apk` («Пульт · помощник») | the TV box, **optional** | Sees the box's media sessions: precise seeking in any app that allows it (YouTube, Plex…), next and previous episode, what's playing and how long is left, opening apps, searching. |

Without the helper the basics still work: play and pause, volume, navigation,
power, YouTube seeking in 10-second steps (through the D-pad), opening and
searching YouTube.

## Requirements

- **Tablet:** Android 10+ (arm64), plugged in, somewhere near the TV.
- **TV box:** Android TV or Google TV with the Android TV Remote Service, so
  any box the Google TV phone app can control.
- **Helper:** adb access to the box (Developer options → network debugging).
- **«Пульт, включи телевизор»:** the box must stay on the network in standby.
  On Google TV that's *Settings → System → Power & energy → Energy modes*, and
  the mode has to list Google Cast (moderate or higher). In the lowest mode the
  box drops Wi-Fi when asleep and can only be woken by its own remote.

## Install

### Tablet

1. Download `tvvoice.apk` from
   [Releases](https://github.com/megarohas/megarohas_tv_voice/releases) and
   install it.
2. Open **Пульт** and allow the microphone and notifications.
3. Tap **Скачать модель распознавания (45 МБ)**, then **Разрешить надписи
   поверх часов** and **Не ограничивать в фоне**. Each button goes away once
   its job is done.
4. Tap **Сопрячь с ТВ**. A code appears on the TV. Type it in and tap
   **Готово**.
5. The app starts in quiet mode. When you're ready, tap **Включить управление
   ТВ**.

Now say «Пульт, пауза». The app restarts by itself after a reboot or an
update. On Samsung, also keep it out of *Battery → Background usage limits*.

### Helper on the TV box (optional)

From a computer on the same network:

```bash
adb connect <tv-ip>:5555
adb -s <tv-ip>:5555 install tvhelper.apk
adb -s <tv-ip>:5555 shell cmd notification allow_listener com.megarohas.tvhelper/.MediaWatcher
adb -s <tv-ip>:5555 shell appops set com.megarohas.tvhelper SYSTEM_ALERT_WINDOW allow
adb -s <tv-ip>:5555 shell dumpsys deviceidle whitelist +com.megarohas.tvhelper
```

Notification access is only the key to other apps' media sessions; the helper
reads no notifications. The overlay permission is what lets it open apps from
the background.

Then give the helper and the tablet one shared secret. For the tablet, turn on
*Developer options → Wireless debugging* and pair it with `adb pair`:

```bash
TOKEN=$(openssl rand -hex 16)
adb -s <tv-ip>:5555 shell am broadcast -n com.megarohas.tvhelper/.Setup --es token $TOKEN
adb -s <tablet-ip>:<port> shell am broadcast -n com.megarohas.tvvoice/.AdbControl --es helper_token $TOKEN
```

The app's status shows «Помощник на ТВ: на связи» when this worked.

## Commands

Every command starts with «Пульт, …». After a lone «Пульт» the TV mutes for up
to 4 seconds and the command alone is enough.

| Group | Say |
| --- | --- |
| Playback | пауза · стоп · играй · продолжи |
| Volume | громче · тише · чуть громче · чуть тише · нормальная громкость · выключи звук · включи звук |
| Seeking | перемотай вперёд / назад (10 s) · перемотай назад на сорок секунд · перемотай на пять минут вперёд · на минуту назад · начни с начала · пропусти заставку |
| Episodes | следующая серия · предыдущая серия |
| Info | что играет · сколько осталось |
| Navigation | окей · назад · влево · вправо · вверх · вниз · домой |
| Power | включи / выключи телевизор (or приставку) · выключи через полчаса / через час… · выключи после этой серии · отмени таймер |
| Apps | включи ютуб · кинопоиск · окко · плекс · вк видео |
| Search | найди на ютубе … (or pause after «найди на ютубе» and say the query) |

Seek amounts: 5 to 50 seconds, полминуты, 1 to 10 minutes, then 12, 15, 20,
25, 30, 40, 45 and 50 minutes, полчаса, час, полтора часа and два часа. Every
seek names its direction. Bare «назад» is the Back key, and bare «вперёд» does
nothing on purpose.

## Setup from a computer

Everything on the tablet screen can also be done over adb:

```bash
adb -s <tablet-ip>:<port> shell am broadcast -n com.megarohas.tvvoice/.AdbControl --es <key> <value>
```

| Key | Effect |
| --- | --- |
| `dry true\|false` | Quiet mode on or off. |
| `pair 1`, then `code XXXXXX` | Pairing with the TV. |
| `host <ip>` | The box's address. Empty means find it by mDNS (the default). |
| `base <n>` | The box volume that «нормальная громкость» returns to (default 10). |
| `helper_token <secret>` | Shared secret with the helper. |
| `card true\|false` | «What's playing» strip over the clock (off by default). |
| `clips true\|false` | Keep 4 s of audio around each detection, to study false triggers (off by default). |
| `say <phrase>` | Act as if «Пульт, <phrase>» was heard. |
| `key <keycode>` | Send one key to the box. |
| `link <url>` | Open a link on the box (YouTube, for example). |
| `model download` | Fetch the model again. |

Every call replies with the status. The receiver is protected by
`android.permission.DUMP`. The adb shell holds it and regular apps can't get
it, so nothing else on the tablet can steer the TV or switch on recording.

## Notes

- **Volume.** Many TVs, Philips among them, ignore volume commands from a
  source over HDMI-CEC. So «громче/тише» changes the box's own volume. Keep the
  box below its maximum (10 of 15, say) and the TV louder, so there's room to go
  up.
- **Power keys.** Google's remote service ignores the SLEEP and WAKEUP keys.
  Power goes through POWER, which toggles, so the app checks whether the screen
  is on before pressing it.
- **Background microphone.** Android feeds a background app silent audio
  instead of failing. The app notices the silence and brings itself forward for
  a split second through an invisible activity, which is what the overlay
  permission is for.
- **The green dot.** The microphone indicator stays on, because the mic is in
  use all the time.
- **adb on Android TV** forgets authorized computers after 7 days without a
  connection (*Developer options → Disable adb authorization timeout*). This
  only matters for installing the helper; the remote itself doesn't use adb.
- **Changing the phrases.** The wake word and commands are Russian. To change
  them, edit `Commands.java` and run `tools/vocab-check.py`: every word must
  exist in the model's vocabulary, or the phrase never matches.

## Privacy

- Speech is recognized on the tablet and nothing is sent anywhere. Network
  traffic goes only to the TV box in your LAN, plus the one-time model download
  from alphacephei.com.
- `events.tsv` in the app's files dir logs recognized commands as text. Audio
  clips are off unless you turn them on.
- The helper answers only requests carrying the shared token (HTTP, port 8765,
  LAN).

## Build from source

Android SDK with build-tools 36 and platform 36, JDK 17, no Gradle:

```bash
make deps                                  # Vosk + JNA from Maven Central (SHA-1 checked) and the Russian model
make                                       # tablet APK → build/tvvoice.apk
make box                                   # helper APK → build-box/tvhelper.apk
export ADB_SERIAL=<tablet-ip>:<port>
make install grant model start             # install, permissions, push the model, start listening
make box-install link TV=<tv-ip>:5555      # helper on the box + shared secret for both
make events                                # what it heard
```

### Tools

- `tools/mictest.sh` — the acoustics test. Phrases show up on the tablet in
  big letters while it records the room, and the TV is paused and played in
  sync.
- `tools/eval.py` — scores such a recording against the grammar with Vosk on
  the computer. Add `--grammar` with the app's `grammar.json` to use exactly the
  app's grammar.
- `tools/boxkeys.py` — shows how an app on the box reacts to remote keys,
  using `dumpsys media_session`.
- `tools/vocab-check.py` — checks every word of the phrases against the model.

## Tested on

Galaxy Tab S6 Lite (Android 13) → Xiaomi TV Box S 2nd gen (Google TV,
Android 14) → Philips TV over HDMI-CEC, with YouTube, Plex and Кинопоиск. The
APKs are signed with a debug key.

## Credits

- Speech recognition: [Vosk](https://alphacephei.com/vosk/) and the
  vosk-model-small-ru-0.22 model by Alpha Cephei (Apache 2.0). JNA (Apache 2.0
  / LGPL 2.1) is bundled in the tablet APK.
- Field numbers of the Android TV Remote v2 protocol come from
  [androidtvremote2](https://github.com/tronikos/androidtvremote2).
