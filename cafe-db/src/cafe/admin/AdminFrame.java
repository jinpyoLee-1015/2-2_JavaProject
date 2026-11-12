package cafe.admin;

import cafe.common.*;
import cafe.common.MenuItem;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.net.*;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

/** 관리자 화면: 서버 시작/중지, 주문 처리, 메뉴·재고 관리, 회원 목록, 로그 */
public class AdminFrame extends JFrame {
    private final DataStore store;
    private final CafeServer server;

    private final JTextField portField = new JTextField(String.valueOf(Protocol.DEFAULT_PORT), 5);
    private final JButton serverBtn = new JButton("서버 시작");
    private final JLabel serverStatus = new JLabel("● 서버 중지됨");
    private final JLabel addressLabel = new JLabel(" ");
    private final JLabel clientLabel = new JLabel("접속자 0명");

    private final DefaultTableModel orderModel = readOnlyModel("대기번호", "회원", "주문 내용", "금액", "상태", "주문 시각");
    private final JTable orderTable = new JTable(orderModel);
    private final JCheckBox showFinished = new JCheckBox("수령 완료/취소 주문도 표시");

    private final DefaultTableModel menuModel = readOnlyModel("ID", "카테고리", "메뉴", "가격", "재고", "상태");
    private final JTable menuTable = new JTable(menuModel);

    private final DefaultTableModel memberModel = readOnlyModel("학번", "이름", "이메일");
    private final JLabel memberCount = new JLabel(" ");

    private final JTextArea logArea = new JTextArea();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("MM-dd HH:mm");
    private final SimpleDateFormat logFmt = new SimpleDateFormat("HH:mm:ss");

