// hi-debug lab 應用程式：用環境變數 LAB 選擇要重現的故障情境。
// 練習時請先不要看這個檔案，把它當成「拿不到原始碼的第三方應用」。
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;

public class App {

    public static void main(String[] args) throws Exception {
        String lab = System.getenv().getOrDefault("LAB", "none");
        log("starting, mode=" + lab);
        switch (lab) {
            case "1" -> configApp();
            case "2" -> localhostApp();
            case "3" -> deadlockApp();
            case "4" -> nativeLeakApp();
            case "5-backend" -> backendApp();
            case "5-frontend" -> frontendApp();
            default -> serve("0.0.0.0", 8080, "ok");
        }
    }

    // ---------- Lab 1：設定檔讀不到 ----------
    static void configApp() throws Exception {
        String path = System.getenv().getOrDefault("APP_CONFIG", "/etc/app/app.properties");
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            p.load(in);
        } catch (IOException e) {
            log("FATAL: unable to load configuration, exiting");
            System.exit(3);
        }
        serve("0.0.0.0", 8080, "greeting=" + p.getProperty("greeting", "?"));
    }

    // ---------- Lab 2：只聽 localhost ----------
    static void localhostApp() throws Exception {
        String bind = System.getenv().getOrDefault("BIND_ADDR", "127.0.0.1");
        serve(bind, 8080, "hello from lab2");
    }

    // ---------- Lab 3：deadlock ----------
    static final Object CACHE_LOCK = new Object();
    static final Object METRICS_LOCK = new Object();

    static void deadlockApp() throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("0.0.0.0", 8080), 0);
        s.setExecutor(Executors.newCachedThreadPool());
        s.createContext("/healthz", ex -> {
            synchronized (CACHE_LOCK) {
                reply(ex, 200, "ok");
            }
        });
        s.start();
        log("listening on 0.0.0.0:8080");

        Thread.sleep(Duration.ofSeconds(30));
        new Thread(() -> {
            synchronized (CACHE_LOCK) {
                sleep(500);
                synchronized (METRICS_LOCK) { log("cache refreshed"); }
            }
        }, "cache-refresher").start();
        new Thread(() -> {
            synchronized (METRICS_LOCK) {
                sleep(500);
                synchronized (CACHE_LOCK) { log("metrics flushed"); }
            }
        }, "metrics-flusher").start();
    }

    // ---------- Lab 4：native（direct buffer）記憶體洩漏 ----------
    static final List<ByteBuffer> SESSIONS = new ArrayList<>();

    static void nativeLeakApp() throws Exception {
        serve("0.0.0.0", 8080, "ok");
        while (true) {
            ByteBuffer b = ByteBuffer.allocateDirect(1024 * 1024);
            for (int i = 0; i < b.capacity(); i += 4096) b.put(i, (byte) 1);
            SESSIONS.add(b);
            if (SESSIONS.size() % 30 == 0) log("active sessions: " + SESSIONS.size());
            Thread.sleep(1000);
        }
    }

    // ---------- Lab 5：上游服務連不到 ----------
    static void backendApp() throws Exception {
        serve("0.0.0.0", 9090, "pong");
    }

    static void frontendApp() throws Exception {
        serve("0.0.0.0", 8080, "frontend");
        String upstream = System.getenv().getOrDefault("UPSTREAM_URL", "http://backend:9091/ping");
        HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        while (true) {
            try {
                HttpResponse<String> r = c.send(
                        HttpRequest.newBuilder(URI.create(upstream)).timeout(Duration.ofSeconds(3)).build(),
                        HttpResponse.BodyHandlers.ofString());
                log("upstream ok: " + r.body());
            } catch (Exception e) {
                log("ERROR: upstream unavailable");
            }
            Thread.sleep(5000);
        }
    }

    // ---------- 共用 ----------
    // HttpServer 的執行緒不是 daemon，所以 serve() 回傳後 JVM 仍會持續執行
    static void serve(String host, int port, String body) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(host, port), 0);
        s.createContext("/", ex -> reply(ex, 200, body));
        s.start();
        log("listening on " + host + ":" + port);
    }

    static void reply(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws IOException {
        byte[] b = (body + "\n").getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length);
        try (var o = ex.getResponseBody()) { o.write(b); }
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    static void log(String m) {
        System.out.println(LocalTime.now().withNano(0) + " " + m);
    }
}
