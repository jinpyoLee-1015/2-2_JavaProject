package cafe.member;

import cafe.common.*;
import cafe.common.MenuItem;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** 회원 주문 화면: 메뉴 조회 · 옵션 선택 · 장바구니 · 주문 · 대기번호/진행 상태 확인 */
public class MainFrame extends JFrame implements ServerConnection.PushListener {
    private final ServerConnection conn;
    private final String myId;
    private final String myName;
    private final String host;

    private final List<MenuItem> menu = new ArrayList<>();
    private final List<OrderItem> cart = new ArrayList<>();
    private final List<Order> myOrders = new ArrayList<>();
    private boolean leaving;

    private final JLabel queueLabel = new JLabel("현재 대기 - ");
    private final JTabbedPane tabs = new JTabbedPane();

    // 메뉴 탭
    private final DefaultTableModel menuModel = readOnly("카테고리", "메뉴", "가격", "상태");
    private final JTable menuTable = new JTable(menuModel);
    private final JComboBox<String> sizeBox = new JComboBox<>(new String[] { Protocol.SIZE_BASIC, Protocol.SIZE_LARGE + " (+" + Protocol.SIZE_UP_PRICE + "원)" });
    private final JSpinner shotSpin = new JSpinner(new SpinnerNumberModel(0, 0, Protocol.MAX_SHOTS, 1));
    private final JSpinner qtySpin = new JSpinner(new SpinnerNumberModel(1, 1, Protocol.MAX_QTY, 1));
    private final JLabel priceLabel = new JLabel(" ");
    private final JButton addBtn = new JButton("장바구니 담기");

    // 장바구니 탭
    private final DefaultTableModel cartModel = readOnly("메뉴", "옵션", "수량", "단가", "소계");
    private final JTable cartTable = new JTable(cartModel);
    private final JLabel totalLabel = new JLabel("합계 0원");

    // 내 주문 탭
    private final DefaultTableModel orderModel = readOnly("대기번호", "주문 내용", "금액", "상태", "내 앞 대기", "주문 시각");
    private final JTable orderTable = new JTable(orderModel);
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("MM-dd HH:mm");

