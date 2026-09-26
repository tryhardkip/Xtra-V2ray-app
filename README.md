# Xtra V2ray — Rust proxy core + Android VPN app

A V2Ray-compatible proxy client for Android. The proxy engine (VMess / VLESS /
Trojan / Shadowsocks) is written in Rust on top of the `leaf` engine; a thin
Kotlin layer provides the `VpnService` tunnel and UI.

## Build on GitHub Actions (no local toolchain needed)

The repo ships a workflow at `.github/workflows/build-apk.yml`. On every push to
`main`/`master` (or via the Actions tab → Run workflow), it:

1. installs JDK 17, the Android SDK + NDK, Rust, and `cargo-ndk`,
2. cross-compiles the Rust core to `libfkcore.so` for arm64-v8a + armeabi-v7a,
3. assembles the debug APK, and
4. uploads it as a build artifact named **app-debug**.

After a run finishes, download the APK from the run's *Artifacts* section.
The `leaf` engine is pulled as a pinned git dependency, so a fresh clone builds
with no extra setup.

## Architecture

```
┌─────────────────────────────┐        ┌──────────────────────────────┐
│  Kotlin / Android SDK layer │        │  Rust core (libfkcore.so)    │
│                             │  JNI   │                              │
│  MainActivity (UI)          │ ─────► │  FkNative.runLeaf(cfg, id)   │
│  FkVpnService (VpnService)  │        │    → leaf::start(...)        │
│    establish() → tun fd     │        │    reads tun-fd, VMess/VLESS │
│    writes leaf .conf        │        │    /Trojan/SS outbounds      │
│    tun-fd = <fd> ───────────┼───────►│                              │
└─────────────────────────────┘        └──────────────────────────────┘
```

Key point: on Android the TUN file descriptor **must** come from
`VpnService.establish()`. The `tun` crate refuses to create an interface by
name on `target_os = "android"`; it only accepts a raw fd. `FkVpnService`
gets that fd and passes it to the engine via the `tun-fd` config entry.

## Layout

```
fkvpn-android/
├── rust/                     # Rust JNI core → libfkcore.so
│   ├── Cargo.toml            # depends on ../../leaf-src/leaf
│   └── src/lib.rs            # Java_com_example_fkvpn_FkNative_* symbols
├── app/                      # Android app module
│   ├── build.gradle.kts      # cargoBuild task runs cargo-ndk into jniLibs
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/fkvpn/
│       │   ├── FkNative.kt       # external fun declarations
│       │   ├── FkVpnService.kt   # VpnService + tun fd + engine thread
│       │   └── MainActivity.kt   # config box + connect/disconnect
│       └── res/...
├── build.gradle.kts / settings.gradle.kts / gradle.properties
└── build-apk.sh              # toolchain setup + build (run on a dev machine)
```

## Building

This needs a full Android toolchain and does **not** build inside the Termux
app sandbox. On a Linux/macOS dev machine (or Termux with the Android SDK
installed and enough space):

1. Prerequisites:
   - Android SDK + NDK (set `ANDROID_SDK_ROOT` and `ANDROID_NDK_HOME`)
   - Rust + `rustup`
   - `cargo install cargo-ndk`
   - `rustup target add aarch64-linux-android armv7-linux-androideabi`
2. Make sure the `leaf-src` checkout sits next to this project (the Rust
   `Cargo.toml` uses `path = "../../leaf-src/leaf"`). Adjust the path if you
   relocate it.
3. Build:
   ```bash
   ./build-apk.sh          # or: ./gradlew :app:assembleDebug
   ```
   The `preBuild` step triggers `cargo-ndk`, which cross-compiles the Rust
   core for both ABIs and drops `libfkcore.so` into `app/src/main/jniLibs/`.
4. Install: `adb install app/build/outputs/apk/debug/app-debug.apk`

## Using

1. Launch the app, edit the `[Proxy]`/`[Rule]` box with a server you own or
   trust, tap **Connect**, and accept the system VPN consent dialog.
2. All device traffic is routed into the engine and out through the configured
   outbound.

### Config reference (leaf .conf)

```
[Proxy]
Trojan = trojan, example.com, 443, password=PW, sni=example.com
VMess  = vmess, example.com, 443, username=UUID, ws=true, ws-path=/ray, tls=true
VLESS  = vless, example.com, 443, password, uuid=UUID
SS     = ss, example.com, 8388, encrypt-method=chacha20-ietf-poly1305, password=PW

[Rule]
DOMAIN-SUFFIX, ads.example.com, Reject
IP-CIDR, 192.168.0.0/16, Direct
FINAL, Trojan
```

The app injects `[General]` with the live `tun-fd` at connect time, so only
`[Proxy]`/`[Rule]` go in the config box.

## Security note

This routes **all** device traffic through whatever server you configure. A
proxy server sees everything sent through it — only point it at servers you own
or trust.

## Status

- Rust core: builds and runs; SOCKS/HTTP inbounds verified end-to-end, tun-fd
  path verified consuming a real TUN fd (see ../fkproxy for the CLI test rig).
- Android layer: complete source, ready to build once the SDK/NDK toolchain is
  available. Not yet compiled into an APK in this environment (no SDK here).
