package com.keluarganaga.calagopusautopower;

import com.google.inject.Inject;
import com.velocitypowered.api.event.AwaitingEventExecutor;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Plugin(
        id = "calagopusautopower",
        name = "CalagopusAutoPower",
        version = "1.4.0",
        description = "Starts a Calagopus-managed backend on join and stops it after an idle period.",
        authors = {"Keluarganaga"}
)
public final class CalagopusAutoPower {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final HttpClient httpClient;

    private final ExecutorService statusExecutor =
            Executors.newSingleThreadExecutor();

    private final ExecutorService webExecutor =
            Executors.newCachedThreadPool();

    private volatile Config config;
    private volatile CalagopusClient api;
    private volatile RegisteredServer backend;

    private ScheduledTask monitorTask;
    private HttpServer webServer;

    private final AtomicBoolean starting = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);

    private volatile long zeroPlayersSince = 0L;
    private volatile boolean lastChunkyRunning = false;
    private volatile int lastPlayerCount = -1;
    private volatile long minecraftStartedAt = 0L;

    // Minecraft/Paper process uptime tracking.
    private volatile int lastMaxPlayers = -1;
    private volatile long lastLoggedIdleMinute = 0L;

    @Inject
    public CalagopusAutoPower(
            ProxyServer proxy,
            Logger logger,
            @com.velocitypowered.api.plugin.annotation.DataDirectory Path dataDirectory) {

        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {

        try {

            this.config = Config.load(dataDirectory, logger);

            resolveBackend();

            createCalagopusClient();

            minecraftConsole.connect();

            AwaitingEventExecutor<ServerPreConnectEvent> preConnectHandler =
                    new AwaitingEventExecutor<>() {

                        @Override
                        public EventTask executeAsync(
                                ServerPreConnectEvent event) {

                            return handleServerPreConnect(event);
                        }
                    };

            proxy.getEventManager().register(
                    this,
                    ServerPreConnectEvent.class,
                    preConnectHandler
            );

            logger.info(
                    "CalagopusAutoPower 1.4.0 enabled for backend '{}' "
                            + "(Calagopus server {})",
                    config.backendServer(),
                    config.serverUuid()
            );

            logger.info(
                    "Idle shutdown: {} minutes; monitor interval: {} seconds",
                    config.idleMinutes(),
                    config.checkIntervalSeconds()
            );

            startMonitor();

            startWebPanel();

        } catch (Exception e) {

            logger.error(
                    "CalagopusAutoPower failed to initialize: {}",
                    e.getMessage(),
                    e
            );
        }
    }

    private void resolveBackend() {

        Config cfg = config;

        this.backend = proxy.getServer(cfg.backendServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Velocity server '" + cfg.backendServer()
                                + "' was not found"
                ));
    }

    private void createCalagopusClient() {

        Config cfg = config;

        String apiKey = System.getenv(config.apiKeyEnv());

        if (apiKey == null || apiKey.isBlank()) {

            throw new IllegalStateException(
                    "Environment variable " + cfg.apiKeyEnv()
                            + " is missing or empty"
            );
        }

        this.api = new CalagopusClient(
                httpClient,
                cfg.calagopusUrl(),
                config.serverUuid(),
                apiKey
        );
    }

    private void startMonitor() {

        if (monitorTask != null) {
            monitorTask.cancel();
        }

        Config cfg = config;

        monitorTask = proxy.getScheduler()
                .buildTask(this, this::monitor)
                .repeat(
                        Duration.ofSeconds(
                                cfg.checkIntervalSeconds()
                        )
                )
                .schedule();
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {

        if (monitorTask != null) {

            monitorTask.cancel();
            monitorTask = null;
        }

        if (webServer != null) {

            webServer.stop(0);
            webServer = null;
        }

        webExecutor.shutdownNow();
        statusExecutor.shutdownNow();

        logger.info(
                "CalagopusAutoPower shut down cleanly."
        );
    }

    private EventTask handleServerPreConnect(
            ServerPreConnectEvent event) {

        if (backend == null || api == null) {
            return null;
        }

        if (stopping.get()) {
            return null;
        }

        RegisteredServer requested =
                event.getOriginalServer();

        if (!requested.getServerInfo()
                .getName()
                .equalsIgnoreCase(config.backendServer())) {

            return null;
        }

        CompletableFuture<Boolean> reachable =
                backend.ping()
                        .handle(
                                (result, error) ->
                                        error == null
                        );

        return EventTask.withContinuation(
                continuation -> {

                    reachable.whenComplete(
                            (isReachable, pingError) -> {

                                if (Boolean.TRUE.equals(isReachable)
                                        && pingError == null) {

                                    continuation.resume();
                                    return;
                                }

                                Player player =
                                        event.getPlayer();

                                player.sendMessage(
                                        Component.text(
                                                config.startupMessage()
                                        )
                                );

                                logger.info(
                                        "{} is trying to connect while "
                                                + "Paper is offline. "
                                                + "Requesting START...",
                                        player.getUsername()
                                );

                                ensureStartedAndReady()
                                        .whenComplete(
                                                (ignored, startError) -> {

                                                    if (startError != null) {

                                                        Throwable cause =
                                                                unwrap(
                                                                        startError
                                                                );

                                                        logger.error(
                                                                "Paper could "
                                                                        + "not be "
                                                                        + "started "
                                                                        + "for {}: {}",
                                                                player.getUsername(),
                                                                cause.getMessage(),
                                                                cause
                                                        );

                                                        event.setResult(
                                                                ServerPreConnectEvent
                                                                        .ServerResult
                                                                        .denied()
                                                        );

                                                        player.sendMessage(
                                                                Component.text(
                                                                        "Paper could "
                                                                                + "not be "
                                                                                + "started. "
                                                                                + "Please try "
                                                                                + "again in "
                                                                                + "a moment."
                                                                )
                                                        );

                                                        continuation.resume();
                                                        return;
                                                    }

                                                    logger.info(
                                                            "Paper is ready; "
                                                                    + "allowing {} "
                                                                    + "to connect.",
                                                            player.getUsername()
                                                    );

                                                    continuation.resume();
                                                }
                                        );
                            }
                    );
                }
        );
    }

    private void monitor() {

        if (backend == null || api == null) {
            return;
        }

        if (stopping.get() || starting.get()) {
            return;
        }

        queryPaperPlayerCount()
                .whenComplete(
                        (playerCount, error) -> {

                            if (error != null) {

                                zeroPlayersSince = 0L;
                                lastLoggedIdleMinute = 0L;
                                lastPlayerCount = -1;
                                minecraftStartedAt = 0L;
                                lastMaxPlayers = -1;
                                lastChunkyRunning = false;

                                logger.debug(
                                        "Paper status unavailable: {}",
                                        unwrap(error).getMessage()
                                );

                                return;
                            }

                            if (lastPlayerCount < 0) {
                                minecraftStartedAt =
                                        System.currentTimeMillis();
                            }

                            if (playerCount != lastPlayerCount) {

                                if (playerCount > 0) {

                                    logger.info(
                                            "Paper has {} player(s). "
                                                    + "Idle shutdown timer reset.",
                                            playerCount
                                    );

                                } else {

                                    logger.info(
                                            "Paper has 0 players."
                                    );
                                }

                                lastPlayerCount = playerCount;
                            }

                            if (playerCount > 0) {

                                if (zeroPlayersSince != 0L) {

                                    logger.info(
                                            "Idle timer cancelled because "
                                                    + "a player joined."
                                    );
                                }

                                if (lastChunkyRunning) {

                                    logger.info(
                                            "Chunky generation state cleared "
                                                    + "because a player is online."
                                    );
                                }

                                zeroPlayersSince = 0L;
                                lastLoggedIdleMinute = 0L;
                                lastChunkyRunning = false;

                                return;
                            }

                            /*
                             * No players are online.
                             *
                             * Check Chunky before starting or continuing
                             * the idle shutdown timer.
                             */
                            queryChunkyRunning()
                                    .whenComplete(
                                            (chunkyRunning, chunkyError) -> {

                                                if (chunkyError != null) {

                                                    /*
                                                     * Fail safe:
                                                     * if the Chunky bridge cannot
                                                     * be reached, do NOT shut down
                                                     * the server because we cannot
                                                     * safely determine whether
                                                     * Chunky is generating.
                                                     */
                                                    zeroPlayersSince = 0L;
                                                    lastLoggedIdleMinute = 0L;

                                                    logger.debug(
                                                            "Chunky status unavailable: {}. "
                                                                    + "Idle shutdown paused.",
                                                            unwrap(chunkyError).getMessage()
                                                    );

                                                    return;
                                                }

                                                if (chunkyRunning) {

                                                    if (!lastChunkyRunning) {

                                                        logger.info(
                                                                "Chunky is actively generating. "
                                                                        + "Idle shutdown paused."
                                                        );
                                                    }

                                                    lastChunkyRunning = true;

                                                    if (zeroPlayersSince != 0L) {

                                                        logger.info(
                                                                "Idle timer paused because "
                                                                        + "Chunky is running."
                                                        );
                                                    }

                                                    zeroPlayersSince = 0L;
                                                    lastLoggedIdleMinute = 0L;

                                                    return;
                                                }

                                                if (lastChunkyRunning) {

                                                    logger.info(
                                                            "Chunky generation finished/stopped. "
                                                                    + "Idle shutdown timer started."
                                                    );
                                                }

                                                lastChunkyRunning = false;

                                                if (zeroPlayersSince == 0L) {

                                                    zeroPlayersSince =
                                                            System.currentTimeMillis();

                                                    lastLoggedIdleMinute = 0L;

                                                    logger.info(
                                                            "Idle timer started."
                                                    );

                                                    return;
                                                }

                                                long idleMillis =
                                                        System.currentTimeMillis()
                                                                - zeroPlayersSince;

                                                long idleMinutesElapsed =
                                                        Duration.ofMillis(idleMillis)
                                                                .toMinutes();

                                                if (idleMinutesElapsed > 0
                                                        && idleMinutesElapsed
                                                        != lastLoggedIdleMinute
                                                        && idleMinutesElapsed
                                                        < config.idleMinutes()) {

                                                    lastLoggedIdleMinute =
                                                            idleMinutesElapsed;

                                                    logger.info(
                                                            "Idle for {}/{} minutes.",
                                                            idleMinutesElapsed,
                                                            config.idleMinutes()
                                                    );
                                                }

                                                long requiredMillis =
                                                        Duration.ofMinutes(
                                                                config.idleMinutes()
                                                        ).toMillis();

                                                if (idleMillis >= requiredMillis) {

                                                    if (!stopping.compareAndSet(
                                                            false,
                                                            true
                                                    )) {
                                                        return;
                                                    }

                                                    zeroPlayersSince = 0L;
                                                    lastLoggedIdleMinute = 0L;

                                                    logger.info(
                                                            "Idle timeout reached. "
                                                                    + "Sending save-all before shutdown..."
                                                    );

                                                    saveThenStop();
                                                }
                                            }
                                    );
                        }
                );
    }

    private CompletionStage<Integer> queryPaperPlayerCount() {

        return CompletableFuture.supplyAsync(
                () -> {

                    try (
                            Socket socket = new Socket()
                    ) {

                        Config cfg = config;

                        socket.connect(
                                new InetSocketAddress(
                                        cfg.statusHost(),
                                        cfg.statusPort()
                                ),
                                cfg.statusTimeoutMillis()
                        );

                        socket.setSoTimeout(
                                cfg.statusTimeoutMillis()
                        );

                        var output =
                                socket.getOutputStream();

                        var input =
                                socket.getInputStream();

                        output.write(0xFE);
                        output.flush();

                        int first = input.read();

                        if (first != 0xFF) {

                            throw new IOException(
                                    "Unexpected legacy ping response"
                            );
                        }

                        int hi = input.read();
                        int lo = input.read();

                        if (hi < 0 || lo < 0) {

                            throw new IOException(
                                    "Incomplete legacy ping response"
                            );
                        }

                        int length =
                                (hi << 8) | lo;

                        if (length <= 0) {

                            throw new IOException(
                                    "Invalid legacy ping response length"
                            );
                        }

                        byte[] data =
                                input.readNBytes(
                                        length * 2
                                );

                        if (data.length < length * 2) {

                            throw new IOException(
                                    "Incomplete legacy ping payload"
                            );
                        }

                        String response =
                                new String(
                                        data,
                                        StandardCharsets.UTF_16BE
                                );

                        String[] parts =
                                response.split(
                                        "§",
                                        -1
                                );

                        if (parts.length < 3) {

                            throw new IOException(
                                    "Invalid legacy ping payload: "
                                            + response
                            );
                        }

                        int online;
                        int maxPlayers;

                        try {

                            online =
                                    Integer.parseInt(parts[1]);

                            maxPlayers =
                                    Integer.parseInt(parts[2]);

                        } catch (NumberFormatException e) {

                            throw new IOException(
                                    "Invalid player count in legacy "
                                            + "ping payload: "
                                            + response,
                                    e
                            );
                        }

                        lastMaxPlayers = maxPlayers;

                        return online;

                    } catch (Exception e) {

                        throw new CompletionException(e);
                    }

                },
                statusExecutor
        );
    }

    private CompletionStage<Boolean> queryChunkyRunning() {

        Config cfg = config;

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        "http://"
                                                + cfg.chunkyStatusHost()
                                                + ":"
                                                + cfg.chunkyStatusPort()
                                                + "/status"
                                )
                        )
                        .timeout(
                                Duration.ofMillis(
                                        cfg.chunkyStatusTimeoutMillis()
                                )
                        )
                        .header(
                                "Accept",
                                "application/json"
                        )
                        .GET()
                        .build();

        return httpClient.sendAsync(
                        request,
                        HttpResponse.BodyHandlers
                                .ofString(
                                        StandardCharsets.UTF_8
                                )
                )
                .thenCompose(
                        response -> {

                            if (response.statusCode() != 200) {

                                return CompletableFuture.failedFuture(
                                        new IOException(
                                                "Chunky bridge HTTP "
                                                        + response.statusCode()
                                        )
                                );
                            }

                            String body =
                                    response.body().trim();

                            if (body.contains(
                                    "\"running\":true"
                            )) {

                                return CompletableFuture
                                        .completedFuture(true);
                            }

                            if (body.contains(
                                    "\"running\":false"
                            )) {

                                return CompletableFuture
                                        .completedFuture(false);
                            }

                            return CompletableFuture.failedFuture(
                                    new IOException(
                                            "Invalid Chunky bridge "
                                                    + "response: "
                                                    + body
                                    )
                            );
                        }
                );
    }

    private CompletionStage<Void> ensureStartedAndReady() {

        if (!starting.compareAndSet(false, true)) {
            return waitForBackendReady();
        }

        logger.info(
                "Paper backend is unreachable. "
                        + "Requesting Calagopus START..."
        );

        return api.power("start")
                .thenCompose(
                        ignored ->
                                waitForBackendReady()
                )
                .whenComplete(
                        (ignored, error) -> {

                            starting.set(false);

                            if (error != null) {

                                logger.error(
                                        "Paper failed to become reachable: {}",
                                        unwrap(error).getMessage()
                                );

                            } else {

                                logger.info(
                                        "Paper backend is reachable again."
                                );

                                zeroPlayersSince = 0L;
                                lastLoggedIdleMinute = 0L;
                                lastPlayerCount = -1;
                                minecraftStartedAt = 0L;
                                lastMaxPlayers = -1;
                            }
                        }
                );
    }

    private CompletionStage<Void> waitForBackendReady() {

        CompletableFuture<Void> result =
                new CompletableFuture<>();

        long deadline =
                System.nanoTime()
                        + Duration.ofSeconds(
                                config.startupTimeoutSeconds()
                        ).toNanos();

        pollBackend(result, deadline);

        return result;
    }

    private void pollBackend(
            CompletableFuture<Void> result,
            long deadline) {

        if (result.isDone()) {
            return;
        }

        backend.ping()
                .whenComplete(
                        (ping, error) -> {

                            if (error == null) {

                                result.complete(null);
                                return;
                            }

                            if (System.nanoTime() >= deadline) {

                                result.completeExceptionally(
                                        new IOException(
                                                "Timed out waiting for "
                                                        + "Paper backend after "
                                                        + config.startupTimeoutSeconds()
                                                        + " seconds"
                                        )
                                );

                                return;
                            }

                            proxy.getScheduler()
                                    .buildTask(
                                            this,
                                            () ->
                                                    pollBackend(
                                                            result,
                                                            deadline
                                                    )
                                    )
                                    .delay(
                                            Duration.ofSeconds(
                                                    config.startupPollSeconds()
                                            )
                                    )
                                    .schedule();
                        }
                );
    }

    private void saveThenStop() {

        CompletionStage<Void> chain =
                CompletableFuture.completedFuture(null);

        if (config.saveBeforeStop()) {
            chain = api.command("save-all");
        }

        chain
                .thenCompose(
                        ignored ->
                                delay(config.saveWaitSeconds())
                )
                .thenCompose(
                        ignored ->
                                api.power("stop")
                )
                .whenComplete(
                        (ignored, error) -> {

                            stopping.set(false);

                            if (error != null) {

                                logger.error(
                                        "Calagopus STOP failed: {}",
                                        unwrap(error).getMessage(),
                                        unwrap(error)
                                );

                            } else {

                                logger.info(
                                        "Calagopus STOP requested after "
                                                + "{} minutes of inactivity.",
                                        config.idleMinutes()
                                );
                            }
                        }
                );
    }

    private CompletionStage<Void> delay(long seconds) {

        CompletableFuture<Void> future =
                new CompletableFuture<>();

        if (seconds <= 0) {
            future.complete(null);
            return future;
        }

        proxy.getScheduler()
                .buildTask(
                        this,
                        () -> future.complete(null)
                )
                .delay(Duration.ofSeconds(seconds))
                .schedule();

        return future;
    }

    private CompletionStage<Void> manualStop() {

        if (starting.get()) {

            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "Paper is currently starting."
                    )
            );
        }

        if (!stopping.compareAndSet(false, true)) {

            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "Paper is already stopping."
                    )
            );
        }

        zeroPlayersSince = 0L;
        lastLoggedIdleMinute = 0L;

        logger.info(
                "Manual STOP requested from web panel."
        );

        saveThenStop();

        return CompletableFuture.completedFuture(null);
    }

    private CompletionStage<Void> manualSave() {

        if (api == null) {

            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "Calagopus API is not available."
                    )
            );
        }

        logger.info(
                "Manual save-all requested from web panel."
        );

        return api.command("save-all");
    }

    /*
     * ================================================================
     * WEB PANEL
     * ================================================================
     */

    private void startWebPanel() {

        Config cfg = config;

        if (!cfg.webEnabled()) {

            logger.info(
                    "Web control panel is disabled."
            );

            return;
        }

        String username =
                System.getenv(
                        "CALAGOPUS_AUTOPOWER_WEB_USERNAME"
                );

        String password =
                System.getenv(
                        "CALAGOPUS_AUTOPOWER_WEB_PASSWORD"
                );

        if (username == null || username.isBlank()
                || password == null || password.isBlank()) {

            logger.warn(
                    "Web panel is enabled but "
                            + "CALAGOPUS_AUTOPOWER_WEB_USERNAME or "
                            + "CALAGOPUS_AUTOPOWER_WEB_PASSWORD is missing. "
                            + "Web panel will NOT start."
            );

            return;
        }

        try {

            InetSocketAddress address =
                    new InetSocketAddress(
                            cfg.webBind(),
                            cfg.webPort()
                    );

            webServer =
                    HttpServer.create(
                            address,
                            0
                    );

            webServer.createContext(
                    "/",
                    this::handleWebRequest
            );

            webServer.setExecutor(
                    webExecutor
            );

            webServer.start();

            logger.info(
                    "Web control panel listening on http://{}:{}",
                    cfg.webBind(),
                    cfg.webPort()
            );

        } catch (Exception e) {

            logger.error(
                    "Could not start web control panel: {}",
                    e.getMessage(),
                    e
            );
        }
    }

    private void handleWebRequest(
            HttpExchange exchange) {

        try {

            String path =
                    exchange.getRequestURI()
                            .getPath();

            if ("/".equals(path)
                    || "/index.html".equals(path)) {

                sendHtml(
                        exchange,
                        DASHBOARD_HTML
                );

                return;
            }

            if (!path.startsWith("/api/")) {

                sendText(
                        exchange,
                        404,
                        "Not Found"
                );

                return;
            }

            if (!authorized(exchange)) {

                exchange.getResponseHeaders()
                        .set(
                                "WWW-Authenticate",
                                "Bearer"
                        );

                sendText(
                        exchange,
                        401,
                        "Unauthorized"
                );

                return;
            }

            switch (path) {

                case "/api/status" ->
                        handleStatus(exchange);

                case "/api/config" -> {

                    if ("GET".equalsIgnoreCase(
                            exchange.getRequestMethod()
                    )) {

                        handleConfigGet(exchange);

                    } else if (
                            "POST".equalsIgnoreCase(
                                    exchange.getRequestMethod()
                            )
                    ) {

                        handleConfigSave(exchange);

                    } else {

                        sendText(
                                exchange,
                                405,
                                "Method Not Allowed"
                        );
                    }
                }

                case "/api/config/reload" ->
                        handleConfigReload(exchange);

                case "/api/start" ->
                        handleStart(exchange);

                case "/api/stop" ->
                        handleStop(exchange);

                case "/api/save" ->
                        handleSave(exchange);

                case "/api/command" ->
                        handleCommand(exchange);

                case "/api/logs/minecraft" ->
		        handleMinecraftLogs(exchange);

		case "/api/logs/velocity" ->
		        handleVelocityLogs(exchange);

                case "/api/resources" ->
                        handleResources(exchange);

                default ->
                        sendText(
                                exchange,
                                404,
                                "Not Found"
                        );
            }

        } catch (IOException e) {

            String message = e.getMessage();

            if (message != null
                    && (message.equalsIgnoreCase("stream closed")
                        || message.equalsIgnoreCase("broken pipe")
                        || message.equalsIgnoreCase("connection reset by peer"))) {

                logger.debug(
                        "Web panel client disconnected: {}",
                        message
                );

            } else {

                logger.warn(
                        "Web panel I/O error: {}",
                        message
                );
            }

        } catch (Exception e) {

            logger.error(
                    "Web panel request error: {}",
                    e.getMessage(),
                    e
            );

            try {

                sendText(
                        exchange,
                        500,
                        "Internal Server Error"
                );

            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Returns recent Velocity journal logs.
     *
     * AutoPower mode filters the Velocity journal to messages generated
     * by this plugin. Velocity mode returns the complete recent journal.
     */
    private void handleMinecraftLogs(
            HttpExchange exchange) throws IOException {

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }

        String output = minecraftConsole.getLogs();

        if (output.isBlank()) {
            output = "Minecraft console is connecting...\n"
                    + "No console output received yet.";
        }

        sendText(
                exchange,
                200,
                output
        );
    }

    private void handleVelocityLogs(
            HttpExchange exchange) throws IOException {

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }

        try {
            Process process = new ProcessBuilder(
                    "journalctl",
                    "-u",
                    "velocity.service",
                    "--no-pager",
                    "-n",
                    "300",
                    "-o",
                    "cat"
            )
                    .redirectErrorStream(true)
                    .start();

            String output;

            try (java.io.InputStream input = process.getInputStream()) {
                output = new String(
                        input.readAllBytes(),
                        StandardCharsets.UTF_8
                );
            }

            process.waitFor(
                    5,
                    java.util.concurrent.TimeUnit.SECONDS
            );

            if (output.isBlank()) {
                output = "No Velocity log entries found.";
            }

            sendText(
                    exchange,
                    200,
                    output
            );

        } catch (Exception e) {

            logger.warn(
                    "Unable to read Velocity logs",
                    e
            );

            sendText(
                    exchange,
                    500,
                    "Unable to read Velocity logs: "
                            + e.getMessage()
            );
        }
    }

    private void handleResources(HttpExchange exchange) throws IOException {

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }

        if (api == null) {
            sendText(exchange, 503, "Calagopus API is not connected.");
            return;
        }

        api.resources().thenAccept(body -> {
            try {
                sendJson(exchange, 200, body);
            } catch (IOException e) {
                logger.warn("Unable to send resource data", e);
            }
        }).exceptionally(error -> {
            try {
                sendText(exchange, 502, "Unable to read Calagopus resources.");
            } catch (IOException e) {
                logger.warn("Unable to send resource error", e);
            }
            return null;
        });
    }


    private boolean authorized(HttpExchange exchange) {
        try {
            String expectedUsername = System.getenv(
                    "CALAGOPUS_AUTOPOWER_WEB_USERNAME"
            );

            String expectedPassword = System.getenv(
                    "CALAGOPUS_AUTOPOWER_WEB_PASSWORD"
            );

            if (expectedUsername == null || expectedUsername.isBlank()
                    || expectedPassword == null || expectedPassword.isBlank()) {
                logger.error(
                        "Web panel credentials are not configured. " +
                        "Set CALAGOPUS_AUTOPOWER_WEB_USERNAME and " +
                        "CALAGOPUS_AUTOPOWER_WEB_PASSWORD."
                );
                return false;
            }

            String username = exchange.getRequestHeaders()
                    .getFirst("X-AutoPower-Username");

            String password = exchange.getRequestHeaders()
                    .getFirst("X-AutoPower-Password");

            return username != null
                    && password != null
                    && java.security.MessageDigest.isEqual(
                            username.getBytes(StandardCharsets.UTF_8),
                            expectedUsername.getBytes(StandardCharsets.UTF_8)
                    )
                    && java.security.MessageDigest.isEqual(
                            password.getBytes(StandardCharsets.UTF_8),
                            expectedPassword.getBytes(StandardCharsets.UTF_8)
                    );

        } catch (Exception e) {
            logger.warn("Web authentication check failed: {}", e.getMessage());
            return false;
        }
    }

    private static boolean constantTimeEquals(
            String a,
            String b) {

        if (a == null || b == null) {
            return false;
        }

        byte[] x =
                a.getBytes(StandardCharsets.UTF_8);

        byte[] y =
                b.getBytes(StandardCharsets.UTF_8);

        if (x.length != y.length) {
            return false;
        }

        int result = 0;

        for (int i = 0; i < x.length; i++) {
            result |= x[i] ^ y[i];
        }

        return result == 0;
    }

    private void handleStatus(
            HttpExchange exchange)
            throws IOException {

        String state;

        if (starting.get()) {
            state = "STARTING";
        } else if (stopping.get()) {
            state = "STOPPING";
        } else if (lastPlayerCount >= 0) {
            state = "ONLINE";
        } else {
            state = "OFFLINE";
        }

        long idleSeconds = 0;
        long remainingSeconds = 0;

        if (zeroPlayersSince != 0L) {

            idleSeconds =
                    Math.max(
                            0,
                            (System.currentTimeMillis()
                                    - zeroPlayersSince)
                                    / 1000
                    );

            long limit =
                    Duration.ofMinutes(
                            config.idleMinutes()
                    ).toSeconds();

            remainingSeconds =
                    Math.max(
                            0,
                            limit - idleSeconds
                    );
        }

        long serverUptimeSeconds = 0;

        if (minecraftStartedAt > 0L && lastPlayerCount >= 0) {
            serverUptimeSeconds =
                    Math.max(
                            0,
                            (System.currentTimeMillis()
                                    - minecraftStartedAt)
                                    / 1000
                    );
        }

        String json =
                "{"
                        + "\"state\":\""
                        + jsonEscape(state)
                        + "\","
                        + "\"players\":"
                        + lastPlayerCount
                        + ","
                        + "\"maxPlayers\":"
                        + lastMaxPlayers
                        + ","
                        + "\"idleSeconds\":"
                        + idleSeconds
                        + ","
                        + "\"remainingSeconds\":"
                        + remainingSeconds
                        + ","
                        + "\"idleLimitMinutes\":"
                        + config.idleMinutes()
                        + ","
                        + "\"serverUptimeSeconds\":"
                        + serverUptimeSeconds
                        + ","
                        + "\"starting\":"
                        + starting.get()
                        + ","
                        + "\"stopping\":"
                        + stopping.get()
                        + "}"
                ;

        sendJson(
                exchange,
                200,
                json
        );
    }

    private void handleConfigGet(
            HttpExchange exchange)
            throws IOException {

        Config cfg = config;

        String json =
                "{"
                        + "\"calagopusUrl\":\""
                        + jsonEscape(cfg.calagopusUrl())
                        + "\","
                        + "\"serverUuid\":\""
                        + jsonEscape(config.serverUuid())
                        + "\","
                        + "\"apiKeyEnv\":\""
                        + jsonEscape(cfg.apiKeyEnv())
                        + "\","
                        + "\"backendServer\":\""
                        + jsonEscape(cfg.backendServer())
                        + "\","
                        + "\"statusHost\":\""
                        + jsonEscape(cfg.statusHost())
                        + "\","
                        + "\"statusPort\":"
                        + cfg.statusPort()
                        + ","
                        + "\"statusTimeoutMillis\":"
                        + cfg.statusTimeoutMillis()
                        + ","
                        + "\"checkIntervalSeconds\":"
                        + cfg.checkIntervalSeconds()
                        + ","
                        + "\"startupTimeoutSeconds\":"
                        + cfg.startupTimeoutSeconds()
                        + ","
                        + "\"startupPollSeconds\":"
                        + cfg.startupPollSeconds()
                        + ","
                        + "\"idleMinutes\":"
                        + cfg.idleMinutes()
                        + ","
                        + "\"saveWaitSeconds\":"
                        + cfg.saveWaitSeconds()
                        + ","
                        + "\"saveBeforeStop\":"
                        + cfg.saveBeforeStop()
                        + ","
                        + "\"startupMessage\":\""
                        + jsonEscape(cfg.startupMessage())
                        + "\""
                        + "}"
                ;

        sendJson(
                exchange,
                200,
                json
        );
    }

    private void handleConfigSave(
            HttpExchange exchange)
            throws IOException {

        String body =
                new String(
                        exchange.getRequestBody()
                                .readAllBytes(),
                        StandardCharsets.UTF_8
                );

        Map<String, String> form =
                parseForm(body);

        try {

            Properties p =
                    new Properties();

            p.setProperty(
                    "calagopus.url",
                    requireForm(form, "calagopusUrl")
            );

            p.setProperty(
                    "calagopus.server-uuid",
                    requireForm(form, "serverUuid")
            );

            p.setProperty(
                    "calagopus.api-key-env",
                    requireForm(form, "apiKeyEnv")
            );

            p.setProperty(
                    "backend-server",
                    requireForm(form, "backendServer")
            );

            p.setProperty(
                    "status-host",
                    requireForm(form, "statusHost")
            );

            p.setProperty(
                    "status-port",
                    requireForm(form, "statusPort")
            );

            p.setProperty(
                    "status-timeout-millis",
                    requireForm(form, "statusTimeoutMillis")
            );

            p.setProperty(
                    "check-interval-seconds",
                    requireForm(form, "checkIntervalSeconds")
            );

            p.setProperty(
                    "startup-timeout-seconds",
                    requireForm(form, "startupTimeoutSeconds")
            );

            p.setProperty(
                    "startup-poll-seconds",
                    requireForm(form, "startupPollSeconds")
            );

            p.setProperty(
                    "idle-minutes",
                    requireForm(form, "idleMinutes")
            );

            p.setProperty(
                    "save-wait-seconds",
                    requireForm(form, "saveWaitSeconds")
            );

            p.setProperty(
                    "save-before-stop",
                    Boolean.toString(
                            "true".equals(
                                    form.get(
                                            "saveBeforeStop"
                                    )
                            )
                    )
            );

            p.setProperty(
                    "startup-message",
                    form.getOrDefault(
                            "startupMessage",
                            "Paper is starting. Please wait..."
                    )
            );

            /*
             * Web server settings are intentionally preserved.
             * Changing them from the dashboard would require
             * restarting the embedded HTTP server.
             */
            p.setProperty(
                    "web-enabled",
                    Boolean.toString(
                            config.webEnabled()
                    )
            );

            p.setProperty(
                    "web-bind",
                    config.webBind()
            );

            p.setProperty(
                    "web-port",
                    Integer.toString(
                            config.webPort()
                    )
            );

            validateProperties(p);

            Path file =
                    dataDirectory.resolve(
                            "config.properties"
                    );

            try (
                    var writer =
                            Files.newBufferedWriter(
                                    file,
                                    StandardCharsets.UTF_8
                            )
            ) {

                p.store(
                        writer,
                        "CalagopusAutoPower configuration"
                );
            }

            reloadConfiguration();

            sendJson(
                    exchange,
                    200,
                    "{\"success\":true,\"message\":\"Configuration saved and reloaded.\"}"
            );

        } catch (Exception e) {

            sendJson(
                    exchange,
                    400,
                    "{\"success\":false,\"error\":\""
                            + jsonEscape(
                                    e.getMessage()
                            )
                            + "\"}"
            );
        }
    }

    private void handleConfigReload(
            HttpExchange exchange)
            throws IOException {

        try {

            reloadConfiguration();

            sendJson(
                    exchange,
                    200,
                    "{\"success\":true,\"message\":\"Configuration reloaded.\"}"
            );

        } catch (Exception e) {

            sendJson(
                    exchange,
                    400,
                    "{\"success\":false,\"error\":\""
                            + jsonEscape(
                                    e.getMessage()
                            )
                            + "\"}"
            );
        }
    }

    private synchronized void reloadConfiguration()
            throws Exception {

        Config old =
                this.config;

        Config updated =
                Config.load(
                        dataDirectory,
                        logger
                );

        /*
         * Validate the Velocity backend before replacing
         * the active configuration.
         */
        RegisteredServer newBackend =
                proxy.getServer(
                        updated.backendServer()
                ).orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Velocity server '"
                                                + updated.backendServer()
                                                + "' was not found"
                                )
                );

        String apiKey =
                System.getenv(
                        updated.apiKeyEnv()
                );

        if (apiKey == null || apiKey.isBlank()) {

            throw new IllegalStateException(
                    "Environment variable "
                            + updated.apiKeyEnv()
                            + " is missing or empty"
            );
        }

        CalagopusClient newApi =
                new CalagopusClient(
                        httpClient,
                        updated.calagopusUrl(),
                        updated.serverUuid(),
                        apiKey
                );

        this.backend = newBackend;
        this.api = newApi;
        this.config = updated;

        if (old == null
                || old.checkIntervalSeconds()
                != updated.checkIntervalSeconds()) {

            startMonitor();
        }

        zeroPlayersSince = 0L;
        lastLoggedIdleMinute = 0L;

        logger.info(
                "CalagopusAutoPower configuration reloaded."
        );
    }

    private void handleStart(
            HttpExchange exchange)
            throws IOException {

        if (stopping.get()) {

            sendJson(
                    exchange,
                    409,
                    "{\"success\":false,\"error\":\"Paper is currently stopping.\"}"
            );

            return;
        }

        backend.ping()
                .whenComplete(
                        (ping, error) -> {

                            if (error == null) {

                                sendJsonSafe(
                                        exchange,
                                        200,
                                        "{\"success\":true,\"message\":\"Paper is already online.\"}"
                                );

                                return;
                            }

                            ensureStartedAndReady()
                                    .whenComplete(
                                            (ignored, startError) -> {

                                                if (startError != null) {

                                                    sendJsonSafe(
                                                            exchange,
                                                            500,
                                                            "{\"success\":false,\"error\":\""
                                                                    + jsonEscape(
                                                                            unwrap(
                                                                                    startError
                                                                            ).getMessage()
                                                                    )
                                                                    + "\"}"
                                                    );

                                                } else {

                                                    sendJsonSafe(
                                                            exchange,
                                                            200,
                                                            "{\"success\":true,\"message\":\"Paper started successfully.\"}"
                                                    );
                                                }
                                            }
                                    );
                        }
                );
    }

    private void handleStop(
            HttpExchange exchange)
            throws IOException {

        manualStop()
                .whenComplete(
                        (ignored, error) -> {

                            if (error != null) {

                                sendJsonSafe(
                                        exchange,
                                        409,
                                        "{\"success\":false,\"error\":\""
                                                + jsonEscape(
                                                        unwrap(error)
                                                                .getMessage()
                                                )
                                                + "\"}"
                                );

                            } else {

                                sendJsonSafe(
                                        exchange,
                                        200,
                                        "{\"success\":true,\"message\":\"Stop requested.\"}"
                                );
                            }
                        }
                );
    }

    private void handleSave(
            HttpExchange exchange)
            throws IOException {

        manualSave()
                .whenComplete(
                        (ignored, error) -> {

                            if (error != null) {

                                sendJsonSafe(
                                        exchange,
                                        500,
                                        "{\"success\":false,\"error\":\""
                                                + jsonEscape(
                                                        unwrap(error)
                                                                .getMessage()
                                                )
                                                + "\"}"
                                );

                            } else {

                                sendJsonSafe(
                                        exchange,
                                        200,
                                        "{\"success\":true,\"message\":\"save-all sent.\"}"
                                );
                            }
                        }
                );
    }

    private void handleCommand(
            HttpExchange exchange)
            throws IOException {

        if (!"POST".equalsIgnoreCase(
                exchange.getRequestMethod()
        )) {

            sendJsonSafe(
                    exchange,
                    405,
                    "{\"success\":false,\"error\":\"Method Not Allowed\"}"
            );

            return;
        }

        String body;

        try (java.io.InputStream input =
                     exchange.getRequestBody()) {

            body = new String(
                    input.readAllBytes(),
                    StandardCharsets.UTF_8
            );
        }

        Map<String, String> form =
                parseForm(body);

        String command =
                form.get("command");

        if (command == null || command.isBlank()) {

            sendJsonSafe(
                    exchange,
                    400,
                    "{\"success\":false,\"error\":\"Command cannot be empty.\"}"
            );

            return;
        }

        command = command.trim();

        if (command.startsWith("/")) {
            command = command.substring(1);
        }

        final String finalCommand = command;

        api.command(finalCommand)
                .whenComplete(
                        (ignored, error) -> {

                            if (error != null) {

                                sendJsonSafe(
                                        exchange,
                                        500,
                                        "{\"success\":false,\"error\":\""
                                                + jsonEscape(
                                                        unwrap(error)
                                                                .getMessage()
                                                )
                                                + "\"}"
                                );

                            } else {

                                sendJsonSafe(
                                        exchange,
                                        200,
                                        "{\"success\":true,\"message\":\"Command sent.\"}"
                                );
                            }
                        }
                );
    }

    private static Map<String, String> parseForm(
            String body) {

        Map<String, String> result =
                new HashMap<>();

        if (body == null || body.isEmpty()) {
            return result;
        }

        for (String pair : body.split("&")) {

            String[] parts =
                    pair.split(
                            "=",
                            2
                    );

            String key =
                    URLDecoder.decode(
                            parts[0],
                            StandardCharsets.UTF_8
                    );

            String value =
                    parts.length > 1
                            ? URLDecoder.decode(
                                    parts[1],
                                    StandardCharsets.UTF_8
                            )
                            : "";

            result.put(
                    key,
                    value
            );
        }

        return result;
    }

    private static String requireForm(
            Map<String, String> form,
            String key) {

        String value =
                form.get(key);

        if (value == null || value.isBlank()) {

            throw new IllegalArgumentException(
                    "Missing field: " + key
            );
        }

        return value.trim();
    }

    private static void validateProperties(
            Properties p) {

        required(p, "calagopus.url");
        required(p, "calagopus.server-uuid");
        required(p, "calagopus.api-key-env");
        required(p, "backend-server");
        required(p, "status-host");

        positiveInt(p, "status-port");
        positiveInt(p, "status-timeout-millis");
        positiveInt(p, "check-interval-seconds");
        positiveInt(p, "startup-timeout-seconds");
        positiveInt(p, "startup-poll-seconds");
        positiveInt(p, "idle-minutes");
        nonNegativeInt(p, "save-wait-seconds");
    }

    private static String required(
            Properties p,
            String key) {

        String value =
                p.getProperty(key);

        if (value == null || value.isBlank()) {

            throw new IllegalArgumentException(
                    "Missing config: " + key
            );
        }

        return value.trim();
    }

    private static int positiveInt(
            Properties p,
            String key) {

        int value;

        try {

            value =
                    Integer.parseInt(
                            required(p, key)
                    );

        } catch (NumberFormatException e) {

            throw new IllegalArgumentException(
                    key + " must be a number"
            );
        }

        if (value <= 0) {

            throw new IllegalArgumentException(
                    key + " must be > 0"
            );
        }

        return value;
    }

    private static int nonNegativeInt(
            Properties p,
            String key) {

        int value;

        try {

            value =
                    Integer.parseInt(
                            required(p, key)
                    );

        } catch (NumberFormatException e) {

            throw new IllegalArgumentException(
                    key + " must be a number"
            );
        }

        if (value < 0) {

            throw new IllegalArgumentException(
                    key + " must be >= 0"
            );
        }

        return value;
    }

    private void sendHtml(
            HttpExchange exchange,
            String html)
            throws IOException {

        byte[] data =
                html.getBytes(
                        StandardCharsets.UTF_8
                );

        exchange.getResponseHeaders()
                .set(
                        "Content-Type",
                        "text/html; charset=UTF-8"
                );

        exchange.getResponseHeaders()
                .set(
                        "Cache-Control",
                        "no-store"
                );

        exchange.sendResponseHeaders(
                200,
                data.length
        );

        try (
                OutputStream output =
                        exchange.getResponseBody()
        ) {

            output.write(data);
        }
    }

    private void sendJson(
            HttpExchange exchange,
            int status,
            String json)
            throws IOException {

        byte[] data =
                json.getBytes(
                        StandardCharsets.UTF_8
                );

        exchange.getResponseHeaders()
                .set(
                        "Content-Type",
                        "application/json; charset=UTF-8"
                );

        exchange.getResponseHeaders()
                .set(
                        "Cache-Control",
                        "no-store"
                );

        exchange.sendResponseHeaders(
                status,
                data.length
        );

        try (
                OutputStream output =
                        exchange.getResponseBody()
        ) {

            output.write(data);
        }
    }

    private void sendJsonSafe(
            HttpExchange exchange,
            int status,
            String json) {

        try {

            sendJson(
                    exchange,
                    status,
                    json
            );

        } catch (IOException ignored) {
        }
    }

    private void sendText(
            HttpExchange exchange,
            int status,
            String text)
            throws IOException {

        byte[] data =
                text.getBytes(
                        StandardCharsets.UTF_8
                );

        exchange.getResponseHeaders()
                .set(
                        "Content-Type",
                        "text/plain; charset=UTF-8"
                );

        exchange.sendResponseHeaders(
                status,
                data.length
        );

        try (
                OutputStream output =
                        exchange.getResponseBody()
        ) {

            output.write(data);
        }
    }

    private static String jsonValue(
            String json,
            String key) {

        String pattern =
                "\"" + java.util.regex.Pattern.quote(key)
                        + "\"\\s*:\\s*\"([^\"]*)\"";

        java.util.regex.Matcher matcher =
                java.util.regex.Pattern
                        .compile(pattern)
                        .matcher(json);

        if (matcher.find()) {
            return matcher.group(1);
        }

        return null;
    }

    private static String jsonArrayFirst(
            String json,
            String key) {

        String pattern =
                "\"" + java.util.regex.Pattern.quote(key)
                        + "\"\\s*:\\s*\\[\\s*\"([^\"]*)\"";

        java.util.regex.Matcher matcher =
                java.util.regex.Pattern
                        .compile(pattern)
                        .matcher(json);

        if (matcher.find()) {
            return matcher.group(1);
        }

        return null;
    }

    private static String jsonEscape(
            String value) {

        if (value == null) {
            return "";
        }

        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    private static Throwable unwrap(
            Throwable throwable) {

        Throwable t =
                throwable;

        while (
                (
                        t instanceof CompletionException
                                ||
                        t instanceof java.util.concurrent.ExecutionException
                )
                        && t.getCause() != null
        ) {

            t = t.getCause();
        }

        return t;
    }

    /*
     * ================================================================
     * CONFIG
     * ================================================================
     */

    private static final class Config {

        private final Properties p;

        private Config(Properties p) {
            this.p = p;
        }

        static Config load(
                Path dir,
                Logger logger)
                throws IOException {

            Files.createDirectories(dir);

            Path file =
                    dir.resolve(
                            "config.properties"
                    );

            if (Files.notExists(file)) {

                try (
                        InputStream in =
                                CalagopusAutoPower.class
                                        .getResourceAsStream(
                                                "/default.properties"
                                        )
                ) {

                    if (in == null) {

                        throw new IOException(
                                "default.properties missing "
                                        + "from plugin JAR"
                        );
                    }

                    Files.copy(
                            in,
                            file
                    );
                }

                logger.info(
                        "Created default config at {}",
                        file
                );
            }

            Properties p =
                    new Properties();

            try (
                    Reader reader =
                            Files.newBufferedReader(
                                    file,
                                    StandardCharsets.UTF_8
                            )
            ) {

                p.load(reader);
            }

            return new Config(p);
        }

        String calagopusUrl() {
            return required(
                    "calagopus.url"
            ).replaceAll(
                    "/$",
                    ""
            );
        }

        String serverUuid() {
            return required(
                    "calagopus.server-uuid"
            );
        }

        String apiKeyEnv() {
            return required(
                    "calagopus.api-key-env"
            );
        }

        String backendServer() {
            return required(
                    "backend-server"
            );
        }

        String statusHost() {
            return p.getProperty(
                    "status-host",
                    "127.0.0.1"
            ).trim();
        }

        int statusPort() {
            return positiveInt(
                    "status-port"
            );
        }

        int statusTimeoutMillis() {
            return positiveInt(
                    "status-timeout-millis"
            );
        }

        String chunkyStatusHost() {
            return p.getProperty(
                    "chunky-status-host",
                    "127.0.0.1"
            ).trim();
        }

        int chunkyStatusPort() {
            String value = p.getProperty(
                    "chunky-status-port",
                    "28199"
            ).trim();

            return positiveIntValue(
                    "chunky-status-port",
                    value
            );
        }

        int chunkyStatusTimeoutMillis() {
            String value = p.getProperty(
                    "chunky-status-timeout-millis",
                    "3000"
            ).trim();

            return positiveIntValue(
                    "chunky-status-timeout-millis",
                    value
            );
        }

        int checkIntervalSeconds() {
            return positiveInt(
                    "check-interval-seconds"
            );
        }

        int startupTimeoutSeconds() {
            return positiveInt(
                    "startup-timeout-seconds"
            );
        }

        int startupPollSeconds() {
            return positiveInt(
                    "startup-poll-seconds"
            );
        }

        int idleMinutes() {
            return positiveInt(
                    "idle-minutes"
            );
        }

        int saveWaitSeconds() {
            return nonNegativeInt(
                    "save-wait-seconds"
            );
        }

        boolean saveBeforeStop() {

            return Boolean.parseBoolean(
                    p.getProperty(
                            "save-before-stop",
                            "true"
                    )
            );
        }

        String startupMessage() {

            return p.getProperty(
                    "startup-message",
                    "Paper is starting. Please wait..."
            );
        }

        boolean webEnabled() {

            return Boolean.parseBoolean(
                    p.getProperty(
                            "web-enabled",
                            "true"
                    )
            );
        }

        String webBind() {

            return p.getProperty(
                    "web-bind",
                    "127.0.0.1"
            ).trim();
        }

        int webPort() {

            return positiveInt(
                    "web-port"
            );
        }

        String webAuthUsernameEnv() {

            return "CALAGOPUS_AUTOPOWER_WEB_USERNAME";
        }

        String webAuthPasswordEnv() {

            return "CALAGOPUS_AUTOPOWER_WEB_PASSWORD";
        }

        private String required(
                String key) {

            String value =
                    p.getProperty(key);

            if (value == null || value.isBlank()) {

                throw new IllegalStateException(
                        "Missing config: " + key
                );
            }

            return value.trim();
        }

        private int positiveInt(
                String key) {

            return positiveIntValue(
                    key,
                    required(key)
            );
        }

        private int positiveIntValue(
                String key,
                String rawValue) {

            int value;

            try {

                value =
                        Integer.parseInt(
                                rawValue
                        );

            } catch (NumberFormatException e) {

                throw new IllegalStateException(
                        key + " must be a number"
                );
            }

            if (value <= 0) {

                throw new IllegalArgumentException(
                        key + " must be > 0"
                );
            }

            return value;
        }

        private int nonNegativeInt(
                String key) {

            int value;

            try {

                value =
                        Integer.parseInt(
                                required(key)
                        );

            } catch (NumberFormatException e) {

                throw new IllegalStateException(
                        key + " must be a number"
                );
            }

            if (value < 0) {

                throw new IllegalArgumentException(
                        key + " must be >= 0"
                );
            }

            return value;
        }
    }

    /*
     * ================================================================
     * CALAGOPUS API
     * ================================================================
     */


    private final MinecraftConsole minecraftConsole =
            new MinecraftConsole();

    private final class MinecraftConsole
            implements WebSocket.Listener {

        private final java.util.Deque<String> lines =
                new java.util.ArrayDeque<>();

        private volatile WebSocket socket;

        private StringBuilder messageBuffer =
                new StringBuilder();

        synchronized String getLogs() {
            StringBuilder out = new StringBuilder();

            for (String line : lines) {
                out.append(line).append('\n');
            }

            return out.toString();
        }

        synchronized void addLine(String line) {
            if (line == null || line.isBlank()) {
                return;
            }

            lines.addLast(line);

            while (lines.size() > 300) {
                lines.removeFirst();
            }
        }

        void connect() {
            try {
                String apiKey =
                        System.getenv(config.apiKeyEnv());

                if (apiKey == null || apiKey.isBlank()) {
                    logger.warn(
                            "Minecraft console: API key unavailable"
                    );
                    return;
                }

                HttpRequest request =
                        HttpRequest.newBuilder()
                                .uri(
                                        URI.create(
                                                config.calagopusUrl()
                                                        + "/api/client/servers/"
                                                        + config.serverUuid()
                                                        + "/websocket"
                                        )
                                )
                                .timeout(Duration.ofSeconds(15))
                                .header(
                                        "Authorization",
                                        "Bearer " + apiKey
                                )
                                .header(
                                        "Accept",
                                        "application/json"
                                )
                                .GET()
                                .build();

                httpClient.sendAsync(
                        request,
                        HttpResponse.BodyHandlers.ofString(
                                StandardCharsets.UTF_8
                        )
                ).thenAccept(response -> {

                    if (response.statusCode() < 200
                            || response.statusCode() >= 300) {

                        logger.warn(
                                "Minecraft console JWT request failed: HTTP "
                                        + response.statusCode()
                        );

                        return;
                    }

                    try {
                        String body = response.body();

                        String token =
                                jsonValue(body, "token");

                        String url =
                                jsonValue(body, "url");

                        if (token == null
                                || url == null) {

                            logger.warn(
                                    "Minecraft console: invalid WebSocket response"
                            );

                            return;
                        }

                        httpClient.newWebSocketBuilder()
                                .connectTimeout(
                                        Duration.ofSeconds(15)
                                )
                                .buildAsync(
                                        URI.create(url),
                                        this
                                )
                                .thenAccept(ws -> {

                                    socket = ws;

                                    ws.sendText(
                                            "{\"event\":\"auth\",\"args\":[\""
                                                    + jsonEscape(token)
                                                    + "\"]}",
                                            true
                                    );

                                    ws.sendText(
                                            "{\"event\":\"send logs\",\"args\":[]}",
                                            true
                                    );

                                    logger.info(
                                            "Minecraft console WebSocket connected"
                                    );
                                })
                                .exceptionally(error -> {

                                    logger.warn(
                                            "Minecraft console WebSocket connection failed",
                                            error
                                    );

                                    return null;
                                });

                    } catch (Exception e) {

                        logger.warn(
                                "Minecraft console WebSocket setup failed",
                                e
                        );
                    }
                });

            } catch (Exception e) {

                logger.warn(
                        "Minecraft console connection failed",
                        e
                );
            }
        }

        @Override
        public CompletionStage<?> onText(
                WebSocket webSocket,
                CharSequence data,
                boolean last) {

            messageBuffer.append(data);

            if (last) {

                String message =
                        messageBuffer.toString();

                messageBuffer.setLength(0);

                handleMessage(message);
            }

            webSocket.request(1);

            return null;
        }

        private void handleMessage(String message) {

            try {

                String event =
                        jsonValue(message, "event");

                if (!"console output".equals(event)) {
                    return;
                }

                String args =
                        jsonArrayFirst(message, "args");

                if (args != null) {

                    String clean = args;

                    // Normalize Calagopus console terminal artifacts.
                    clean = clean.replace(">....", "");

                    // Detect command terminal initialization.
                    String commandPrefix =
                            "\\u001b[?1h\\u001b=\\u001b[?2004h";

                    if (clean.startsWith(commandPrefix)) {
                        clean = "Command = "
                                + clean.substring(commandPrefix.length());
                    }

                    // Remove literal ANSI sequences.
                    clean = clean.replaceAll(
                            "\\\\u001b\\[[0-9;?]*[ -/]*[@-~]",
                            ""
                    );

                    // Remove actual ANSI CSI sequences.
                    clean = clean.replaceAll(
                            "\u001B\\[[0-9;?]*[ -/]*[@-~]",
                            ""
                    );

                    // Remove actual ESC single-character controls.
                    clean = clean.replaceAll(
                            "\u001B[@-_]",
                            ""
                    );

                    // Remove literal carriage returns.
                    clean = clean.replace("\\r", "");

                    if (!clean.isBlank()) {
                        addLine(clean);
                    }
                }

            } catch (Exception e) {

                logger.debug(
                        "Unable to parse Minecraft console message",
                        e
                );
            }
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onClose(
                WebSocket webSocket,
                int statusCode,
                String reason) {

            socket = null;

            logger.info(
                    "Minecraft console WebSocket closed: "
                            + statusCode
                            + " "
                            + reason
            );

            scheduleReconnect();

            return null;
        }

        @Override
        public void onError(
                WebSocket webSocket,
                Throwable error) {

            socket = null;

            logger.warn(
                    "Minecraft console WebSocket error",
                    error
            );

            scheduleReconnect();
        }

        private void scheduleReconnect() {

            java.util.concurrent.CompletableFuture.delayedExecutor(
                    5,
                    java.util.concurrent.TimeUnit.SECONDS
            ).execute(() -> {

                if (socket != null) {
                    return;
                }

                logger.info(
                        "Minecraft console WebSocket reconnecting..."
                );

                connect();
            });
        }
    }

    private static final class CalagopusClient {

        private final HttpClient http;
        private final String baseUrl;
        private final String serverUuid;
        private final String apiKey;

        CalagopusClient(
                HttpClient http,
                String baseUrl,
                String serverUuid,
                String apiKey) {

            this.http = http;
            this.baseUrl = baseUrl;
            this.serverUuid = serverUuid;
            this.apiKey = apiKey;
        }

        CompletionStage<Void> power(
                String signal) {

            String json =
                    "{\"signal\":\""
                            + escape(signal)
                            + "\"}";

            return post(
                    "/power",
                    json
            );
        }

        CompletionStage<Void> command(
                String command) {

            String json =
                    "{\"command\":\""
                            + escape(command)
                            + "\"}";

            return post(
                    "/command",
                    json
            );
        }

        CompletionStage<String> resources() {

            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(
                                    baseUrl
                                            + "/api/client/servers/"
                                            + serverUuid
                                            + "/resources"))
                            .timeout(Duration.ofSeconds(15))
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Accept", "application/json")
                            .GET()
                            .build();

            return http.sendAsync(
                            request,
                            HttpResponse.BodyHandlers.ofString(
                                    StandardCharsets.UTF_8))
                    .thenCompose(response -> {
                        int code = response.statusCode();
                        if (code >= 200 && code < 300) {
                            return CompletableFuture.completedFuture(
                                    response.body());
                        }
                        return CompletableFuture.failedFuture(
                                new IOException(
                                        "Calagopus API " + code
                                                + " for /resources"))
                                ;
                    });
        }


        private CompletionStage<Void> post(
                String path,
                String json) {

            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(
                                    URI.create(
                                            baseUrl
                                                    + "/api/client/servers/"
                                                    + serverUuid
                                                    + path
                                    )
                            )
                            .timeout(
                                    Duration.ofSeconds(15)
                            )
                            .header(
                                    "Authorization",
                                    "Bearer " + apiKey
                            )
                            .header(
                                    "Accept",
                                    "application/json"
                            )
                            .header(
                                    "Content-Type",
                                    "application/json"
                            )
                            .POST(
                                    HttpRequest.BodyPublishers
                                            .ofString(
                                                    json,
                                                    StandardCharsets.UTF_8
                                            )
                            )
                            .build();

            return http.sendAsync(
                            request,
                            HttpResponse.BodyHandlers
                                    .ofString(
                                            StandardCharsets.UTF_8
                                    )
                    )
                    .thenCompose(
                            response -> {

                                int code =
                                        response.statusCode();

                                if (code >= 200
                                        && code < 300) {

                                    return CompletableFuture
                                            .completedFuture(
                                                    null
                                            );
                                }

                                String body =
                                        response.body();

                                if (body.length() > 500) {

                                    body =
                                            body.substring(
                                                    0,
                                                    500
                                            );
                                }

                                return CompletableFuture
                                        .failedFuture(
                                                new IOException(
                                                        "Calagopus API "
                                                                + code
                                                                + " for "
                                                                + path
                                                                + ": "
                                                                + body
                                                )
                                        );
                            }
                    );
        }

        private static String escape(
                String value) {

            return value
                    .replace(
                            "\\",
                            "\\\\"
                    )
                    .replace(
                            "\"",
                            "\\\""
                    );
        }
    }

    /*
     * ================================================================
     * DASHBOARD
     * ================================================================
     */

    private static final String DASHBOARD_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Calagopus AutoPower</title>

<style>
.state-dot{
    display:inline-block;
    width:9px;
    height:9px;
    border-radius:50%;
    margin-right:7px;
    vertical-align:middle;
}

.state-dot.online{
    background:#22c55e;
}

.state-dot.offline{
    background:#ef4444;
}


*{
    box-sizing:border-box;
}

html{
    background:#0b1120;
}

body{
    margin:0;
    min-height:100vh;
    font-family:Arial,Helvetica,sans-serif;
    background:
        radial-gradient(circle at top,#172554 0%,#0f172a 42%,#0b1120 100%);
    color:#e5e7eb;
}

.container{
    width:100%;
    max-width:1100px;
    margin:0 auto;
    padding:24px;
}

header{
    display:flex;
    justify-content:space-between;
    align-items:center;
    gap:16px;
    margin-bottom:20px;
}

header h1{
    margin:0;
    font-size:25px;
    line-height:1.2;
}

header small{
    display:block;
    margin-top:6px;
    color:#94a3b8;
}

h1{
    margin:0;
}

h2{
    margin:0 0 18px;
    font-size:20px;
}

.card{
    background:rgba(30,41,59,.94);
    border:1px solid #334155;
    border-radius:14px;
    padding:20px;
    margin-bottom:18px;
    box-shadow:0 12px 30px rgba(0,0,0,.18);
}

.status{
    display:flex;
    justify-content:space-between;
    align-items:center;
    flex-wrap:wrap;
    gap:20px;
}

.state{
    font-size:28px;
    font-weight:bold;
    margin-top:5px;
}

.online{color:#4ade80}
.offline{color:#f87171}
.starting{color:#facc15}
.stopping{color:#fb923c}

.stats{
    display:grid;
    grid-template-columns:repeat(3,minmax(0,1fr));
    gap:12px;
    margin-top:18px;
}

.stat{
    background:#0f172a;
    border:1px solid #1e293b;
    border-radius:10px;
    padding:15px;
    min-width:0;
}

.stat small{
    color:#94a3b8;
    display:block;
    margin-bottom:6px;
}

.stat strong{
    font-size:22px;
    word-break:break-word;
}

.buttons{
    display:flex;
    gap:10px;
    flex-wrap:wrap;
    margin-top:18px;
}

button{
    border:0;
    border-radius:8px;
    padding:11px 18px;
    min-height:42px;
    cursor:pointer;
    font-weight:bold;
    background:#3b82f6;
    color:white;
    transition:opacity .15s,transform .05s;
}

button:hover{
    opacity:.88;
}

button:active{
    transform:translateY(1px);
}

button:disabled{
    opacity:.4;
    cursor:not-allowed;
}

.danger{background:#dc2626}
.save{background:#16a34a}
.secondary{background:#475569}

.grid{
    display:grid;
    grid-template-columns:repeat(2,minmax(0,1fr));
    gap:14px;
}

label{
    display:block;
    color:#94a3b8;
    font-size:13px;
    margin-bottom:6px;
}

/* Configuration layout */
#settingsPage .card {
    width: 100%;
    box-sizing: border-box;
}

#settingsPage .config-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 12px 16px;
    width: 100%;
    box-sizing: border-box;
}

#settingsPage .config-field {
    min-width: 0;
}

#settingsPage .config-field label {
    display: block;
    margin-bottom: 6px;
}

#settingsPage .config-field input:not([type="checkbox"]),
#settingsPage .config-field textarea {
    width: 100%;
    box-sizing: border-box;
}

#settingsPage .config-field textarea {
    min-height: 70px;
    resize: vertical;
}

#settingsPage .config-wide {
    grid-column: 1 / -1;
}

#settingsPage .config-checkbox {
    grid-column: 1 / -1;
}

#settingsPage .config-checkbox label {
    display: flex;
    align-items: center;
    gap: 8px;
}

