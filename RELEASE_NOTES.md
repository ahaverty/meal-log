> Unofficial and not a medical device. Meal Log never doses insulin and never suggests a dose.

1. Download **meal-log.apk** below on your Android phone and open it to install. Install or update
   [FX Watcher](https://github.com/ahaverty/fx-watcher) (3.11.0 or later) **first**, so Android lets
   Meal Log hear its boluses.
2. In FX Watcher: Settings > Connections > **Share boluses with other apps**.
3. In Meal Log: allow **Display over other apps** and notifications, and fill in Settings with your
   meal server (see the README).

**New in v0.7.1:** the plan over CamAPS starts in the empty space under Total Units, clear of the slider, Glucose, Carbs and Next. With FX Watcher 3.12.1, it shrinks to the "Bolus 15 g" pill when the Carbohydrates box and keyboard open, and comes back when you confirm.

**New in v0.7.0:** a bolus that matches your plan (carbs within 5 g or 20%, within 45 min of the planned time) logs the planned meal by itself, with a notification to fix it if wrong. The plan over CamAPS starts mid-screen, is more compact, and shrinks to a small pill with "–". With FX Watcher 3.12's opt-in setting, CamAPS opens straight on the Bolus tab.

**New in v0.6.0:** the Plan page shows the plan as numbered CamAPS steps in CamAPS's own words (Bolus, Add meal, Boost, check glucose and what to do if low), and "Open CamAPS with this plan" floats the steps over CamAPS while you type them in.

**v0.5.0:** a tidier app with three pages. **Plan** (where it opens) has an "Eating now / in 10-45 min" choice and a full-size camera photo, and the plan now sets its own reminders: the bolus (it cancels itself when you bolus), a "did you bolus?" check when it says bolus now, and later steps such as Boost or the slow carbs, each with an X. **Log** opens by itself when a meal bolus isn't logged yet. **Reminders** lists what's coming up. Updates and the server moved into **Settings**, which also shows whether FX Watcher's boluses are getting through. Long-press the app icon for Plan, Photo, Log and "Bolus in 15 min", and `meallog://` links open any page.

**v0.4.0:** a **Plan** tab. Describe a meal, or photograph the plate or the menu, and get the CamAPS entry (bolus calculator and slowly absorbed carbs) and when to bolus, from your meal server's planner. Reply to correct it ("the bread was thin"). **Remind me to bolus in N min** sets the smart reminder, and after the bolus the sheet offers the planned meal first. Fixed: a one-tap log showed an error although it had logged.

**v0.3.0:** Meal Log updates itself from GitHub: it checks every few hours and posts a quiet notification, and the main screen has **Check for update** / **Install** and **Releases**.

**v0.2:** first version. After a meal bolus a sheet opens over CamAPS with your likely meals, one tap to log; type-ahead over foods you've had; and a bolus reminder that cancels itself when you bolus.
