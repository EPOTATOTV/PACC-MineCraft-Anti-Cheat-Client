package com.potatotv.pacc.cluster;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 极简 Redis 客户端（纯 JDK，RESP2）。
 *
 * <p>命令集覆盖限流 / OTP / 告警队列 / 热缓存 / 会话状态 / 分布式锁 / 指标聚合所需：
 * ZADD、ZREMRANGEBYSCORE、ZCARD、DEL、HSET、HGETALL、EXPIRE、AUTH、SET（含 NX / EX）、GET、
 * INCR、INCRBY、LPUSH、RPOP、BRPOP、SADD、SREM、SCARD、SMEMBERS、PUBLISH。</p>
 *
 * <p>连接按线程借用并归还，空闲连接复用；池满时按等待超时阻塞，超时抛错而不是无限挂起。
 * 单次命令失败只丢弃当前连接，不影响池内其它连接。</p>
 */
public final class RedisClient implements Closeable {

    /** 默认连接池大小；对齐 Spring MVC 常见线程数，避免每个请求都新建 TCP 连接。 */
    public static final int DEFAULT_POOL_SIZE = 16;

    /** 空闲超过该时长后，借出前先发 PING 探活，避免拿到被服务端断开的连接。 */
    private static final long IDLE_PROBE_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final String host;
    private final int port;
    private final String password;
    private final int timeoutMs;
    private final int poolSize;

    private final ArrayDeque<Connection> idle = new ArrayDeque<>();
    private final Set<Connection> all = ConcurrentHashMap.newKeySet();
    private final AtomicInteger total = new AtomicInteger();
    private final Object poolLock = new Object();
    private volatile boolean closed;

    public RedisClient(String host, int port, String password, int timeoutMs, int poolSize) {
        this.host = host;
        this.port = port;
        this.password = password == null ? "" : password;
        this.timeoutMs = Math.max(100, timeoutMs);
        this.poolSize = Math.max(1, poolSize);
    }

    /** 执行一段 RESP2 命令并返回解析后的结果：Long / String / null / java.util.List. */
    public Object execute(String... args) {
        Connection connection = borrow();
        boolean reusable = false;
        try {
            Object reply = connection.execute(args, 0);
            reusable = true;
            return reply;
        } finally {
            release(connection, reusable);
        }
    }

    // -------------------------------- 字符串 / 过期 --------------------------------

    public String get(String key) {
        return asString(execute("GET", key));
    }

    /** 读取并删除（Redis 6.2+ GETDEL）；用于一次性挑战等场景。 */
    public String getdel(String key) {
        return asString(execute("GETDEL", key));
    }

    public void set(String key, String value) {
        execute("SET", key, value);
    }

    public void setEx(String key, String value, long ttlSeconds) {
        execute("SET", key, value, "EX", String.valueOf(Math.max(1, ttlSeconds)));
    }

    /** SET NX EX：抢到返回 true，键已存在返回 false。分布式锁的基础原语。 */
    public boolean setNxEx(String key, String value, long ttlSeconds) {
        return "OK".equals(asString(execute("SET", key, value,
                "NX", "EX", String.valueOf(Math.max(1, ttlSeconds)))));
    }

    public boolean expire(String key, long ttlSeconds) {
        return asLong(execute("EXPIRE", key, String.valueOf(Math.max(1, ttlSeconds)))) == 1L;
    }

    public long del(String... keys) {
        if (keys == null || keys.length == 0) {
            return 0L;
        }
        String[] args = new String[keys.length + 1];
        args[0] = "DEL";
        System.arraycopy(keys, 0, args, 1, keys.length);
        return asLong(execute(args));
    }

    public long incr(String key) {
        return asLong(execute("INCR", key));
    }

    public long incrBy(String key, long delta) {
        return asLong(execute("INCRBY", key, String.valueOf(delta)));
    }

    /** 批量取值；不存在的键在返回列表中对应 null。 */
    public List<String> mget(String... keys) {
        if (keys == null || keys.length == 0) {
            return List.of();
        }
        String[] args = new String[keys.length + 1];
        args[0] = "MGET";
        System.arraycopy(keys, 0, args, 1, keys.length);
        List<?> raw = asList(execute(args));
        List<String> values = new ArrayList<>(raw.size());
        for (Object item : raw) {
            values.add(asString(item));
        }
        return values;
    }