#settingsPage .config-checkbox input[type="checkbox"] {
    width: auto;
    margin: 0;
}

#settingsPage .config-actions {
    display: flex;
    gap: 10px;
    flex-wrap: wrap;
    margin-top: 16px;
}

@media(max-width:700px){
    #settingsPage .config-grid {
        grid-template-columns: 1fr;
        gap: 10px;
    }

    #settingsPage .config-wide,
    #settingsPage .config-checkbox {
        grid-column: auto;
    }

    #settingsPage .config-actions {
        flex-direction: column;
    }

    #settingsPage .config-actions button {
        width: 100%;
    }
}

input,
textarea{
    width:100%;
    max-width:100%;
    padding:11px;
    border-radius:8px;
    border:1px solid #475569;
    background:#0f172a;
    color:#e5e7eb;
    outline:none;
    font:inherit;
}

input:focus,
textarea:focus{
    border-color:#60a5fa;
    box-shadow:0 0 0 2px rgba(96,165,250,.15);
}

textarea{
    min-height:80px;
    resize:vertical;
}

.full{
    grid-column:1/-1;
}

.message{
    padding:10px 12px;
    margin-bottom:15px;
    border-radius:8px;
    display:none;
    border:1px solid rgba(255,255,255,.08);
}

#login{
    max-width:430px;
    margin:0 auto;
    min-height:100vh;
    display:flex;
    align-items:center;
    padding-top:30px;
    padding-bottom:30px;
}

