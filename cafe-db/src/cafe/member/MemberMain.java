package cafe.member;

import cafe.common.UiUtil;

import javax.swing.*;

/** 회원(주문) 프로그램 시작점 */
/**
 * 
 */
public class MemberMain {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
            UiUtil.applyFont("맑은 고딕", 13);
            new LoginFrame().setVisible(true);
        });
    }
}
