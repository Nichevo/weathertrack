# Planned

What's coming, in order. Each release moves to What's new when it ships.

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
- Weather fronts on the map: they're only published as finished chart images under copyright. The Pressure and storms map draws pressure lines, highs and lows instead.
- Live lightning strikes: Blitzortung's terms don't allow use in apps like this, and other feeds are paid or Europe only. The thunder risk layer on the map stays.