#login .card{
    width:100%;
}

#login h1{
    font-size:26px;
}

#login p{
    color:#94a3b8;
    margin:8px 0 22px;
}

#login label{
    margin-top:14px;
}

#login input{
    margin-bottom:2px;
}

#login .buttons button{
    width:100%;
}

@media(max-width:700px){
    .container{
        padding:14px;
    }

    header{
        align-items:flex-start;
    }

    header h1{
        font-size:21px;
    }

    .card{
        padding:16px;
        border-radius:12px;
    }

    .state{
        font-size:24px;
    }

    .stats{
        grid-template-columns:1fr;
    }

    .grid{
        grid-template-columns:1fr;
    }

    .full{
        grid-column:auto;
    }

    .buttons{
        flex-direction:column;
    }

    .buttons button{
        width:100%;
    }

    #login{
        padding:14px;
    }

    #login .card{
        padding:20px 16px;
    }
}

@media(max-width:380px){
    .container{
        padding:10px;
    }

    .card{
        padding:14px;
    }

    #login h1{
        font-size:23px;
    }

    .state{
        font-size:22px;
    }
}
</style>
</head>

<body>

<div id="login" class="container">
    <div class="card">
        <h1>Calagopus AutoPower</h1>
        <p>Web Control Panel</p>

        <label>Username</label>
        <input id="username"
               type="text"
               autocomplete="username"
               placeholder="Username">

        <label>Password</label>
        <input id="password"
               type="password"
               autocomplete="current-password"
               placeholder="Password">

        <label style="display:flex;align-items:center;gap:8px;margin-top:10px;cursor:pointer">
            <input id="rememberMe"
                   type="checkbox"
                   style="width:auto">
            Remember me
        </label>

        <div class="buttons">
            <button onclick="login()">Login</button>
        </div>

        <div id="loginMsg" class="message"></div>
    </div>
