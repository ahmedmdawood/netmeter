# NetMeter

**صنع بواسطة أحمد داود**

An Android app that measures your internet usage (mobile data or Wi-Fi), shows you
exactly which apps consumed it, and keeps your history for as long as you choose.

## Features (v1)

- **Daily usage** with a per-app breakdown (names + icons), animated bars.
- **Day navigation**: move day by day with arrows, or tap the date to jump to any
  specific day with a date picker.
- **Hour zoom**: pick a specific hour of the selected day to see which apps used
  data in that hour.
- **Week / Month stats**: animated bar chart, period total, daily average and your
  heaviest day.
- **Retention you control**: keep history for 1, 3, 6, 9 or 12 months. Older
  records are deleted automatically.
- **Local snapshots**: a daily background worker records each day's total so your
  history survives beyond what the system keeps, up to your retention period.
- **Lightweight, dark, professional UI** built with Jetpack Compose and Material 3,
  with smooth animations.
- **Private by design**: everything is stored locally on your phone. No account,
  no server, no tracking.

## The one permission it needs

Android requires apps that read network usage to hold **Usage Access**
(`PACKAGE_USAGE_STATS`). This permission cannot be granted with a normal popup;
the app opens the system *Usage Access* settings screen and you switch NetMeter on
once. Until you do, no usage data can be shown — that is an Android platform rule.

## Notes and honest limitations

- History **before the install date** depends on how long the phone itself keeps
  usage records (usually a few weeks). From install onward, the app records its own
  daily snapshots for your chosen retention period.
- Hourly detail comes from the system's usage records; very old hours may not be
  available.
- Dual-SIM phones report usage for the default data SIM.

## Build

Open the project in Android Studio (Hedgehog or newer) and let it sync.

- Kotlin 2.0.21, AGP 8.7.3, Gradle 8.9
- `compileSdk 35`, `minSdk 26`, JDK 17
- No `gradle-wrapper.jar` is committed; Android Studio will use its own Gradle
  distribution automatically.

Then **Run** on a device or emulator. Grant Usage Access when the app asks.

## Roadmap

- Arabic UI + full RTL
- Monthly data-plan limit with alerts
- Home-screen widget
- Per-day notification summary
