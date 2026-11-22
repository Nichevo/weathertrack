# Planned

What's coming, in order. Each release moves to What's new when it ships.

## Release 3 · Comfort model
- Learn from Chilly / Too warm / Pollen taps how the weather feels to you, and use it in the triggers and walk ratings.

## Release 4 · Our own radar nowcast
- Rain 30 to 60 minutes ahead, worked out from recent radar frames.

## Release 5 · Analysis kit
- Exports as Parquet for Python (pandas or polars), with CSV still available.
- A described data schema and a starter Python notebook.
- Scheduled exports and a weekly digest.

## Release 6 · Watch, home station and small widget
- Wear OS tile and complication for a Galaxy Watch (Watch4 or later): jacket call, rain-soon countdown and temperature, installed alongside the phone app.
- Home weather station: WeatherFlow Tempest (local UDP broadcast) and Ecowitt (local API), stored next to the forecasts and scored against them. A sheltered station's wind is kept for the record only, not used to score wind forecasts.
- A 2x1 widget.

## Not planned
- Live lightning strikes: Blitzortung's terms don't allow use in apps like this, and other feeds are paid or Europe only. The thunder risk layer on the map stays.
