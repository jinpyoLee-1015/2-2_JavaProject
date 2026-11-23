package cafe.admin;

import cafe.common.Protocol;
import cafe.common.Status;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 예전 schema.sql 로 이미 만들어 둔 DB 를 새 구조로 맞춘다. (DataStore 가 시작할 때 자동 실행)
 *  1) orders 에 당일 대기번호(daily_no)와 주문 날짜(order_date) 열 추가 + 기존 주문 번호 채우기
 *  2) orders.status 를 VARCHAR 에서 ENUM 으로 변경
 *  3) CHECK 제약 추가 (재고·가격·수량이 음수가 되지 않도록)
 *  4) members.pw_hash 를 VARCHAR(64) → VARCHAR(255) 로 넓힘 (salt + PBKDF2 형식은 64자보다 길다)
 * 이미 적용된 단계는 건너뛰므로 몇 번을 실행해도 안전하다.
 * 한 단계가 실패해도 프로그램은 계속 실행되고, 결과는 messages() 로 확인할 수 있다.
 */
final class SchemaMigration {

    /** {제약 이름, 테이블, 조건} - schema.sql / SetupDB 와 같은 내용 */
    static final String[][] CHECKS = {
            { "chk_menu_price", "menu", "price >= 0" },
            { "chk_menu_stock", "menu", "stock >= 0" },
            { "chk_orders_daily_no", "orders", "daily_no >= 1" },
            { "chk_items_qty", "order_items", "qty >= 1" },
            { "chk_items_shots", "order_items", "shots >= 0" },
            { "chk_items_unit_price", "order_items", "unit_price >= 0" },
            { "chk_items_size", "order_items",
                    "opt_size IN ('" + Protocol.SIZE_BASIC + "','" + Protocol.SIZE_LARGE + "')" },
    };

    private final List<String> messages = new ArrayList<>();

    List<String> messages() { return messages; }

    void run(Connection c) throws SQLException {
        addDailyNo(c);
        statusToEnum(c);
        addChecks(c);
        widenPwHash(c);
    }

    // ---------------------------------------------------------------- 4) 비밀번호 열 길이

    static final int PW_HASH_LENGTH = 255;

    private void widenPwHash(Connection c) throws SQLException {
        String type = columnType(c, "members", "pw_hash");          // 예: "varchar(64)"
        if (type == null) return;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("varchar\\((\\d+)\\)").matcher(type.toLowerCase());
        if (m.matches() && Integer.parseInt(m.group(1)) >= PW_HASH_LENGTH) return;
        exec(c, "ALTER TABLE members MODIFY pw_hash VARCHAR(" + PW_HASH_LENGTH + ") NOT NULL"
                + " COMMENT '비밀번호 해시 (pbkdf2$반복$salt$해시, 예전 회원은 SHA-256)'");
        messages.add("members.pw_hash 를 VARCHAR(" + PW_HASH_LENGTH + ") 으로 변경 (salt 적용 비밀번호 저장용)");
    }

    // ---------------------------------------------------------------- 1) 당일 대기번호

    private void addDailyNo(Connection c) throws SQLException {
        if (!hasColumn(c, "orders", "daily_no")) {
            exec(c, "ALTER TABLE orders"
                    + " ADD COLUMN daily_no INT NOT NULL DEFAULT 0 AFTER order_no,"
                    + " ADD COLUMN order_date DATE AS (DATE(order_time)) STORED AFTER order_time");
            int n = backfillDailyNo(c);
            exec(c, "ALTER TABLE orders MODIFY daily_no INT NOT NULL COMMENT '당일 대기번호 (매일 1번부터)'");
            messages.add("orders 에 당일 대기번호 열 추가 (기존 주문 " + n + "건 번호 부여)");
        }
        if (!hasIndex(c, "orders", "uq_orders_daily")) {
            exec(c, "ALTER TABLE orders ADD UNIQUE KEY uq_orders_daily (order_date, daily_no)");
        }
    }

