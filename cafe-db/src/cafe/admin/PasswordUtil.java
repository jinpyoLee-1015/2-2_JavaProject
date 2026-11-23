package cafe.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 회원 비밀번호 저장용 해시 (PBKDF2-HMAC-SHA256 + 회원마다 다른 salt).
 *
 * 저장 형식: pbkdf2$반복횟수$salt(Base64)$해시(Base64)   예) pbkdf2$120000$Qk9...$3s8...
 *  - salt : 회원마다 무작위 16바이트. 비밀번호가 같아도 저장되는 값이 달라진다.
 *  - 반복 : 해시를 12만 번 반복해서, 비밀번호를 하나씩 대입해 보는 공격을 느리게 만든다.
 *
 * 예전 방식(SHA-256 한 번, 64자 16진수)으로 저장된 회원도 그대로 로그인할 수 있고,
 * 로그인에 성공하면 DataStore 가 새 방식으로 바꿔 저장한다. (needsUpgrade)
 */
final class PasswordUtil {

    private static final String PREFIX = "pbkdf2";
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 120_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordUtil() {}

    /** 새 비밀번호를 저장용 문자열로 만든다. (회원가입·업그레이드 때 사용) */
    static String hash(String pw) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] h = pbkdf2(pw, salt, ITERATIONS);
        Base64.Encoder b64 = Base64.getEncoder();
        return PREFIX + "$" + ITERATIONS + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(h);
    }

    /** 입력한 비밀번호가 저장된 값과 맞는지 확인 (새 방식·예전 방식 모두 지원) */
    static boolean verify(String id, String pw, String stored) {
        if (stored == null || pw == null) return false;
        if (!stored.startsWith(PREFIX + "$")) {
            // 예전 방식: SHA-256("cafe:" + 학번 + ":" + 비밀번호)
            return constantTimeEquals(legacyHash(id, pw), stored);
        }
        String[] p = stored.split("\\$");
        if (p.length != 4) return false;
        try {
            int iterations = Integer.parseInt(p[1]);
            byte[] salt = Base64.getDecoder().decode(p[2]);
            byte[] expected = Base64.getDecoder().decode(p[3]);
            byte[] actual = pbkdf2(pw, salt, iterations);
            return MessageDigest.isEqual(expected, actual);   // 비교 시간이 일정해서 시간 차이로 추측할 수 없다
        } catch (IllegalArgumentException e) {
            return false;   // 형식이 깨진 값
        }
    }

    /** 예전 방식이거나 반복 횟수가 지금 기준보다 적으면 true (로그인 성공 후 다시 저장할 대상) */
    static boolean needsUpgrade(String stored) {
        if (stored == null || !stored.startsWith(PREFIX + "$")) return true;
        String[] p = stored.split("\\$");
        try {
            return p.length != 4 || Integer.parseInt(p[1]) < ITERATIONS;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private static byte[] pbkdf2(String pw, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(pw.toCharArray(), salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 를 사용할 수 없습니다.", e);
        } finally {
            spec.clearPassword();
        }
    }

    /** 예전 저장 방식 (SHA-256 한 번) - 기존 회원 로그인 확인용으로만 남겨 둔다 */
    private static String legacyHash(String id, String pw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(("cafe:" + id + ":" + pw).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
