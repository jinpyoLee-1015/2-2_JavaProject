package cafe.admin;

import cafe.common.*;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 관리자 노트북에서 여는 TCP 서버.
 * 회원 프로그램은 한 줄짜리 텍스트 명령(Protocol 참고)을 보내고, 서버는 한 줄로 응답한다.
 * 메뉴/주문 상태가 바뀌면 로그인된 모든 클라이언트에게 PUSH 메시지를 보낸다.
 */
public class CafeServer {
    private final DataStore store;
    private final Set<ClientHandler> clients = ConcurrentHashMap.newKeySet();
    private volatile Consumer<String> log = s -> {};
    private volatile Runnable onClientsChanged = () -> {};
    private ServerSocket server;
    private volatile boolean running;

    private final DataStore.Listener pushListener = new DataStore.Listener() {
        @Override public void menuChanged() { broadcast(Protocol.pack("PUSH", "MENU")); }
        @Override public void orderChanged(Order o) {
            broadcast(Protocol.pack("PUSH", "ORDER", String.valueOf(o.no), o.status.name(), o.memberId));
        }
    };

    public CafeServer(DataStore store) { this.store = store; }

    public void setLog(Consumer<String> log) { this.log = log; }
    public void setOnClientsChanged(Runnable r) { this.onClientsChanged = r; }
    public boolean isRunning() { return running; }

    public synchronized void start(int port) throws IOException {
        if (running) return;
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(port));
        server = ss;
        running = true;
        store.addListener(pushListener);
        Thread t = new Thread(this::acceptLoop, "cafe-accept");
        t.setDaemon(true);
        t.start();
        log.accept("서버 시작 (포트 " + port + ")");
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        store.removeListener(pushListener);
        try { server.close(); } catch (IOException ignored) {}
        for (ClientHandler h : clients) h.close();
        clients.clear();
        onClientsChanged.run();
        log.accept("서버 중지");
    }

    /** 로그인 상태인 접속자 수 */
    public int loggedInCount() {
        int c = 0;
        for (ClientHandler h : clients) if (h.user != null) c++;
        return c;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                s.setKeepAlive(true);
                s.setTcpNoDelay(true);
                ClientHandler h = new ClientHandler(s);
                clients.add(h);
                h.start();
            } catch (IOException e) {
                if (running) log.accept("[오류] 접속 수락 실패: " + e.getMessage());
            }
        }
    }

    private void broadcast(String line) {
        for (ClientHandler h : clients) if (h.user != null) h.send(line);
    }

    // ---------------------------------------------------------------- 접속자 1명 담당

    private class ClientHandler extends Thread {
        private final Socket socket;
        private final BufferedReader in;
        private final BufferedWriter out;
        volatile Member user;

        ClientHandler(Socket socket) throws IOException {
            super("cafe-client-" + socket.getRemoteSocketAddress());
            setDaemon(true);
            this.socket = socket;
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        synchronized void send(String line) {
            try {
                out.write(line);
                out.write('\n');
                out.flush();
            } catch (IOException e) {
                close();
            }
        }

        void close() {
            try { socket.close(); } catch (IOException ignored) {}
        }

        @Override public void run() {
            String addr = String.valueOf(socket.getRemoteSocketAddress());
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    String[] f = Protocol.unpack(line);
                    try {
                        handle(f);
                    } catch (RuntimeException e) {
                        send(Protocol.pack("ERR", "잘못된 요청입니다."));
                        log.accept("[오류] 요청 처리 실패 " + addr + ": " + e);
                    }
                }
            } catch (IOException ignored) {
                // 연결 종료
            } finally {
                clients.remove(this);
                close();
                if (user != null) {
                    log.accept("접속 종료: " + user.name + " (" + user.id + ")");
                    user = null;
                    onClientsChanged.run();
                }
            }
        }

        private void ok(String... extra) {
            String[] r = new String[extra.length + 1];
            r[0] = "OK";
            System.arraycopy(extra, 0, r, 1, extra.length);
            send(Protocol.pack(r));
        }

        private void err(String msg) { send(Protocol.pack("ERR", msg)); }

        private void handle(String[] f) {
            String cmd = f[0];
            switch (cmd) {
                case "PING":
                    ok();
                    return;
                case "SIGNUP": {
                    String e = store.signup(f[1], f[2], f[3], f[4]);
                    if (e == null) ok(); else err(e);
                    return;
                }
                case "LOGIN": {
                    Member m = store.login(f[1], f[2]);
                    if (m == null) { err("학번 또는 비밀번호가 올바르지 않습니다."); return; }
                    user = m;
                    log.accept("로그인: " + m.name + " (" + m.id + ")");
                    onClientsChanged.run();
                    ok(m.name);
                    return;
                }
                default:
            }
            if (user == null) { err("로그인이 필요합니다."); return; }

            switch (cmd) {
                case "LOGOUT":
                    log.accept("로그아웃: " + user.name + " (" + user.id + ")");
                    user = null;
                    onClientsChanged.run();
                    ok();
                    break;
                case "WITHDRAW": {
                    String e = store.withdraw(user.id, f[1]);
                    if (e == null) {
                        user = null;
                        onClientsChanged.run();
                        ok();
                    } else err(e);
                    break;
                }
                case "MENU": {
                    List<MenuItem> menu = store.menuSnapshot();
                    String[] r = new String[menu.size() + 1];
                    r[0] = "OK";
                    for (int i = 0; i < menu.size(); i++) r[i + 1] = Protocol.pack(menu.get(i).toFields());
                    send(Protocol.pack(r));
                    break;
                }
                case "QUEUE": {
                    int c = store.queueCount();
                    ok(String.valueOf(c), String.valueOf(c * Protocol.MINUTES_PER_ORDER));
                    break;
                }
                case "ORDER": {
                    try {
                        Order o = store.placeOrder(user, OrderItem.unpackList(f[1]));
                        int ahead = store.aheadOf(o.no);
                        ok(String.valueOf(o.no), String.valueOf(ahead),
                                String.valueOf((ahead + 1) * Protocol.MINUTES_PER_ORDER));
                    } catch (DataStore.OrderException e) {
                        err(e.getMessage());
                    }
                    break;
                }
                case "MYORDERS": {
                    List<Order> mine = store.ordersOf(user.id);
                    String[] r = new String[mine.size() + 1];
                    r[0] = "OK";
                    for (int i = 0; i < mine.size(); i++) {
                        Order o = mine.get(i);
                        String[] of = o.toFields();
                        String[] withAhead = new String[of.length + 1];
                        System.arraycopy(of, 0, withAhead, 0, of.length);
                        withAhead[of.length] = String.valueOf(store.aheadOf(o.no));
                        r[i + 1] = Protocol.pack(withAhead);
                    }
                    send(Protocol.pack(r));
                    break;
                }
                case "CANCEL": {
                    String e = store.cancelOrder(Integer.parseInt(f[1]), user.id);
                    if (e == null) ok(); else err(e);
                    break;
                }
                default:
                    err("알 수 없는 명령입니다.");
            }
        }
    }
}
