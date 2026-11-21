# WeatherTrack

A personal Android weather app: a "do I need a jacket?" verdict, NZ rain radar, forecast-accuracy
tracking, and storage of raw weather data for analysis.

Not published on any app store. Built APKs are sideloaded.

- Build plan: [docs/PLAN.md](docs/PLAN.md)
- Signing, upgrades and getting an APK: [docs/SIGNING.md](docs/SIGNING.md)

## Layout

- `core/`: plain Kotlin. Data models, Open-Meteo URL building and parsing, jacket logic. Unit tested.
- `app/`: Android app (Compose, Material 3, Room, WorkManager).

## Building

CI builds every push (see `.github/workflows/build.yml`). Locally, with the Android SDK installed:

```sh
./gradlew :core:test :app:assembleDebug
```

Weather data: [Open-Meteo](https://open-meteo.com) (CC BY 4.0).
