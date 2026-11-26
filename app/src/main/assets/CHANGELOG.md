# What's new

Each release adds a section at the top. The app shows the one matching the installed version.

## 1.8 · Time zones map
- New Time zones layer on the map, under World in the layer menu. Each zone is coloured by its offset from UTC, with its local time, city and offset written on it.
- Your place's zone is outlined in blue. Tap anywhere to see that zone's clock: the time there, its offset, how far ahead or behind you it is, and when daylight saving next starts or ends.
- The timeline steps through the next 24 hours, so you can see what time it will be elsewhere when it's, say, 7 pm here. Clocks tick over each minute while the map is open.
- Works offline: the zone borders are built into the app (from timezone-boundary-builder, © OpenStreetMap contributors), and the clocks use your phone's own time zone rules, so daylight saving changes stay right as the phone updates.
- The map zooms out further on this layer, to see most of the world at once.

## 1.7 · How hard it's raining
- The Raining / Dry buttons on Today are now four: Dry, Spitting, Rain and Heavy. Spitting means under 0.5 mm an hour, Rain 0.5 to 4, Heavy over 4.
- Tap either widget (the 4x2, or the map on the rain map widget) for a pop-up with the same four buttons, so you can report without opening the app. It also has a button through to the app.
- Forecast check: "Your reports" now says how often the forecast amount was in the same band as what you saw, how often it was too heavy or too light, and a table of what you reported against what the forecast said.
- Sources: each source's amount is checked against your level too ("amt" under Your reports). When you both call it wet, a source loses a little rain weight for each step its amount was off, so the blend favours sources that get the intensity right.
- The export's user_report table has a new rain_level column (dry, spitting, rain, heavy). Reports made before this release keep an empty level: dry ones still count as Dry, raining ones count for wet or dry only.

## 1.6 · Map layers
- The map's title is now a menu: tap "Rain map ▾" to switch to cloud, wind, temperature, UV, humidity or fog. Each item says where its data comes from and whether it's live or a forecast.
- Cloud splits into all, low, mid and high cloud. Wind shows speed or gusts with arrows. Temperature shows actual or feels like. Values are written on the map and the legend gives the figure at your spot.
- The weather layers step hour by hour through the next 24 hours, and Thunder risk can sit on top of any of them.
- Satellite: live Himawari infrared images from NASA, every 10 minutes for the last 2 hours, over the whole country.
- Pressure and storms: sea-level pressure lines every 4 hPa with highs and lows, wind colours, and any active tropical cyclone's track and forecast cone (from GDACS) for 3 days ahead. A card says how far away the nearest cyclone is.
- Weather fronts aren't drawn: they're only published as finished chart images under copyright, so the pressure lines and lows stand in for them.
- Fix: moving the map or switching layers no longer shows "Forecast: StandaloneCoroutine was cancelled". A download replaced by a newer one now stops quietly, here and on the other screens.

## 1.5 · History tabs and explorer
- History now has tabs across the top: Past weather, Forecast check, Home energy and Data. They replace the small icons, and tapping History in the bottom bar always brings you back to Past weather.
- Past weather opens with "this month so far": highs against normal, rain as a share of normal, and rain days.
- New explorer: pick High, Low, Rain, Sun or Gusts, a month, season or year, and compare with the last 2 years, the usual 1991–2020 range and decades. For highs, lows and rain the month-by-decade chart keeps its low-to-high bars with rain. Rain and sun build up through the period on the chart; highs, lows and gusts show day by day.
- The year-on-year table adds how far each year was from normal, a count of notable days (rain days, sunny days, frosty nights…) and its rank since records began ("4th driest").
- "Save this view" writes the table and chart lines to CSV; "Raw days" saves every stored day behind it.
- Forecast check: chips at the top jump straight to each section.
- Every saved place now builds its record in the background, not just the one you have open: Forecast check actuals and earlier runs, home climate and the archive history all sync for each place about twice a day.
- Places: a new "All sources" switch saves every forecast source hourly for that place too, so Sources can score them there. It's off by default because it costs about 13 downloads an hour per place.

## 1.4 · Clearer Sources page
- One Rain / Temp / Wind / Cloud switch at the top of Sources now drives every section.
- How far ahead is shown as clock times: each lead window says the hours it covers right now (e.g. "1–7 pm · 6–12 h ahead"), and a strip shows which window each upcoming hour's blend uses.
- New "Accuracy by how far ahead" heatmap: every source against every lead window, coloured poor to good, with thin evidence faded.
- "Detail by window": share of the blend as bars, the score, hours checked and your reports for the window you pick, plus every window's weights side by side.
- A chart of each source's accuracy from 1–2 days out to the last 3 hours; tap a source to highlight it.
- "When forecasts settle" is now on Sources too, as bars on a 2-days-to-now axis with a coloured table.
- All score tables, including Past forecasts, use one smooth red-to-green colour scale.

## 1.3 · Pollen and folded widget
- Our own hay fever estimate where there's no pollen forecast (all of NZ): a pollen season calendar times the weather. Warm, sunny, dry and breezy days raise it, rain clears it, and a sunny day after rain can bring a burst. Today shows Low, Moderate or High with the main reasons and the worst time of day. It's an estimate, not a measured count.
- New "No symptoms today" button next to Pollen. After 30 reported days (at least 5 of each) the estimate fits itself to you.
- Forecast check: how the pollen estimate did on the days you reported.
- Forecast check: "How the weather links up here" works out from your own actuals how cloud, low cloud, sunshine, wind and rain move the day's high and the overnight low, with the season taken out. Each link has a scatter chart.
- Forecast check: bar charts for jacket calls by how far ahead and for when each source's forecasts settle.
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
