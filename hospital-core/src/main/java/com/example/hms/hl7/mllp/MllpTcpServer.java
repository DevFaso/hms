package com.example.hms.hl7.mllp;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plain-JDK MLLP TCP server. One accept thread + a bounded worker pool for
 * connection handling. Designed to coexist with the rest of the Spring app
 * without adding a heavy integration framework.
 *
 * <p>The bean is only registered when {@code app.hl7.mllp.enabled=true}.
 * In test profile and in {@code local-h2} we keep it off to avoid binding
 * a privileged port and to keep CI isolated.
 *
 * <p>The worker pool is sized at {@link MllpProperties#getMaxConcurrentConnections()}
 * with a {@link ThreadPoolExecutor.CallerRunsPolicy CallerRunsPolicy} fallback —
 * under flood conditions the accept thread itself processes the over-limit
 * connection, providing natural backpressure instead of unbounded thread
 * spawning.
 */
public class MllpTcpServer {

    private static final Logger log = LoggerFactory.getLogger(MllpTcpServer.class);

    private final MllpProperties properties;
    private final Hl7MessageDispatcher dispatcher;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService workers;
    private ServerSocket serverSocket;
    private volatile int boundPort = -1;

    public MllpTcpServer(MllpProperties properties, Hl7MessageDispatcher dispatcher) {
        this.properties = properties;
        this.dispatcher = dispatcher;
        int max = Math.max(1, properties.getMaxConcurrentConnections());
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
            max, max,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            r -> {
                Thread t = new Thread(r, "mllp-worker-" + System.nanoTime());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        pool.allowCoreThreadTimeOut(true);
        this.workers = pool;
    }

    @PostConstruct
    public void start() throws IOException {
        ServerSocket socket = openAndBind();
        this.serverSocket = socket;
        this.boundPort = socket.getLocalPort();
        running.set(true);

        Thread accept = new Thread(this::acceptLoop, "mllp-accept");
        accept.setDaemon(true);
        accept.start();

        log.info("[MLLP] HL7 v2 listener started on {}:{} charset={} maxFrameBytes={}",
            properties.getBindAddress(), boundPort,
            properties.getCharset(), properties.getMaxFrameBytes());
    }

    /** Bind the listening socket; closes it cleanly if any setup step fails. */
    private ServerSocket openAndBind() throws IOException {
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(
                InetAddress.getByName(properties.getBindAddress()),
                properties.getPort()));
            socket.setSoTimeout(1_000);   // periodically wake to honour shutdown
            return socket;
        } catch (IOException | RuntimeException ex) {
            closeQuietly(socket, ex);
            throw ex;
        }
    }

    private static void closeQuietly(ServerSocket socket, Throwable cause) {
        try {
            socket.close();
        } catch (IOException suppressed) {
            cause.addSuppressed(suppressed);
        }
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        try {
            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
        } catch (IOException ignored) {
            // Closing during shutdown — nothing useful to do.
        }
        workers.shutdown();
        log.info("[MLLP] HL7 v2 listener stopped (was {}:{})", properties.getBindAddress(), boundPort);
    }

    public int getBoundPort() { return boundPort; }

    private void acceptLoop() {
        Charset charset = properties.resolvedCharset();
        while (running.get()) {
            Socket client;
            try {
                client = serverSocket.accept();
            } catch (java.net.SocketTimeoutException timeout) {
                continue;
            } catch (IOException e) {
                if (running.get()) {
                    log.error("[MLLP] accept loop error", e);
                }
                return;
            }
            workers.submit(() -> handle(client, charset));
        }
    }

    private void handle(Socket client, Charset charset) {
        String remote = describe(client);
        try (Socket socket = client) {
            socket.setSoTimeout(properties.getReadTimeoutMs());
            socket.setTcpNoDelay(true);
            try (
                InputStream in = new BufferedInputStream(socket.getInputStream());
                OutputStream out = new BufferedOutputStream(socket.getOutputStream())
            ) {
                while (running.get()) {
                    byte[] frame = MllpFrameCodec.readFrame(in, properties.getMaxFrameBytes());
                    if (frame.length == 0) {
                        log.debug("[MLLP {}] peer closed", remote);
                        return;
                    }
                    String body = new String(frame, charset);
                    String ack;
                    try {
                        ack = dispatcher.dispatch(body, remote);
                    } catch (RuntimeException ex) {
                        log.error("[MLLP {}] dispatcher error", remote, ex);
                        ack = Hl7AckBuilder.buildAck(safeHeader(body),
                            Hl7AckBuilder.AckCode.AE, "Server-side handler error");
                    }
                    MllpFrameCodec.writeFrame(out, ack, charset);
                }
            }
        } catch (java.net.SocketTimeoutException timeout) {
            log.debug("[MLLP {}] read timeout — closing", remote);
        } catch (IOException ioe) {
            log.warn("[MLLP {}] I/O error: {}", remote, ioe.getMessage());
        }
    }

    private static Hl7MessageHeader safeHeader(String body) {
        try {
            return Hl7MessageInspector.parseHeader(body);
        } catch (RuntimeException ignored) {
            return new Hl7MessageHeader("|", "^~\\&", "?", "?", "HMS", "HMS", "", "ACK", "?", "P", "2.5");
        }
    }

    private static String describe(Socket s) {
        try {
            return s.getInetAddress().getHostAddress() + ":" + s.getPort();
        } catch (RuntimeException ex) {
            return "unknown";
        }
    }
}
