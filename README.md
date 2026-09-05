# Calagopus AutoPower Android

Native Android controller for the existing CalagopusAutoPower Velocity plugin.

## Features

- Login with the existing `X-AutoPower-Username` / `X-AutoPower-Password` API authentication.
- Dashboard with Paper status, players, idle timer, uptime and resource usage.
- Start, Save and Stop controls.
- Minecraft console log polling and command input.
- Velocity journal log viewer.
- CalagopusAutoPower configuration viewer/editor for the main idle/startup settings.

The app uses the existing plugin API and does **not** modify the server plugin.
Minecraft and Velocity logs are polled periodically; no SSE/WebSocket changes are required.

## Build without Android Studio

This project includes a GitHub Actions workflow at `.github/workflows/build-apk.yml`.

1. Create an empty GitHub repository.
2. Upload this project to the repository.
3. Push to the `main` branch, or manually run the **Build APK** workflow.
4. Open the completed GitHub Actions run.
5. Download the artifact named `CalagopusAutoPower-debug-apk`.
6. Extract it and install `app-debug.apk` on your Android phone.

## Local build

Requires JDK 17, Android SDK API 37, Build Tools 36.0.0 and Gradle 9.6.

```bash
gradle assembleDebug
```

## Server URL

On the first screen enter the URL that currently opens your CalagopusAutoPower web panel, for example:

`https://autopower.example.com`

Do not put your API password into source code. Enter it in the app's login screen.

## API used

- `GET /api/status`
- `GET /api/resources`
- `POST /api/start`
- `POST /api/stop`
- `POST /api/save`
- `POST /api/command` with `command=...`
- `GET /api/logs/minecraft`
- `GET /api/logs/velocity`
- `GET /api/config`
- `POST /api/config`
