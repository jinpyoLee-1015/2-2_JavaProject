package cafe.admin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * MySQL 연결을 만들어 주는 클래스 (JDBC).
 * 접속 정보는 실행 폴더의 db.properties 에서 읽고, 파일이 없으면 기본값을 쓴다.
 * 다른 클래스는 DBConnect.get() 으로만 연결을 받는다. (try-with-resources 로 꼭 닫을 것)
 */
public final class DBConnect {
    private static final String DEFAULT_URL =
            "jdbc:mysql://localhost:3306/cafe?serverTimezone=Asia/Seoul&characterEncoding=UTF-8"
                    + "&useSSL=false&allowPublicKeyRetrieval=true";
    private static final String DEFAULT_USER = "root";
    private static final String DEFAULT_PASSWORD = "1234";

    private static final String url;
    private static final String user;
    private static final String password;
    private static final boolean driverLoaded;

    static {
        Properties p = new Properties();
        Path file = Paths.get("db.properties").toAbsolutePath();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // 읽기에 실패하면 기본값 사용
            }
        }
        url = p.getProperty("db.url", DEFAULT_URL).trim();
        user = p.getProperty("db.user", DEFAULT_USER).trim();
        password = p.getProperty("db.password", DEFAULT_PASSWORD);

        boolean ok;
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");   // JDBC 드라이버 로드 (mysql-connector-j)
            ok = true;
        } catch (ClassNotFoundException e) {
            ok = false;
        }
        driverLoaded = ok;
    }

    private DBConnect() {}

    public static Connection get() throws SQLException {
        if (!driverLoaded) {
            throw new SQLException("MySQL JDBC 드라이버(mysql-connector-j jar)가 프로젝트 라이브러리에 없습니다.");
        }
        return DriverManager.getConnection(url, user, password);
    }

    /** DB 이름 없이 MySQL 서버에만 연결 (cafe DB 를 처음 만들 때 사용) */
    static Connection getServer() throws SQLException {
        if (!driverLoaded) {
            throw new SQLException("MySQL JDBC 드라이버(mysql-connector-j jar)가 프로젝트 라이브러리에 없습니다.");
        }
        String serverUrl = url.replaceFirst("/cafe(\\?|$)", "/$1");
        return DriverManager.getConnection(serverUrl, user, password);
    }

    /** 화면 로그에 보여줄 접속 정보 (비밀번호 제외) */
    public static String describe() {
        return url.split("\\?")[0] + " (user=" + user + ")";
    }
}
