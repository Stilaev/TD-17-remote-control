package ru.td17.pult;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Главный экран. Интерфейс пульта нарисован в assets/index.html,
 * а связь с модулем (Bluetooth MIDI и USB) делает MidiLink через системный MidiManager.
 */
public class MainActivity extends Activity {

    private static final int REQ_BLE = 7;

    private WebView web;
    private MidiLink link;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable pendingGranted;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        web.setBackgroundColor(0xFF121416);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        web.setWebViewClient(new WebViewClient());

        link = new MidiLink(this, this::emit);
        web.addJavascriptInterface(new Bridge(), "TDNative");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    /** Передаёт событие в интерфейс: window.tdNativeEvent({...}). */
    void emit(JSONObject event) {
        final String js = "window.tdNativeEvent&&window.tdNativeEvent(" + event.toString() + ")";
        main.post(() -> {
            if (web != null) web.evaluateJavascript(js, null);
        });
    }

    /** Методы, которые вызывает JavaScript интерфейса. Вызываются не в главном потоке. */
    final class Bridge {
        @JavascriptInterface
        public void connect(String kind, boolean showAll) {
            main.post(() -> {
                if ("ble".equals(kind)) withBlePermissions(() -> link.connectBle(showAll));
                else link.connectUsb();
            });
        }

        @JavascriptInterface
        public void disconnect() {
            main.post(() -> link.close(true));
        }

        @JavascriptInterface
        public void send(String hex) {
            link.send(hexToBytes(hex));
        }
    }

    // ---------- Разрешения Bluetooth ----------

    private void withBlePermissions(Runnable onGranted) {
        String[] needed = Build.VERSION.SDK_INT >= 31
                ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        List<String> missing = new ArrayList<>();
        for (String p : needed) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (missing.isEmpty()) {
            onGranted.run();
            return;
        }
        pendingGranted = onGranted;
        requestPermissions(missing.toArray(new String[0]), REQ_BLE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_BLE) return;
        boolean ok = grantResults.length > 0;
        for (int r : grantResults) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
        Runnable r = pendingGranted;
        pendingGranted = null;
        if (ok && r != null) {
            r.run();
        } else {
            link.error(Build.VERSION.SDK_INT >= 31
                    ? "Нет разрешения «Устройства поблизости». Разрешите его в настройках приложения и попробуйте снова."
                    : "Нет разрешения на геолокацию — без него Android не ищет Bluetooth-устройства.");
        }
    }

    // ---------- Жизненный цикл ----------

    @Override
    public void onBackPressed() {
        if (web == null) {
            super.onBackPressed();
            return;
        }
        // Сначала закрываем открытый лист интерфейса, потом выходим.
        web.evaluateJavascript("window.tdBack?String(window.tdBack()):'false'", value -> {
            if (value == null || !value.contains("true")) finish();
        });
    }

    @Override
    protected void onDestroy() {
        link.close(true);
        if (web != null) {
            web.removeJavascriptInterface("TDNative");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    static byte[] hexToBytes(String hex) {
        String[] parts = hex.trim().split("\\s+");
        byte[] out = new byte[parts.length];
        int n = 0;
        for (String p : parts) {
            if (p.isEmpty()) continue;
            out[n++] = (byte) Integer.parseInt(p, 16);
        }
        if (n == out.length) return out;
        byte[] trimmed = new byte[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }
}