    // -------------------------------- 列表（告警队列） --------------------------------

    public long lpush(String key, String... values) {
        if (values == null || values.length == 0) {
            return 0L;
        }
        String[] args = new String[values.length + 2];
        args[0] = "LPUSH";
        args[1] = key;
        System.arraycopy(values, 0, args, 2, values.length);
        return asLong(execute(args));
    }

    public String rpop(String key) {
        return asString(execute("RPOP", key));
    }

    /** BRPOP 单键阻塞出队；超时返回 null。 */
    public String brpop(long timeoutSeconds, String key) {
        long safeTimeout = Math.max(1, timeoutSeconds);
        Object reply = executeBlocking((int) Math.min(Integer.MAX_VALUE, (safeTimeout + 2) * 1000L),
                "BRPOP", key, String.valueOf(safeTimeout));
        List<?> values = asList(reply);
        // BRPOP 返回 [key, value]；超时返回 null（RESP 空数组）
        return values.size() == 2 ? asString(values.get(1)) : null;
    }

    public long llen(String key) {
        return asLong(execute("LLEN", key));
    }

    /** 裁剪列表，保留 [start, stop] 区间；用于给死信队列设上限。 */
    public void ltrim(String key, long start, long stop) {
        execute("LTRIM", key, String.valueOf(start), String.valueOf(stop));
    }

    // -------------------------------- 集合（去重 / 设备） --------------------------------

    public long sadd(String key, String... members) {
        if (members == null || members.length == 0) {
            return 0L;
        }
        String[] args = new String[members.length + 2];
        args[0] = "SADD";
        args[1] = key;
        System.arraycopy(members, 0, args, 2, members.length);
        return asLong(execute(args));
    }

    public long srem(String key, String... members) {
        if (members == null || members.length == 0) {
            return 0L;
        }
        String[] args = new String[members.length + 2];
        args[0] = "SREM";
        args[1] = key;
        System.arraycopy(members, 0, args, 2, members.length);
        return asLong(execute(args));
    }

    public long scard(String key) {
        return asLong(execute("SCARD", key));
    }

    public List<String> smembers(String key) {
        List<?> raw = asList(execute("SMEMBERS", key));
        List<String> values = new ArrayList<>(raw.size());
        for (Object item : raw) {
            String value = asString(item);
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    // -------------------------------- 发布订阅 --------------------------------

    public long publish(String channel, String message) {
        return asLong(execute("PUBLISH", channel, message));
    }

    /**
     * 订阅一个频道。返回的订阅持有独立连接，不占连接池；断线后自动重连。
     * 回调在订阅线程执行，调用方不要在里面做长时间阻塞操作。
     */
    public Subscription subscribe(String channel, java.util.function.Consumer<String> onMessage) {
        Subscription subscription = new Subscription(host, port, password, timeoutMs, channel, onMessage);
        subscription.start();
        return subscription;
    }

    // -------------------------------- 连接池 --------------------------------

    private Object executeBlocking(int socketTimeoutMs, String... args) {
        Connection connection = borrow();
        boolean reusable = false;
        try {
            Object reply = connection.execute(args, socketTimeoutMs);
            reusable = true;
            return reply;
        } finally {
            release(connection, reusable);
        }
    }

    private Connection borrow() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            Connection pooled = null;
            boolean create = false;
            synchronized (poolLock) {
                if (closed) {
                    throw new IllegalStateException("Redis 客户端已关闭");
                }
                pooled = idle.pollFirst();
                if (pooled == null) {
                    if (total.get() < poolSize) {
                        total.incrementAndGet();
                        create = true;
                    } else {
                        long remaining = deadline - System.nanoTime();
                        if (remaining <= 0L) {
                            throw new IllegalStateException("Redis 连接池等待超时: " + host + ":" + port);
                        }
                        try {
                            TimeUnit.NANOSECONDS.timedWait(poolLock, remaining);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Redis 连接池等待被中断", e);
                        }
                        continue;
                    }
                }
            }
            if (create) {
                try {
                    Connection connection = new Connection(host, port, password, timeoutMs);
                    synchronized (poolLock) {
                        if (closed) {
                            connection.closeQuietly();
                            total.decrementAndGet();
                            poolLock.notifyAll();
                            throw new IllegalStateException("Redis 客户端已关闭");
                        }
                        all.add(connection);
                    }
                    return connection;
                } catch (IOException e) {
                    synchronized (poolLock) {
                        total.decrementAndGet();
                        poolLock.notifyAll();
                    }
                    throw new IllegalStateException("Redis 连接失败: " + host + ":" + port, e);
                }
            }
            if (validate(pooled)) {
                return pooled;
            }
            discard(pooled);
        }
    }

