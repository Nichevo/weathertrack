"""Packs simplified time zone boundaries into the app's compact asset (timezones.bin).

How the asset was made (see README.md in this folder):
  1. export.py writes every zone from the timezonefinder package (boundaries from
     timezone-boundary-builder, which is built from OpenStreetMap) as GeoJSON.
  2. mapshaper simplifies it while keeping shared borders together:
       mapshaper-xl 8gb tz-full.geojson -simplify interval=1500 keep-shapes \
         -filter-slivers min-area=2km2 -o tz-1500.geojson precision=0.0001
  3. This script packs that GeoJSON:
       python pack_timezones.py tz-1500.geojson ../../app/src/main/assets/timezones.bin

Format (big-endian, read by core TimeZoneShapes.read):
  "TZB1", int zoneCount, then per zone:
    UTF tzid, int labelLat*1e4, int labelLon*1e4, int polygonCount, then per polygon:
      int ringCount (first is the outline, the rest are holes), then per ring:
        int pointCount, then lon/lat in 1e-4 degrees: the first point as two ints,
        the rest as zigzag varint deltas from the previous point.
"""
import json
import struct
import sys

from shapely.geometry import shape
from shapely.ops import polylabel


def varint(n: int) -> bytes:
    z = (n << 1) ^ (n >> 63)
    out = bytearray()
    while True:
        b = z & 0x7F
        z >>= 7
        if z:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def utf(s: str) -> bytes:
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def q(v: float) -> int:
    return int(round(v * 10000))


def main(src: str, dst: str) -> None:
    feats = json.load(open(src))["features"]
    out = bytearray(b"TZB1")
    zones = [f for f in feats if f.get("geometry")]
    out += struct.pack(">i", len(zones))
    points = 0
    for f in sorted(zones, key=lambda f: f["properties"]["tzid"]):
        geom = f["geometry"]
        polys = geom["coordinates"] if geom["type"] == "MultiPolygon" else [geom["coordinates"]]
        biggest = max((shape({"type": "Polygon", "coordinates": p}) for p in polys), key=lambda p: p.area)
        try:
            label = polylabel(biggest.buffer(0) if not biggest.is_valid else biggest, tolerance=0.05)
        except Exception:
            label = biggest.representative_point()
        out += utf(f["properties"]["tzid"])
        out += struct.pack(">iii", q(label.y), q(label.x), len(polys))
        for p in polys:
            out += struct.pack(">i", len(p))
            for ring in p:
                out += struct.pack(">i", len(ring))
                px, py = q(ring[0][0]), q(ring[0][1])
                out += struct.pack(">ii", px, py)
                for lon, lat in ring[1:]:
                    x, y = q(lon), q(lat)
                    out += varint(x - px) + varint(y - py)
                    px, py = x, y
                points += len(ring)
    open(dst, "wb").write(out)
    print(f"{len(zones)} zones, {points} points, {len(out)} bytes")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
