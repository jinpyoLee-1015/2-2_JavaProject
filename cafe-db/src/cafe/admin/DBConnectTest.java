package cafe.admin;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** DB 연결 확인용 (수업 예제 JDBCTest1/2 방식). 이 파일의 main 을 실행해 "DB연결완료"가 나오면 성공. */
public class DBConnectTest {
    public static void main(String[] args) {
        try (Connection conn = DBConnect.get();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, category, name, price, stock FROM menu")) {
            System.out.println("DB연결완료 - " + DBConnect.describe());
            while (rs.next()) {
                System.out.println(rs.getInt("id") + " " + rs.getString("category") + " "
                        + rs.getString("name") + " " + rs.getInt("price") + "원 재고 " + rs.getInt("stock"));
            }
        } catch (SQLException e) {
            System.out.println("DB 연결 오류: " + e.getMessage());
        }
    }
}
