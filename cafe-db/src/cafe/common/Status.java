package cafe.common;

/** 주문 진행 상태 (접수 대기 > 제조 중 > 수령 가능 > 수령 완료 / 취소) */
public enum Status {
    WAITING("접수 대기"),
    MAKING("제조 중"),
    READY("수령 가능"),
    DONE("수령 완료"),
    CANCELED("취소");

    public final String label;

    Status(String label) { this.label = label; }

    /** 대기열에 포함되는 상태 (아직 제조가 끝나지 않은 주문) */
    public boolean isQueued() { return this == WAITING || this == MAKING; }

    public boolean isFinished() { return this == DONE || this == CANCELED; }

    public static Status fromName(String s) {
        try { return valueOf(s); } catch (Exception e) { return WAITING; }
    }
}
