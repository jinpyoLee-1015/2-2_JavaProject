package cafe.member;

import cafe.common.Protocol;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** 관리자(서버) 노트북과의 TCP 연결. 요청-응답은 request(), 서버가 먼저 보내는 알림은 PushListener 로 받는다. */
public class ServerConnection implements Closeable {

    public interface PushListener {
        /** 서버 알림 수신 (reader 스레드에서 호출되므로 화면 갱신은 invokeLater 로) */
        void onPush(String[] fields);
        void onDisconnected();
    }

    private final Socket socket;
    private final BufferedReader in;
    private final BufferedWriter out;
    private final BlockingQueue<String[]> responses = new LinkedBlockingQueue<>();
    private volatile PushListener listener;
    private volatile boolean closed;

    public ServerConnection(String host, int port) throws IOException {
        socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), 3000);
        } catch (IOException e) {
            try { socket.close(); } catch (IOException ignored) {}
            throw e;
        }
        socket.setKeepAlive(true);
        socket.setTcpNoDelay(true);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        Thread t = new Thread(this::readLoop, "server-reader");
        t.setDaemon(true);
        t.start();
    }

    public void setListener(PushListener l) { this.listener = l; }

    private void readLoop() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                String[] f = Protocol.unpack(line);
                if (f.length > 0 && "PUSH".equals(f[0])) {
                    PushListener l = listener;
                    if (l != null) l.onPush(f);
                } else {
                    responses.add(f);
                }
            }
        } catch (IOException ignored) {
            // 연결 종료
        } finally {
            boolean wasOpen = !closed;
            closed = true;
            try { socket.close(); } catch (IOException ignored) {}
            PushListener l = listener;
            if (wasOpen && l != null) l.onDisconnected();
        }
    }

    /** 요청을 보내고 응답(필드 배열)을 기다린다. 응답[0] 은 "OK" 또는 "ERR". */
    public synchronized String[] request(String... fields) throws IOException {
        if (closed) throw new IOException("서버와 연결이 끊어졌습니다.");
        responses.clear();
        out.write(Protocol.pack(fields));
        out.write('\n');
        out.flush();
        try {
            String[] r = responses.poll(5, TimeUnit.SECONDS);
            if (r == null) throw new IOException("서버 응답이 없습니다.");
            return r;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("요청이 중단되었습니다.");
        }
    }

    public boolean isClosed() { return closed; }

    @Override public void close() {
        closed = true;
        try { socket.close(); } catch (IOException ignored) {}
    }
}
