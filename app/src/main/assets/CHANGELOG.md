# What's new

Each release adds a section at the top. The app shows the one matching the installed version.

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
