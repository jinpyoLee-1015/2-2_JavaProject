package cafe.admin;

import cafe.common.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 회원 / 메뉴·재고 / 주문 데이터를 메모리에 들고 있으면서 CSV 파일로 저장·복원한다.
 * 모든 변경 메서드는 synchronized 이므로 여러 클라이언트가 동시에 주문해도 안전하다.
 */
public class DataStore {

    public interface Listener {
        default void menuChanged() {}
        default void orderChanged(Order o) {}
        default void membersChanged() {}
    }

    public static class OrderException extends Exception {
        public OrderException(String msg) { super(msg); }
    }

    private static final String[] MEMBER_HEADER = { "학번", "이름", "이메일", "비밀번호해시" };
    private static final String[] MENU_HEADER = { "id", "카테고리", "이름", "가격", "재고", "수동품절" };
    private static final String[] ORDER_HEADER = { "대기번호", "학번", "이름", "주문내용(내부형식)", "금액", "상태", "주문시각(ms)" };

    private final Path dir;
    private final Map<String, Member> members = new LinkedHashMap<>();
    private final List<MenuItem> menu = new ArrayList<>();
    private final List<Order> orders = new ArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile Consumer<String> log = s -> {};
    private int nextMenuId = 1;
    private int nextOrderNo = 1;