    public AdminFrame(DataStore store) {
        super("카페 관리자 (서버 내장)");
        this.store = store;
        this.server = new CafeServer(store);

        server.setLog(this::appendLog);
        store.setLog(this::appendLog);
        server.setOnClientsChanged(() -> SwingUtilities.invokeLater(this::updateClientLabel));
        store.addListener(new DataStore.Listener() {
            @Override public void menuChanged() { SwingUtilities.invokeLater(AdminFrame.this::refreshMenu); }
            @Override public void orderChanged(Order o) { SwingUtilities.invokeLater(AdminFrame.this::refreshOrders); }
            @Override public void membersChanged() { SwingUtilities.invokeLater(AdminFrame.this::refreshMembers); }
        });

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { onClose(); }
        });

        setLayout(new BorderLayout(0, 0));
        add(buildTopBar(), BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("주문 관리", buildOrderTab());
        tabs.addTab("메뉴 · 재고", buildMenuTab());
        tabs.addTab("회원", buildMemberTab());
        tabs.addTab("서버 로그", buildLogTab());
        add(tabs, BorderLayout.CENTER);

        refreshOrders();
        refreshMenu();
        refreshMembers();
        appendLog("데이터베이스: " + store.getDbInfo());

        setSize(1000, 640);
        setLocationRelativeTo(null);
    }

    // ---------------------------------------------------------------- 상단 서버 바

    private JComponent buildTopBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY));
        bar.add(new JLabel("포트"));
        bar.add(portField);
        bar.add(serverBtn);
        serverStatus.setForeground(Color.GRAY);
        bar.add(serverStatus);
        bar.add(addressLabel);
        bar.add(clientLabel);
        serverBtn.addActionListener(e -> toggleServer());
        return bar;
    }

    private void toggleServer() {
        if (server.isRunning()) {
            server.stop();
            serverBtn.setText("서버 시작");
            portField.setEnabled(true);
            serverStatus.setText("● 서버 중지됨");
            serverStatus.setForeground(Color.GRAY);
            addressLabel.setText(" ");
            updateClientLabel();
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
            if (port < 1024 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "포트는 1024~65535 사이의 숫자로 입력해 주세요.");
            return;
        }
        try {
            server.start(port);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "서버를 시작할 수 없습니다.\n(이미 같은 포트를 쓰는 프로그램이 있을 수 있어요)\n" + e.getMessage(),
                    "서버 오류", JOptionPane.ERROR_MESSAGE);
            return;
        }
        serverBtn.setText("서버 중지");
        portField.setEnabled(false);
        serverStatus.setText("● 서버 실행 중");
        serverStatus.setForeground(new Color(0, 140, 60));
        List<String> ips = localAddresses();
        String addr = ips.isEmpty() ? "(IP를 찾지 못했습니다 - 와이파이 연결을 확인하세요)" : String.join("  /  ", ips);
        addressLabel.setText("회원 프로그램에 입력할 주소 ▶ " + addr + "  (포트 " + port + ")");
        updateClientLabel();
    }

    private void updateClientLabel() { clientLabel.setText("  접속자 " + server.loggedInCount() + "명"); }

    /** 이 노트북의 사설 IPv4 주소 목록 (와이파이/핫스팟에서 회원 프로그램이 접속할 주소) */
    static List<String> localAddresses() {
        List<String> list = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLinkLocalAddress()) list.add(a.getHostAddress());
                }
            }
        } catch (SocketException ignored) {}
        return list;
    }

    // ---------------------------------------------------------------- 주문 탭

    private JComponent buildOrderTab() {
        orderTable.setRowHeight(26);
        orderTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        orderTable.getTableHeader().setReorderingAllowed(false);
        int[] w = { 70, 140, 380, 80, 90, 110 };
        for (int i = 0; i < w.length; i++) orderTable.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
        orderTable.getColumnModel().getColumn(4).setCellRenderer(new StatusRenderer());

        JButton start = new JButton("제조 시작");
        JButton ready = new JButton("제조 완료 (수령 가능 알림)");
        JButton done = new JButton("수령 완료");
        JButton cancel = new JButton("주문 취소");
        JButton export = new JButton("주문 내역 CSV 저장");
        start.addActionListener(e -> changeStatus(Status.MAKING));
        ready.addActionListener(e -> changeStatus(Status.READY));
        done.addActionListener(e -> changeStatus(Status.DONE));
        cancel.addActionListener(e -> {
            int no = selectedOrderNo();
            if (no < 0) return;
            if (JOptionPane.showConfirmDialog(this, "#" + no + " 주문을 취소할까요? (재고가 복구됩니다)", "주문 취소",
                    JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            String err = store.cancelOrder(no, null);
            if (err != null) JOptionPane.showMessageDialog(this, err);
        });
        export.addActionListener(e -> exportCsv());
        showFinished.addActionListener(e -> refreshOrders());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        buttons.add(start);
        buttons.add(ready);
        buttons.add(done);
        buttons.add(cancel);
        buttons.add(Box.createHorizontalStrut(16));
        buttons.add(showFinished);
        buttons.add(export);

        JPanel p = new JPanel(new BorderLayout());
        p.add(buttons, BorderLayout.NORTH);
        p.add(new JScrollPane(orderTable), BorderLayout.CENTER);
        return p;
    }

    private int selectedOrderNo() {
        int row = orderTable.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "표에서 주문을 먼저 선택해 주세요.");
            return -1;
        }
        return (Integer) orderModel.getValueAt(orderTable.convertRowIndexToModel(row), 0);
    }

    private void changeStatus(Status next) {
        int no = selectedOrderNo();
        if (no < 0) return;
        String err = store.setStatus(no, next);
        if (err != null) JOptionPane.showMessageDialog(this, err);
    }

    private void exportCsv() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("주문내역_" + new SimpleDateFormat("yyyyMMdd_HHmm").format(new Date()) + ".csv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path p = fc.getSelectedFile().toPath();
        try {
            store.exportOrdersCsv(p);
            JOptionPane.showMessageDialog(this, "저장했습니다.\n" + p);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "저장 실패: " + e.getMessage(), "오류", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void refreshOrders() {
        int keepNo = -1;
        int sel = orderTable.getSelectedRow();
        if (sel >= 0) keepNo = (Integer) orderModel.getValueAt(sel, 0);

        orderModel.setRowCount(0);
        for (Order o : store.ordersSnapshot()) {
            if (!showFinished.isSelected() && o.status.isFinished()) continue;
            orderModel.addRow(new Object[] { o.no, o.memberName + " (" + o.memberId + ")", o.summary(),
                    UiUtil.won(o.total()), o.status.label, timeFmt.format(new Date(o.time)) });
        }
        if (keepNo >= 0) {
            for (int i = 0; i < orderModel.getRowCount(); i++) {
                if ((Integer) orderModel.getValueAt(i, 0) == keepNo) {
                    orderTable.setRowSelectionInterval(i, i);
                    break;
                }
            }
        }
    }

    /** 상태 칸 색상 */
    private static class StatusRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
            Component comp = super.getTableCellRendererComponent(t, v, sel, foc, r, c);
            setHorizontalAlignment(CENTER);
            if (!sel) {
                String s = String.valueOf(v);
                if (s.equals(Status.WAITING.label)) comp.setBackground(new Color(255, 245, 200));
                else if (s.equals(Status.MAKING.label)) comp.setBackground(new Color(205, 230, 255));
                else if (s.equals(Status.READY.label)) comp.setBackground(new Color(200, 245, 210));
                else comp.setBackground(new Color(235, 235, 235));
                comp.setForeground(Color.BLACK);
            }
            return comp;
        }
    }

    // ---------------------------------------------------------------- 메뉴 · 재고 탭

    private JComponent buildMenuTab() {
        menuTable.setRowHeight(26);
        menuTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        menuTable.getTableHeader().setReorderingAllowed(false);
        menuTable.getColumnModel().getColumn(0).setMaxWidth(50);

        JButton add = new JButton("메뉴 추가");
        JButton edit = new JButton("메뉴 수정");
        JButton del = new JButton("메뉴 삭제");
        JButton stock = new JButton("재고 수정");
        JButton sold = new JButton("품절 처리 / 해제");
        add.addActionListener(e -> {
            MenuItem m = menuDialog(null);
            if (m != null) store.addMenu(m.category, m.name, m.price, m.stock);
        });
        edit.addActionListener(e -> {
            MenuItem cur = selectedMenu();
            if (cur == null) return;
            MenuItem m = menuDialog(cur);
            if (m != null) store.updateMenu(cur.id, m.category, m.name, m.price, m.stock);
        });
        del.addActionListener(e -> {
            MenuItem cur = selectedMenu();
            if (cur == null) return;
            if (JOptionPane.showConfirmDialog(this, "'" + cur.name + "' 메뉴를 삭제할까요?", "메뉴 삭제",
                    JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) store.deleteMenu(cur.id);
        });
        stock.addActionListener(e -> {
            MenuItem cur = selectedMenu();
            if (cur == null) return;
            String s = JOptionPane.showInputDialog(this, "'" + cur.name + "' 재고 수량 (현재 " + cur.stock + "개)", cur.stock);
            if (s == null) return;
            try {
                int v = Integer.parseInt(s.trim());
                if (v < 0) throw new NumberFormatException();
                store.setStock(cur.id, v);
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "0 이상의 숫자를 입력해 주세요.");
            }
        });
        sold.addActionListener(e -> {
            MenuItem cur = selectedMenu();
            if (cur == null) return;
            store.setManualSoldOut(cur.id, !cur.manualSoldOut);
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        for (JButton b : new JButton[] { add, edit, del, stock, sold }) buttons.add(b);
        JPanel p = new JPanel(new BorderLayout());
        p.add(buttons, BorderLayout.NORTH);
        p.add(new JScrollPane(menuTable), BorderLayout.CENTER);
        JLabel hint = new JLabel("  재고가 0이 되면 자동으로 품절 처리되고, 회원 화면에서 주문 버튼이 비활성화됩니다.");
        hint.setForeground(Color.GRAY);
        p.add(hint, BorderLayout.SOUTH);
        return p;
    }

    private MenuItem selectedMenu() {
        int row = menuTable.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "표에서 메뉴를 먼저 선택해 주세요.");
            return null;
        }
        int id = (Integer) menuModel.getValueAt(menuTable.convertRowIndexToModel(row), 0);
        for (MenuItem m : store.menuSnapshot()) if (m.id == id) return m;
        return null;
    }

    private MenuItem menuDialog(MenuItem cur) {
        JComboBox<String> cat = new JComboBox<>(new String[] { "커피", "논커피", "에이드/티", "디저트" });
        cat.setEditable(true);
        JTextField name = new JTextField(14);
        JTextField price = new JTextField(8);
        JTextField stock = new JTextField(8);
        if (cur != null) {
            cat.setSelectedItem(cur.category);
            name.setText(cur.name);
            price.setText(String.valueOf(cur.price));
            stock.setText(String.valueOf(cur.stock));
        } else stock.setText("30");

        JPanel p = new JPanel(new GridLayout(0, 2, 6, 6));
        p.add(new JLabel("카테고리")); p.add(cat);
        p.add(new JLabel("메뉴 이름")); p.add(name);
        p.add(new JLabel("가격(원)")); p.add(price);
        p.add(new JLabel("재고(개)")); p.add(stock);
        while (true) {
            if (JOptionPane.showConfirmDialog(this, p, cur == null ? "메뉴 추가" : "메뉴 수정",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return null;
            try {
                String n = Protocol.clean(name.getText());
                String c = Protocol.clean(String.valueOf(cat.getSelectedItem()));
                int pr = Integer.parseInt(price.getText().trim());
                int st = Integer.parseInt(stock.getText().trim());
                if (n.isEmpty() || c.isEmpty() || pr < 0 || st < 0) throw new NumberFormatException();
                return new MenuItem(cur == null ? 0 : cur.id, c, n, pr, st, cur != null && cur.manualSoldOut);
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, "이름/카테고리를 입력하고, 가격과 재고는 0 이상의 숫자로 입력해 주세요.");
            }
        }
    }

    private void refreshMenu() {
        int keepId = -1;
        int sel = menuTable.getSelectedRow();
        if (sel >= 0) keepId = (Integer) menuModel.getValueAt(sel, 0);
        menuModel.setRowCount(0);
        for (MenuItem m : store.menuSnapshot()) {
		
		String state;
		
		if (m.manualSoldOut) {
		    state = "품절 (수동)";
		} else if (m.stock <= 0) {
		    state = "품절 (재고 소진)";
		} else if (m.stock <= 5) {
		    state = "재고 부족";
		} else {
		    state = "판매 중";
		}

            menuModel.addRow(new Object[] { m.id, m.category, m.name, UiUtil.won(m.price), m.stock, state });
        }
        if (keepId >= 0) {
            for (int i = 0; i < menuModel.getRowCount(); i++) {
                if ((Integer) menuModel.getValueAt(i, 0) == keepId) { menuTable.setRowSelectionInterval(i, i); break; }
            }
        }
    }

    // ---------------------------------------------------------------- 회원 / 로그

    private JComponent buildMemberTab() {
        JTable t = new JTable(memberModel);
        t.setRowHeight(26);
        JPanel p = new JPanel(new BorderLayout());
        p.add(new JScrollPane(t), BorderLayout.CENTER);
        p.add(memberCount, BorderLayout.SOUTH);
        return p;
    }

    private void refreshMembers() {
        memberModel.setRowCount(0);
        List<Member> list = store.membersSnapshot();
        for (Member m : list) memberModel.addRow(new Object[] { m.id, m.name, m.email });
        memberCount.setText("  가입 회원 " + list.size() + "명");
    }

    private JComponent buildLogTab() {
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        return new JScrollPane(logArea);
    }

    private void appendLog(String msg) {
        String line = "[" + logFmt.format(new Date()) + "] " + msg + "\n";
        SwingUtilities.invokeLater(() -> {
            logArea.append(line);
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void onClose() {
        if (server.isRunning()) {
            int r = JOptionPane.showConfirmDialog(this, "서버가 실행 중입니다. 종료하면 접속 중인 회원 프로그램의 연결이 끊어집니다.\n종료할까요?",
                    "종료", JOptionPane.YES_NO_OPTION);
            if (r != JOptionPane.YES_OPTION) return;
            server.stop();
        }
        dispose();
        System.exit(0);
    }

    private static DefaultTableModel readOnlyModel(String... cols) {
        return new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
    }
}
