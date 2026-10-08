package com.example.wallswitch;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 只读的单文件局域网服务，手写 HTTP/1.1 里够用的一小撮，不引任何库。
 *
 * <p>只用 java.net / java.io，check/LanSyncTest.java 能在同一个进程里真起服务、真连接跑断言。
 *
 * <p>三条硬规矩：
 * <ol>
 *   <li>只认 {@code GET|HEAD /<口令>/info} 与 {@code GET|HEAD /<口令>/file}，其余一律 404/405
 *       且<b>不解释原因</b>；不给目录列表、不接受写；</li>
 *   <li>一连接一请求，{@code Connection: close}，不实现 keep-alive（一次同步最多两个请求，
 *       实现它只增加出错面）；</li>
 *   <li>{@link Server#close()} 关监听 socket 与当前正在服务的那一条连接，accept 立刻抛
 *       {@link SocketException} 让线程退出 —— Activity 退出必须调到它，否则页面关了、
 *       服务还在后台发用户的照片。</li>
 * </ol>
 *
 * <p>流量是明文的：只在局域网内、只在页面活着的时候存在、口令 8 位随机、可随时停止。
 * 不上自签证书跑 HTTPS 是明写过的取舍（见 docs/lan-sync-plan.md §1 第 9 条）。
 */
public final class LanHttp {

    static final int BACKLOG = 8;
    static final int CHUNK = 64 * 1024;
    /** 进度回调的最小字节间隔，免得每 64KB 刷一次界面。 */
    static final long PROGRESS_STEP_BYTES = 1024 * 1024;

    private LanHttp() {
    }

    /** 要发出去的东西。留成接口：将来想直接流式分享 SAF 里的现成包，不必改服务。 */
    public interface Payload {
        long length();

        InputStream open() throws IOException;
    }

    /** 本地文件实现。 */
    public static final class FilePayload implements Payload {
        private final File file;

        public FilePayload(File file) {
            this.file = file;
        }

        @Override
        public long length() {
            return file == null ? 0L : file.length();
        }

        @Override
        public InputStream open() throws IOException {
            return new FileInputStream(file);
        }
    }

    /** 界面关心的四件事，都在服务线程上回调。 */
    public interface Listener {
        void onConnected(String peer);

        void onProgress(long done, long total);

        void onFileSent(long bytes);

        void onError(String message);
    }

    /** 服务实例：{@link #start()} 之后 getPort()/getToken() 才有意义。 */
    public static final class Server implements Closeable {
        private final String token;
        private final String infoText;
        private final Payload payload;
        private volatile Listener listener;
        private volatile boolean closed;
        private volatile Socket current;
        private ServerSocket serverSocket;
        private Thread thread;

        public Server(String token, String infoText, Payload payload) {
            this.token = token;
            this.infoText = infoText;
            this.payload = payload;
        }

        public void setListener(Listener l) {
            this.listener = l;
        }

        /** 系统分配的端口；0 表示还没 start。写死端口会跟别的 App 撞，也更像后门。 */
        public int getPort() {
            ServerSocket ss = serverSocket;
            return ss == null ? 0 : ss.getLocalPort();
        }

        public String getToken() {
            return token;
        }

        public void start() throws IOException {
            serverSocket = new ServerSocket(0, BACKLOG);
            thread = new Thread(this::loop, "lan-http-accept");
            thread.start();
        }

        private void loop() {
            while (!closed) {
                Socket s = null;
                try {
                    s = serverSocket.accept();
                    current = s;
                    s.setTcpNoDelay(true);
                    Listener l = listener;
                    if (l != null) {
                        l.onConnected(s.getInetAddress().getHostAddress());
                    }
                    handle(s);
                } catch (SocketException e) {
                    return;                     // close() 的结果，正常收摊
                } catch (IOException e) {
                    Listener l = listener;
                    if (l != null && !closed) {
                        l.onError("这次没发成：" + e.getClass().getSimpleName());
                    }
                } finally {
                    closeQuietly(s);
                    current = null;
                }
            }
        }

        private void handle(Socket s) throws IOException {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            String[] line = firstLine(in);
            if (line == null) {
                return;
            }
            String method = line[0].toUpperCase(Locale.US);
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                // 写方法给 405、别的乱码给 404，都不告诉对方「这里其实有文件」
                writeHead(out, "POST".equals(method) || "PUT".equals(method)
                        || "DELETE".equals(method) ? 405 : 404,
                        "Method Not Allowed", "text/plain", 0);
                out.flush();
                return;
            }
            String what = what(line[1], token);
            if (what == null) {
                writeHead(out, 404, "Not Found", "text/plain", 0);
                out.flush();
                return;
            }
            if (what.equals("info")) {
                byte[] body = infoText.getBytes(StandardCharsets.UTF_8);
                writeHead(out, 200, "OK", "text/plain; charset=utf-8", body.length);
                out.write(body);
                out.flush();
                return;
            }
            long total = payload.length();
            writeHead(out, 200, "OK", "application/zip", total);
            if ("HEAD".equals(method)) {
                out.flush();
                return;
            }
            Listener l = listener;
            sendFile(payload.open(), out, total, l);
            if (l != null) {
                l.onFileSent(total);
            }
        }

        @Override
        public void close() {
            closed = true;
            closeQuietly(current);
            closeQuietly(serverSocket);
            Thread t = thread;
            thread = null;
            if (t != null) {
                try {
                    t.join(1000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * 读请求行，并把剩下的头部丢干净 —— 必须读到空行为止，否则对端可能因为头部没被读完而卡住。
     * 头部超长（乱码/攻击探测）直接当坏请求丢弃。
     */
    static String[] firstLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(256);
        int b;
        while ((b = in.read()) >= 0 && b != '\n') {
            if (b != '\r') {
                line.write(b);
            }
            if (line.size() > 8192) {
                return null;
            }
        }
        if (b < 0) {
            return null;
        }
        while (true) {
            ByteArrayOutputStream h = new ByteArrayOutputStream(256);
            int c;
            while ((c = in.read()) >= 0 && c != '\n') {
                if (c != '\r') {
                    h.write(c);
                }
            }
            if (c < 0 || h.size() == 0) {
                break;                          // 空行到了，头部读完
            }
            if (h.size() > 8192) {
                return null;
            }
        }
        String[] parts = new String(line.toByteArray(), StandardCharsets.US_ASCII).split(" ");
        return parts.length < 2 ? null : new String[]{parts[0], parts[1]};
    }

    /**
     * path 必须正好是 {@code /<token>/info} 或 {@code /<token>/file}；
     * 含点号或百分号一律拒（挡 {@code ../} 穿越与编码探测），多余尾斜杠容忍。
     */
    public static String what(String path, String token) {
        if (path == null || token == null || path.indexOf('.') >= 0 || path.indexOf('%') >= 0) {
            return null;
        }
        String expect = "/" + token + "/";
        if (!path.startsWith(expect)) {
            return null;
        }
        String what = path.substring(expect.length());
        if (what.endsWith("/")) {
            what = what.substring(0, what.length() - 1);
        }
        return what.equals("info") || what.equals("file") ? what : null;
    }

    static void writeHead(OutputStream out, int status, String reason, String contentType, long length)
            throws IOException {
        String head = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * 分块发出文件。对端半路关连接时 write 抛 IOException，上层当普通失败处理 ——
     * 定稿里不做断点续传，重来一次比实现 Range 便宜得多。
     */
    static void sendFile(InputStream in, OutputStream out, long total, Listener l) throws IOException {
        byte[] buf = new byte[CHUNK];
        long done = 0;
        long nextReport = PROGRESS_STEP_BYTES;
        try {
            while (done < total) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, total - done));
                if (n < 0) {
                    throw new IOException("包读到头了但只发出 " + done + " / " + total);
                }
                out.write(buf, 0, n);
                done += n;
                if (done >= nextReport) {
                    if (l != null) {
                        l.onProgress(done, total);
                    }
                    nextReport = done + PROGRESS_STEP_BYTES;
                }
            }
            out.flush();
        } finally {
            closeQuietly(in);
            if (l != null) {
                l.onProgress(done, total);
            }
        }
    }

    static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }
}
