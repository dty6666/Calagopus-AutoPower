# CalagopusAutoPower

Velocity 4.1.x plugin for a Calagopus-managed Paper backend.

## Behavior

- Keeps Velocity/Geyser online.
- When a player tries to connect to the configured `paper` backend and the backend is unreachable, calls Calagopus `START`.
- Waits until Velocity can ping the backend, then allows the connection to continue.
- Tracks actual players connected to the configured backend using Velocity's player state.
- After 10 minutes with zero players, sends `save-all`, waits 5 seconds, then calls Calagopus `STOP`.
- Uses a single in-flight start request so simultaneous Java/Bedrock joins do not spam the Calagopus API.
- API key is read from an environment variable; it is not stored in the plugin config.

## Requirements

- Velocity 4.1.x / Java 25.
- Calagopus API key with `control.start`, `control.stop`, and `control.console` (your current key has `*`).
- Calagopus-managed Paper server registered in `velocity.toml` as `paper`.

## Build

```bash
gradle clean build
```

The JAR is created at:

```text
build/libs/CalagopusAutoPower-1.0.0.jar
```

## Install

Copy the JAR to:

```text
/opt/velocity/plugins/CalagopusAutoPower.jar
```

The plugin creates:

```text
/opt/velocity/plugins/calagopusautopower/config.properties
```

on first start.

## API key

Do NOT put the API key in `config.properties` and do not paste it into chat.

Set it in the environment of the Velocity process:

```text
CALAGOPUS_API_KEY=your-key-here
```

The plugin sends:

```text
Authorization: Bearer <key>
```

## Important

Do not enable a Calagopus schedule that independently starts the server, because it can conflict with this controller. `unless_stopped` is compatible with intentional API STOP operations.

The plugin uses the Velocity `ServerPreConnectEvent`, which is an awaited event: it waits for the handler to finish before Velocity starts the backend connection. This is what lets the plugin start Paper and wait for it before allowing the connection attempt to proceed.