    /** 空闲连接借出前探活；刚归还的连接跳过 PING，避免每条命令都多一次往返。 */
    private boolean validate(Connection connection) {
        if (System.nanoTime() - connection.idleSinceNanos < IDLE_PROBE_NANOS) {
            return true;
        }
        try {
            Object reply = connection.execute(new String[]{"PING"}, timeoutMs);
            return "PONG".equals(asString(reply));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void discard(Connection connection) {
        synchronized (poolLock) {
            all.remove(connection);
            total.decrementAndGet();
            poolLock.notifyAll();
        }
        connection.closeQuietly();
    }

    private void release(Connection connection, boolean reusable) {
        synchronized (poolLock) {
            if (reusable && !closed && idle.size() < poolSize) {
                connection.idleSinceNanos = System.nanoTime();
                idle.addLast(connection);
            } else {
                all.remove(connection);
                total.decrementAndGet();
                connection.closeQuietly();
            }
            poolLock.notifyAll();
        }
    }

    @Override
    public void close() {
        List<Connection> toClose;
        synchronized (poolLock) {
            if (closed) {
                return;
            }
            closed = true;
            toClose = new ArrayList<>(all);
            all.clear();
            idle.clear();
            total.set(0);
            poolLock.notifyAll();
        }
        for (Connection connection : toClose) {
            connection.closeQuietly();
        }
    }

    // -------------------------------- RESP2 --------------------------------

    /** 频道订阅：独立连接 + 自动重连。close 幂等。 */
    public static final class Subscription implements Closeable {

        private static final long RECONNECT_DELAY_MS = 1000L;

        private final String host;
        private final int port;
        private final String password;
        private final int timeoutMs;
        private final String channel;
        private final java.util.function.Consumer<String> onMessage;
        private final java.util.concurrent.atomic.AtomicBoolean running =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        private volatile Socket socket;
        private volatile Thread thread;

        private Subscription(String host, int port, String password, int timeoutMs,
                             String channel, java.util.function.Consumer<String> onMessage) {
            this.host = host;
            this.port = port;
            this.password = password;
            this.timeoutMs = timeoutMs;
            this.channel = channel;
            this.onMessage = onMessage;
        }

        private void start() {
            if (!running.compareAndSet(false, true)) {
                return;
            }
            thread = Thread.ofVirtual().name("pacc-redis-sub-" + channel).start(this::loop);
        }

        private void loop() {
            while (running.get()) {
                Socket created = null;
                try {
                    created = new Socket();
                    created.connect(new InetSocketAddress(host, port), timeoutMs);
                    created.setSoTimeout(0); // 订阅是阻塞读，由 close 打断
                    socket = created;
                    java.io.OutputStream out = created.getOutputStream();
                    DataInputStream in = new DataInputStream(created.getInputStream());
                    if (!password.isBlank()) {
                        out.write(encode(new String[]{"AUTH", password}));
                        out.flush();
                        readReply(in);
                    }
                    out.write(encode(new String[]{"SUBSCRIBE", channel}));
                    out.flush();
                    readReply(in); // 订阅确认
                    while (running.get()) {
                        Object reply = readReply(in);
                        if (reply instanceof List<?> frame && frame.size() >= 3
                                && "message".equals(asString(frame.get(0)))) {
                            String payload = asString(frame.get(2));
                            if (payload != null) {
                                onMessage.accept(payload);
                            }
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    if (!running.get()) {
                        return;
                    }
                } finally {
                    closeSocket(created);
                }
                if (running.get()) {
                    try {
                        Thread.sleep(RECONNECT_DELAY_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }

        @Override
        public void close() {
            if (!running.compareAndSet(true, false)) {
                return;
            }
            closeSocket(socket);
            Thread worker = thread;
            if (worker != null) {
                worker.interrupt();
            }
        }

        private static void closeSocket(Socket socket) {
            if (socket == null) {
                return;
            }
            try {
                socket.close();
            } catch (IOException ignored) {
                // 关闭失败没有补救手段
            }
        }
    }

    private static final class Connection {

        private final Socket socket;
        private final DataInputStream in;
        private final java.io.OutputStream out;
        private final int defaultTimeoutMs;
        private volatile long idleSinceNanos = System.nanoTime();

        private Connection(String host, int port, String password, int timeoutMs) throws IOException {
            Socket created = new Socket();
            try {
                created.connect(new InetSocketAddress(host, port), timeoutMs);
                created.setSoTimeout(timeoutMs);
                this.defaultTimeoutMs = timeoutMs;
                this.socket = created;
                this.out = created.getOutputStream();
                this.in = new DataInputStream(created.getInputStream());
                if (!password.isBlank()) {
                    out.write(encode(new String[]{"AUTH", password}));
                    out.flush();
                    readReply(in); // 认证失败在此抛出
                }
            } catch (IOException | RuntimeException e) {
                created.close();
                throw e;
            }
        }

        private Object execute(String[] args, int socketTimeoutMs) {
            try {
                if (socketTimeoutMs > 0) {
                    socket.setSoTimeout(socketTimeoutMs);
                }
                out.write(encode(args));
                out.flush();
                return readReply(in);
            } catch (IOException e) {
                throw new IllegalStateException("Redis 命令执行失败: " + args[0], e);
            } finally {
                if (socketTimeoutMs > 0) {
                    try {
                        socket.setSoTimeout(defaultTimeoutMs);
                    } catch (IOException ignored) {
                        // 连接已坏，归还时会被丢弃，这里的恢复失败不影响调用方
                    }
                }
            }
        }

        private void closeQuietly() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 关闭失败没有补救手段
            }
        }
    }

    private static byte[] encode(String[] args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, ("*" + args.length + "\r\n"));
        for (String arg : args) {
            byte[] b = arg.getBytes(StandardCharsets.UTF_8);
            write(out, ("$" + b.length + "\r\n"));
            out.writeBytes(b);
            write(out, "\r\n");
        }
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    private static Object readReply(DataInputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new IOException("连接被对端关闭");
        return switch (b) {
            case '+' -> readLine(in);                     // Simple String
            case '-' -> throw new IllegalStateException("Redis 错误: " + readLine(in));
            case ':' -> readLong(readLine(in));           // Integer
            case '$' -> readBulk(in);                     // Bulk String
            case '*' -> readArray(in);                    // Array
            default -> throw new IOException("未知 RESP 前缀: " + (char) b);
        };
    }

    private static String readBulk(DataInputStream in) throws IOException {
        long len = readLong(readLine(in));
        if (len < 0) return null;
        byte[] buf = in.readNBytes((int) len);
        in.skipNBytes(2); // trailing CRLF
        return new String(buf, StandardCharsets.UTF_8);
    }

    private static List<Object> readArray(DataInputStream in) throws IOException {
        long n = readLong(readLine(in));
        if (n < 0) return null;
        List<Object> list = new ArrayList<>((int) Math.min(n, 1024));
        for (long i = 0; i < n; i++) list.add(readReply(in));
        return list;
    }

    private static String readLine(DataInputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1 && c != '\n') {
            if (c != '\r') sb.append((char) c);
        }
        return sb.toString();
    }

    /** RESP 整数解析：对端返回非数字时按协议错误处理，不向调用方抛 NFE。 */
    private static long readLong(String line) throws IOException {
        try {
            return Long.parseLong(line);
        } catch (NumberFormatException e) {
            throw new IOException("Redis 协议整数非法: " + line);
        }
    }

    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }

    private static long asLong(Object value) {
        return value instanceof Long l ? l : 0L;
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }
}
