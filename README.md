<div align="center">

# Firewall for GrapheneOS

**A per-app firewall built on GrapheneOS's own Network permission. No VPN, and no internet access of its own.**

[![Latest release](https://img.shields.io/github/v/release/haintrainn/grapheneos-firewall-app?label=release)](https://github.com/haintrainn/grapheneos-firewall-app/releases/latest)
[![License: GPL v3](https://img.shields.io/badge/license-GPLv3-blue.svg)](LICENSE)
[![Android 14+](https://img.shields.io/badge/Android-14%2B-3ddc84.svg)](#requirements)
[![Downloads](https://img.shields.io/github/downloads/haintrainn/grapheneos-firewall-app/total)](https://github.com/haintrainn/grapheneos-firewall-app/releases)

</div>

> [!NOTE]
> This is an independent, unofficial app. It is not affiliated with or endorsed by the GrapheneOS project.

<!-- Screenshots: add images to docs/ and uncomment.
<p align="center">
  <img src="docs/light.png" width="30%" alt="App list, light theme">
  <img src="docs/dark.png" width="30%" alt="App list, dark theme">
</p>
-->

## Why

GrapheneOS lets you turn off the **Network** permission for any app, which cuts that app off from
every network at the OS level. That toggle is buried in each app's settings, though:
Settings → Apps → *app* → Permissions → Network.

Firewall puts every app's Network permission on one screen, with search and filters, so you can
see and change them at a glance.

| | Firewall | VPN-based firewalls (NetGuard, RethinkDNS…) |
|---|---|---|
| Uses your VPN slot | **No**, works alongside any VPN | Yes |
| Enforcement | GrapheneOS permission, enforced by the OS | Local VPN that filters traffic |
| Keeps working if the app is killed | **Yes**, the permission stays revoked | No, the VPN must stay running |
| Needs network access itself | **No** (no `INTERNET` permission) | Yes |
| Per-domain rules / traffic logs | No | Yes |

## Features

- Every app that requests network access, with its live **Allowed / Blocked** state
- Toggle access with one tap, using the same permission as the switch in Settings
- Search, filters (All / Allowed / Blocked) and an optional system-app view
- Warns before blocking system apps
- Material You design that follows your wallpaper colors and dark mode, styled like GrapheneOS
  Settings
- Tiny (~60 KB), with no dependencies, trackers or analytics

## How it works

On GrapheneOS, `android.permission.INTERNET` is a runtime permission shown as **Network**. Only
privileged callers (the system or the ADB shell) can grant or revoke it, so a normal app can't
flip it alone.

Firewall uses [Shizuku](https://shizuku.rikka.app/) to run the same shell commands you could type
over ADB:

```
pm revoke <package> android.permission.INTERNET   # block
pm grant  <package> android.permission.INTERNET   # allow
```

The Shizuku client is a single ~340-line dependency-free file,
[`Shizuku.java`](src/io/github/haintrainn/firewall/shizuku/Shizuku.java), so it's easy to audit.

**Without Shizuku** the app still shows every app's state. Tapping a switch opens that app's
permission page in Settings so you can change it there.

## Requirements

- GrapheneOS based on Android 14 or newer
- [Shizuku](https://shizuku.rikka.app/download/) for one-tap toggling (optional, but recommended)

## Install

1. Download `firewall.apk` from the [latest release](https://github.com/haintrainn/grapheneos-firewall-app/releases/latest).
   For automatic updates, add this repo's URL to [Obtainium](https://github.com/ImranR98/Obtainium).
2. Install and start Shizuku. On GrapheneOS, enable **Developer options → Wireless debugging**,
   then follow the pairing steps in the Shizuku app.
3. Open Firewall and tap **Allow** when it asks to use Shizuku.

Shizuku has to be started again after each reboot, which is a Shizuku limitation without root.

> [!IMPORTANT]
> **Upgrading from 1.0:** version 1.1 changed the package name to `io.github.haintrainn.firewall`,
> so Android treats it as a new app. Uninstall 1.0 first. Your Network permission choices are
> stored by the OS, not the app, so they stay as they are.

### Verify the APK

Check the signing certificate before installing:

```
apksigner verify --print-certs firewall.apk
```

The SHA-256 certificate digest must be:

```
1b9c2aca23291a449c3adb4fb7c12afcd1d01c0e14978126a1c00afb85bd151c
```

## Build from source

The build doesn't need Gradle and runs natively on a phone in Termux, or on any Linux machine with
the Android SDK.

Requirements: JDK 17+, `aapt2`, and an Android SDK with `platforms/android-36` and
`build-tools/37.0.0` (set `ANDROID_HOME`, default `~/android-sdk`).

```
./build.sh            # -> build/firewall.apk
```

The pipeline is `aapt2` → `javac` → `d8` → [`tools/ApkPack.java`](tools/ApkPack.java), which
merges the files and 4-byte aligns the APK in place of zip/zipalign, then `apksigner`. On the first
build a new signing key is created in `signing/`, which is git-ignored. Keep it: updates must be
signed with the same key.

## FAQ

**Is Shizuku safe?** Shizuku gives apps you approve ADB-shell-level access. Firewall only uses it
to run `pm grant` and `pm revoke` for the Network permission. You can confirm this in the source,
and revoke Firewall's access in the Shizuku app at any time.

**Why does a blocked app sometimes restart?** Android restarts an app's process when one of its
runtime permissions is revoked. That's expected.

**Can I block only mobile data or only Wi-Fi?** No. The Network permission is all-or-nothing,
which is also what makes it so robust. Use a VPN-based firewall for finer control.

**Does it work on stock Android?** Stock Android doesn't let users turn off network access per
app, so the toggles have nothing to control there. It's built for GrapheneOS.

## Contributing

Issues and pull requests are welcome, especially reports from different GrapheneOS devices and
versions. If something doesn't work, please include your device, GrapheneOS version and Shizuku
version.

## License

[GPL-3.0](LICENSE). Shizuku is a separate app by RikkaApps. This project only implements its
public binder interface.

---

<div align="center">

If Firewall is useful to you, a ⭐ helps other GrapheneOS users find it.

</div>