</div>

<div id="dashboard" class="container" style="display:none">

<header>
    <div>
        <h1>Calagopus AutoPower</h1>
        <small>Paper / Geyser automatic power management</small>
    </div>

    <div class="buttons" style="margin-top:0">
        <button class="secondary" id="settingsNavBtn"
                onclick="showPage('settingsPage')">
            ⚙ Settings
        </button>

        <button class="secondary" onclick="logout()">
            Logout
        </button>
    </div>
</header>

<div id="message" class="message"></div>

<div id="dashboardPage">

<div class="card">

    <div class="status">

        <div>
            <div>Paper Server</div>
            <div id="state" class="state">
                <span id="stateDot" class="state-dot"></span>
                <span id="stateText">LOADING</span>
            </div>
        </div>

        <div>
            <div>💤 Idle shutdown</div>
            <div id="countdown" class="state">--</div>
        </div>

    </div>

    <div class="stats">

        <div class="stat">
            <small>👥 Players</small>
            <strong id="players">--</strong>
        </div>

        <div class="stat">
            <small>⏱ Idle limit</small>
            <strong id="idleLimit">--</strong>
        </div>

        <div class="stat">
            <small>⏳ Idle time</small>
            <strong id="idleTime">--</strong>
        </div>

    </div>

    <div class="stats">

        <div class="stat">
            <small>🧠 RAM Usage</small>
            <strong id="ramUsage">--</strong>
        </div>

        <div class="stat">
            <small>⚡ CPU Usage</small>
            <strong id="cpuUsage">--</strong>
        </div>

        <div class="stat">
            <small>💾 Disk Usage</small>
            <strong id="diskUsage">--</strong>
        </div>

        <div class="stat">
            <small>🌐 Network</small>
            <strong id="networkUsage">--</strong>
        </div>

        <div class="stat">
            <small>🕐 Uptime</small>
            <strong id="serverUptime">--</strong>
        </div>

    </div>

    <div class="buttons">

        <button id="startBtn" onclick="action('/api/start')">
            ▶ Start
        </button>

        <button id="stopBtn" class="danger"
                onclick="action('/api/stop')">
            ⏹ Stop
        </button>

        <button class="save"
                onclick="action('/api/save')">
            💾 Save All
        </button>

    </div>

