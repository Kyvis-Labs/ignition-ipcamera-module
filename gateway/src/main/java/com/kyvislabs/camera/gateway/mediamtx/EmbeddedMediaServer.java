package com.kyvislabs.camera.gateway.mediamtx;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.kyvislabs.camera.gateway.config.MediaServerConfig;

/**
 * Runs the MediaMTX bundled in a platform build of the module as a child process of the gateway.
 *
 * <p>The binary ships in the gateway jar ({@code com/kyvislabs/camera/mediamtx/}) and is unpacked
 * once per version into {@code <data>/camera/mediamtx/<version>/}. The config is generated from
 * the module's settings: every port but WebRTC media listens on loopback only (browsers reach
 * signalling and recordings through the gateway's proxy), and MediaMTX asks the gateway to
 * authorise each request with a key that lives only in this process. A watcher restarts MediaMTX
 * with backoff if it exits; a pid file lets a later start stop an instance orphaned by a crash.
 */
public class EmbeddedMediaServer {
    private static final LoggerEx log = LoggerEx.newBuilder().build("camera.MediaMTX");
    private static final String RESOURCES = "com/kyvislabs/camera/mediamtx/";
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(60);

    /** What this build of the module bundles. */
    public record Bundle(String platform, String version, String binary) {
    }

    private final Bundle bundle;
    private final Path home;
    private final String callbackKey;
    private volatile Process process;
    private volatile boolean running;
    private volatile String config;
    private volatile String lastError;
    private volatile Instant startedAt;
    private Thread watcher;
    private final Runnable onStart;
    private volatile int apiPort;

    /** @param onStart called once each (re)started MediaMTX answers on its API, to re-add its paths */
    public EmbeddedMediaServer(Bundle bundle, Path dataDir, Runnable onStart) {
        this.bundle = bundle;
        this.onStart = onStart;
        // absolute: the gateway's data dir is relative to its working directory, and MediaMTX runs
        // with its own folder as the working directory
        this.home = dataDir.toAbsolutePath().resolve("camera").resolve("mediamtx").resolve(bundle.version());
        byte[] key = new byte[24];
        new SecureRandom().nextBytes(key);
        this.callbackKey = Base64.getUrlEncoder().withoutPadding().encodeToString(key);
    }

