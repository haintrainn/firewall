# Firewall

A per-app firewall for GrapheneOS built on the OS's own **Network** permission (the runtime
`android.permission.INTERNET` toggle) rather than a VPN. Blocking an app revokes the permission and
the OS cuts it off from every network. The VPN slot stays free, and Firewall itself has no network
access.

## How changes are applied

GrapheneOS only lets privileged callers grant or revoke the Network permission. Firewall talks to
[Shizuku](https://shizuku.rikka.app/) over raw binder transactions (no library) and runs
`pm grant|revoke --user N <pkg> android.permission.INTERNET` as the shell user. That is the same
change the Settings → Apps → Permissions → Network toggle makes.

Without Shizuku, the list still shows each app's live state, and tapping a switch opens that app's
system settings page so you can change it there.

## Style

The app uses only framework widgets and `Theme.DeviceDefault.DayNight` with the Android 14+ Material You
system color tokens (`@android:color/system_*`). It follows the device's dynamic palette and dark
mode, like the stock GrapheneOS apps. The layout mirrors GrapheneOS Settings: a collapsing large
title, grouped rounded rows, M3 switches with a divider, and a themed (monochrome) launcher icon.

## Build (on device, in Termux)

Requires `openjdk-21`, `aapt2` (Termux package), and an Android SDK at `~/android-sdk` with
`platforms/android-36` and `build-tools/37.0.0`.

    ./build.sh            # -> build/firewall.apk

The build runs aapt2 → javac → d8 → `tools/ApkPack.java` (merge + 4-byte align, replacing
zip/zipalign) → apksigner. A signing key is generated in `signing/` on the first build. Keep it,
because updates must be signed with the same key.

## Use

1. Install `build/firewall.apk`.
2. Install and start Shizuku (wireless debugging), then tap **Allow** in Firewall.
3. Toggle apps. Switching an app off revokes its Network permission, and the app is restarted by
   the system.
