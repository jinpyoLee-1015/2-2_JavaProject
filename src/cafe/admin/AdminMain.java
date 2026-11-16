package cafe.admin;

import cafe.common.UiUtil;

import javax.swing.*;
import java.nio.file.Paths;

/** 관리자 프로그램 (서버 내장) 시작점 */
public class AdminMain {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
            UiUtil.applyFont("맑은 고딕", 13);
            try {
                DataStore store = new DataStore(Paths.get("data").toAbsolutePath());
                new AdminFrame(store).setVisible(true);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null, "데이터 폴더를 열 수 없습니다.\n" + e.getMessage(),
                        "시작 오류", JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }
        });
    }
}
