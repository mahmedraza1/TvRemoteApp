# TvRemoteApp

An Android phone app that turns your phone into a remote control for an Android TV or Google TV on
the same Wi-Fi. It talks the **Android TV Remote protocol v2**, the same protocol the official
Google TV phone app uses, so nothing needs to be installed on the TV.

The protocol is implemented from scratch in a small, dependency-free Kotlin library (`core`), with a
Jetpack Compose app (`app`) on top.

## Features

- **Automatic discovery** of TVs on the network (mDNS), or add one by IP address.
- **One-time pairing** with the 6-character code shown on the TV. The phone remembers the TV and
  reconnects on its own.
- **D-pad** with OK (hold to repeat), plus **Back**, **Home**, **Menu** and **Search**.
- **Touchpad mode:** swipe to move, tap to select.
- **Volume, mute and media keys** (previous, rewind, play/pause, fast-forward, next), and the
  phone's hardware volume buttons while connected.
- **Keyboard:** type with your phone's own keyboard into any text field open on the TV.
- **Power** button, automatic reconnect, and an on-screen connection log to help diagnose problems.

## How it works

| Piece | Details |
| --- | --- |
| Discovery | `_androidtvremote2._tcp` via `NsdManager` |
| Pairing | TLS 1.2 to port **6467**. The client presents a self-signed certificate; the TV shows a 6-character hex code. The secret sent back is `SHA-256(clientModulus, clientExponent, serverModulus, serverExponent, code[2:])`, and its first byte must equal `code[0:2]`. |
| Remote channel | TLS 1.2 to port **6466**, with the same client certificate. Length-prefixed protobuf messages. The TV pings every few seconds and the client answers. |
| Keys | `RemoteKeyInject` with Android key codes |
| Text | `RemoteImeBatchEdit` messages |
| Identity | A 2048-bit RSA key and a self-signed certificate generated once by `SelfSigned` and kept in the app's private storage |

The TLS trust manager accepts the TV's self-signed certificate on purpose: trust comes from the
pairing code, which is bound to both certificates, not from a certificate chain.

## Project layout

```
core/   Pure Kotlin/JVM library, no Android dependencies
  Proto.kt       minimal protobuf encoder and decoder
  Messages.kt    Polo (pairing) and remote message builders, key codes
  Session.kt     PairingSession and RemoteSession over TLS
  SelfSigned.kt  generates the client key and certificate
  Cli.kt         developer command line tool (not shipped in the app)
app/    Android app (Jetpack Compose)
  Identity.kt, Discovery.kt, Controller.kt   connection and state
  Components.kt, Screens.kt, MainActivity.kt UI
```

## Requirements

- JDK 17
- Android SDK with platform 36 and build-tools (`sdk.dir` in `local.properties`, or let Android
  Studio create it)
- A phone on Android 8.0 (API 26) or newer, on the same network as the TV

## Build and install

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open the app, pick your TV, tap **Start pairing**, and enter the code shown on the TV.

## Developer CLI

The `core` module includes a command line tool for testing the protocol from a PC. It needs a
client certificate in a PKCS#12 file (password `changeit`):

```bash
keytool -genkeypair -alias client -keyalg RSA -keysize 2048 -validity 3650 \
  -dname "CN=atvremote" -keystore client.p12 -storetype PKCS12 \
  -storepass changeit -keypass changeit

./gradlew :core:installDist
core/build/install/core/bin/core pair client.p12 <tv-ip> code.txt   # then write the code into code.txt
core/build/install/core/bin/core keys client.p12 <tv-ip> 23         # send key code 23 (DPAD_CENTER)
core/build/install/core/bin/core text client.p12 <tv-ip> "hello"    # type into a focused text field
```

Other commands (`selftest`, `pairconnect`, `rawvol`, `ime`) exist for protocol experiments. Keep
the `.p12` file private; it is git-ignored.

## Known limitations

These come from how TV firmware handles the protocol, not from the app itself:

- **Volume and media keys depend on the TV's key mapping.** The TV's remote service injects keys
  through a virtual keyboard. On some firmware (found on a Google AX-550 box) keys such as volume,
  mute, fast-forward, rewind, next, previous and Menu are not in the key table and are ignored.
  Play/pause, search and the D-pad do work. A TV with standard firmware should work normally.
- **Deleting text** is sent as a Delete key press. Some text fields (for example YouTube's in-app
  search keyboard) ignore it; the protocol's text-edit message can delete there, but the app does
  not use it yet.
- Tested only against one TV model. Behaviour on other devices may differ.

## Credits

The message layouts were cross-checked against the open-source
[androidtvremote2](https://github.com/tronikos/androidtvremote2) project and the public
Google TV pairing protocol ("Polo").

## Related

[TvHomeLauncher](https://github.com/mahmedraza1/TvHomeLauncher) is a lightweight home-screen
launcher for the TV itself.