    public DataStore(Path dir) throws IOException {
        this.dir = dir;
        Files.createDirectories(dir);
        load();
    }

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }
    public void setLog(Consumer<String> log) { this.log = log; }
    public Path getDir() { return dir; }

    // ---------------------------------------------------------------- 로드 / 저장

    private synchronized void load() throws IOException {
        for (String[] f : readCsv("members.csv")) {
            if (f.length >= 4) members.put(f[0], new Member(f[0], f[1], f[2], f[3]));
        }
        for (String[] f : readCsv("menu.csv")) {
            try {
                MenuItem m = MenuItem.fromFields(f);
                menu.add(m);
                nextMenuId = Math.max(nextMenuId, m.id + 1);
            } catch (RuntimeException e) { log.accept("menu.csv 일부 줄을 건너뜀: " + Arrays.toString(f)); }
        }
        for (String[] f : readCsv("orders.csv")) {
            try {
                Order o = Order.fromFields(f);
                orders.add(o);
                nextOrderNo = Math.max(nextOrderNo, o.no + 1);
            } catch (RuntimeException e) { log.accept("orders.csv 일부 줄을 건너뜀: " + Arrays.toString(f)); }
        }
        if (menu.isEmpty()) {
            seedMenu();
            saveMenu();
        }
    }

    private void seedMenu() {
        String[][] seed = {
                { "커피", "아메리카노", "2500" }, { "커피", "카페라떼", "3000" },
                { "커피", "바닐라라떼", "3500" }, { "커피", "카푸치노", "3200" },
                { "논커피", "초코라떼", "3500" }, { "논커피", "딸기라떼", "3800" },
                { "에이드/티", "레몬에이드", "3500" }, { "에이드/티", "아이스티", "2800" },
                { "디저트", "크로플", "3000" } };
        for (String[] s : seed) {
            menu.add(new MenuItem(nextMenuId++, s[0], s[1], Integer.parseInt(s[2]), 30, false));
        }
    }

    private List<String[]> readCsv(String file) throws IOException {
        Path p = dir.resolve(file);
        List<String[]> rows = new ArrayList<>();
        if (!Files.exists(p)) return rows;
        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {           // 0번 줄은 헤더
            String ln = lines.get(i);
            if (!ln.trim().isEmpty()) rows.add(CsvUtil.parse(ln));
        }
        return rows;
    }

    private void writeCsv(String file, String[] header, List<String[]> rows) {
        List<String> lines = new ArrayList<>();
        lines.add(CsvUtil.line(header));
        for (String[] r : rows) lines.add(CsvUtil.line(r));
        try {
            Path tmp = dir.resolve(file + ".tmp");
            Files.write(tmp, lines, StandardCharsets.UTF_8);
            Files.move(tmp, dir.resolve(file), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.accept("[오류] " + file + " 저장 실패: " + e.getMessage());
        }
    }

    private void saveMembers() {
        List<String[]> rows = new ArrayList<>();
        for (Member m : members.values()) rows.add(new String[] { m.id, m.name, m.email, m.pwHash });
        writeCsv("members.csv", MEMBER_HEADER, rows);
    }

    private void saveMenu() {
        List<String[]> rows = new ArrayList<>();
        for (MenuItem m : menu) rows.add(m.toFields());
        writeCsv("menu.csv", MENU_HEADER, rows);
    }

    private void saveOrders() {
        List<String[]> rows = new ArrayList<>();
        for (Order o : orders) rows.add(o.toFields());
        writeCsv("orders.csv", ORDER_HEADER, rows);
    }

    /** 관리자 화면용: 사람이 읽기 쉬운 형태의 주문 내역 CSV (엑셀에서 열 수 있도록 BOM 포함) */
    public synchronized void exportOrdersCsv(Path file) throws IOException {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        List<String> lines = new ArrayList<>();
        lines.add("\uFEFF" + CsvUtil.line("대기번호", "학번", "이름", "주문내용", "금액", "상태", "주문시각"));
        for (Order o : orders) {
            lines.add(CsvUtil.line(String.valueOf(o.no), o.memberId, o.memberName, o.summary(),
                    String.valueOf(o.total()), o.status.label, fmt.format(new Date(o.time))));
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- 회원

    static String hash(String id, String pw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(("cafe:" + id + ":" + pw).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** @return 오류 메시지 (성공이면 null) */
    public String signup(String id, String name, String email, String pw) {
        id = Protocol.clean(id);
        name = Protocol.clean(name);
        email = Protocol.clean(email);
        if (!id.matches("[0-9A-Za-z]{4,15}")) return "학번은 영문/숫자 4~15자로 입력해 주세요.";
        if (name.isEmpty() || name.length() > 20) return "이름을 20자 이내로 입력해 주세요.";
        if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) return "이메일 형식이 올바르지 않습니다.";
        if (pw == null || pw.length() < 4) return "비밀번호는 4자 이상이어야 합니다.";
        synchronized (this) {
            if (members.containsKey(id)) return "이미 가입된 학번입니다.";
            for (Member m : members.values()) {
                if (m.email.equalsIgnoreCase(email)) return "이미 가입된 이메일입니다.";
            }
            members.put(id, new Member(id, name, email, hash(id, pw)));
            saveMembers();
        }
        log.accept("회원가입: " + id + " (" + name + ")");
        listeners.forEach(Listener::membersChanged);
        return null;
    }

    public synchronized Member login(String id, String pw) {
        Member m = members.get(Protocol.clean(id));
        if (m == null || pw == null || !m.pwHash.equals(hash(m.id, pw))) return null;
        return m;
    }

    /** @return 오류 메시지 (성공이면 null) */
    public String withdraw(String id, String pw) {
        synchronized (this) {
            Member m = login(id, pw);
            if (m == null) return "비밀번호가 올바르지 않습니다.";
            for (Order o : orders) {
                if (o.memberId.equals(id) && (o.status.isQueued() || o.status == Status.READY)) {
                    return "진행 중인 주문이 있어 탈퇴할 수 없습니다.";
                }
            }
            members.remove(id);
            saveMembers();
        }
        log.accept("회원탈퇴: " + id);
        listeners.forEach(Listener::membersChanged);
        return null;
    }

    public synchronized List<Member> membersSnapshot() { return new ArrayList<>(members.values()); }

    // ---------------------------------------------------------------- 메뉴 / 재고

    private MenuItem findMenu(int id) {
        for (MenuItem m : menu) if (m.id == id) return m;
        return null;
    }

    public synchronized List<MenuItem> menuSnapshot() {
        List<MenuItem> list = new ArrayList<>();
        for (MenuItem m : menu) list.add(m.copy());
        return list;
    }

    public void addMenu(String category, String name, int price, int stock) {
        synchronized (this) {
            menu.add(new MenuItem(nextMenuId++, Protocol.clean(category), Protocol.clean(name),
                    Math.max(0, price), Math.max(0, stock), false));
            saveMenu();
        }
        log.accept("메뉴 추가: " + name);
        listeners.forEach(Listener::menuChanged);
    }

    public void updateMenu(int id, String category, String name, int price, int stock) {
        synchronized (this) {
            MenuItem m = findMenu(id);
            if (m == null) return;
            m.category = Protocol.clean(category);
            m.name = Protocol.clean(name);
            m.price = Math.max(0, price);
            m.stock = Math.max(0, stock);
            saveMenu();
        }
        log.accept("메뉴 수정: " + name);
        listeners.forEach(Listener::menuChanged);
    }

    public void setStock(int id, int stock) {
        String name;
        synchronized (this) {
            MenuItem m = findMenu(id);
            if (m == null) return;
            m.stock = Math.max(0, stock);
            name = m.name;
            saveMenu();
        }
        log.accept("재고 수정: " + name + " → " + Math.max(0, stock) + "개");
        listeners.forEach(Listener::menuChanged);
    }

    public void setManualSoldOut(int id, boolean soldOut) {
        String name;
        synchronized (this) {
            MenuItem m = findMenu(id);
            if (m == null) return;
            m.manualSoldOut = soldOut;
            name = m.name;
            saveMenu();
        }
        log.accept((soldOut ? "수동 품절 처리: " : "품절 해제: ") + name);
        listeners.forEach(Listener::menuChanged);
    }

    public void deleteMenu(int id) {
        synchronized (this) {
            MenuItem m = findMenu(id);
            if (m == null) return;
            menu.remove(m);
            saveMenu();
        }
        log.accept("메뉴 삭제: id=" + id);
        listeners.forEach(Listener::menuChanged);
    }

    // ---------------------------------------------------------------- 주문

    private Order findOrder(int no) {
        for (Order o : orders) if (o.no == no) return o;
        return null;
    }

    public Order placeOrder(Member m, List<OrderItem> requested) throws OrderException {
        Order created;
        synchronized (this) {
            if (requested == null || requested.isEmpty()) throw new OrderException("장바구니가 비어 있습니다.");
            Map<Integer, Integer> need = new LinkedHashMap<>();
            List<OrderItem> clean = new ArrayList<>();
            for (OrderItem it : requested) {
                MenuItem mi = findMenu(it.menuId);
                if (mi == null) throw new OrderException("메뉴가 변경되었습니다. 메뉴를 새로고침해 주세요.");
                if (it.qty < 1 || it.qty > Protocol.MAX_QTY) throw new OrderException("수량이 올바르지 않습니다.");
                if (it.shots < 0 || it.shots > Protocol.MAX_SHOTS) throw new OrderException("샷 추가 개수가 올바르지 않습니다.");
                if (mi.isSoldOut()) throw new OrderException("'" + mi.name + "'은(는) 품절입니다.");
                String size = Protocol.SIZE_LARGE.equals(it.size) ? Protocol.SIZE_LARGE : Protocol.SIZE_BASIC;
                need.merge(mi.id, it.qty, Integer::sum);
                // 가격은 항상 서버가 다시 계산한다
                clean.add(new OrderItem(mi.id, mi.name, size, it.shots, it.qty,
                        OrderItem.calcUnitPrice(mi.price, size, it.shots)));
            }
            for (Map.Entry<Integer, Integer> e : need.entrySet()) {
                MenuItem mi = findMenu(e.getKey());
                if (mi.stock < e.getValue()) {
                    throw new OrderException("'" + mi.name + "' 재고가 부족합니다. (남은 수량 " + mi.stock + "개)");
                }
            }
            for (Map.Entry<Integer, Integer> e : need.entrySet()) findMenu(e.getKey()).stock -= e.getValue();
            created = new Order(nextOrderNo++, m.id, m.name, clean, Status.WAITING, System.currentTimeMillis());
            orders.add(created);
            saveMenu();
            saveOrders();
        }
        log.accept("새 주문 #" + created.no + " " + m.name + " : " + created.summary());
        listeners.forEach(Listener::menuChanged);
        Order snap = created.copy();
        listeners.forEach(l -> l.orderChanged(snap));
        return snap;
    }

    /**
     * 주문 취소. memberId 가 null 이면 관리자 취소.
     * @return 오류 메시지 (성공이면 null)
     */
    public String cancelOrder(int no, String memberId) {
        Order snap;
        synchronized (this) {
            Order o = findOrder(no);
            if (o == null) return "주문을 찾을 수 없습니다.";
            if (memberId != null && !o.memberId.equals(memberId)) return "본인의 주문만 취소할 수 있습니다.";
            if (o.status.isFinished()) return "이미 처리된 주문입니다.";
            if (memberId != null && o.status != Status.WAITING) return "제조가 시작된 주문은 취소할 수 없습니다.";
            for (OrderItem it : o.items) {              // 재고 복구
                MenuItem mi = findMenu(it.menuId);
                if (mi != null) mi.stock += it.qty;
            }
            o.status = Status.CANCELED;
            saveMenu();
            saveOrders();
            snap = o.copy();
        }
        log.accept("주문 취소 #" + no + (memberId == null ? " (관리자)" : " (회원)"));
        listeners.forEach(Listener::menuChanged);
        listeners.forEach(l -> l.orderChanged(snap));
        return null;
    }

    /**
     * 상태를 앞 단계로 변경 (접수 대기 > 제조 중 > 수령 가능 > 수령 완료).
     * @return 오류 메시지 (성공이면 null)
     */
    public String setStatus(int no, Status next) {
        if (next == Status.CANCELED) return cancelOrder(no, null);
        Order snap;
        synchronized (this) {
            Order o = findOrder(no);
            if (o == null) return "주문을 찾을 수 없습니다.";
            if (o.status.isFinished()) return "이미 종료된 주문입니다.";
            if (next.ordinal() <= o.status.ordinal()) return "이전 단계로 되돌릴 수 없습니다.";
            o.status = next;
            saveOrders();
            snap = o.copy();
        }
        log.accept("주문 #" + no + " → " + next.label);
        listeners.forEach(l -> l.orderChanged(snap));
        return null;
    }

    public synchronized List<Order> ordersSnapshot() {
        List<Order> list = new ArrayList<>();
        for (Order o : orders) list.add(o.copy());
        return list;
    }

    public synchronized List<Order> ordersOf(String memberId) {
        List<Order> list = new ArrayList<>();
        for (Order o : orders) if (o.memberId.equals(memberId)) list.add(o.copy());
        return list;
    }

    /** 현재 대기열(접수 대기 + 제조 중) 주문 수 */
    public synchronized int queueCount() {
        int c = 0;
        for (Order o : orders) if (o.status.isQueued()) c++;
        return c;
    }

    /** 해당 주문보다 앞에서 대기 중인 주문 수 (이미 제조가 끝났으면 0) */
    public synchronized int aheadOf(int no) {
        Order me = findOrder(no);
        if (me == null || !me.status.isQueued()) return 0;
        int c = 0;
        for (Order o : orders) if (o.status.isQueued() && o.no < no) c++;
        return c;
    }
}