</div>

<div class="card">

    <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap">
        <div>
            <h2 style="margin-bottom:4px">Logs</h2>
            <small style="color:#94a3b8">
                Recent logs from Minecraft Server Console and Velocity
            </small>
        </div>

        <div style="
            display:flex;
            align-items:center;
            justify-content:flex-end;
            gap:8px;
            flex-wrap:wrap;
            margin-top:0;
        ">
            <button
                class="secondary"
                onclick="refreshLogs()"
                style="
                    margin:0;
                    min-width:110px;
                    height:40px;
                    display:inline-flex;
                    align-items:center;
                    justify-content:center;
                "
            >
                🔄 Refresh
            </button>

            <button
                class="secondary"
                id="pauseLogsBtn"
                onclick="toggleLogPause()"
                style="
                    margin:0;
                    min-width:110px;
                    height:40px;
                    display:inline-flex;
                    align-items:center;
                    justify-content:center;
                "
            >
                ⏸ Pause
            </button>
        </div>
    </div>

    <div style="
        display:flex;
        gap:8px;
        flex-wrap:wrap;
        align-items:stretch;
        margin-top:18px;
    ">

        <button
            id="minecraftLogTab"
            onclick="switchLogTab('minecraft')"
            style="
                margin:0;
                min-height:40px;
                flex:1 1 220px;
                display:inline-flex;
                align-items:center;
                justify-content:center;
            "
        >
            Minecraft Server Console
        </button>

        <button
            id="velocityLogTab"
            class="secondary"
            onclick="switchLogTab('velocity')"
            style="
                margin:0;
                min-height:40px;
                flex:1 1 160px;
                display:inline-flex;
                align-items:center;
                justify-content:center;
            "
        >
            Velocity Logs
        </button>

    </div>

    <div style="margin-top:14px">

        <pre id="logViewer"
             style="
                margin:0;
                width:100%;
                height:420px;
                overflow:auto;
                padding:14px;
                border-radius:10px;
                border:1px solid #334155;
                background:#020617;
                color:#cbd5e1;
                font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,monospace;
                font-size:12px;
                line-height:1.5;
                white-space:pre-wrap;
                overflow-wrap:anywhere;
             ">Loading logs...</pre>

    </div>

    <div id="minecraftCommandBox" style="
        display:flex;
        gap:8px;
        margin-top:10px;
        align-items:center;
    ">
        <input
            id="minecraftCommandInput"
            type="text"
            placeholder="Enter Minecraft command..."
            autocomplete="off"
            onkeydown="handleMinecraftCommandKey(event)"
            style="
                flex:1;
                min-width:0;
                font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,monospace;
            "
        >

        <button
            id="minecraftCommandSend"
            onclick="sendMinecraftCommand()"
            style="white-space:nowrap"
        >
            SEND
        </button>
    </div>

    <div style="
        display:flex;
        justify-content:space-between;
        align-items:center;
        gap:10px;
        flex-wrap:wrap;
        margin-top:10px;
        color:#64748b;
        font-size:12px;
    ">
        <span id="logStatus">Loading...</span>

        <label style="
            margin:0;
            display:flex;
            align-items:center;
            gap:6px;
            color:#94a3b8;
        ">
            <input id="autoScrollLogs"
                   type="checkbox"
                   checked
                   style="width:auto">
            Auto-scroll
        </label>
    </div>

