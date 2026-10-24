package com.potatotv.pacc.cluster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RedisClient 连接池与命令集测试。用进程内假 RESP 服务端，不依赖本机 Redis。
 */
class RedisClientPoolTest {

    private FakeRedisServer server;
    private RedisClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeRedisServer();
        client = new RedisClient("127.0.0.1", server.port(), "", 2000, 4);
    }

    @AfterEach
    void tearDown() {
        client.close();
        server.close();
    }

    @Test
    void pooledConnectionsAreReusedUnderConcurrency() throws Exception {
        int tasks = 200;
        ExecutorService workers = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(tasks);
        List<Long> results = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < tasks; i++) {
            workers.execute(() -> {
                try {
                    start.await();
                    results.add(client.incr("counter"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS), "并发命令未在超时内完成");
        workers.shutdownNow();

        assertEquals(tasks, results.size());
        assertEquals(1L, results.stream().mapToLong(Long::longValue).min().orElse(0L));
        assertEquals(tasks, results.stream().mapToLong(Long::longValue).max().orElse(0L));
        assertTrue(server.acceptedConnections() <= 4,
                "连接池上限未被遵守，实际建连 " + server.acceptedConnections());
    }

    @Test
    void stringAndExpiryCommandsRoundTrip() {
        client.set("k", "v");
        assertEquals("v", client.get("k"));

        assertTrue(client.setNxEx("lock", "owner-1", 30));
        assertTrue(!client.setNxEx("lock", "owner-2", 30), "NX 不应重复抢锁");
        assertEquals("owner-1", client.get("lock"));

        assertTrue(client.expire("k", 10));
        assertEquals(1L, client.del("k"));
        assertNull(client.get("k"));
    }

    @Test
    void listQueueSupportsPushAndBlockingPop() {
        client.lpush("alerts", "a1", "a2");
        assertEquals("a1", client.brpop(1, "alerts"));
        assertEquals("a2", client.brpop(1, "alerts"));
        assertNull(client.brpop(1, "alerts"));
    }

    @Test
    void setCommandsSupportDeduplication() {
        assertEquals(1L, client.sadd("seen", "id-1"));
        assertEquals(0L, client.sadd("seen", "id-1"));
        assertEquals(1L, client.scard("seen"));
        assertEquals(List.of("id-1"), client.smembers("seen"));
        assertEquals(1L, client.srem("seen", "id-1"));
    }

    /** 最小 RESP2 服务端：只实现测试用到的命令。 */
    private static final class FakeRedisServer implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final ExecutorService workers = Executors.newCachedThreadPool();
        private final AtomicInteger accepted = new AtomicInteger();
        private final Map<String, String> values = new ConcurrentHashMap<>();
        private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
        private final Map<String, Deque<String>> lists = new ConcurrentHashMap<>();
        private final Map<String, Map<String, String>> sets = new ConcurrentHashMap<>();
        private volatile boolean running = true;

        private FakeRedisServer() throws IOException {
            serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread acceptor = new Thread(this::acceptLoop, "fake-redis-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        private int port() {
            return serverSocket.getLocalPort();
        }

        private int acceptedConnections() {
            return accepted.get();
        }

        private void acceptLoop() {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    accepted.incrementAndGet();
                    workers.execute(() -> serve(socket));
                } catch (IOException e) {
                    return;
                }
            }
        }

        private void serve(Socket socket) {
            try (Socket s = socket;
                 InputStream raw = s.getInputStream();
                 OutputStream out = s.getOutputStream()) {
                DataInputStream in = new DataInputStream(raw);
                String[] command;
                while (running && (command = readCommand(in)) != null) {
                    handle(command, out);
                }
            } catch (IOException ignored) {
                // 客户端关闭连接
            }
        }

        private void handle(String[] command, OutputStream out) throws IOException {
            String name = command[0].toUpperCase(Locale.ROOT);
            switch (name) {
                case "AUTH", "SET" -> {
                    if ("SET".equals(name) && command.length >= 5 && "NX".equalsIgnoreCase(command[3])) {
                        if (values.putIfAbsent(command[1], command[2]) != null) {
                            nilBulk(out);
                        } else {
                            simple(out, "OK");
                        }
                    } else if ("SET".equals(name)) {
                        values.put(command[1], command[2]);
                        simple(out, "OK");
                    } else {
                        simple(out, "OK");
                    }
                }
                case "GET" -> {
                    String value = values.get(command[1]);
                    if (value == null) {
                        nilBulk(out);
                    } else {
                        bulk(out, value);
                    }
                }
                case "EXPIRE" -> integer(out, 1);
                case "DEL" -> {
                    long removed = 0;
                    for (int i = 1; i < command.length; i++) {
                        if (values.remove(command[i]) != null) removed++;
                    }
                    integer(out, removed);
                }
                case "INCR" -> integer(out,
                        counters.computeIfAbsent(command[1], k -> new AtomicLong()).incrementAndGet());
                case "INCRBY" -> integer(out,
                        counters.computeIfAbsent(command[1], k -> new AtomicLong())
                                .addAndGet(Long.parseLong(command[2])));
                case "LPUSH" -> {
                    Deque<String> list = lists.computeIfAbsent(command[1], k -> new ArrayDeque<>());
                    for (int i = 2; i < command.length; i++) {
                        list.addFirst(command[i]);
                    }
                    integer(out, list.size());
                }
                case "RPOP" -> {
                    Deque<String> list = lists.get(command[1]);
                    String value = list == null ? null : list.pollLast();
                    if (value == null) nilBulk(out); else bulk(out, value);
                }
                case "BRPOP" -> {
                    Deque<String> list = lists.get(command[1]);
                    String value = list == null ? null : list.pollLast();
                    if (value == null) {
                        out.write("*-1\r\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } else {
                        array(out, command[1], value);
                    }
                }
                case "SADD" -> {
                    Map<String, String> set = sets.computeIfAbsent(command[1], k -> new ConcurrentHashMap<>());
                    long added = 0;
                    for (int i = 2; i < command.length; i++) {
                        if (set.putIfAbsent(command[i], command[i]) == null) added++;
                    }
                    integer(out, added);
                }
                case "SREM" -> {
                    Map<String, String> set = sets.computeIfAbsent(command[1], k -> new ConcurrentHashMap<>());
                    long removed = 0;
                    for (int i = 2; i < command.length; i++) {
                        if (set.remove(command[i]) != null) removed++;
                    }
                    integer(out, removed);
                }
                case "SCARD" -> integer(out, sets.getOrDefault(command[1], Map.of()).size());
                case "SMEMBERS" -> {
                    List<String> members = new ArrayList<>(sets.getOrDefault(command[1], Map.of()).keySet());
                    array(out, members.toArray(String[]::new));
                }
                case "PING" -> simple(out, "PONG");
                case "PUBLISH" -> integer(out, 0);
                case "LLEN" -> integer(out, lists.getOrDefault(command[1], new ArrayDeque<>()).size());
                default -> error(out, "unsupported " + name);
            }
        }

        private static String[] readCommand(DataInputStream in) throws IOException {
            int prefix = in.read();
            if (prefix < 0) {
                return null;
            }
            if (prefix != '*') {
                throw new IOException("unexpected RESP prefix " + prefix);
            }
            int count = Integer.parseInt(readLine(in));
            String[] args = new String[count];
            for (int i = 0; i < count; i++) {
                int bulk = in.read();
                if (bulk != '$') {
                    throw new IOException("unexpected bulk prefix " + bulk);
                }
                int length = Integer.parseInt(readLine(in));
                byte[] buffer = in.readNBytes(length);
                in.skipNBytes(2);
                args[i] = new String(buffer, StandardCharsets.UTF_8);
            }
            return args;
        }

        private static String readLine(DataInputStream in) throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) >= 0 && b != '\n') {
                if (b != '\r') buffer.write(b);
            }
            return buffer.toString(StandardCharsets.UTF_8);
        }

        private static void simple(OutputStream out, String value) throws IOException {
            write(out, "+" + value + "\r\n");
        }

        private static void error(OutputStream out, String value) throws IOException {
            write(out, "-" + value + "\r\n");
        }

        private static void integer(OutputStream out, long value) throws IOException {
            write(out, ":" + value + "\r\n");
        }

        private static void bulk(OutputStream out, String value) throws IOException {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            write(out, "$" + bytes.length + "\r\n" + value + "\r\n");
        }

        private static void nilBulk(OutputStream out) throws IOException {
            write(out, "$-1\r\n");
        }

        private static void array(OutputStream out, String... values) throws IOException {
            StringBuilder frame = new StringBuilder("*" + values.length + "\r\n");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                frame.append('$').append(bytes.length).append("\r\n").append(value).append("\r\n");
            }
            write(out, frame.toString());
        }

        private static void write(OutputStream out, String frame) throws IOException {
            out.write(frame.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        @Override
        public void close() {
            running = false;
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // 测试清理
            }
            workers.shutdownNow();
        }
    }
}
