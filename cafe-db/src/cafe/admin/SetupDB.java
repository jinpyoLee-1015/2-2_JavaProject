package cafe.admin;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * cafe 데이터베이스와 테이블 4개를 만든다. (schema.sql 과 같은 내용)
 * MySQL Workbench 가 없을 때 이 파일의 main 을 한 번 실행하면 된다.
 * 여러 번 실행해도 기존 데이터는 지워지지 않는다. (IF NOT EXISTS)
 */
public class SetupDB {
    private static final String[] SQL = {
            "CREATE DATABASE IF NOT EXISTS cafe DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_unicode_ci",

            "CREATE TABLE IF NOT EXISTS cafe.members ("
                    + " id VARCHAR(15) NOT NULL,"
                    + " name VARCHAR(20) NOT NULL,"
                    + " email VARCHAR(100) NOT NULL,"
                    + " pw_hash VARCHAR(64) NOT NULL,"
                    + " joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + " PRIMARY KEY (id),"
                    + " UNIQUE KEY uq_members_email (email))",

            "CREATE TABLE IF NOT EXISTS cafe.menu ("
                    + " id INT NOT NULL AUTO_INCREMENT,"
                    + " category VARCHAR(30) NOT NULL,"
                    + " name VARCHAR(50) NOT NULL,"
                    + " price INT NOT NULL,"
                    + " stock INT NOT NULL DEFAULT 0,"
                    + " manual_sold_out BOOLEAN NOT NULL DEFAULT FALSE,"
                    + " PRIMARY KEY (id))",

            "CREATE TABLE IF NOT EXISTS cafe.orders ("
                    + " order_no INT NOT NULL AUTO_INCREMENT,"
                    + " member_id VARCHAR(15) NOT NULL,"
                    + " member_name VARCHAR(20) NOT NULL,"
                    + " status VARCHAR(10) NOT NULL DEFAULT 'WAITING',"
                    + " order_time DATETIME(3) NOT NULL,"
                    + " PRIMARY KEY (order_no),"
                    + " KEY idx_orders_member (member_id),"
                    + " KEY idx_orders_status (status),"
                    + " KEY idx_orders_time (order_time))",

            "CREATE TABLE IF NOT EXISTS cafe.order_items ("
                    + " id INT NOT NULL AUTO_INCREMENT,"
                    + " order_no INT NOT NULL,"
                    + " menu_id INT NOT NULL,"
                    + " menu_name VARCHAR(50) NOT NULL,"
                    + " opt_size VARCHAR(10) NOT NULL,"
                    + " shots INT NOT NULL DEFAULT 0,"
                    + " qty INT NOT NULL,"
                    + " unit_price INT NOT NULL,"
                    + " PRIMARY KEY (id),"
                    + " KEY idx_items_menu (menu_id),"
                    + " CONSTRAINT fk_items_order FOREIGN KEY (order_no) REFERENCES cafe.orders (order_no) ON DELETE CASCADE)"
    };

    public static void main(String[] args) {
        try (Connection conn = DBConnect.getServer();
             Statement stmt = conn.createStatement()) {
            for (String sql : SQL) stmt.executeUpdate(sql);
            System.out.println("cafe 데이터베이스와 테이블 4개(members, menu, orders, order_items) 생성 완료");
            System.out.println("이제 AdminMain 을 실행하세요. (첫 실행 때 기본 메뉴 9개가 자동으로 들어갑니다)");
        } catch (SQLException e) {
            System.out.println("DB 생성 오류: " + e.getMessage());
        }
    }
}
