package cafe.common;

public class Member {
    public final String id;      // 학번
    public final String name;
    public final String email;
    public final String pwHash;

    public Member(String id, String name, String email, String pwHash) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.pwHash = pwHash;
    }
}
