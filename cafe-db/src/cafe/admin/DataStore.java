package cafe.admin;

import cafe.common.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Date;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 회원 / 메뉴·재고 / 주문 데이터를 MySQL 에 저장하고 JDBC 로 읽고 쓴다.
 * (예전 CSV 파일 저장 방식을 대체한 버전. 공개 메서드는 그대로라서 서버·화면 코드는 바꿀 필요가 없다.)
 * 변경 메서드는 synchronized 로 한 번에 하나씩 처리하고, 주문/취소는 DB 트랜잭션으로 묶는다.
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

    private static final String DB_ERROR = "데이터베이스 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.";
    private static final int MYSQL_DUPLICATE_KEY = 1062;
    private static final String QUEUED_SQL = "('WAITING','MAKING')";

    private static final String ORDER_SELECT =
            "SELECT o.order_no, o.member_id, o.member_name, o.status, o.order_time, "
                    + "i.menu_id, i.menu_name, i.opt_size, i.shots, i.qty, i.unit_price "
                    + "FROM orders o LEFT JOIN order_items i ON i.order_no = o.order_no ";

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile Consumer<String> log = s -> {};

    /** DB 에 연결해 보고, 메뉴가 비어 있으면 기본 메뉴를 넣는다. */
    public DataStore() throws SQLException {
        seedMenuIfEmpty();
    }

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }
    public void setLog(Consumer<String> log) { this.log = log; }
    public String getDbInfo() { return DBConnect.describe(); }

    private String dbError(SQLException e) {
        log.accept("[DB 오류] " + e.getMessage());
        return DB_ERROR;
    }

    private void seedMenuIfEmpty() throws SQLException {
        try (Connection c = DBConnect.get();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM menu")) {
            rs.next();
            if (rs.getInt(1) > 0) return;
            String[][] seed = {
                    { "커피", "아메리카노", "2500" }, { "커피", "카페라떼", "3000" },
                    { "커피", "바닐라라떼", "3500" }, { "커피", "카푸치노", "3200" },
                    { "논커피", "초코라떼", "3500" }, { "논커피", "딸기라떼", "3800" },
                    { "에이드/티", "레몬에이드", "3500" }, { "에이드/티", "아이스티", "2800" },
                    { "디저트", "크로플", "3000" } };
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO menu (category, name, price, stock, manual_sold_out) VALUES (?, ?, ?, 30, FALSE)")) {
                for (String[] s : seed) {
                    ps.setString(1, s[0]);
                    ps.setString(2, s[1]);
                    ps.setInt(3, Integer.parseInt(s[2]));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
    }

    /** 관리자 화면용: 사람이 읽기 쉬운 형태의 주문 내역 CSV (엑셀에서 열 수 있도록 BOM 포함) */
    public void exportOrdersCsv(Path file) throws IOException {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        List<String> lines = new ArrayList<>();
        lines.add("﻿" + CsvUtil.line("대기번호", "학번", "이름", "주문내용", "금액", "상태", "주문시각"));
        for (Order o : ordersSnapshot()) {
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
            try (Connection c = DBConnect.get()) {
                try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM members WHERE id = ?")) {
                    ps.setString(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) return "이미 가입된 학번입니다.";
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM members WHERE LOWER(email) = LOWER(?)")) {
                    ps.setString(1, email);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) return "이미 가입된 이메일입니다.";
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO members (id, name, email, pw_hash) VALUES (?, ?, ?, ?)")) {
                    ps.setString(1, id);
                    ps.setString(2, name);
                    ps.setString(3, email);
                    ps.setString(4, hash(id, pw));
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                if (e.getErrorCode() == MYSQL_DUPLICATE_KEY) return "이미 가입된 학번 또는 이메일입니다.";
                return dbError(e);
            }
        }
        log.accept("회원가입: " + id + " (" + name + ")");
        listeners.forEach(Listener::membersChanged);
        return null;
    }

    public Member login(String id, String pw) {
        id = Protocol.clean(id);
        if (pw == null) return null;
        try (Connection c = DBConnect.get();
             PreparedStatement ps = c.prepareStatement("SELECT id, name, email, pw_hash FROM members WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Member m = new Member(rs.getString("id"), rs.getString("name"),
                        rs.getString("email"), rs.getString("pw_hash"));
                return m.pwHash.equals(hash(m.id, pw)) ? m : null;
            }
        } catch (SQLException e) {
            dbError(e);
            return null;
        }
    }

    /** @return 오류 메시지 (성공이면 null) */
    public String withdraw(String id, String pw) {
        synchronized (this) {
            Member m = login(id, pw);
            if (m == null) return "비밀번호가 올바르지 않습니다.";
            try (Connection c = DBConnect.get()) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT COUNT(*) FROM orders WHERE member_id = ? AND status IN ('WAITING','MAKING','READY')")) {
                    ps.setString(1, m.id);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        if (rs.getInt(1) > 0) return "진행 중인 주문이 있어 탈퇴할 수 없습니다.";
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM members WHERE id = ?")) {
                    ps.setString(1, m.id);
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                return dbError(e);
            }
        }
        log.accept("회원탈퇴: " + id);
        listeners.forEach(Listener::membersChanged);
        return null;
    }

    public List<Member> membersSnapshot() {
        List<Member> list = new ArrayList<>();
        try (Connection c = DBConnect.get();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, name, email, pw_hash FROM members ORDER BY joined_at, id")) {
            while (rs.next()) {
                list.add(new Member(rs.getString("id"), rs.getString("name"),
                        rs.getString("email"), rs.getString("pw_hash")));
            }
        } catch (SQLException e) {
            dbError(e);
        }
        return list;
    }

    // ---------------------------------------------------------------- 메뉴 / 재고

    private static MenuItem readMenu(ResultSet rs) throws SQLException {
        return new MenuItem(rs.getInt("id"), rs.getString("category"), rs.getString("name"),
                rs.getInt("price"), rs.getInt("stock"), rs.getBoolean("manual_sold_out"));
    }

    public List<MenuItem> menuSnapshot() {
        List<MenuItem> list = new ArrayList<>();
        try (Connection c = DBConnect.get();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, category, name, price, stock, manual_sold_out FROM menu ORDER BY id")) {
            while (rs.next()) list.add(readMenu(rs));
        } catch (SQLException e) {
            dbError(e);
        }
        return list;
    }

    public void addMenu(String category, String name, int price, int stock) {
        synchronized (this) {
            try (Connection c = DBConnect.get();
                 PreparedStatement ps = c.prepareStatement(
                         "INSERT INTO menu (category, name, price, stock, manual_sold_out) VALUES (?, ?, ?, ?, FALSE)")) {
                ps.setString(1, Protocol.clean(category));
                ps.setString(2, Protocol.clean(name));
                ps.setInt(3, Math.max(0, price));
                ps.setInt(4, Math.max(0, stock));
                ps.executeUpdate();
            } catch (SQLException e) {
                dbError(e);
                return;
            }
        }
        log.accept("메뉴 추가: " + name);
        listeners.forEach(Listener::menuChanged);
    }

    public void updateMenu(int id, String category, String name, int price, int stock) {
        synchronized (this) {
            try (Connection c = DBConnect.get();
                 PreparedStatement ps = c.prepareStatement(
                         "UPDATE menu SET category = ?, name = ?, price = ?, stock = ? WHERE id = ?")) {
                ps.setString(1, Protocol.clean(category));
                ps.setString(2, Protocol.clean(name));
                ps.setInt(3, Math.max(0, price));
                ps.setInt(4, Math.max(0, stock));
                ps.setInt(5, id);
                if (ps.executeUpdate() == 0) return;
            } catch (SQLException e) {
                dbError(e);
                return;
            }
        }
        log.accept("메뉴 수정: " + name);
        listeners.forEach(Listener::menuChanged);
    }

    public void setStock(int id, int stock) {
        String name;
        synchronized (this) {
            try (Connection c = DBConnect.get()) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE menu SET stock = ? WHERE id = ?")) {
                    ps.setInt(1, Math.max(0, stock));
                    ps.setInt(2, id);
                    if (ps.executeUpdate() == 0) return;
                }
                name = menuName(c, id);
            } catch (SQLException e) {
                dbError(e);
                return;
            }
        }
        log.accept("재고 수정: " + name + " → " + Math.max(0, stock) + "개");
        listeners.forEach(Listener::menuChanged);
    }

    public void setManualSoldOut(int id, boolean soldOut) {
        String name;
        synchronized (this) {
            try (Connection c = DBConnect.get()) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE menu SET manual_sold_out = ? WHERE id = ?")) {
                    ps.setBoolean(1, soldOut);
                    ps.setInt(2, id);
                    if (ps.executeUpdate() == 0) return;
                }
                name = menuName(c, id);
            } catch (SQLException e) {
                dbError(e);
                return;
            }
        }
        log.accept((soldOut ? "수동 품절 처리: " : "품절 해제: ") + name);
        listeners.forEach(Listener::menuChanged);
    }

    public void deleteMenu(int id) {
        synchronized (this) {
            try (Connection c = DBConnect.get();
                 PreparedStatement ps = c.prepareStatement("DELETE FROM menu WHERE id = ?")) {
                ps.setInt(1, id);
                if (ps.executeUpdate() == 0) return;
            } catch (SQLException e) {
                dbError(e);
                return;
            }
        }
        log.accept("메뉴 삭제: id=" + id);
        listeners.forEach(Listener::menuChanged);
    }

    private static String menuName(Connection c, int id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT name FROM menu WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "id=" + id;
            }
        }
    }

    // ---------------------------------------------------------------- 주문

    /** 주문 읽기: orders + order_items 를 JOIN 으로 한 번에 읽어 Order 객체로 조립한다. */
    private static List<Order> queryOrders(Connection c, String where, Object... args) throws SQLException {
        String sql = ORDER_SELECT + (where == null ? "" : "WHERE " + where + " ") + "ORDER BY o.order_no, i.id";
        Map<Integer, Order> map = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int no = rs.getInt("order_no");
                    Order o = map.get(no);
                    if (o == null) {
                        o = new Order(no, rs.getString("member_id"), rs.getString("member_name"),
                                new ArrayList<>(), Status.fromName(rs.getString("status")),
                                rs.getTimestamp("order_time").getTime());
                        map.put(no, o);
                    }
                    int menuId = rs.getInt("menu_id");
                    if (!rs.wasNull()) {
                        o.items.add(new OrderItem(menuId, rs.getString("menu_name"), rs.getString("opt_size"),
                                rs.getInt("shots"), rs.getInt("qty"), rs.getInt("unit_price")));
                    }
                }
            }
        }
        return new ArrayList<>(map.values());
    }

    private static Order findOrder(Connection c, int no) throws SQLException {
        List<Order> list = queryOrders(c, "o.order_no = ?", no);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 주문 1건 저장: 재고 확인 → 재고 차감 → 주문/주문상세 저장을 하나의 트랜잭션으로 처리 */
    public Order placeOrder(Member m, List<OrderItem> requested) throws OrderException {
        Order created;
        synchronized (this) {
            if (requested == null || requested.isEmpty()) throw new OrderException("장바구니가 비어 있습니다.");
            try (Connection c = DBConnect.get()) {
                c.setAutoCommit(false);
                try {
                    Map<Integer, MenuItem> rows = new LinkedHashMap<>();   // 잠근 메뉴 행
                    Map<Integer, Integer> need = new LinkedHashMap<>();
                    List<OrderItem> clean = new ArrayList<>();
                    for (OrderItem it : requested) {
                        MenuItem mi = rows.get(it.menuId);
                        if (mi == null) {
                            mi = lockMenu(c, it.menuId);
                            if (mi == null) throw new OrderException("메뉴가 변경되었습니다. 메뉴를 새로고침해 주세요.");
                            rows.put(mi.id, mi);
                        }
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
                        MenuItem mi = rows.get(e.getKey());
                        if (mi.stock < e.getValue()) {
                            throw new OrderException("'" + mi.name + "' 재고가 부족합니다. (남은 수량 " + mi.stock + "개)");
                        }
                    }
                    try (PreparedStatement ps = c.prepareStatement("UPDATE menu SET stock = stock - ? WHERE id = ?")) {
                        for (Map.Entry<Integer, Integer> e : need.entrySet()) {
                            ps.setInt(1, e.getValue());
                            ps.setInt(2, e.getKey());
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                    long now = System.currentTimeMillis();
                    int no;
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO orders (member_id, member_name, status, order_time) VALUES (?, ?, 'WAITING', ?)",
                            Statement.RETURN_GENERATED_KEYS)) {
                        ps.setString(1, m.id);
                        ps.setString(2, m.name);
                        ps.setTimestamp(3, new Timestamp(now));
                        ps.executeUpdate();
                        try (ResultSet keys = ps.getGeneratedKeys()) {
                            keys.next();
                            no = keys.getInt(1);
                        }
                    }
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO order_items (order_no, menu_id, menu_name, opt_size, shots, qty, unit_price) "
                                    + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                        for (OrderItem it : clean) {
                            ps.setInt(1, no);
                            ps.setInt(2, it.menuId);
                            ps.setString(3, it.name);
                            ps.setString(4, it.size);
                            ps.setInt(5, it.shots);
                            ps.setInt(6, it.qty);
                            ps.setInt(7, it.unitPrice);
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                    c.commit();
                    created = new Order(no, m.id, m.name, clean, Status.WAITING, now);
                } catch (OrderException | SQLException | RuntimeException e) {
                    c.rollback();
                    throw e;
                }
            } catch (SQLException e) {
                log.accept("[DB 오류] 주문 저장 실패: " + e.getMessage());
                throw new OrderException(DB_ERROR);
            }
        }
        log.accept("새 주문 #" + created.no + " " + m.name + " : " + created.summary());
        listeners.forEach(Listener::menuChanged);
        Order snap = created.copy();
        listeners.forEach(l -> l.orderChanged(snap));
        return snap;
    }

    /** 메뉴 행을 잠그고(FOR UPDATE) 읽는다. 없으면 null. */
    private static MenuItem lockMenu(Connection c, int id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, category, name, price, stock, manual_sold_out FROM menu WHERE id = ? FOR UPDATE")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readMenu(rs) : null;
            }
        }
    }

    /**
     * 주문 취소. memberId 가 null 이면 관리자 취소.
     * @return 오류 메시지 (성공이면 null)
     */
    public String cancelOrder(int no, String memberId) {
        Order snap;
        synchronized (this) {
            try (Connection c = DBConnect.get()) {
                c.setAutoCommit(false);
                try {
                    String owner;
                    Status status;
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT member_id, status FROM orders WHERE order_no = ? FOR UPDATE")) {
                        ps.setInt(1, no);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) { c.rollback(); return "주문을 찾을 수 없습니다."; }
                            owner = rs.getString("member_id");
                            status = Status.fromName(rs.getString("status"));
                        }
                    }
                    if (memberId != null && !owner.equals(memberId)) { c.rollback(); return "본인의 주문만 취소할 수 있습니다."; }
                    if (status.isFinished()) { c.rollback(); return "이미 처리된 주문입니다."; }
                    if (memberId != null && status != Status.WAITING) { c.rollback(); return "제조가 시작된 주문은 취소할 수 없습니다."; }

                    // 재고 복구 (이미 삭제된 메뉴는 UPDATE 대상이 없으므로 그냥 건너뜀)
                    try (PreparedStatement sel = c.prepareStatement(
                            "SELECT menu_id, SUM(qty) FROM order_items WHERE order_no = ? GROUP BY menu_id");
                         PreparedStatement upd = c.prepareStatement("UPDATE menu SET stock = stock + ? WHERE id = ?")) {
                        sel.setInt(1, no);
                        try (ResultSet rs = sel.executeQuery()) {
                            while (rs.next()) {
                                upd.setInt(1, rs.getInt(2));
                                upd.setInt(2, rs.getInt(1));
                                upd.addBatch();
                            }
                        }
                        upd.executeBatch();
                    }
                    try (PreparedStatement ps = c.prepareStatement("UPDATE orders SET status = 'CANCELED' WHERE order_no = ?")) {
                        ps.setInt(1, no);
                        ps.executeUpdate();
                    }
                    c.commit();
                } catch (SQLException | RuntimeException e) {
                    c.rollback();
                    throw e;
                }
                snap = findOrder(c, no);
            } catch (SQLException e) {
                return dbError(e);
            }
        }
        log.accept("주문 취소 #" + no + (memberId == null ? " (관리자)" : " (회원)"));
        listeners.forEach(Listener::menuChanged);
        if (snap != null) listeners.forEach(l -> l.orderChanged(snap));
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
            try (Connection c = DBConnect.get()) {
                c.setAutoCommit(false);
                try {
                    Status cur;
                    try (PreparedStatement ps = c.prepareStatement("SELECT status FROM orders WHERE order_no = ? FOR UPDATE")) {
                        ps.setInt(1, no);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) { c.rollback(); return "주문을 찾을 수 없습니다."; }
                            cur = Status.fromName(rs.getString(1));
                        }
                    }
                    if (cur.isFinished()) { c.rollback(); return "이미 종료된 주문입니다."; }
                    if (next.ordinal() <= cur.ordinal()) { c.rollback(); return "이전 단계로 되돌릴 수 없습니다."; }
                    try (PreparedStatement ps = c.prepareStatement("UPDATE orders SET status = ? WHERE order_no = ?")) {
                        ps.setString(1, next.name());
                        ps.setInt(2, no);
                        ps.executeUpdate();
                    }
                    c.commit();
                } catch (SQLException | RuntimeException e) {
                    c.rollback();
                    throw e;
                }
                snap = findOrder(c, no);
            } catch (SQLException e) {
                return dbError(e);
            }
        }
        log.accept("주문 #" + no + " → " + next.label);
        if (snap != null) listeners.forEach(l -> l.orderChanged(snap));
        return null;
    }

    public List<Order> ordersSnapshot() {
        try (Connection c = DBConnect.get()) {
            return queryOrders(c, null);
        } catch (SQLException e) {
            dbError(e);
            return new ArrayList<>();
        }
    }

    public List<Order> ordersOf(String memberId) {
        try (Connection c = DBConnect.get()) {
            return queryOrders(c, "o.member_id = ?", memberId);
        } catch (SQLException e) {
            dbError(e);
            return new ArrayList<>();
        }
    }

    /** 현재 대기열(접수 대기 + 제조 중) 주문 수 */
    public int queueCount() {
        try (Connection c = DBConnect.get();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM orders WHERE status IN " + QUEUED_SQL)) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            dbError(e);
            return 0;
        }
    }

    /** 해당 주문보다 앞에서 대기 중인 주문 수 (이미 제조가 끝났으면 0) */
    public int aheadOf(int no) {
        try (Connection c = DBConnect.get()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT status FROM orders WHERE order_no = ?")) {
                ps.setInt(1, no);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || !Status.fromName(rs.getString(1)).isQueued()) return 0;
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM orders WHERE status IN " + QUEUED_SQL + " AND order_no < ?")) {
                ps.setInt(1, no);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            dbError(e);
            return 0;
        }
    }
}
