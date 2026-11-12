> Unofficial and not a medical device. Meal Log never doses insulin and never suggests a dose.

1. Download **meal-log.apk** below on your Android phone and open it to install. Install or update
   [FX Watcher](https://github.com/ahaverty/fx-watcher) (3.11.0 or later) **first**, so Android lets
   Meal Log hear its boluses.
2. In FX Watcher: Settings > Connections > **Share boluses with other apps**.
3. In Meal Log: allow **Display over other apps** and notifications, and fill in Settings with your
   meal server (see the README).

**New in v0.11.1:** the "What are you eating?" popup after a bolus is rebuilt so you can't hit the wrong button. The filled **Log** button is always bottom right and never moves; "Not now" is gone from the bottom (use the X or Back), and tapping the dim area no longer dismisses it. **Nothing to log** sits bottom left and gives you 4 s to Undo. While your usual meals load, their space is held so nothing jumps under your thumb, and a tapped meal shows a spinner. Typed words and picked foods are always logged together, never one dropped. The plan's breakdown no longer shows the planner's raw JSON.

**v0.11.0:** calmer alerts. Only urgent reminders ring and vibrate: a bolus that hasn't been seen, or a glucose check that came true. They now also pop up over whatever app is open, marked Meal Log, with Done, Quiet 5 min and Skip. Everything else (log what you ate, plan steps, plain timers) is one silent notification with no vibration, and it no longer nags.

**v0.10.0:** a cleaner Plan page. Tap the mic to say what you're eating (words appear as you talk, tap again to stop), and the keyboard's Send key now sends. While it plans you see what it's doing (looking at the photo, checking your glucose, finding the menu...), a progress bar, a clock and Cancel. The long help text is gone.

**v0.9.0:** reminders you can trust. The next one is a big countdown timer on the Reminders page and a live countdown in the notification shade. Each reminder rings until you tap Done, again every 5 min (louder from the third ring); unanswered after 4 rings it is marked missed and says so. Anything left over 2 h past its time is closed as missed on its own, and Earlier shows what was done, skipped or missed. Checks like "check glucose if under 5 and falling" read your live glucose first and only ring if it's true. With FX Watcher 3.13: a hypo treatment entered in CamAPS is logged as a glucose shot without asking, a meal or snack entered without a bolus opens the log sheet (put it off and it keeps reminding you until it's logged), and a "start Ease-off" reminder closes itself when CamAPS shows it started. New timer box for plain timers like dinner.

**v0.8.2:** tidier Plan page. Softer hint text and a clear (X) button in the box, a big "Plan a new meal" button under a plan, and an old plan now clears itself an hour after you eat (it used to stay 3 h).

**New in v0.8.1:** each menu pick has Have this and Change it. Change it puts the order in the box so you only type the difference (a different side, no sauce). After any plan you can still reply with a change and it re-plans.

**New in v0.8.0:** menus. Photograph a menu or just type the restaurant ("Wagamama Dundrum") and the Plan tab finds the menu and gives your top 3 picks for a type 1 diabetic, with carbs and meal type. Tap one for the CamAPS plan. It remembers which picks you choose and any food you say you don't like.

**New in v0.7.3:** weak signal that times out while connecting ("failed to connect ... after 10000ms") is retried too, with a plain message if it still can't get through.

**New in v0.7.2:** a brief loss of signal (like "Unable to resolve host") is retried for a few seconds before showing an error, and the error now says in plain words that the phone has no internet.

**New in v0.7.1:** the plan over CamAPS starts in the empty space under Total Units, clear of the slider, Glucose, Carbs and Next. With FX Watcher 3.12.1, it shrinks to the "Bolus 15 g" pill when the Carbohydrates box and keyboard open, and comes back when you confirm.

**New in v0.7.0:** a bolus that matches your plan (carbs within 5 g or 20%, within 45 min of the planned time) logs the planned meal by itself, with a notification to fix it if wrong. The plan over CamAPS starts mid-screen, is more compact, and shrinks to a small pill with "–". With FX Watcher 3.12's opt-in setting, CamAPS opens straight on the Bolus tab.

**New in v0.6.0:** the Plan page shows the plan as numbered CamAPS steps in CamAPS's own words (Bolus, Add meal, Boost, check glucose and what to do if low), and "Open CamAPS with this plan" floats the steps over CamAPS while you type them in.

**v0.5.0:** a tidier app with three pages. **Plan** (where it opens) has an "Eating now / in 10-45 min" choice and a full-size camera photo, and the plan now sets its own reminders: the bolus (it cancels itself when you bolus), a "did you bolus?" check when it says bolus now, and later steps such as Boost or the slow carbs, each with an X. **Log** opens by itself when a meal bolus isn't logged yet. **Reminders** lists what's coming up. Updates and the server moved into **Settings**, which also shows whether FX Watcher's boluses are getting through. Long-press the app icon for Plan, Photo, Log and "Bolus in 15 min", and `meallog://` links open any page.

**v0.4.0:** a **Plan** tab. Describe a meal, or photograph the plate or the menu, and get the CamAPS entry (bolus calculator and slowly absorbed carbs) and when to bolus, from your meal server's planner. Reply to correct it ("the bread was thin"). **Remind me to bolus in N min** sets the smart reminder, and after the bolus the sheet offers the planned meal first. Fixed: a one-tap log showed an error although it had logged.

**v0.3.0:** Meal Log updates itself from GitHub: it checks every few hours and posts a quiet notification, and the main screen has **Check for update** / **Install** and **Releases**.

**v0.2:** first version. After a meal bolus a sheet opens over CamAPS with your likely meals, one tap to log; type-ahead over foods you've had; and a bolus reminder that cancels itself when you bolus.
