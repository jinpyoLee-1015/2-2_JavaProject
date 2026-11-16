package cafe.common;

import java.util.ArrayList;
import java.util.List;

public class Order {
    public final int no;            // 대기번호
    public final String memberId;   // 학번
    public final String memberName;
    public final List<OrderItem> items;
    public Status status;
    public final long time;

    public Order(int no, String memberId, String memberName, List<OrderItem> items, Status status, long time) {
        this.no = no;
        this.memberId = memberId;
        this.memberName = memberName;
        this.items = new ArrayList<>(items);
        this.status = status;
        this.time = time;
    }

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

    public String[] toFields() {
        return new String[] { String.valueOf(no), memberId, memberName, OrderItem.packList(items),
                String.valueOf(total()), status.name(), String.valueOf(time) };
    }

    public static Order fromFields(String[] f) {
        return new Order(Integer.parseInt(f[0]), f[1], f[2], OrderItem.unpackList(f[3]),
                Status.fromName(f[5]), Long.parseLong(f[6]));
    }

    public Order copy() { return new Order(no, memberId, memberName, items, status, time); }
}
