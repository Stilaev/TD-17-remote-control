package ru.td17.pult;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiInputPort;
import android.media.midi.MidiManager;
import android.media.midi.MidiOutputPort;
import android.media.midi.MidiReceiver;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.widget.ArrayAdapter;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Подключение к модулю через системный Android MIDI:
 * Bluetooth MIDI (поиск + MidiManager.openBluetoothDevice) или USB (MidiManager.getDevices).
 * Android сам упаковывает SysEx в BLE-пакеты; здесь входящий поток собирается в целые сообщения.
 */
final class MidiLink {

    interface Sink {
        void event(JSONObject e);
    }

    private static final UUID MIDI_SERVICE = UUID.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700");
    private static final long SCAN_MS = 20000;

    private final Context ctx;
    private final Sink sink;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MidiManager midi;

    private MidiDevice device;
    private MidiDeviceInfo info;
    private MidiInputPort toModule;     // сюда пишем — данные уходят в модуль
    private MidiOutputPort fromModule;  // отсюда читаем — данные от модуля

    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private AlertDialog picker;
    private final Runnable stopScanLater = this::stopScan;

    MidiLink(Context ctx, Sink sink) {
        this.ctx = ctx;
        this.sink = sink;
        this.midi = (MidiManager) ctx.getSystemService(Context.MIDI_SERVICE);
        if (midi != null) {
            midi.registerDeviceCallback(new MidiManager.DeviceCallback() {
                @Override
                public void onDeviceRemoved(MidiDeviceInfo removed) {
                    if (info != null && removed.getId() == info.getId()) {
                        close(false);
                    }
                }
            }, main);
        }
    }

    // ---------- Bluetooth ----------

    @SuppressLint("MissingPermission")
    void connectBle(final boolean showAll) {
        if (midi == null) {
            error("На этом телефоне нет поддержки MIDI.");
            return;
        }
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null) {
            error("На этом телефоне нет Bluetooth.");
            return;
        }
        if (!adapter.isEnabled()) {
            error("Bluetooth выключен. Включите его в шторке телефона и нажмите «Подключить» ещё раз.");
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            error("Не удалось начать поиск Bluetooth-устройств.");
            return;
        }

        final List<BluetoothDevice> found = new ArrayList<>();
        final ArrayAdapter<String> rows = new ArrayAdapter<>(ctx, android.R.layout.simple_list_item_1, new ArrayList<String>());

