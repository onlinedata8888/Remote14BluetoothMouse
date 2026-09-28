package com.example.tvremote;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppQosSettings;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Real Bluetooth HID mouse for Android 9+ hosts.
 *
 * The VU Cinema 55LX is an Android TV and exposes Bluetooth HID support. The
 * Android TV Remote v2 network protocol only carries keys/IME, so a HID mouse
 * is used for a real system pointer.
 */
final class BluetoothMouseBridge {
    interface Listener { void onState(boolean connected); }

    private static final byte[] REPORT_DESC = new byte[] {
        0x05,0x01, 0x09,0x02, (byte)0xA1,0x01,
        0x09,0x01, (byte)0xA1,0x00,
        0x05,0x09, 0x19,0x01, 0x29,0x03,
        0x15,0x00, 0x25,0x01, 0x95,0x03, (byte)0x75,0x01,
        (byte)0x81,0x02,
        0x95,0x01, (byte)0x75,0x05, (byte)0x81,0x03,
        0x05,0x01, 0x09,0x30, 0x09,0x31,
        0x15,(byte)0x81, 0x25,0x7F, (byte)0x75,0x08, (byte)0x95,0x02,
        (byte)0x81,0x06,
        0x09,0x38,
        0x15,(byte)0x81, 0x25,0x7F, (byte)0x75,0x08, (byte)0x95,0x01,
        (byte)0x81,0x06,
        (byte)0xC0, (byte)0xC0
    };

    private final Activity act;
    private final Context ctx;
    private final Executor exec = Executors.newSingleThreadExecutor();
    private final Listener listener;
    private BluetoothHidDevice hid;
    private BluetoothDevice host;
    private boolean registered;

    BluetoothMouseBridge(Activity a, Listener l) {
        act = a;
        ctx = a.getApplicationContext();
        listener = l;
    }

    boolean start() {
        if (Build.VERSION.SDK_INT < 28) return false;
        if (Build.VERSION.SDK_INT >= 31 &&
            (act.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != android.content.pm.PackageManager.PERMISSION_GRANTED ||
             act.checkSelfPermission("android.permission.BLUETOOTH_ADVERTISE") != android.content.pm.PackageManager.PERMISSION_GRANTED)) {
            act.requestPermissions(new String[]{
                "android.permission.BLUETOOTH_CONNECT",
                "android.permission.BLUETOOTH_ADVERTISE"
            }, 4201);
            return false;
        }
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null) return false;
        if (!a.isEnabled()) {
            try { act.startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); } catch (Throwable ignored) {}
            return false;
        }
        if (hid != null) return host != null;
        try {
            BluetoothAdapter.getDefaultAdapter().getProfileProxy(ctx, serviceListener, BluetoothProfile.HID_DEVICE);
            try {
                Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
                i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
                act.startActivity(i);
            } catch (Throwable ignored) {}
        } catch (Throwable e) {
            TvLog.d("bt hid start: " + e);
            return false;
        }
        return false;
    }

    boolean isConnected() { return hid != null && host != null; }

    private final BluetoothProfile.ServiceListener serviceListener = new BluetoothProfile.ServiceListener() {
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile != BluetoothProfile.HID_DEVICE) return;
            hid = (BluetoothHidDevice) proxy;
            try {
                BluetoothHidDeviceAppSdpSettings sdp =
                    new BluetoothHidDeviceAppSdpSettings(
                        "Remote 13 Mouse", "Phone as a TV mouse", "Remote13",
                        BluetoothHidDevice.SUBCLASS1_MOUSE, REPORT_DESC);
                BluetoothHidDeviceAppQosSettings qos = null;
                registered = hid.registerApp(sdp, null, qos, exec, callback);
                TvLog.d("bt hid register=" + registered);
            } catch (Throwable e) {
                TvLog.d("bt hid register error: " + e);
            }
        }
        public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hid = null; host = null; registered = false;
                notifyState(false);
            }
        }
    };

    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean registeredNow) {
            registered = registeredNow;
            if (registeredNow) {
                TvLog.d("bt hid ready; pair the TV with Remote 13 Mouse");
            } else {
                host = null; notifyState(false);
            }
        }
        @Override public void onConnectionStateChanged(BluetoothDevice device, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                host = device;
                notifyState(true);
                TvLog.d("bt mouse connected: " + safeName(device));
            } else if (state == BluetoothProfile.STATE_DISCONNECTED ||
                       state == BluetoothProfile.STATE_DISCONNECTING) {
                if (host != null && host.equals(device)) host = null;
                notifyState(false);
            }
        }
        @Override public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {}
        @Override public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) {}
        @Override public void onSetProtocol(BluetoothDevice device, byte protocol) {}
        @Override public void onInterruptData(BluetoothDevice device, byte reportId, byte[] data) {}
        @Override public void onVirtualCableUnplug(BluetoothDevice device) {
            if (host != null && host.equals(device)) host = null;
            notifyState(false);
        }
    };

    void move(int dx, int dy) {
        send(new byte[]{0, clamp(dx), clamp(dy), 0});
    }
    void click() {
        send(new byte[]{1,0,0,0});
        send(new byte[]{0,0,0,0});
    }
    void down() { send(new byte[]{1,0,0,0}); }
    void up() { send(new byte[]{0,0,0,0}); }

    private void send(final byte[] report) {
        final BluetoothHidDevice h = hid;
        final BluetoothDevice d = host;
        if (h == null || d == null || !registered) return;
        exec.execute(new Runnable() {
            public void run() {
                try { h.sendReport(d, 0, report); }
                catch (Throwable e) { TvLog.d("bt hid report: " + e); }
            }
        });
    }

    private static byte clamp(int v) {
        if (v > 127) v = 127;
        if (v < -127) v = -127;
        return (byte)v;
    }
    private static String safeName(BluetoothDevice d) {
        try { return d.getName(); } catch (Throwable e) { return ""; }
    }
    private void notifyState(final boolean connected) {
        act.runOnUiThread(new Runnable() { public void run() { listener.onState(connected); }});
    }

    void close() {
        try {
            if (hid != null && registered) hid.unregisterApp();
        } catch (Throwable ignored) {}
        try {
            BluetoothAdapter.getDefaultAdapter().closeProfileProxy(BluetoothProfile.HID_DEVICE, hid);
        } catch (Throwable ignored) {}
        hid = null; host = null; registered = false;
    }
}
