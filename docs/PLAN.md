# WeatherTrack build plan

A personal Android app (sideloaded, not on any store). Kotlin, Jetpack Compose, Material 3.
Data: Open-Meteo (forecast, UV, archive back to 1940), RainViewer (radar), LINZ (tides),
Blitzortung (lightning).

**Principle:** every API response is stored raw, stamped with when it was fetched, and every
forecast is kept exactly as issued. Parsed tables can always be rebuilt from the raw store.

## Phase 1: foundation and the jacket call (this PR)

- Project scaffold: `:core` (pure Kotlin: models, Open-Meteo parsing, jacket logic, unit tests)
  and `:app` (Android).
- Local database (Room/SQLite):
  - `raw_pull`: every HTTP response body, URL, status, timing and trigger.
  - `forecast_hour` / `forecast_day`: each value as issued, keyed by pull, with lead time.
  - `place`: saved places plus an optional "My location".
- Hourly background refresh of every saved place (WorkManager), building the forecast archive.
- Today screen: jacket YES / MAYBE / NO for the next 3 or 6 hours from rain chance and amount,
  wind gusts and feels-like temperature, with the reasons; 15-minute rain-start nowcast;
  current conditions, 24-hour strip, 7 days with UV.
- Places: search any city worldwide, add, remove, use device location.
- Data tab: storage counts, CSV export per table, whole-SQLite export.
- CI: GitHub Actions builds a signed release APK on every push; pushes to `main` publish a
  GitHub Release. A fixed signing key means each new APK installs over the old one.

## Phase 2: widgets and alerts

- 4x2 widget: now, today in 2-hour steps (temp, rain, UV), next 3 days (Glance).
- Morning jacket notification at a chosen time.
- Short-term rain alert while out walking: radar/15-minute nowcast says rain within 30 minutes.
- Settings: jacket thresholds, refresh interval, units.
- In-app "update available" check against the latest GitHub Release.

## Phase 3: rain map

- NZ rain map: RainViewer radar loop (past) plus Open-Meteo forecast precipitation (future),
  timeline scrubber, saved places pinned.
- 4x4 widget: local-area rain map stepping Now to +4 hours.
- Lightning layer (Blitzortung) on the map.
- Raw radar frame metadata stored like every other pull.

## Phase 4: forecast check

- Pull actuals (Open-Meteo archive / best-match past hours) for every stored forecast hour.
- Screen: jacket-call hit rate, temperature error by lead time, rain-probability calibration,
  biggest misses, per place.
- Export joined forecast-vs-actual tables.

## Phase 5: history and extras

- Year-on-year: this month vs the last 5 years, decades back to 1940 (Open-Meteo archive).
- Own daily log built from stored pulls.
- Tides (LINZ) for coastal places.
- Optional Google Drive sync of exports.

## Home heating and cooling (done)

- Home tab: heating and cooling degree days (bases set in Settings, default 18° and 22°), sun energy,
  sunshine hours, humidity, dew point, muggy and damp-cold days.
- Month so far against the same days in the last 5 years and the 1991–2020 average; last 8 weeks
  and last 12 months against average; degree-day totals for every year since 1991.
- Optional power-usage CSV import (stays on the phone), fitted against degree days:
  base kWh per day, kWh per heating and per cooling degree day, and how much the weather explains.
- Stored in climate_day and energy_day; both export as CSV.
