package cafe.common;

import javax.swing.*;
import javax.swing.plaf.FontUIResource;
import java.awt.*;
import java.util.Enumeration;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Swing 공통 유틸 */
public final class UiUtil {
    private UiUtil() {}

    /** 모든 Swing 컴포넌트의 기본 폰트를 한글 폰트로 통일 */
    public static void applyFont(String family, int size) {
        FontUIResource f = new FontUIResource(new Font(family, Font.PLAIN, size));
        Enumeration<Object> keys = UIManager.getDefaults().keys();
        while (keys.hasMoreElements()) {
            Object k = keys.nextElement();
            if (UIManager.get(k) instanceof FontUIResource) UIManager.put(k, f);
        }
    }

    /** 네트워크 작업을 백그라운드에서 실행하고 결과는 화면 스레드(EDT)에서 처리 */
    public static <T> void async(Callable<T> job, Consumer<T> ok, Consumer<Exception> fail) {
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return job.call(); }
            @Override protected void done() {
                T result;
                try {
                    result = get();
                } catch (Exception e) {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    fail.accept(c instanceof Exception ? (Exception) c : new Exception(c));
                    return;
                }
                ok.accept(result);
            }
        }.execute();
    }

    public static JPanel padded(JComponent c, int n) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(n, n, n, n));
        p.add(c, BorderLayout.CENTER);
        return p;
    }

    public static String won(int v) { return String.format("%,d원", v); }
}
