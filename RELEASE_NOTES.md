> Unofficial and not a medical device. Meal Log never doses insulin and never suggests a dose.

1. Download **meal-log.apk** below on your Android phone and open it to install. Install or update
   [FX Watcher](https://github.com/ahaverty/fx-watcher) (3.11.0 or later) **first**, so Android lets
   Meal Log hear its boluses.
2. In FX Watcher: Settings > Connections > **Share boluses with other apps**.
3. In Meal Log: allow **Display over other apps** and notifications, and fill in Settings with your
   meal server (see the README).

**New in v0.14.0:** **Drinks** for nights out. Tap a drink once to log it, then the big **Another** button repeats it. A row of glasses shows the count and your plan (4 by default, then soft drinks). Tap a glass further along and the ones you missed are logged too. When you reach your plan the button switches to a soft drink or 0.0 (the carbs are on every button: 0.0 beer and Coke count). Taps show at once and save in the background, retrying on bad signal. While a night is on (a drink in the last 5 h), the app opens on Drinks; a pint logged from the Log page, by voice or on the web joins the count. **Done for tonight** ends it. The **Plan** page now shows the last 24 hours of carbs, protein, fat and sat fat against your nutrition-system goals, and the planner sees them and tonight's drinks.

**v0.13.1:** a Slowly absorbed meal no longer asks on its own. It's part of the meal bolus, so entering the slow part before the bolus has finished (or after it) gives one question for the meal, not two, and the bolus's sheet shows the whole meal's carbs. A Meal or snack entry that matches an Add meal step in your plan is treated the same way. If a slow part never gets a bolus, a quiet reminder 45 min later still asks.

**v0.13.0:** the planner asks when it isn't sure. When the carb count is a rough guess (a wide range, like an unknown takeaway or an unclear photo), the plan says so at the top ("Rough guess: 40 to 75 g") with up to 3 quick questions you tap to answer, plus **Add a photo** or **Give the weight** when that would settle it. One question sends on the tap; with several, tap your answers and **Update the plan**. If you haven't answered a few minutes before eating, a quiet reminder nudges you. The plan still gives a count you can bolus on straight away. In the CamAPS steps, a Slowly absorbed meal now says **not Meal or snack**. The plan page is calmer too: one card holds what to type in CamAPS (bolus grams biggest, eating time and Later +10/+20 at its top, Open CamAPS inside it), questions sit right under it, and reminders and the breakdown fold away under **Details**, which still shows when the next reminder is.

**v0.12.0:** a smarter, tidier Plan page. Under the box are your usual meals for that time of day (up to 3, with carbs); tap one to fill the box, edit it if you like, then Plan it. They're kept on the phone and refreshed in the background every few hours, so they show instantly. The row of time buttons is now one Eating line: - and + to nudge it, or tap it for a clock. After planning, the plan shows when you're eating with **Later +10 / +20**, Pick a time, or Not eating after all: the plan's times and all its reminders move with it, and one that's already ringing goes quiet until the new time.

**v0.11.1:** the "What are you eating?" popup after a bolus is rebuilt so you can't hit the wrong button. The filled **Log** button is always bottom right and never moves; "Not now" is gone from the bottom (use the X or Back), and tapping the dim area no longer dismisses it. **Nothing to log** sits bottom left and gives you 4 s to Undo. While your usual meals load, their space is held so nothing jumps under your thumb, and a tapped meal shows a spinner. Typed words and picked foods are always logged together, never one dropped. The plan's breakdown no longer shows the planner's raw JSON.

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
