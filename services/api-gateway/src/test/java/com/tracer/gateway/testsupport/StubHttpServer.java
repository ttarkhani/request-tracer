package com.tracer.gateway.testsupport;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

/** Minimal real HTTP stub (JDK built-in server) that records every request. Test-only. */
public final class StubHttpServer implements AutoCloseable {

    public record Recorded(String method, String path, Map<String, List<String>> headers, String body) {
        public String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue().isEmpty() ? null : e.getValue().get(0);
                }
            }
            return null;
        }
    }

    private record Reply(int status, String body) {}

    private final HttpServer server;
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();

    public StubHttpServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getPath();
            requests.add(new Recorded(exchange.getRequestMethod(), path, Map.copyOf(exchange.getRequestHeaders()), body));
            Reply reply = replies.getOrDefault(path, new Reply(200, "stored"));
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type",
                    reply.body().startsWith("{") ? "application/json" : "text/plain");
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    public StubHttpServer reply(String path, int status, String body) {
        replies.put(path, new Reply(status, body));
        return this;
    }

    public String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    public List<Recorded> requests(String path) {
        List<Recorded> out = new ArrayList<>();
        for (Recorded r : requests) {
            if (r.path().equals(path)) {
                out.add(r);
            }
        }
        return out;
    }

    public void clear() {
        requests.clear();
    }

    /** Waits until at least {@code count} requests to {@code path} match, then returns all matching ones. */
    public List<Recorded> await(String path, Predicate<Recorded> filter, int count, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            List<Recorded> matching = new ArrayList<>();
            for (Recorded r : requests(path)) {
                if (filter.test(r)) {
                    matching.add(r);
                }
            }
            if (matching.size() >= count || System.nanoTime() > deadline) {
                return matching;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return matching;
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** A port with nothing listening on it (connection refused). */
    public static int closedPort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Accepts TCP connections and never sends a byte back: a hung dependency. */
    public static final class HangingServer implements AutoCloseable {
        private final ServerSocket socket;
        private final List<Socket> held = new CopyOnWriteArrayList<>();

        public HangingServer() {
            try {
                socket = new ServerSocket(0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            Thread acceptor = new Thread(() -> {
                while (!socket.isClosed()) {
                    try {
                        Socket s = socket.accept();
                        held.add(s);
                        InputStream in = s.getInputStream();
                        Thread drain = new Thread(() -> {
                            try {
                                in.transferTo(OutputStream.nullOutputStream());
                            } catch (IOException ignored) {
                            }
                        });
                        drain.setDaemon(true);
                        drain.start();
                    } catch (IOException e) {
                        return;
                    }
                }
            }, "hanging-server");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        public String url(String path) {
            return "http://127.0.0.1:" + socket.getLocalPort() + path;
        }

        public int connectionsAccepted() {
            return held.size();
        }

        @Override
        public void close() throws IOException {
            socket.close();
            for (Socket s : held) {
                s.close();
            }
        }
    }
}