    public MainFrame(ServerConnection conn, String myId, String myName, String host) {
        super("교내 카페 사전 주문 - " + myName + "님");
        this.conn = conn;
        this.myId = myId;
        this.myName = myName;
        this.host = host;

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { leave(false); }
        });

        add(buildHeader(), BorderLayout.NORTH);
        tabs.addTab("메뉴 주문", buildMenuTab());
        tabs.addTab("장바구니", buildCartTab());
        tabs.addTab("내 주문 / 대기 현황", buildOrdersTab());
        add(tabs, BorderLayout.CENTER);

        conn.setListener(this);
        refreshAll();

        setSize(860, 600);
        setLocationRelativeTo(null);
    }

    // ---------------------------------------------------------------- 화면 구성

    private JComponent buildHeader() {
        JPanel h = new JPanel(new BorderLayout());
        h.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        JLabel hello = new JLabel(myName + "님 (" + myId + ")  |  서버 " + host);
        queueLabel.setFont(queueLabel.getFont().deriveFont(Font.BOLD));
        queueLabel.setForeground(new Color(180, 70, 0));
        JButton logout = new JButton("로그아웃");
        JButton withdraw = new JButton("회원탈퇴");
        logout.addActionListener(e -> leave(false));
        withdraw.addActionListener(e -> doWithdraw());
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.add(logout);
        right.add(withdraw);
        h.add(hello, BorderLayout.WEST);
        h.add(queueLabel, BorderLayout.CENTER);
        queueLabel.setHorizontalAlignment(SwingConstants.CENTER);
        h.add(right, BorderLayout.EAST);
        h.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY), h.getBorder()));
        return h;
    }

    private JComponent buildMenuTab() {
        menuTable.setRowHeight(28);
        menuTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        menuTable.getTableHeader().setReorderingAllowed(false);
        menuTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
                Component comp = super.getTableCellRendererComponent(t, v, sel, foc, r, c);
                int idx = t.convertRowIndexToModel(r);
                boolean sold = idx < menu.size() && menu.get(idx).isSoldOut();
                if (!sel) comp.setForeground(sold ? Color.LIGHT_GRAY : Color.BLACK);
                if (c == 2) setHorizontalAlignment(RIGHT); else setHorizontalAlignment(LEFT);
                return comp;
            }
        });
        menuTable.getSelectionModel().addListSelectionListener(e -> updateMenuControls());
        sizeBox.addActionListener(e -> updateMenuControls());
        shotSpin.addChangeListener(e -> updateMenuControls());
        qtySpin.addChangeListener(e -> updateMenuControls());
        addBtn.addActionListener(e -> addToCart());

        JPanel opt = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        opt.add(new JLabel("사이즈"));
        opt.add(sizeBox);
        opt.add(new JLabel("샷 추가 (+" + Protocol.SHOT_PRICE + "원)"));
        opt.add(shotSpin);
        opt.add(new JLabel("수량"));
        opt.add(qtySpin);
        opt.add(priceLabel);
        opt.add(addBtn);

        JPanel p = new JPanel(new BorderLayout());
        p.add(new JScrollPane(menuTable), BorderLayout.CENTER);
        p.add(opt, BorderLayout.SOUTH);
        return p;
    }

    private JComponent buildCartTab() {
        cartTable.setRowHeight(28);
        cartTable.getTableHeader().setReorderingAllowed(false);
        JButton remove = new JButton("선택 삭제");
        JButton clear = new JButton("비우기");
        JButton order = new JButton("주문하기");
        order.setFont(order.getFont().deriveFont(Font.BOLD));
        remove.addActionListener(e -> {
            int r = cartTable.getSelectedRow();
            if (r >= 0) { cart.remove(r); refreshCart(); }
        });
        clear.addActionListener(e -> { cart.clear(); refreshCart(); });
        order.addActionListener(e -> placeOrder());

        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 8));
        totalLabel.setFont(totalLabel.getFont().deriveFont(Font.BOLD, 15f));
        south.add(totalLabel);
        south.add(remove);
        south.add(clear);
        south.add(order);

        JPanel p = new JPanel(new BorderLayout());
        p.add(new JScrollPane(cartTable), BorderLayout.CENTER);
        p.add(south, BorderLayout.SOUTH);
        JLabel note = new JLabel("  결제는 매장 수령 시 진행됩니다. (이 프로그램은 결제 기능이 없습니다)");
        note.setForeground(Color.GRAY);
        p.add(note, BorderLayout.NORTH);
        return p;
    }

    private JComponent buildOrdersTab() {
        orderTable.setRowHeight(28);
        orderTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        orderTable.getTableHeader().setReorderingAllowed(false);
        int[] w = { 70, 330, 80, 90, 80, 100 };
        for (int i = 0; i < w.length; i++) orderTable.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
        orderTable.getColumnModel().getColumn(3).setCellRenderer(new DefaultTableCellRenderer() {
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
        });
        JButton refresh = new JButton("새로고침");
        JButton cancel = new JButton("선택한 주문 취소 (접수 대기만)");
        refresh.addActionListener(e -> refreshAll());
        cancel.addActionListener(e -> cancelSelected());
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        top.add(refresh);
        top.add(cancel);
        JPanel p = new JPanel(new BorderLayout());
        p.add(top, BorderLayout.NORTH);
        p.add(new JScrollPane(orderTable), BorderLayout.CENTER);
        return p;
    }

    // ---------------------------------------------------------------- 메뉴 / 장바구니 동작

    private MenuItem selectedMenu() {
        int r = menuTable.getSelectedRow();
        if (r < 0) return null;
        int idx = menuTable.convertRowIndexToModel(r);
        return idx < menu.size() ? menu.get(idx) : null;
    }

    private String currentSize() {
        return sizeBox.getSelectedIndex() == 1 ? Protocol.SIZE_LARGE : Protocol.SIZE_BASIC;
    }

    private void updateMenuControls() {
        MenuItem m = selectedMenu();
        if (m == null) {
            priceLabel.setText("메뉴를 선택하세요");
            addBtn.setEnabled(false);
            return;
        }
        int unit = OrderItem.calcUnitPrice(m.price, currentSize(), (Integer) shotSpin.getValue());
        int qty = (Integer) qtySpin.getValue();
        if (m.isSoldOut()) {
            priceLabel.setText("품절된 메뉴입니다");
            addBtn.setEnabled(false);
        } else {
            priceLabel.setText("합계 " + UiUtil.won(unit * qty));
            addBtn.setEnabled(true);
        }
    }

    private void addToCart() {
        MenuItem m = selectedMenu();
        if (m == null || m.isSoldOut()) return;
        String size = currentSize();
        int shots = (Integer) shotSpin.getValue();
        int qty = (Integer) qtySpin.getValue();
        OrderItem item = new OrderItem(m.id, m.name, size, shots, qty, OrderItem.calcUnitPrice(m.price, size, shots));

        for (int i = 0; i < cart.size(); i++) {
            OrderItem o = cart.get(i);
            if (o.sameAs(item)) {
                int merged = Math.min(Protocol.MAX_QTY, o.qty + qty);
                cart.set(i, new OrderItem(o.menuId, o.name, o.size, o.shots, merged, item.unitPrice));
                refreshCart();
                tabs.setSelectedIndex(1);
                return;
            }
        }
        cart.add(item);
        refreshCart();
        qtySpin.setValue(1);
        shotSpin.setValue(0);
        JOptionPane.showMessageDialog(this, "'" + m.name + "'을(를) 장바구니에 담았습니다.", "장바구니", JOptionPane.INFORMATION_MESSAGE);
    }

    private void refreshCart() {
        cartModel.setRowCount(0);
        int total = 0;
        for (OrderItem it : cart) {
            cartModel.addRow(new Object[] { it.name, it.optionText(), it.qty, UiUtil.won(it.unitPrice), UiUtil.won(it.lineTotal()) });
            total += it.lineTotal();
        }
        totalLabel.setText("합계 " + UiUtil.won(total));
        tabs.setTitleAt(1, cart.isEmpty() ? "장바구니" : "장바구니 (" + cart.size() + ")");
    }

    private void placeOrder() {
        if (cart.isEmpty()) {
            JOptionPane.showMessageDialog(this, "장바구니가 비어 있습니다.");
            return;
        }
        int total = 0;
        for (OrderItem it : cart) total += it.lineTotal();
        if (JOptionPane.showConfirmDialog(this, "총 " + UiUtil.won(total) + " 주문할까요?", "주문 확인",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        String packed = OrderItem.packList(new ArrayList<>(cart));
        UiUtil.async(() -> conn.request("ORDER", packed), r -> {
            if (r[0].equals("OK")) {
                cart.clear();
                refreshCart();
                JOptionPane.showMessageDialog(this,
                        "주문이 접수되었습니다!\n\n대기번호: " + r[1] + "번\n내 앞 대기: " + r[2] + "팀\n예상 소요 시간: 약 " + r[3] + "분",
                        "주문 완료", JOptionPane.INFORMATION_MESSAGE);
                tabs.setSelectedIndex(2);
            } else {
                JOptionPane.showMessageDialog(this, r[1], "주문 실패", JOptionPane.WARNING_MESSAGE);
            }
            refreshAll();
        }, this::onError);
    }

    // ---------------------------------------------------------------- 서버에서 데이터 가져오기

    private void refreshAll() {
        refreshMenu();
        refreshOrders();
        refreshQueue();
    }

    private void refreshMenu() {
        UiUtil.async(() -> conn.request("MENU"), r -> {
            if (!r[0].equals("OK")) return;
            int keepId = -1;
            MenuItem sel = selectedMenu();
            if (sel != null) keepId = sel.id;
            menu.clear();
            menuModel.setRowCount(0);
            for (int i = 1; i < r.length; i++) {
                MenuItem m = MenuItem.fromFields(Protocol.unpack(r[i]));
                menu.add(m);
                menuModel.addRow(new Object[] { m.category, m.name, UiUtil.won(m.price), m.isSoldOut() ? "품절" : "주문 가능" });
            }
            for (int i = 0; i < menu.size(); i++) {
                if (menu.get(i).id == keepId) { menuTable.setRowSelectionInterval(i, i); break; }
            }
            updateMenuControls();
        }, this::onError);
    }

    private void refreshOrders() {
        UiUtil.async(() -> conn.request("MYORDERS"), r -> {
            if (!r[0].equals("OK")) return;
            myOrders.clear();
            orderModel.setRowCount(0);
            List<Object[]> rows = new ArrayList<>();
            for (int i = 1; i < r.length; i++) {
                String[] f = Protocol.unpack(r[i]);
                Order o = Order.fromFields(f);
                int ahead = Integer.parseInt(f[7]);
                myOrders.add(o);
                rows.add(0, new Object[] { o.no, o.summary(), UiUtil.won(o.total()), o.status.label,
                        o.status.isQueued() ? ahead + "팀" : "-", timeFmt.format(new Date(o.time)) });
            }
            // 최근 주문이 위로 오도록 표시 (myOrders 도 같은 순서로 맞춘다)
            java.util.Collections.reverse(myOrders);
            for (Object[] row : rows) orderModel.addRow(row);
        }, this::onError);
    }

    private void refreshQueue() {
        UiUtil.async(() -> conn.request("QUEUE"), r -> {
            if (r[0].equals("OK")) queueLabel.setText("현재 대기 " + r[1] + "팀 · 예상 약 " + r[2] + "분");
        }, this::onError);
    }

    private void cancelSelected() {
        int row = orderTable.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "취소할 주문을 선택해 주세요.");
            return;
        }
        int no = (Integer) orderModel.getValueAt(orderTable.convertRowIndexToModel(row), 0);
        if (JOptionPane.showConfirmDialog(this, "대기번호 " + no + "번 주문을 취소할까요?", "주문 취소",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        UiUtil.async(() -> conn.request("CANCEL", String.valueOf(no)), r -> {
            if (!r[0].equals("OK")) JOptionPane.showMessageDialog(this, r[1], "취소 불가", JOptionPane.WARNING_MESSAGE);
            refreshAll();
        }, this::onError);
    }

    // ---------------------------------------------------------------- 서버 알림 (PUSH)

    @Override public void onPush(String[] f) {
        SwingUtilities.invokeLater(() -> {
            if (leaving) return;
            if (f.length >= 2 && f[1].equals("MENU")) refreshMenu();
            if (f.length >= 5 && f[1].equals("ORDER")) {
                refreshOrders();
                refreshQueue();
                if (f[4].equals(myId)) notifyMyOrder(f[2], Status.fromName(f[3]));
            }
        });
    }

    private void notifyMyOrder(String no, Status st) {
        if (st == Status.READY) {
            Toolkit.getDefaultToolkit().beep();
            toFront();
            JOptionPane.showMessageDialog(this, "대기번호 " + no + "번 음료가 준비되었어요!\n수령대에서 받아가 주세요.",
                    "수령 가능", JOptionPane.INFORMATION_MESSAGE);
        } else if (st == Status.MAKING) {
            queueLabel.setToolTipText("대기번호 " + no + "번 제조 시작");
        } else if (st == Status.CANCELED) {
            JOptionPane.showMessageDialog(this, "대기번호 " + no + "번 주문이 취소되었습니다.", "주문 취소", JOptionPane.WARNING_MESSAGE);
        }
    }

    @Override public void onDisconnected() {
        SwingUtilities.invokeLater(() -> {
            if (leaving) return;
            leaving = true;
            JOptionPane.showMessageDialog(this, "서버와 연결이 끊어졌습니다.\n로그인 화면으로 돌아갑니다.", "연결 끊김", JOptionPane.WARNING_MESSAGE);
            backToLogin();
        });
    }

    private void onError(Exception e) {
        if (leaving) return;
        if (conn.isClosed()) return; // onDisconnected 가 처리
        JOptionPane.showMessageDialog(this, "서버와 통신하지 못했습니다.\n" + e.getMessage(), "통신 오류", JOptionPane.ERROR_MESSAGE);
    }

    // ---------------------------------------------------------------- 로그아웃 / 탈퇴

    private void leave(boolean ignored) {
        if (leaving) return;
        leaving = true;
        UiUtil.async(() -> { try { conn.request("LOGOUT"); } catch (Exception ignore) {} return null; },
                r -> backToLogin(), e -> backToLogin());
    }

    private void doWithdraw() {
        JPasswordField pw = new JPasswordField(14);
        JPanel p = new JPanel(new GridLayout(0, 1, 4, 4));
        p.add(new JLabel("회원탈퇴를 진행합니다. 비밀번호를 입력해 주세요."));
        p.add(pw);
        if (JOptionPane.showConfirmDialog(this, p, "회원탈퇴", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        String pass = new String(pw.getPassword());
        UiUtil.async(() -> conn.request("WITHDRAW", pass), r -> {
            if (r[0].equals("OK")) {
                leaving = true;
                JOptionPane.showMessageDialog(this, "탈퇴가 완료되었습니다.");
                backToLogin();
            } else {
                JOptionPane.showMessageDialog(this, r[1], "탈퇴 불가", JOptionPane.WARNING_MESSAGE);
            }
        }, this::onError);
    }

    private void backToLogin() {
        conn.close();
        dispose();
        new LoginFrame().setVisible(true);
    }

    private static DefaultTableModel readOnly(String... cols) {
        return new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
    }
}
