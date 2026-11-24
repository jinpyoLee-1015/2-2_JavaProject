package cafe.admin;
import cafe.common.*;
import cafe.common.MenuItem;
import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.net.*;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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
    // 표의 각 행에 해당하는 주문 고유번호 (표에는 당일 대기번호를 보여 주고, 상태 변경·취소는 이 번호로 한다)
    private final List<Integer> orderRowNos = new ArrayList<>();
    private final JCheckBox showFinished = new JCheckBox("수령 완료/취소 주문도 표시");
    private final JCheckBox orderSoundEnabled = new JCheckBox("새 주문 알림음", true);
    private final Set<Integer> knownOrderNumbers = new HashSet<>();
    private final DefaultTableModel menuModel = readOnlyModel("ID", "카테고리", "메뉴", "가격", "재고", "상태");
    private final JTable menuTable = new JTable(menuModel);
    private final DefaultTableModel memberModel = readOnlyModel("학번", "이름", "이메일");
    private final JLabel memberCount = new JLabel(" ");
    private final JTextField memberSearchField = new JTextField(18);
    private final TableRowSorter<DefaultTableModel> memberSorter = new TableRowSorter<>(memberModel);
    // 매출 통계 탭
    private static final DateTimeFormatter STATS_DAY_FMT = DateTimeFormatter.ofPattern("MM월 dd일 (E)", Locale.KOREAN);
    private final JComboBox<SalesPeriod> statsPeriodBox = new JComboBox<>(SalesPeriod.values());   // 일별 / 주별 / 월별
    private final JComboBox<LocalDate> statsDateBox = new JComboBox<>();                          // 기간 시작 날짜
    private final DefaultTableModel statsDayModel = readOnlyModel("날짜", "주문 건수", "판매 수량", "매출");
    private final JTabbedPane statsBottomTabs = new JTabbedPane();
    private final JLabel statsSummary = new JLabel(" ");
    private final DefaultTableModel statsMenuModel = readOnlyModel("메뉴", "판매 수량", "매출");
    private final DefaultTableModel statsOrderModel = readOnlyModel("대기번호", "주문 시각", "회원", "주문 내용", "금액", "상태");
    private final SimpleDateFormat statsTimeFmt = new SimpleDateFormat("MM월 dd일 HH시 mm분");
    private boolean updatingStatsDates;
    private final JTextArea logArea = new JTextArea();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("MM-dd HH:mm");
    private final SimpleDateFormat logFmt = new SimpleDateFormat("HH:mm:ss");
    public AdminFrame(DataStore store) {
        super("카페 관리자 (서버 내장)");
        this.store = store;
        this.server = new CafeServer(store);
        for (Order order : store.ordersSnapshot()) knownOrderNumbers.add(order.no);
        server.setLog(this::appendLog);
        store.setLog(this::appendLog);
        server.setOnClientsChanged(() -> SwingUtilities.invokeLater(this::updateClientLabel));
        store.addListener(new DataStore.Listener() {
            @Override public void menuChanged() { SwingUtilities.invokeLater(AdminFrame.this::refreshMenu); }
            @Override public void orderChanged(Order o) {
                SwingUtilities.invokeLater(() -> {
                    refreshOrders();
                    notifyNewOrder(o);
                    refreshStatsIfToday();
                });
            }
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
        tabs.addTab("매출 통계", buildStatsTab());
        tabs.addTab("서버 로그", buildLogTab());
        add(tabs, BorderLayout.CENTER);
        refreshOrders();
        refreshMenu();
        refreshMembers();
        refreshStats();
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
            int row = selectedOrderRow();
            if (row < 0) return;
            int no = orderRowNos.get(row);
            if (JOptionPane.showConfirmDialog(this, "대기번호 " + orderModel.getValueAt(row, 0)
                    + "번 주문을 취소할까요? (재고가 복구됩니다)", "주문 취소",
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
        buttons.add(orderSoundEnabled);
        buttons.add(export);
        JPanel p = new JPanel(new BorderLayout());
        p.add(buttons, BorderLayout.NORTH);
        p.add(new JScrollPane(orderTable), BorderLayout.CENTER);
        return p;
    }

    private void notifyNewOrder(Order order) {
        if (order.status != Status.WAITING || !knownOrderNumbers.add(order.no)) return;
        appendLog("새 주문 접수 대기번호 " + order.displayNo() + "번 - " + order.memberName);
        if (orderSoundEnabled.isSelected()) playOrderBell();
    }
    // WAV 파일 없이 코드로 부드러운 2음 카페 주문벨 생성
    private void playOrderBell() {
        Thread audioThread = new Thread(() -> {
            SourceDataLine line = null;
            try {
                final int sampleRate = 44100;
                AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
                line = AudioSystem.getSourceDataLine(format);
                line.open(format);
                line.start();
                double duration = 1.25;
                int samples = (int) (duration * sampleRate);
                byte[] pcm = new byte[samples * 2];
                double[] noteStarts = {0.0, 0.34};
                double[] fundamentals = {880.0, 659.25};
                for (int i = 0; i < samples; i++) {
                    double t = i / (double) sampleRate;
                    double sum = 0.0;
                    for (int n = 0; n < noteStarts.length; n++) {
                        double elapsed = t - noteStarts[n];
                        if (elapsed < 0) continue;
                        double attack = Math.min(1.0, elapsed / 0.015);
                        double fade = Math.exp(-4.7 * elapsed);
                        double f = fundamentals[n];
                        double fundamental = Math.sin(2 * Math.PI * f * elapsed);
                        double overtone = 0.22 * Math.sin(2 * Math.PI * f * 2 * elapsed);
                        double sparkle = 0.08 * Math.sin(2 * Math.PI * f * 3 * elapsed);
                        sum += attack * fade * (fundamental + overtone + sparkle);
                    }
                    sum = Math.max(-1.0, Math.min(1.0, sum * 0.44));
                    short value = (short) (sum * 32767);
                    pcm[2 * i] = (byte) (value & 0xff);
                    pcm[2 * i + 1] = (byte) ((value >>> 8) & 0xff);
                }
                int offset = 0;
                while (offset < pcm.length) {
                    offset += line.write(pcm, offset, pcm.length - offset);
                }
                line.drain();
            } catch (Exception ex) {
                appendLog("주문벨 재생 실패: " + ex.getMessage());
            } finally {
                if (line != null) {
                    line.stop();
                    line.close();
                }
            }
        }, "cafe-order-bell");
        audioThread.setDaemon(true);
        audioThread.start();
    }

    /** 선택한 주문의 표 행 번호 (선택 안 했으면 안내 후 -1) */
    private int selectedOrderRow() {
        int row = orderTable.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "표에서 주문을 먼저 선택해 주세요.");
            return -1;
        }
        return orderTable.convertRowIndexToModel(row);
    }

    /** 선택한 주문의 고유번호 (선택 안 했으면 -1) */
    private int selectedOrderNo() {
        int row = selectedOrderRow();
        return row < 0 ? -1 : orderRowNos.get(row);
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
        if (sel >= 0) keepNo = orderRowNos.get(orderTable.convertRowIndexToModel(sel));
        orderModel.setRowCount(0);
        orderRowNos.clear();
        for (Order o : store.ordersSnapshot()) {
            if (!showFinished.isSelected() && o.status.isFinished()) continue;
            orderRowNos.add(o.no);
            orderModel.addRow(new Object[] { o.displayNo(), o.memberName + " (" + o.memberId + ")", o.summary(),
                    UiUtil.won(o.total()), o.status.label, timeFmt.format(new Date(o.time)) });
        }
        if (keepNo >= 0) {
            int i = orderRowNos.indexOf(keepNo);
            if (i >= 0) {
                int v = orderTable.convertRowIndexToView(i);
                if (v >= 0) orderTable.setRowSelectionInterval(v, v);
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

    // ---------------------------------------------------------------- 매출 통계 탭
    // 일별 / 주별 / 월별 중 하나와 기간을 고르면 메뉴별 판매 수량·매출, 날짜별 매출, 주문 목록을 보여 주고 CSV 로 내보낸다.
    private SalesPeriod statsPeriod() {
        SalesPeriod p = (SalesPeriod) statsPeriodBox.getSelectedItem();
        return p == null ? SalesPeriod.DAY : p;
    }

    private JComponent buildStatsTab() {
        statsDateBox.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                    boolean sel, boolean focus) {
                super.getListCellRendererComponent(list, value, index, sel, focus);
                if (value instanceof LocalDate) {
                    LocalDate d = (LocalDate) value;
                    SalesPeriod p = statsPeriod();
                    setText(p.describe(d) + (p.contains(d, LocalDate.now()) ? "  - " + p.currentTag() : ""));
                }
                return this;
            }
        });
        statsDateBox.addActionListener(e -> { if (!updatingStatsDates) showStats(); });
        statsPeriodBox.addActionListener(e -> {
            refreshStats();   // 보던 날짜가 들어 있는 주·달로 바뀐다
            // 주별·월별은 날짜별 매출 표를, 일별은 주문 목록을 먼저 보여 준다
            statsBottomTabs.setSelectedIndex(statsPeriod() == SalesPeriod.DAY ? 1 : 0);
        });
        JButton refresh = new JButton("새로고침");
        JButton export = new JButton("매출 통계 CSV 저장");
        refresh.addActionListener(e -> refreshStats());
        export.addActionListener(e -> exportStatsCsv());
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        top.add(new JLabel("보기"));
        top.add(statsPeriodBox);
        top.add(new JLabel("기간"));
        top.add(statsDateBox);
        top.add(refresh);
        top.add(export);
        statsSummary.setFont(statsSummary.getFont().deriveFont(Font.BOLD, statsSummary.getFont().getSize2D() + 1f));
        statsSummary.setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 12));
        JPanel north = new JPanel(new BorderLayout());
        north.add(top, BorderLayout.NORTH);
        north.add(statsSummary, BorderLayout.SOUTH);
        JTable menuTable = new JTable(statsMenuModel);
        JTable dayTable = new JTable(statsDayModel);
        JTable orderTable = new JTable(statsOrderModel);
        int[][] widths = { { 260, 100, 120 }, { 200, 100, 100, 120 }, { 70, 150, 140, 380, 90, 80 } };
        JTable[] tables = { menuTable, dayTable, orderTable };
        for (int t = 0; t < tables.length; t++) {
            tables[t].setRowHeight(26);
            tables[t].getTableHeader().setReorderingAllowed(false);
            for (int i = 0; i < widths[t].length; i++) tables[t].getColumnModel().getColumn(i).setPreferredWidth(widths[t][i]);
        }
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        menuTable.getColumnModel().getColumn(1).setCellRenderer(right);
        menuTable.getColumnModel().getColumn(2).setCellRenderer(right);
        for (int i = 1; i <= 3; i++) dayTable.getColumnModel().getColumn(i).setCellRenderer(right);
        orderTable.getColumnModel().getColumn(4).setCellRenderer(right);
        JScrollPane menuScroll = new JScrollPane(menuTable);
        menuScroll.setBorder(BorderFactory.createTitledBorder("메뉴별 판매"));
        statsBottomTabs.addTab("날짜별 매출", new JScrollPane(dayTable));
        statsBottomTabs.addTab("주문 목록 (취소 제외)", new JScrollPane(orderTable));
        statsBottomTabs.setSelectedIndex(1);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, menuScroll, statsBottomTabs);
        split.setResizeWeight(0.4);
        split.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        JPanel p = new JPanel(new BorderLayout());
        p.add(north, BorderLayout.NORTH);
        p.add(split, BorderLayout.CENTER);
        return p;
    }
    /** 기간 목록을 다시 읽고(오늘이 든 기간은 주문이 없어도 포함), 고르고 있던 기간의 통계를 다시 보여 준다. */
    private void refreshStats() {
        SalesPeriod period = statsPeriod();
        LocalDate keep = (LocalDate) statsDateBox.getSelectedItem();
        if (keep != null) keep = period.start(keep);   // 일별 ↔ 주별 ↔ 월별로 바꿔도 보던 날짜가 든 기간을 고른다
        LocalDate current = period.start(LocalDate.now());
        List<LocalDate> starts = period.starts(store.orderDates(), LocalDate.now());
        updatingStatsDates = true;
        statsDateBox.removeAllItems();
        for (LocalDate d : starts) statsDateBox.addItem(d);
        statsDateBox.setSelectedItem(keep != null && starts.contains(keep) ? keep : current);
        updatingStatsDates = false;
        showStats();
    }
    /** 오늘이 든 기간을 보고 있을 때만 새로고침 (새 주문·취소가 생겼을 때 호출) */
    private void refreshStatsIfToday() {
        LocalDate sel = (LocalDate) statsDateBox.getSelectedItem();
        if (sel == null || statsPeriod().contains(sel, LocalDate.now())) refreshStats();
    }

    private void showStats() {
        LocalDate start = (LocalDate) statsDateBox.getSelectedItem();
        if (start == null) return;
        SalesPeriod period = statsPeriod();
        SalesReport r = store.salesReport(start, period.end(start));
        String text = period.describe(start) + "   총 매출 " + UiUtil.won(r.totalAmount())
                + "   ·   판매 " + r.totalQty() + "잔   ·   주문 " + r.orderCount() + "건"
                + (r.canceledCount > 0 ? "   ·   취소 " + r.canceledCount + "건" : "");
        if (period != SalesPeriod.DAY) {
            text += "   ·   하루 평균 " + UiUtil.won(r.averagePerDay()) + " (영업 " + r.salesDays() + "일)";
        }
        statsSummary.setText(text);
        statsMenuModel.setRowCount(0);
        for (SalesReport.MenuLine m : r.menuLines) {
            statsMenuModel.addRow(new Object[] { m.menuName, m.qty + "잔", UiUtil.won(m.amount) });
        }
        if (r.menuLines.isEmpty()) statsMenuModel.addRow(new Object[] { "(판매 내역 없음)", "", "" });
        statsDayModel.setRowCount(0);
        SalesReport.DayLine best = r.bestDay();
        for (SalesReport.DayLine d : r.dayLines) {
            String day = d.date.format(STATS_DAY_FMT) + (r.dayLines.size() > 1 && d == best ? "  ★ 최고" : "");
            statsDayModel.addRow(new Object[] { day, d.orderCount + "건", d.qty + "잔", UiUtil.won(d.amount) });
        }
        if (r.dayLines.isEmpty()) statsDayModel.addRow(new Object[] { "(판매 내역 없음)", "", "", "" });
        statsOrderModel.setRowCount(0);
        for (Order o : r.orders) {
            statsOrderModel.addRow(new Object[] { o.displayNo(), statsTimeFmt.format(new Date(o.time)),
                    o.memberName + " (" + o.memberId + ")", o.summary(), UiUtil.won(o.total()), o.status.label });
        }
    }

    private void exportStatsCsv() {
        LocalDate start = (LocalDate) statsDateBox.getSelectedItem();
        if (start == null) return;
        SalesPeriod period = statsPeriod();
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("매출통계_" + period.label + "_" + period.fileTag(start) + ".csv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path p = fc.getSelectedFile().toPath();
        try {
            store.exportSalesCsv(period, start, p);
            JOptionPane.showMessageDialog(this, "저장했습니다.\n" + p);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "저장 실패: " + e.getMessage(), "오류", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ---------------------------------------------------------------- 회원 / 로그
    private JComponent buildMemberTab() {
        JTable t = new JTable(memberModel);
        t.setRowHeight(26);
        t.setRowSorter(memberSorter);
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JButton searchBtn = new JButton("검색");
        JButton resetBtn = new JButton("초기화");
        JButton withdrawBtn = new JButton("강제 탈퇴");
        withdrawBtn.addActionListener(e -> forceWithdrawMember(t));
        searchBtn.addActionListener(e -> searchMembers());
        memberSearchField.addActionListener(e -> searchMembers());
        resetBtn.addActionListener(e -> {
            memberSearchField.setText("");
            memberSorter.setRowFilter(null);
            updateMemberCount();
        });
        JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        searchPanel.add(new JLabel("학번 또는 이름"));
        searchPanel.add(memberSearchField);
        searchPanel.add(searchBtn);
        searchPanel.add(resetBtn);
        searchPanel.add(withdrawBtn);
        JPanel p = new JPanel(new BorderLayout());
        p.add(searchPanel, BorderLayout.NORTH);
        p.add(new JScrollPane(t), BorderLayout.CENTER);
        p.add(memberCount, BorderLayout.SOUTH);
        return p;
    }

    private void forceWithdrawMember(JTable table) {
        int row = table.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "탈퇴시킬 회원을 먼저 선택해 주세요.");
            return;
        }
        int modelRow = table.convertRowIndexToModel(row);
        String id = String.valueOf(memberModel.getValueAt(modelRow, 0));
        String name = String.valueOf(memberModel.getValueAt(modelRow, 1));
        if (JOptionPane.showConfirmDialog(this, name + " (" + id + ") 회원을 강제 탈퇴시킬까요?",
                "회원 강제 탈퇴", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
        String err = server.forceWithdraw(id);
        if (err != null) {
            JOptionPane.showMessageDialog(this, err, "탈퇴 실패", JOptionPane.ERROR_MESSAGE);
            return;
        }
        refreshMembers();
        JOptionPane.showMessageDialog(this, "회원 강제 탈퇴가 완료되었습니다.");
    }

    private void searchMembers() {
        String keyword = memberSearchField.getText().trim();
        if (keyword.isEmpty()) {
            memberSorter.setRowFilter(null);
        } else {
            String pattern = "(?i)" + java.util.regex.Pattern.quote(keyword);
            memberSorter.setRowFilter(RowFilter.regexFilter(pattern, 0, 1));
        }
        updateMemberCount();
    }

    private void updateMemberCount() {
        int total = memberModel.getRowCount();
        int shown = memberSorter.getViewRowCount();
        if (memberSearchField.getText().trim().isEmpty()) {
            memberCount.setText("  가입 회원 " + total + "명");
        } else {
            memberCount.setText("  검색 결과 " + shown + "명 / 전체 " + total + "명");
        }
    }

    private void refreshMembers() {
        memberModel.setRowCount(0);
        List<Member> list = store.membersSnapshot();
        for (Member m : list) memberModel.addRow(new Object[] { m.id, m.name, m.email });
        updateMemberCount();
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