</div>

</div>

<div id="settingsPage" style="display:none">

<div class="card">

    <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap">
        <div>
            <h2>Configuration</h2>
            <small style="color:#94a3b8">
                Calagopus AutoPower settings
            </small>
        </div>

        <button class="secondary" onclick="showPage('dashboardPage')">
            ← Dashboard
        </button>
    </div>



    <div class="config-grid">

        <div class="config-field">
            <label>Calagopus URL</label>
            <input id="calagopusUrl">
        </div>

        <div class="config-field">
            <label>Server UUID</label>
            <input id="serverUuid">
        </div>

        <div class="config-field">
            <label>API Key Environment Variable</label>
            <input id="apiKeyEnv">
        </div>

        <div class="config-field">
            <label>Velocity Backend</label>
            <input id="backendServer">
        </div>

        <div class="config-field">
            <label>Status Host</label>
            <input id="statusHost">
        </div>

        <div class="config-field">
            <label>Status Port</label>
            <input id="statusPort" type="number">
        </div>

        <div class="config-field">
            <label>Status Timeout (ms)</label>
            <input id="statusTimeoutMillis" type="number">
        </div>

        <div class="config-field">
            <label>Check Interval (seconds)</label>
            <input id="checkIntervalSeconds" type="number">
        </div>

        <div class="config-field">
            <label>Startup Timeout (seconds)</label>
            <input id="startupTimeoutSeconds" type="number">
        </div>

        <div class="config-field">
            <label>Startup Poll (seconds)</label>
            <input id="startupPollSeconds" type="number">
        </div>

        <div class="config-field">
            <label>Idle Shutdown (minutes)</label>
            <input id="idleMinutes" type="number">
        </div>

        <div class="config-field">
            <label>Save Wait (seconds)</label>
            <input id="saveWaitSeconds" type="number">
        </div>

        <div class="config-field config-wide">
            <label>Startup Message</label>
            <textarea id="startupMessage"></textarea>
        </div>

        <div class="config-field config-checkbox">
            <label>
                <input id="saveBeforeStop"
                       type="checkbox"
                       style="width:auto">
                Save before automatic shutdown
            </label>
        </div>

    </div>

    <div class="config-actions">

        <button class="save"
                onclick="saveConfig()">
            💾 Save Configuration
        </button>

        <button class="secondary"
                onclick="loadConfig()">
            🔄 Reload
        </button>

    </div>

