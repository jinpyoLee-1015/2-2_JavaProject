package cafe.common;

import java.util.ArrayList;
import java.util.List;

public class Order {
    public final int no;            // 주문 고유번호 (DB order_no, 취소·상태 변경에 사용)
    public final int dailyNo;       // 당일 대기번호 (매일 1번부터 시작, 0 이면 아직 없음)
    public final String memberId;   // 학번
    public final String memberName;
    public final List<OrderItem> items;
    public Status status;
    public final long time;

    public Order(int no, int dailyNo, String memberId, String memberName, List<OrderItem> items, Status status, long time) {
        this.no = no;
        this.dailyNo = dailyNo;
        this.memberId = memberId;
        this.memberName = memberName;
        this.items = new ArrayList<>(items);
        this.status = status;
        this.time = time;
    }

    /** 당일 대기번호 없이 만드는 기존 생성자 (통신으로 받은 주문 등) */
    public Order(int no, String memberId, String memberName, List<OrderItem> items, Status status, long time) {
        this(no, 0, memberId, memberName, items, status, time);
    }

    /** 화면에 보여 줄 대기번호: 당일 번호가 있으면 그 번호, 없으면 주문 고유번호 */
    public int displayNo() { return dailyNo > 0 ? dailyNo : no; }

    public int total() {
        int t = 0;
        for (OrderItem it : items) t += it.lineTotal();
        return t;
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        for (OrderItem it : items) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(it.summary());
        }
        return sb.toString();
    }

    /**
     * 통신용 필드 7개. 회원 화면이 f[7] 을 '앞 대기 팀 수'로 읽기 때문에 필드 순서와 개수는 바꾸지 않는다.
     * (당일 대기번호를 회원 화면에 보내려면 서버·회원 화면 담당과 규약을 함께 정할 것)
     */
    public String[] toFields() {
        return new String[] { String.valueOf(no), memberId, memberName, OrderItem.packList(items),
                String.valueOf(total()), status.name(), String.valueOf(time) };
    }

    public static Order fromFields(String[] f) {
        return new Order(Integer.parseInt(f[0]), f[1], f[2], OrderItem.unpackList(f[3]),
                Status.fromName(f[5]), Long.parseLong(f[6]));
    }

    public Order copy() { return new Order(no, dailyNo, memberId, memberName, items, status, time); }
}
