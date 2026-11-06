# Meal Log

An Android companion for [FX Watcher](https://github.com/ahaverty/fx-watcher) (CamAPS FX users).
Right after you bolus for a meal, Meal Log slides up over CamAPS and asks what you're eating, with
your likely meals ready to tap: what you usually have at this time of day, for about this many carbs.
It's the moment you remember best, and one tap beats being asked an hour later.

**[Download the latest Meal Log (meal-log.apk)](https://github.com/ahaverty/meal-log/releases/latest/download/meal-log.apk)**
· [What's new](https://github.com/ahaverty/meal-log/releases)

> **Unofficial and not a medical device.** Not made by or affiliated with CamDiab or Ypsomed.
> Meal Log only records food. It never doses insulin, never suggests a dose and never touches CamAPS.

## What it does

- **After a meal bolus** (10 g or more, changeable): a sheet over CamAPS with up to four guesses
  from your own food history. Tap one to log it at the bolus time; Undo is there for a few seconds.
  Or type: foods you've had before come up as you type, or log your words as typed. "Nothing to
  log" for corrections, and X, "Not now", the dim area or Back to dismiss.
- **Open it any time**: it guesses from the time of day and shows what's logged today.
- **Remind me to bolus**: ordering now, eating in 10 minutes? Pick 5-30 min. If FX Watcher sees a
  bolus before then the reminder cancels itself; if not, it rings like an alarm.
- **Updates itself** from this repo's releases (quiet notification, or Check for update).

## Setup

1. Install or update FX Watcher 3.11.0+ **before** Meal Log (Android only grants FX Watcher's
   bolus permission to apps installed after it). Turn on FX Watcher's Settings > Connections >
   **Share boluses with other apps**.
2. Install Meal Log, allow **Display over other apps** (so it may open over CamAPS) and
   notifications.
3. Settings: your meal server's address and token, plus a Cloudflare Access service token if the
   server is behind Access.

## The meal server

Meal Log keeps no food data itself. It talks to a small HTTP server you run next to your food diary
(the author's is a Python service in front of a self-hosted SparkyFitness). Every request carries
`Authorization: Bearer <token>`; times are local, `YYYY-MM-DD HH:MM`.

| Call | Does |
|---|---|
| `GET /suggest?at=&carbs=` | `{meal_type, suggestions: [{id, label, carbs, times, items: [food]}], today: [{time, name, carbs}]}` |
| `GET /foods?q=&at=` | `{foods: [food]}`, past foods matching the typed words |
| `POST /log {at, items: [food]}` | logs those foods at `at`; `{logged: [text], entry_ids, problems}` |
| `POST /undo {entry_ids}` | deletes what `/log` just made |
| `POST /text {at, text}` | free text to estimate and log later; `{queued: true}` |
| `POST /skip {at}` | that bolus had no food |

`food` is `{food_id, name, quantity, unit, carbs}`.

## Building

JDK 17+, Android SDK 35: `gradle :app:assembleRelease`. A local `secrets.properties`
(`MEAL_URL`, `MEAL_TOKEN`, `CF_ID`, `CF_SECRET`) bakes default settings into a private build; it
is git-ignored and the public build has none. Releases: bump `versionCode`/`versionName` in
`app/build.gradle.kts`, add a `**New in ...**` line to `RELEASE_NOTES.md`, push a `v*` tag; CI
signs it and publishes `meal-log.apk` + `version.json`, which the app's updater reads.