</div>

</div>

</div>

<script>

let authUsername=null;
let authPassword=null;

const rememberedLogin =
    localStorage.getItem("autopowerRememberMe") === "true";

if(rememberedLogin){
    authUsername=localStorage.getItem("autopowerUsername");
    authPassword=localStorage.getItem("autopowerPassword");
}else{
    authUsername=sessionStorage.getItem("autopowerUsername");
    authPassword=sessionStorage.getItem("autopowerPassword");
}

function headers(){
    return {
        "X-AutoPower-Username": authUsername || "",
        "X-AutoPower-Password": authPassword || ""
    };
}

async function api(path,options={}){
    options.headers={
        ...(options.headers||{}),
        ...headers()
    };

    let r=await fetch(path,options);

    if(r.status===401){
        logout();
        throw new Error("Unauthorized");
    }

    return r;
}

function showMessage(text,error=false){
    let el=document.getElementById("message");
    el.textContent=text;
    el.style.display="block";
    el.style.background=error?"#7f1d1d":"#14532d";

    setTimeout(()=>{
        el.style.display="none";
    },4000);
}

function showPage(page){
    const dashboard = document.getElementById("dashboardPage");
    const settings = document.getElementById("settingsPage");

    if(page === "settingsPage"){
        dashboard.style.display = "none";
        settings.style.display = "block";
        loadConfig();
    } else {
        settings.style.display = "none";
        dashboard.style.display = "block";
        updateStatus();
        refreshLogs();
    }

    window.scrollTo({top:0, behavior:"smooth"});
}

async function login(){
    const username=document.getElementById("username").value;
    const password=document.getElementById("password").value;
    const msg=document.getElementById("loginMsg");

    if(!username || !password){
        msg.textContent="Enter username and password.";
        return;
    }

    const oldUsername=authUsername;
    const oldPassword=authPassword;

    authUsername=username;
    authPassword=password;

    try{
        const r=await fetch("/api/status",{
            headers:headers(),
            cache:"no-store"
        });

        if(r.status===401){
            throw new Error("Invalid username or password");
        }

        if(!r.ok){
            throw new Error("HTTP "+r.status);
        }

        const rememberMe =
            document.getElementById("rememberMe").checked;

        if(rememberMe){
            localStorage.setItem("autopowerUsername",username);
            localStorage.setItem("autopowerPassword",password);
            localStorage.setItem("autopowerRememberMe","true");

            sessionStorage.removeItem("autopowerUsername");
            sessionStorage.removeItem("autopowerPassword");
        }else{
            sessionStorage.setItem("autopowerUsername",username);
            sessionStorage.setItem("autopowerPassword",password);

            localStorage.removeItem("autopowerUsername");
            localStorage.removeItem("autopowerPassword");
            localStorage.removeItem("autopowerRememberMe");
        }

        document.getElementById("login").style.display="none";
        document.getElementById("dashboard").style.display="block";

        msg.textContent="";

        updateStatus();
        updateResources();
        loadConfig();
        refreshLogs();
        startLogRefresh();

    }catch(e){
        authUsername=oldUsername;
        authPassword=oldPassword;

        msg.textContent=e.message || "Login failed.";
    }
}


function logout(){

    authUsername = null;
    authPassword = null;

    sessionStorage.removeItem("autopowerUsername");
    sessionStorage.removeItem("autopowerPassword");

    localStorage.removeItem("autopowerUsername");
    localStorage.removeItem("autopowerPassword");

    // Reload so the normal login initialization runs.
    window.location.reload();
}

