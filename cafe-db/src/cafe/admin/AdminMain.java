package cafe.admin;

import cafe.common.UiUtil;

import javax.swing.*;

/** 관리자 프로그램 (서버 내장) 시작점 */
public class AdminMain {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
            UiUtil.applyFont("맑은 고딕", 13);
            try {
                DataStore store = new DataStore();
                new AdminFrame(store).setVisible(true);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null,
                        "데이터베이스에 연결할 수 없습니다.\n"
                                + "MySQL 서버가 켜져 있는지, schema.sql 을 실행했는지, db.properties 의 계정/비밀번호가 맞는지 확인해 주세요.\n\n"
                                + e.getMessage(),
                        "시작 오류", JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }
        });
    }
}