    /** The bundled MediaMTX, if this build has one. */
    public static Optional<Bundle> bundled() {
        try (InputStream in = EmbeddedMediaServer.class.getClassLoader().getResourceAsStream(RESOURCES + "mediamtx.properties")) {
            if (in == null) {
                return Optional.empty();
            }
            Properties p = new Properties();
            p.load(in);
            return Optional.of(new Bundle(p.getProperty("platform"), p.getProperty("version"), p.getProperty("binary")));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** This JVM's platform in the module's naming, e.g. linux-x64; null if no build covers it. */
    public static String hostPlatform() {
        return platform(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    static String platform(String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = osArch.toLowerCase(Locale.ROOT);
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
        if (os.startsWith("linux")) {
            return x64 ? "linux-x64" : arm64 ? "linux-arm64" : null;
        }
        if (os.startsWith("windows")) {
            return x64 ? "windows-x64" : null;
        }
        if (os.startsWith("mac")) {
            return arm64 ? "macos-arm64" : null;
        }
        return null;
    }

    public Bundle bundle() {
        return bundle;
    }

    /** The key MediaMTX presents to the gateway's auth callback. */
    public String callbackKey() {
        return callbackKey;
    }

    public boolean alive() {
        Process p = process;
        return p != null && p.isAlive();
    }

    public Optional<Long> pid() {
        Process p = process;
        return p != null && p.isAlive() ? Optional.of(p.pid()) : Optional.empty();
    }

    public String lastError() {
        return lastError;
    }

    public Instant startedAt() {
        return startedAt;
    }

    /**
     * Starts MediaMTX, or applies a changed config to the running one. MediaMTX re-reads its
     * config file when it changes, but a reload also drops the paths the module added through the
     * API, so the file is only rewritten when it actually differs; the reconciler re-adds them.
     */
    public synchronized void apply(MediaServerConfig s, int gatewayHttpPort, Path defaultRecordings) throws IOException {
        if (!bundle.platform().equals(hostPlatform())) {
            throw new IOException("this build bundles MediaMTX for " + bundle.platform() + " but the gateway runs on "
                + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        }
        Path binary = unpack();
        apiPort = s.embeddedApiPort();
        Path recordings = s.embeddedRecordingsPath().isEmpty() ? defaultRecordings : Path.of(s.embeddedRecordingsPath());
        Files.createDirectories(recordings);
        String yml = config(s, gatewayHttpPort, callbackKey, recordings.toAbsolutePath().toString());
        Path file = home.resolve("mediamtx.yml");
        boolean changed = !yml.equals(config);
        if (changed) {
            Path tmp = home.resolve("mediamtx.yml.tmp");
            Files.writeString(tmp, yml, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            config = yml;
        }
        if (!running) {
            running = true;
            stopOrphan();
            watcher = new Thread(() -> supervise(binary, file), "camera-mediamtx");
            watcher.setDaemon(true);
            watcher.start();
        } else if (changed && !alive()) {
            log.info("Config changed while MediaMTX is down; it starts with the new config");
        }
    }

    public synchronized void stop() {
        running = false;
        if (watcher != null) {
            watcher.interrupt();
        }
        Process p = process;
        if (p != null) {
            p.destroy();
            try {
                if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                p.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        try {
            Files.deleteIfExists(home.resolve("mediamtx.pid"));
        } catch (IOException e) {
            // harmless: a stale pid file is checked against the live process list
        }
    }

    private void supervise(Path binary, Path file) {
        long backoff = 1000;
        while (running) {
            Instant t0 = Instant.now();
            try {
                ProcessBuilder pb = new ProcessBuilder(binary.toString(), file.toString())
                    .directory(home.toFile()).redirectErrorStream(true);
                // inherited by the transcoders MediaMTX starts
                com.kyvislabs.camera.gateway.transcode.Ffmpeg.quietGpuDrivers(pb.environment());
                Process p = pb.start();
                process = p;
                startedAt = t0;
                Files.writeString(home.resolve("mediamtx.pid"), Long.toString(p.pid()));
                log.infof("MediaMTX %s (%s) started, pid %d", bundle.version(), bundle.platform(), p.pid());
                pipe(p.getInputStream());
                notifyWhenUp(p);
                int code = p.waitFor();
                if (!running) {
                    return;
                }
                lastError = "MediaMTX exited with code " + code;
                log.warnf("%s; restarting in %d s", lastError, backoff / 1000);
            } catch (InterruptedException e) {
                return;
            } catch (IOException e) {
                lastError = "could not start MediaMTX: " + e.getMessage();
                log.warnf("%s; retrying in %d s", lastError, backoff / 1000);
            }
            // a process that ran a while was healthy: start over from a short backoff
            if (Duration.between(t0, Instant.now()).compareTo(Duration.ofMinutes(1)) > 0) {
                backoff = 1000;
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                return;
            }
            backoff = Math.min(MAX_BACKOFF.toMillis(), backoff * 2);
        }
    }

    /** Waits (up to 10 s) for the API to accept connections, then tells the manager. */
    private void notifyWhenUp(Process p) throws InterruptedException {
        for (int i = 0; i < 100 && p.isAlive(); i++) {
            try (java.net.Socket ignored = new java.net.Socket("127.0.0.1", apiPort)) {
                lastError = null;
                onStart.run();
                return;
            } catch (IOException e) {
                Thread.sleep(100);
            }
        }
    }

    /** MediaMTX's log lines, at the gateway log level they carry (INF/WAR/ERR/DEB). */
    private void pipe(InputStream out) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(out, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    // "2026/10/25 18:00:13 WAR [path cam/x] ..." -> level + message
                    String msg = line.length() > 20 && line.charAt(4) == '/' ? line.substring(20) : line;
                    if (msg.startsWith("ERR ")) {
                        log.error(msg.substring(4));
                    } else if (msg.startsWith("WAR ")) {
                        log.warn(msg.substring(4));
                    } else if (msg.startsWith("DEB ")) {
                        log.debug(msg.substring(4));
                    } else if (msg.startsWith("INF ")) {
                        // per-change config reloads are the module's own API calls: noise at INFO
                        if (msg.contains("reloading configuration (API request)")) {
                            log.debug(msg.substring(4));
                        } else {
                            log.info(msg.substring(4));
                        }
                    } else {
                        // not MediaMTX's own line: output of a command it runs (a transcoder's
                        // ffmpeg, which only prints at -loglevel error)
                        log.warn(msg);
                    }
                }
            } catch (IOException e) {
                // the process ended
            }
        }, "camera-mediamtx-log");
        t.setDaemon(true);
        t.start();
    }

    /**
     * A MediaMTX left running by a gateway that died without shutting the module down holds the
     * ports; stop it, but only if the pid still belongs to our binary.
     */
    private void stopOrphan() {
        Path pidFile = home.resolve("mediamtx.pid");
        try {
            if (!Files.exists(pidFile)) {
                return;
            }
            long pid = Long.parseLong(Files.readString(pidFile).trim());
            ProcessHandle.of(pid).ifPresent(h -> {
                String cmd = h.info().command().orElse("");
                if (cmd.contains(home.getFileName().toString()) && cmd.contains("mediamtx")) {
                    log.warnf("Stopping MediaMTX left running by an earlier gateway (pid %d)", pid);
                    h.destroy();
                    try {
                        h.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (Exception e) {
                        h.destroyForcibly();
                    }
                }
            });
        } catch (Exception e) {
            log.debugf("orphan check failed: %s", e.getMessage());
        }
    }

    /** Unpacks the binary unless this version is already there, complete. */
    private Path unpack() throws IOException {
        Files.createDirectories(home);
        Path target = home.resolve(bundle.binary());
        String resource = RESOURCES + bundle.binary();
        long size;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("the module has no " + resource);
            }
            if (Files.exists(target) && Files.size(target) == sizeOf(resource)) {
                return target;
            }
            Path tmp = home.resolve(bundle.binary() + ".part");
            size = Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            if (!tmp.toFile().setExecutable(true, true) && !bundle.binary().endsWith(".exe")) {
                throw new IOException("could not make " + tmp + " executable");
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        log.infof("Unpacked MediaMTX %s (%s, %d MB) to %s", bundle.version(), bundle.platform(), size >> 20, home);
        return target;
    }

    private long sizeOf(String resource) throws IOException {
        var url = getClass().getClassLoader().getResource(resource);
        if (url == null) {
            return -1;
        }
        var conn = url.openConnection();
        conn.setUseCaches(false);
        return conn.getContentLengthLong();
    }

    /** The MediaMTX config for the embedded instance. */
    static String config(MediaServerConfig s, int gatewayHttpPort, String key, String recordings) {
        List<String> hosts = new ArrayList<>(s.embeddedHosts());
        StringBuilder y = new StringBuilder();
        y.append("# Written by the Ignition camera module; edit its settings instead.\n");
        y.append("logLevel: info\nlogDestinations: [stdout]\n");
        y.append("api: yes\napiAddress: 127.0.0.1:").append(s.embeddedApiPort()).append('\n');
        y.append("metrics: no\npprof: no\n");
        y.append("rtsp: yes\nrtspAddress: 127.0.0.1:").append(s.embeddedRtspPort()).append("\nrtspTransports: [tcp]\n");
        y.append("rtmp: no\nsrt: no\nmoq: no\n");
        // low-latency HLS: 1 s segments in 200 ms parts, which is what keeps it near 1-2 s behind
        y.append("hls: yes\nhlsAddress: 127.0.0.1:").append(s.embeddedHlsPort()).append('\n');
        y.append("hlsVariant: lowLatency\nhlsSegmentCount: 7\nhlsSegmentDuration: 1s\nhlsPartDuration: 200ms\n");
        y.append("webrtc: yes\nwebrtcAddress: 127.0.0.1:").append(s.embeddedWebrtcPort()).append('\n');
        y.append("webrtcLocalUDPAddress: :").append(s.embeddedIcePort()).append('\n');
        y.append("webrtcLocalTCPAddress: :").append(s.embeddedIcePort()).append('\n');
        y.append("webrtcIPsFromInterfaces: yes\n");
        y.append("webrtcAdditionalHosts: [").append(String.join(", ", hosts.stream().map(EmbeddedMediaServer::quote).toList())).append("]\n");
        y.append("playback: yes\nplaybackAddress: 127.0.0.1:").append(s.embeddedPlaybackPort()).append('\n');
        y.append("authMethod: http\n");
        y.append("authHTTPAddress: ").append(quote("http://127.0.0.1:" + gatewayHttpPort + "/data/camera/auth?key=" + key)).append('\n');
        y.append("authHTTPExclude:\n  - action: api\n  - action: metrics\n  - action: pprof\n");
        y.append("pathDefaults:\n");
        y.append("  recordPath: ").append(quote(recordings.replace('\\', '/') + "/%path/%Y-%m-%d_%H-%M-%S-%f")).append('\n');
        y.append("  recordFormat: fmp4\n  recordPartDuration: 1s\n");
        y.append("paths: {}\n");
        return y.toString();
    }

    /** A YAML double-quoted scalar. */
    static String quote(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
