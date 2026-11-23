# What's new

Each release adds a section at the top. The app shows the one matching the installed version.

## 1.3 · Pollen and folded widget
- Our own hay fever estimate where there's no pollen forecast (all of NZ): a pollen season calendar times the weather. Warm, sunny, dry and breezy days raise it, rain clears it, and a sunny day after rain can bring a burst. Today shows Low, Moderate or High with the main reasons and the worst time of day. It's an estimate, not a measured count.
- New "No symptoms today" button next to Pollen. After 30 reported days (at least 5 of each) the estimate fits itself to you.
- Forecast check: how the pollen estimate did on the days you reported.
- The season calendar is a plain file in the app (pollen_calendar.csv), so it's easy to adjust.
- 4x2 widget on a folded phone: bigger day rows and the spare height shared out evenly, to match the unfolded look.

## 1.2 · Comfort and lead time
- The app learns from your Chilly and Too warm taps: once there are 3 of a kind from the last year, where you usually feel it replaces the jacket feels-like and too-hot settings, and walks flag "Warm for you". Settings shows what it has learned and can switch it off.
- Sources are now scored separately by how far ahead the forecast was made (up to 3 h, 3 to 6 h, 6 to 12 h, 12 to 24 h, 1 to 2 days), and the blend uses the weights for each hour's lead.
- Sources: Wind and Cloud next to Rain chance and Temperature in the side-by-side grid and in Past forecasts (hours, days and weeks), shaded by how far off they were.
- Forecast check: "When forecasts settle" shows how far ahead each source got rain, temperature, wind and cloud right and then stayed right.
- Forecast check: jacket calls scored 1, 3, 6 and 12 hours ahead.
- Today: big changes since the morning, like "Rain at 3 pm up from 20% to 60% since 6 am".
- Every forecast run is now kept for a week so these can be worked out. Older hours keep the latest forecast in each lead group.

## 1.1 · Harden
- Stored data stays a sensible size: downloads older than 30 days are compressed, failed downloads are removed, and older forecasts are thinned to one an hour (or every three hours, or kept in full, your choice on the Data screen). It runs weekly while charging and idle, or with "Tidy up now".
- Lighter on the battery: background refresh waits for a battery that isn't low, the rain map widget redraws every 30 minutes when the screen is off or battery saver is on, and rain-soon checks relax in battery saver.
- Every database upgrade is now tested from the very first version on each build.
- Dropbox: each backup is checked before upload, and "Test the newest backup restores" downloads and opens the latest one.
- Settings: "Report a problem" opens a GitHub issue with app, device and download details (no places, coordinates or accounts). The last crash is kept to include.
- NZ tides now use LINZ's official predictions for the nearest standard port, with a port picker in Settings.
- Lightning: live strike data isn't allowed for apps like this, so the thunder risk layer stays.

## 1.0 · Clouds, wind and roadmap
- Cloud layers on Today (low, mid and high), with what they mean: "Low cloud until 11 am keeps it near 16°", "Clear tonight: cold, frost possible".
- "Sunny" now looks at low and mid cloud only. Thin high cloud no longer turns a sunny day into "Overcast".
- Forecast check: wind speed, gusts, wind direction, cloud cover and low cloud, each by lead time with the range 8 in 10 hours landed in.
- Forecast check: a sunny-or-not score for daylight hours.
- Forecast check: daily high misses grouped by low cloud, clear and mixed days. On a low-cloud day Today's likely range for the high comes from past low-cloud days once there are 10 of them.
- Actual wind speed and direction are now downloaded, and the last 92 days of earlier forecasts are fetched again with wind and cloud.
- Sources: wind and cloud are scored for every source and blended on their own records, separately from rain and temperature.
- Settings: this What's new page, the Planned list, and a check for a newer build on GitHub.

## 0.1 · First builds
- Jacket yes/no call for the next hours, hot / cold / rain-soon triggers and walk windows.
- Rain radar map and the 4x4 rain map widget, the 4x2 detail widget, and lock screen weather.
- Every forecast stored as issued, Forecast check against what happened, and Raining/Dry reports.
- Several forecast sources side by side, scored here and blended, with rain and temperature weighted separately.
- History back to 1940, year-on-year months, the seasonal graph with the ECMWF outlook and El Niño / La Niña effects.
- Home heating and cooling insights, tides, UV and air quality, official warnings, wind direction.
- Chilly / Too warm / Pollen buttons, raw data export and Dropbox backup.