async function updateStatus(){

    if(!authUsername || !authPassword) return;

    try{

        let r=await api("/api/status");
        let s=await r.json();

        let state=document.getElementById("state");
        let stateText=document.getElementById("stateText");
        let stateDot=document.getElementById("stateDot");

        const online =
            s.state === "ONLINE";

        if(state){
            state.className =
                "state " +
                (online ? "online" : "offline");
        }

        if(stateText){
            stateText.textContent =
                online ? "ONLINE" : "OFFLINE";
        }

        if(stateDot){
            stateDot.className =
                "state-dot " +
                (online ? "online" : "offline");
        }

        document.getElementById("players").textContent =
            s.players>=0
                ? s.players+" / "+s.maxPlayers
                : "--";

        document.getElementById("idleLimit").textContent =
            s.idleLimitMinutes+" min";

        const uptime =
            document.getElementById("serverUptime");

        if(uptime){

            const sec =
                Number(s.serverUptimeSeconds || 0);

            if(!online){

                uptime.textContent =
                    "Offline";

            }else{

                const d =
                    Math.floor(sec / 86400);

                const h =
                    Math.floor((sec % 86400) / 3600);

                const m =
                    Math.floor((sec % 3600) / 60);

                const ss =
                    Math.floor(sec % 60);

                if(d > 0){

                    uptime.textContent =
                        d+"d "+h+"h "+m+"m";

                }else if(h > 0){

                    uptime.textContent =
                        h+"h "+m+"m";

                }else{

                    uptime.textContent =
                        m+"m "+
                        String(ss).padStart(2,"0")+"s";
                }
            }
        }

        const countdown =
            document.getElementById("countdown");

        const idleTime =
            document.getElementById("idleTime");

        if(
            online &&
            s.players === 0 &&
            s.idleSeconds > 0
        ){

            if(countdown){
                countdown.textContent =
                    "Active";
            }

            let minutes=Math.floor(
                s.idleSeconds/60
            );

            let seconds=s.idleSeconds%60;

            if(idleTime){
                idleTime.textContent =
                    minutes+"m "+
                    String(seconds).padStart(2,"0")+"s";
            }

        }else{

            if(countdown){
                countdown.textContent =
                    "Not Active";
            }

            if(idleTime){
                idleTime.textContent =
                    "Not idle";
            }
        }

        document.getElementById("startBtn").disabled =
            s.starting || s.stopping;

        document.getElementById("stopBtn").disabled =
            s.stopping || s.starting;

    }catch(e){}
}

async function updateResources(){

    if(!authUsername || !authPassword) return;

    try{
        const r=await api("/api/resources");
        if(!r.ok) return;

        const data=await r.json();
        const x=data.resources;
        if(!x) return;

        const fmtBytes=(n)=>{
            if(!n) return "0 B";
            const u=["B","KB","MB","GB","TB"];
            let i=0;
            while(n>=1024 && i<u.length-1){n/=1024;i++;}
            return n.toFixed(i?2:0)+" "+u[i];
        };

        const ram=document.getElementById("ramUsage");
        const cpu=document.getElementById("cpuUsage");
        const disk=document.getElementById("diskUsage");
        const network=document.getElementById("networkUsage");
        if(ram) ram.textContent=fmtBytes(x.memory_bytes)+" / "+fmtBytes(x.memory_limit_bytes);
        if(cpu) cpu.textContent=Number(x.cpu_absolute||0).toFixed(1)+"%";
        if(disk) disk.textContent=fmtBytes(x.disk_bytes);
        if(network) network.textContent="↓ "+fmtBytes(x.network.rx_bytes)+"  ↑ "+fmtBytes(x.network.tx_bytes);

    }catch(e){}
}


async function action(path){

    try{

        let r=await api(
            path,
            {method:"POST"}
        );

        let data=await r.json();

        if(!r.ok){
            throw new Error(data.error||"Request failed");
        }

        showMessage(
            data.message||"Request successful."
        );

        setTimeout(updateStatus,500);

    }catch(e){

        showMessage(
            e.message,
            true
        );
    }
}

async function loadConfig(){

    try{

        let r=await api("/api/config");
        let c=await r.json();

        for(let key of [
            "calagopusUrl",
            "serverUuid",
            "apiKeyEnv",
            "backendServer",
            "statusHost",
            "statusPort",
            "statusTimeoutMillis",
            "checkIntervalSeconds",
            "startupTimeoutSeconds",
            "startupPollSeconds",
            "idleMinutes",
            "saveWaitSeconds",
            "startupMessage"
        ]){
            document.getElementById(key).value=
                c[key];
        }

        document.getElementById(
            "saveBeforeStop"
        ).checked=c.saveBeforeStop;

    }catch(e){

        showMessage(
            e.message,
            true
        );
    }
}

async function saveConfig(){

    let data=new URLSearchParams();

    for(let key of [
        "calagopusUrl",
        "serverUuid",
        "apiKeyEnv",
        "backendServer",
        "statusHost",
        "statusPort",
        "statusTimeoutMillis",
        "checkIntervalSeconds",
        "startupTimeoutSeconds",
        "startupPollSeconds",
        "idleMinutes",
        "saveWaitSeconds",
        "startupMessage"
    ]){

        data.set(
            key,
            document.getElementById(key).value
        );
    }

    if(document.getElementById(
        "saveBeforeStop"
    ).checked){

        data.set(
            "saveBeforeStop",
            "true"
        );
    }

    try{

        let r=await api(
            "/api/config",
            {
                method:"POST",
                headers:{
                    "Content-Type":
                        "application/x-www-form-urlencoded"
                },
                body:data.toString()
            }
        );

        let result=await r.json();

        if(!r.ok){
            throw new Error(
                result.error||"Failed to save configuration"
            );
        }

        showMessage(
            result.message
        );

        updateStatus();

    }catch(e){

        showMessage(
            e.message,
            true
        );
    }
}


let currentLogTab = "minecraft";
let logsPaused = false;
let logRefreshTimer = null;

function switchLogTab(tab){

    currentLogTab = tab;

    const commandBox =
        document.getElementById("minecraftCommandBox");

    if(commandBox){
        commandBox.style.display =
            tab === "minecraft" ? "flex" : "none";
    }

    const autoPowerTab =
        document.getElementById("minecraftLogTab");

    const velocityTab =
        document.getElementById("velocityLogTab");

    if(tab === "minecraft"){

        autoPowerTab.className = "";
        velocityTab.className = "secondary";

    }else{

        autoPowerTab.className = "secondary";
        velocityTab.className = "";
    }

    refreshLogs();
}

let minecraftCommandHistory = [];
let minecraftCommandHistoryIndex = -1;

async function sendMinecraftCommand(){

    const input =
        document.getElementById("minecraftCommandInput");

    const button =
        document.getElementById("minecraftCommandSend");

    if(!input || !button){
        return;
    }

    let command = input.value.trim();

    if(!command){
        return;
    }

    if(command.startsWith("/")){
        command = command.substring(1);
    }

    button.disabled = true;
    button.textContent = "SENDING...";

    try{

        const body =
            "command=" +
            encodeURIComponent(command);

        const r = await api(
            "/api/command",
            {
                method: "POST",
                headers: {
                    "Content-Type":
                        "application/x-www-form-urlencoded"
                },
                body: body
            }
        );

        const data = await r.json();

        if(!r.ok || !data.success){
            throw new Error(
                data.error || "Failed to send command"
            );
        }

        if(
            minecraftCommandHistory.length === 0 ||
            minecraftCommandHistory[
                minecraftCommandHistory.length - 1
            ] !== command
        ){
            minecraftCommandHistory.push(command);
        }

        if(minecraftCommandHistory.length > 50){
            minecraftCommandHistory.shift();
        }

        minecraftCommandHistoryIndex = -1;
        input.value = "";

        input.focus();

    }catch(e){

        showMessage(
            e.message,
            true
        );

    }finally{

        button.disabled = false;
        button.textContent = "SEND";
    }
}

function handleMinecraftCommandKey(event){

    const input =
        document.getElementById("minecraftCommandInput");

    if(!input){
        return;
    }

    if(event.key === "Enter"){

        event.preventDefault();
        sendMinecraftCommand();
        return;
    }

    if(event.key === "ArrowUp"){

        if(minecraftCommandHistory.length === 0){
            return;
        }

        event.preventDefault();

        if(
            minecraftCommandHistoryIndex === -1
        ){
            minecraftCommandHistoryIndex =
                minecraftCommandHistory.length - 1;
        }else if(
            minecraftCommandHistoryIndex > 0
        ){
            minecraftCommandHistoryIndex--;
        }

        input.value =
            minecraftCommandHistory[
                minecraftCommandHistoryIndex
            ];

        return;
    }

    if(event.key === "ArrowDown"){

        if(minecraftCommandHistory.length === 0){
            return;
        }

        event.preventDefault();

        if(
            minecraftCommandHistoryIndex === -1
        ){
            return;
        }

        if(
            minecraftCommandHistoryIndex <
            minecraftCommandHistory.length - 1
        ){

            minecraftCommandHistoryIndex++;

            input.value =
                minecraftCommandHistory[
                    minecraftCommandHistoryIndex
                ];

        }else{

            minecraftCommandHistoryIndex = -1;
            input.value = "";
        }
    }
}

function toggleMinecraftCommandAvailability(){

    const input =
        document.getElementById("minecraftCommandInput");

    const button =
        document.getElementById("minecraftCommandSend");

    if(!input || !button){
        return;
    }

    const online =
        currentLogTab === "minecraft";

    input.disabled = !online;
    button.disabled = !online;
}

function toggleLogPause(){

    logsPaused = !logsPaused;

    const button =
        document.getElementById("pauseLogsBtn");

    if(logsPaused){

        button.textContent = "▶ Resume";
        document.getElementById("logStatus").textContent =
            "Log refresh paused";

    }else{

        button.textContent = "⏸ Pause";
        refreshLogs();
    }
}

async function refreshLogs(){

    if(logsPaused || !authUsername || !authPassword){
        return;
    }

    const viewer =
        document.getElementById("logViewer");

    const status =
        document.getElementById("logStatus");

    try{

        status.textContent = "Loading...";

        const endpoint =
            currentLogTab === "minecraft"
                ? "/api/logs/minecraft"
                : "/api/logs/velocity";

        const r = await api(endpoint);

        const text = await r.text();

        if(!r.ok){
            throw new Error(text || "Failed to load logs");
        }

        const shouldScroll =
            document.getElementById("autoScrollLogs").checked;

        viewer.textContent = text;

        if(shouldScroll){
            viewer.scrollTop = viewer.scrollHeight;
        }

        const now = new Date();

        status.textContent =
            "Updated " +
            now.toLocaleTimeString() +
            " • " +
            (currentLogTab === "minecraft"
                ? "Minecraft Server Console"
                : "Velocity");

    }catch(e){

        viewer.textContent =
            "Unable to load logs.\\n\\n" +
            (e.message || "Unknown error");

        status.textContent = "Log loading failed";
    }
}

function startLogRefresh(){

    if(logRefreshTimer){
        clearInterval(logRefreshTimer);
    }

    logRefreshTimer = setInterval(
        () => {
            refreshLogs();
            updateResources();
            updateStatus();
        },
        5000
    );
}

if(rememberedLogin){
    const rememberCheckbox =
        document.getElementById("rememberMe");

    if(rememberCheckbox){
        rememberCheckbox.checked=true;
    }
}

if(authUsername && authPassword){

    document.getElementById(
        "login"
    ).style.display="none";

    document.getElementById(
        "dashboard"
    ).style.display="block";

    loadConfig();
    updateStatus();
    refreshLogs();
    startLogRefresh();
}

setInterval(
    updateStatus,
    2000
);

</script>

</body>
</html>
""";
}
