# Time zone borders

`app/src/main/assets/timezones.bin` holds simplified time zone borders for the map's Time zones
layer. They come from [timezone-boundary-builder](https://github.com/evansiroky/timezone-boundary-builder)
(data © OpenStreetMap contributors, ODbL 1.0), via the data bundled in the `timezonefinder` Python
package. Only the shapes and IANA names are stored: offsets and daylight saving come from the
phone's own time zone rules at run time.

To rebuild (for example after a time zone border changes):

```sh
python -m venv venv && venv/bin/pip install timezonefinder shapely
venv/bin/python export_timezones.py tz-full.geojson
npx mapshaper-xl 8gb tz-full.geojson -simplify interval=1500 keep-shapes \
  -filter-slivers min-area=2km2 -o tz-1500.geojson precision=0.0001
venv/bin/python pack_timezones.py tz-1500.geojson ../../app/src/main/assets/timezones.bin
```

`pack_timezones.py` describes the file format; `TimeZoneShapes.read` in core reads it.
