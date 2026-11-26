"""Writes every time zone in the timezonefinder package as GeoJSON (step 1 of README.md)."""
import json, sys
from timezonefinder import TimezoneFinder
tf = TimezoneFinder()
feats = []
for name in tf.timezone_names:
    try:
        g = tf.get_geometry(name, coords_as_pairs=True)
    except Exception as e:
        print('skip', name, e, file=sys.stderr); continue
    if not g: continue
    feats.append({"type": "Feature", "properties": {"tzid": name}, "geometry": {"type": "MultiPolygon", "coordinates": g}})
json.dump({"type": "FeatureCollection", "features": feats}, open(sys.argv[1], 'w'))
print(len(feats), sum(len(r) for f in feats for p in f['geometry']['coordinates'] for r in p))
