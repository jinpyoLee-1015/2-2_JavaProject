package cafe.member;

import cafe.common.Protocol;
import cafe.common.UiUtil;

import javax.swing.*;
import java.awt.*;
import java.util.prefs.Preferences;

/** 서버 주소 입력 + 로그인 + 회원가입 */
public class LoginFrame extends JFrame {
    private static final Preferences PREFS = Preferences.userNodeForPackage(LoginFrame.class);

    private final JTextField hostField = new JTextField(PREFS.get("host", ""), 14);
    private final JTextField portField = new JTextField(PREFS.get("port", String.valueOf(Protocol.DEFAULT_PORT)), 5);
    private final JTextField idField = new JTextField(PREFS.get("id", ""), 14);
    private final JPasswordField pwField = new JPasswordField(14);
    private final JButton loginBtn = new JButton("로그인");
    private final JButton signupBtn = new JButton("회원가입");
    private final JLabel status = new JLabel(" ");

    public LoginFrame() {
        super("교내 카페 사전 주문");
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        JLabel title = new JLabel("교내 카페 사전 주문", SwingConstants.CENTER);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        title.setBorder(BorderFactory.createEmptyBorder(16, 0, 8, 0));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(8, 24, 8, 24));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 4, 4, 4);
        g.anchor = GridBagConstraints.WEST;

        JPanel server = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        server.add(hostField);
        server.add(new JLabel(":"));
        server.add(portField);
        addRow(form, g, 0, "서버 주소", server);
        addRow(form, g, 1, "학번", idField);
        addRow(form, g, 2, "비밀번호", pwField);

        JLabel hint = new JLabel("<html><font color='gray'>서버 주소는 관리자 프로그램 상단에 표시된 IP를 입력하세요.</font></html>");
        g.gridx = 0; g.gridy = 3; g.gridwidth = 2;
        form.add(hint, g);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 6));
        buttons.add(loginBtn);
        buttons.add(signupBtn);
        status.setHorizontalAlignment(SwingConstants.CENTER);
        status.setForeground(Color.GRAY);

        JPanel south = new JPanel(new BorderLayout());
        south.add(buttons, BorderLayout.CENTER);
        south.add(status, BorderLayout.SOUTH);
        south.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));

        add(title, BorderLayout.NORTH);
        add(form, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);

        loginBtn.addActionListener(e -> doLogin());
        pwField.addActionListener(e -> doLogin());
        signupBtn.addActionListener(e -> doSignup());
        getRootPane().setDefaultButton(loginBtn);

        pack();
        setMinimumSize(getSize());
        setLocationRelativeTo(null);
    }

    private void addRow(JPanel p, GridBagConstraints g, int row, String label, JComponent c) {
        g.gridwidth = 1;
        g.gridy = row;
        g.gridx = 0;
        p.add(new JLabel(label), g);
        g.gridx = 1;
        p.add(c, g);
    }

    private ServerConnection connect() throws Exception {
        String host = hostField.getText().trim();
        if (host.isEmpty()) throw new IllegalArgumentException("서버 주소를 입력해 주세요.");
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("포트는 숫자로 입력해 주세요.");
        }
        return new ServerConnection(host, port);
    }

    private void busy(boolean b, String msg) {
        loginBtn.setEnabled(!b);
        signupBtn.setEnabled(!b);
        status.setText(msg);
    }

    private static String friendly(Exception e) {
        if (e instanceof IllegalArgumentException) return e.getMessage();
        return "서버에 연결할 수 없습니다. 주소·포트, 같은 와이파이인지, 관리자 서버가 켜져 있는지 확인해 주세요.\n(" + e.getMessage() + ")";
    }

    private void doLogin() {
        String id = idField.getText().trim();
        String pw = new String(pwField.getPassword());
        if (id.isEmpty() || pw.isEmpty()) {
            JOptionPane.showMessageDialog(this, "학번과 비밀번호를 입력해 주세요.");
            return;
        }
        busy(true, "서버에 연결하는 중...");
        UiUtil.async(() -> {
            ServerConnection conn = connect();
            try {
                String[] r = conn.request("LOGIN", id, pw);
                if (!r[0].equals("OK")) {
                    conn.close();
                    return new Object[] { null, r[1] };
                }
                return new Object[] { conn, r[1] };
            } catch (Exception e) {
                conn.close();
                throw e;
            }
        }, res -> {
            busy(false, " ");
            if (res[0] == null) {
                JOptionPane.showMessageDialog(this, res[1], "로그인 실패", JOptionPane.WARNING_MESSAGE);
                return;
            }
            PREFS.put("host", hostField.getText().trim());
            PREFS.put("port", portField.getText().trim());
            PREFS.put("id", id);
            pwField.setText("");
            MainFrame main = new MainFrame((ServerConnection) res[0], id, (String) res[1], hostField.getText().trim());
            main.setVisible(true);
            dispose();
        }, e -> {
            busy(false, " ");
            JOptionPane.showMessageDialog(this, friendly(e), "연결 실패", JOptionPane.ERROR_MESSAGE);
        });
    }

    private void doSignup() {
        JTextField id = new JTextField(14);
        JTextField name = new JTextField(14);
        JTextField email = new JTextField(14);
        JPasswordField pw = new JPasswordField(14);
        JPasswordField pw2 = new JPasswordField(14);
        JPanel p = new JPanel(new GridLayout(0, 2, 6, 6));
        p.add(new JLabel("학번 (영문/숫자 4~15자)")); p.add(id);
        p.add(new JLabel("이름")); p.add(name);
        p.add(new JLabel("이메일")); p.add(email);
        p.add(new JLabel("비밀번호 (4자 이상)")); p.add(pw);
        p.add(new JLabel("비밀번호 확인")); p.add(pw2);

        if (JOptionPane.showConfirmDialog(this, p, "회원가입", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        String a = new String(pw.getPassword());
        if (!a.equals(new String(pw2.getPassword()))) {
            JOptionPane.showMessageDialog(this, "비밀번호 확인이 일치하지 않습니다.");
            return;
        }
        busy(true, "가입 처리 중...");
        UiUtil.async(() -> {
            try (ServerConnection conn = connect()) {
                return conn.request("SIGNUP", id.getText(), name.getText(), email.getText(), a);
            }
        }, r -> {
            busy(false, " ");
            if (r[0].equals("OK")) {
                idField.setText(id.getText().trim());
                JOptionPane.showMessageDialog(this, "가입이 완료되었습니다. 로그인해 주세요.");
                pwField.requestFocus();
            } else {
                JOptionPane.showMessageDialog(this, r[1], "가입 실패", JOptionPane.WARNING_MESSAGE);
            }
        }, e -> {
            busy(false, " ");
            JOptionPane.showMessageDialog(this, friendly(e), "연결 실패", JOptionPane.ERROR_MESSAGE);
        });
    }
}