    /** 기존 주문에 날짜별로 1, 2, 3 ... 번호를 매긴다. */
    private static int backfillDailyNo(Connection c) throws SQLException {
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        int count = 0;
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT order_no, order_date FROM orders ORDER BY order_date, order_no");
             PreparedStatement upd = c.prepareStatement("UPDATE orders SET daily_no = ? WHERE order_no = ?")) {
            Date day = null;
            int seq = 0;
            while (rs.next()) {
                Date d = rs.getDate("order_date");
                if (!d.equals(day)) { day = d; seq = 0; }
                upd.setInt(1, ++seq);
                upd.setInt(2, rs.getInt("order_no"));
                upd.addBatch();
                count++;
            }
            upd.executeBatch();
            c.commit();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
        return count;
    }

    // ---------------------------------------------------------------- 2) 상태 ENUM

    private void statusToEnum(Connection c) throws SQLException {
        String type = columnType(c, "orders", "status");
        String want = "enum(" + Status.sqlList().toLowerCase() + ")";
        if (type == null || type.toLowerCase().equals(want)) return;

        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM orders WHERE status NOT IN (" + Status.sqlList() + ")")) {
            rs.next();
            int bad = rs.getInt(1);
            if (bad > 0) {
                messages.add("[주의] 알 수 없는 상태 값을 가진 주문이 " + bad + "건 있어 status ENUM 변경을 건너뜀");
                return;
            }
        }
        exec(c, "ALTER TABLE orders MODIFY status ENUM(" + Status.sqlList() + ") NOT NULL DEFAULT 'WAITING'");
        messages.add("orders.status 를 ENUM 으로 변경");
    }

    // ---------------------------------------------------------------- 3) CHECK 제약

    private void addChecks(Connection c) throws SQLException {
        DatabaseMetaData md = c.getMetaData();
        if (!supportsCheck(md.getDatabaseProductVersion())) {
            // MySQL 8.0.16 미만은 CHECK 를 문법만 받아들이고 실제로 검사하지 않는다.
            messages.add("[안내] MySQL " + md.getDatabaseProductVersion() + " 은(는) CHECK 제약을 지원하지 않아 건너뜀 (8.0.16 이상 필요)");
            return;
        }
        for (String[] chk : CHECKS) {
            if (hasConstraint(c, chk[1], chk[0])) continue;
            try {
                exec(c, "ALTER TABLE " + chk[1] + " ADD CONSTRAINT " + chk[0] + " CHECK (" + chk[2] + ")");
                messages.add("CHECK 제약 추가: " + chk[0] + " (" + chk[2] + ")");
            } catch (SQLException e) {
                // 조건에 맞지 않는 기존 데이터가 있으면 추가할 수 없다. 데이터를 고친 뒤 다시 실행하면 된다.
                messages.add("[주의] CHECK 제약 " + chk[0] + " 추가 실패: " + e.getMessage());
            }
        }
    }

    /** "8.0.33" 같은 버전 문자열이 8.0.16 이상인지 (MariaDB 등 알 수 없는 형식이면 시도해 본다) */
    static boolean supportsCheck(String version) {
        if (version.toLowerCase().contains("mariadb")) return true;   // MariaDB 10.2+ 는 CHECK 지원
        try {
            String[] p = version.split("[^0-9]+");
            int major = Integer.parseInt(p[0]), minor = Integer.parseInt(p[1]), patch = Integer.parseInt(p[2]);
            if (major != 8) return major > 8;
            return minor > 0 || patch >= 16;
        } catch (RuntimeException e) {
            return true;
        }
    }

    // ---------------------------------------------------------------- information_schema 조회

    private static boolean hasColumn(Connection c, String table, String column) throws SQLException {
        return columnType(c, table, column) != null;
    }

    private static String columnType(Connection c, String table, String column) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COLUMN_TYPE FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static boolean hasIndex(Connection c, String table, String index) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?")) {
            ps.setString(1, table);
            ps.setString(2, index);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean hasConstraint(Connection c, String table, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM information_schema.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND CONSTRAINT_NAME = ?")) {
            ps.setString(1, table);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        }
    }
}