        picker = new AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(showAll ? "Все устройства рядом" : "Поиск TD-17 MIDI…")
                .setAdapter(rows, (d, which) -> {
                    stopScan();
                    openBle(found.get(which));
                })
                .setNegativeButton("Отмена", (d, w) -> {
                    stopScan();
                    emitType("cancel");
                })
                .setOnCancelListener(d -> {
                    stopScan();
                    emitType("cancel");
                })
                .create();

        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice dev = result.getDevice();
                ScanRecord rec = result.getScanRecord();
                String name = rec != null ? rec.getDeviceName() : null;
                if (name == null) {
                    try {
                        name = dev.getName();
                    } catch (SecurityException ignored) {
                    }
                }
                boolean isMidi = false;
                if (rec != null && rec.getServiceUuids() != null) {
                    for (ParcelUuid u : rec.getServiceUuids()) {
                        if (MIDI_SERVICE.equals(u.getUuid())) isMidi = true;
                    }
                }
                boolean looksTd = name != null && name.toUpperCase(Locale.ROOT).contains("TD-17");
                if (!showAll && !isMidi && !looksTd) return;
                for (BluetoothDevice f : found) {
                    if (f.getAddress().equals(dev.getAddress())) return;
                }
                found.add(dev);
                rows.add((name == null ? "Без имени" : name) + (isMidi ? "   ·  MIDI" : "") + "\n" + dev.getAddress());
            }

            @Override
            public void onScanFailed(int errorCode) {
                stopScan();
                if (picker != null) picker.dismiss();
                error("Поиск Bluetooth не запустился (код " + errorCode + "). Выключите и включите Bluetooth.");
            }
        };

        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        try {
            scanner.startScan(null, settings, scanCallback);
        } catch (SecurityException e) {
            error("Нет разрешения на поиск Bluetooth-устройств.");
            return;
        }
        picker.show();
        main.postDelayed(stopScanLater, SCAN_MS);
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        main.removeCallbacks(stopScanLater);
        if (scanner != null && scanCallback != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (Exception ignored) {
            }
        }
        scanCallback = null;
        if (picker != null && picker.isShowing()) picker.setTitle("Выберите устройство");
    }

    private void openBle(BluetoothDevice dev) {
        close(true);
        emitStatus("Подключаюсь к " + safeName(dev) + "…");
        midi.openBluetoothDevice(dev, d -> {
            if (d == null) {
                error("Не удалось подключиться к модулю. Проверьте, что Bluetooth на модуле включён и модуль не занят другим приложением.");
                return;
            }
            attach(d, "Bluetooth");
        }, main);
    }

    @SuppressLint("MissingPermission")
    private static String safeName(BluetoothDevice dev) {
        try {
            String n = dev.getName();
            return n == null ? dev.getAddress() : n;
        } catch (SecurityException e) {
            return dev.getAddress();
        }
    }

    // ---------- USB ----------

    void connectUsb() {
        if (midi == null) {
            error("На этом телефоне нет поддержки MIDI.");
            return;
        }
        MidiDeviceInfo pick = null;
        for (MidiDeviceInfo i : midi.getDevices()) {
            if (i.getType() != MidiDeviceInfo.TYPE_USB) continue;
            if (i.getInputPortCount() < 1 || i.getOutputPortCount() < 1) continue;
            String n = i.getProperties().getString(MidiDeviceInfo.PROPERTY_NAME, "");
            if (pick == null || n.toUpperCase(Locale.ROOT).contains("TD-17")) pick = i;
        }
        if (pick == null) {
            error("Модуль по USB не найден. Подключите TD-17 кабелем через OTG-переходник и включите модуль.");
            return;
        }
        close(true);
        midi.openDevice(pick, d -> {
            if (d == null) {
                error("Не удалось открыть USB-устройство.");
                return;
            }
            attach(d, "USB");
        }, main);
    }

    // ---------- Общее ----------

    private void attach(MidiDevice d, String kind) {
        device = d;
        info = d.getInfo();
        toModule = d.openInputPort(0);
        fromModule = d.openOutputPort(0);
        if (toModule == null || fromModule == null) {
            close(true);
            error("MIDI-порт модуля занят другим приложением. Закройте его и попробуйте снова.");
            return;
        }
        fromModule.connect(new Framer());
        String name = info.getProperties().getString(MidiDeviceInfo.PROPERTY_NAME, "TD-17");
        JSONObject e = obj("connected");
        put(e, "name", name);
        put(e, "kind", kind);
        sink.event(e);
    }

    synchronized void send(byte[] data) {
        if (toModule == null || data.length == 0) return;
        try {
            toModule.send(data, 0, data.length);
        } catch (IOException e) {
            error("Ошибка отправки: " + e.getMessage());
        }
    }

    /** byUser = true — отключение по кнопке, событие «closed» не отправляется. */
    synchronized void close(boolean byUser) {
        boolean wasOpen = device != null;
        try {
            if (fromModule != null) fromModule.close();
        } catch (IOException ignored) {
        }
        try {
            if (toModule != null) toModule.close();
        } catch (IOException ignored) {
        }
        try {
            if (device != null) device.close();
        } catch (IOException ignored) {
        }
        fromModule = null;
        toModule = null;
        device = null;
        info = null;
        if (wasOpen && !byUser) emitType("closed");
    }

    void error(String message) {
        JSONObject e = obj("error");
        put(e, "message", message);
        sink.event(e);
    }

    private void emitStatus(String message) {
        JSONObject e = obj("status");
        put(e, "message", message);
        sink.event(e);
    }

    private void emitType(String type) {
        sink.event(obj(type));
    }

    private static JSONObject obj(String type) {
        JSONObject o = new JSONObject();
        put(o, "type", type);
        return o;
    }

    private static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (JSONException ignored) {
        }
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private void emitMidi(byte[] m) {
        StringBuilder sb = new StringBuilder(m.length * 3);
        for (int i = 0; i < m.length; i++) {
            if (i > 0) sb.append(' ');
            int b = m[i] & 0xFF;
            sb.append(HEX[b >> 4]).append(HEX[b & 15]);
        }
        JSONObject e = obj("midi");
        put(e, "hex", sb.toString());
        sink.event(e);
    }

    /** Собирает поток байтов от модуля в целые MIDI-сообщения (SysEx может приходить кусками). */
    private final class Framer extends MidiReceiver {
        private final ByteArrayOutputStream sysex = new ByteArrayOutputStream();
        private final byte[] msg = new byte[3];
        private boolean inSysex;
        private int running;
        private int need;
        private int have;

        @Override
        public void onSend(byte[] data, int offset, int count, long timestamp) {
            for (int i = offset; i < offset + count; i++) {
                int b = data[i] & 0xFF;
                if (b >= 0xF8) {                       // реальное время, может прийти внутри SysEx
                    emitMidi(new byte[]{(byte) b});
                    continue;
                }
                if (b == 0xF0) {
                    inSysex = true;
                    sysex.reset();
                    sysex.write(b);
                    continue;
                }
                if (b == 0xF7) {
                    if (inSysex) {
                        sysex.write(b);
                        emitMidi(sysex.toByteArray());
                    }
                    inSysex = false;
                    sysex.reset();
                    continue;
                }
                if ((b & 0x80) != 0) {                 // новый статус
                    inSysex = false;
                    running = b;
                    need = dataLength(b);
                    have = 0;
                    msg[0] = (byte) b;
                    if (need == 0) {
                        emitMidi(new byte[]{(byte) b});
                        running = 0;
                    }
                    continue;
                }
                if (inSysex) {
                    sysex.write(b);
                    continue;
                }
                if (running == 0) continue;
                msg[1 + have++] = (byte) b;
                if (have == need) {
                    emitMidi(Arrays.copyOf(msg, 1 + need));
                    have = 0;
                    if (running >= 0xF0) running = 0;
                }
            }
        }

        private int dataLength(int status) {
            int hi = status & 0xF0;
            if (hi == 0xC0 || hi == 0xD0) return 1;
            if (hi >= 0x80 && hi <= 0xE0) return 2;
            if (status == 0xF1 || status == 0xF3) return 1;
            if (status == 0xF2) return 2;
            return 0;
        }
    }
}
