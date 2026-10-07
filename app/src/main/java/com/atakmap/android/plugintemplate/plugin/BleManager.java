package com.atakmap.android.plugintemplate.plugin;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

public class BleManager {

    private static final String TAG = "DragonBLE";

    // ActiveLook BLE profile
    public static final UUID AL_SERVICE_UUID =
            UUID.fromString("0783b03e-8535-b5a0-7140-a304d2495cb7");
    public static final UUID AL_RX_CHAR_UUID =
            UUID.fromString("0783b03e-8535-b5a0-7140-a304d2495cba");
    public static final UUID AL_TX_CHAR_UUID =
            UUID.fromString("0783b03e-8535-b5a0-7140-a304d2495cb8");
    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // ActiveLook command IDs
    private static final int CMD_POWER        = 0x00;
    private static final int CMD_CLEAR        = 0x01;
    private static final int CMD_LUMA         = 0x08;
    private static final int CMD_LAYOUT_SAVE  = 0x37;
    private static final int CMD_LAYOUT_DISP  = 0x39;

    // Layout IDs for the 3 HUD lines
    public static final int LAYOUT_TOP = 11;
    public static final int LAYOUT_MID = 12;
    public static final int LAYOUT_BOT = 13;

    private static final long SCAN_TIMEOUT_MS = 10000;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rxCharacteristic;
    private boolean scanning = false;
    private final List<BluetoothDevice> discoveredDevices = new ArrayList<>();
    private Callback callback;

    // Write queue for serializing BLE writes
    private final ConcurrentLinkedQueue<byte[]> writeQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean writeInProgress = false;

    public interface Callback {
        void onDeviceFound(BluetoothDevice device);
        void onScanFinished();
        void onConnected(BluetoothDevice device);
        void onDisconnected();
        void onServicesReady();
        void onDataReceived(String message);
        void onError(String message);
    }

    public BleManager(Context context) {
        this.context = context;
        BluetoothManager btManager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (btManager != null) {
            bluetoothAdapter = btManager.getAdapter();
        }
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public boolean isBluetoothAvailable() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    public boolean isScanning() {
        return scanning;
    }

    public boolean isConnected() {
        return gatt != null && rxCharacteristic != null;
    }

    public List<BluetoothDevice> getDiscoveredDevices() {
        return discoveredDevices;
    }

    // ---- Scanning (filtered for ActiveLook service) ----

    public void startScan() {
        if (!isBluetoothAvailable()) {
            notifyError("Bluetooth is not available or not enabled");
            return;
        }
        if (scanning) return;

        discoveredDevices.clear();
        scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            notifyError("BLE scanner not available");
            return;
        }

        scanning = true;
        try {
            ScanFilter filter = new ScanFilter.Builder()
                    .setServiceUuid(new ParcelUuid(AL_SERVICE_UUID))
                    .build();
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
        } catch (SecurityException e) {
            notifyError("Missing BLE scan permission");
            scanning = false;
            return;
        }

        mainHandler.postDelayed(this::stopScan, SCAN_TIMEOUT_MS);
    }

    public void stopScan() {
        if (!scanning) return;
        scanning = false;
        try {
            if (scanner != null) {
                scanner.stopScan(scanCallback);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "SecurityException stopping scan", e);
        }
        if (callback != null) {
            mainHandler.post(() -> callback.onScanFinished());
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            for (BluetoothDevice d : discoveredDevices) {
                if (d.getAddress().equals(device.getAddress())) return;
            }
            discoveredDevices.add(device);
            if (callback != null) {
                mainHandler.post(() -> callback.onDeviceFound(device));
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            notifyError("Scan failed with error code: " + errorCode);
        }
    };

    // ---- Connection ----

    public void connect(BluetoothDevice device) {
        stopScan();
        try {
            gatt = device.connectGatt(context, false, gattCallback,
                    BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            notifyError("Missing BLE connect permission");
        }
    }

    public void disconnect() {
        writeQueue.clear();
        writeInProgress = false;
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (SecurityException e) {
                Log.w(TAG, "SecurityException disconnecting", e);
            }
            gatt = null;
            rxCharacteristic = null;
            if (callback != null) {
                mainHandler.post(() -> callback.onDisconnected());
            }
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to GATT server");
                if (callback != null) {
                    mainHandler.post(() -> callback.onConnected(g.getDevice()));
                }
                try {
                    g.discoverServices();
                } catch (SecurityException e) {
                    notifyError("Missing permission for service discovery");
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected from GATT server");
                gatt = null;
                rxCharacteristic = null;
                writeQueue.clear();
                writeInProgress = false;
                if (callback != null) {
                    mainHandler.post(() -> callback.onDisconnected());
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                notifyError("Service discovery failed: " + status);
                return;
            }

            BluetoothGattService alService = g.getService(AL_SERVICE_UUID);
            if (alService == null) {
                notifyError("ActiveLook service not found on device");
                return;
            }

            rxCharacteristic = alService.getCharacteristic(AL_RX_CHAR_UUID);
            if (rxCharacteristic == null) {
                notifyError("ActiveLook RX characteristic not found");
                return;
            }

            // Enable notifications on TX characteristic
            BluetoothGattCharacteristic txChar =
                    alService.getCharacteristic(AL_TX_CHAR_UUID);
            if (txChar != null) {
                try {
                    g.setCharacteristicNotification(txChar, true);
                    BluetoothGattDescriptor desc = txChar.getDescriptor(CCCD_UUID);
                    if (desc != null) {
                        desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        g.writeDescriptor(desc);
                    }
                } catch (SecurityException e) {
                    Log.w(TAG, "SecurityException enabling notifications", e);
                }
            }

            // Initialize the glasses display
            mainHandler.post(() -> {
                initDisplay();
                if (callback != null) callback.onServicesReady();
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g,
                BluetoothGattCharacteristic characteristic) {
            if (AL_TX_CHAR_UUID.equals(characteristic.getUuid())) {
                byte[] data = characteristic.getValue();
                if (data != null && data.length > 0) {
                    String text = new String(data, StandardCharsets.UTF_8);
                    Log.i(TAG, "Received: " + text);
                    if (callback != null) {
                        mainHandler.post(() -> callback.onDataReceived(text));
                    }
                }
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g,
                BluetoothGattCharacteristic characteristic, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Write failed, status: " + status);
            }
            writeInProgress = false;
            mainHandler.post(BleManager.this::processWriteQueue);
        }
    };

    // ---- Write queue ----

    private void enqueueWrite(byte[] data) {
        writeQueue.add(data);
        mainHandler.post(this::processWriteQueue);
    }

    private void processWriteQueue() {
        if (writeInProgress || writeQueue.isEmpty()) return;
        if (gatt == null || rxCharacteristic == null) return;

        byte[] data = writeQueue.poll();
        if (data == null) return;

        rxCharacteristic.setValue(data);
        writeInProgress = true;
        try {
            boolean ok = gatt.writeCharacteristic(rxCharacteristic);
            if (!ok) {
                writeInProgress = false;
                Log.w(TAG, "writeCharacteristic returned false");
            }
        } catch (SecurityException e) {
            writeInProgress = false;
            notifyError("Missing BLE write permission");
        }
    }

    // ---- ActiveLook command framing ----

    private void sendCommand(int cmdId, byte... params) {
        byte[] frame = new byte[3 + params.length];
        frame[0] = (byte) 0xFF;
        frame[1] = (byte) cmdId;
        System.arraycopy(params, 0, frame, 2, params.length);
        frame[frame.length - 1] = (byte) 0xAA;
        enqueueWrite(frame);
    }

    // ---- ActiveLook display commands ----

    /** Power on the glasses display. */
    public void powerOn() {
        sendCommand(CMD_POWER);
    }

    /** Clear the entire display. */
    public void clearScreen() {
        sendCommand(CMD_CLEAR);
    }

    /** Set display brightness (0-15). */
    public void setLuma(int level) {
        sendCommand(CMD_LUMA, (byte) (level & 0x0F));
    }

    /**
     * Save a text layout region on the glasses.
     * @param id     layout ID (e.g. 11, 12, 13)
     * @param x      X position (0-303)
     * @param y      Y position (0-255)
     * @param width  region width
     * @param height region height
     * @param font   font ID
     */
    public void saveLayout(int id, int x, int y, int width, int height, int font) {
        byte[] params = new byte[] {
                (byte) id,
                (byte) ((x >> 8) & 0xFF), (byte) (x & 0xFF),
                (byte) y,
                (byte) ((width >> 8) & 0xFF), (byte) (width & 0xFF),
                (byte) height,
                (byte) 15,  // foreground = white
                (byte) 0,   // background = black
                (byte) font,
                (byte) 1,   // textValid = true
                0, 0,        // textX offset = 0
                0,           // textY offset = 0
                0,           // rotation = BOTTOM_RL
                0            // textOpacity = transparent bg
        };
        sendCommand(CMD_LAYOUT_SAVE, params);
    }

    /**
     * Clear a layout region and display text (cmd 0x39).
     * @param layoutId saved layout ID
     * @param x        X position
     * @param y        Y position
     * @param text     text to display
     */
    public void displayText(int layoutId, int x, int y, String text) {
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        byte[] params = new byte[4 + textBytes.length];
        params[0] = (byte) layoutId;
        params[1] = (byte) ((x >> 8) & 0xFF);
        params[2] = (byte) (x & 0xFF);
        params[3] = (byte) y;
        System.arraycopy(textBytes, 0, params, 4, textBytes.length);
        sendCommand(CMD_LAYOUT_DISP, params);
    }

    /** Initialize display on connect: power on, clear, set brightness, save layouts. */
    private void initDisplay() {
        powerOn();
        clearScreen();
        setLuma(10);
        // 304x256 display — 3 lines spaced vertically
        saveLayout(LAYOUT_TOP, 10, 30,  284, 50, 1);
        saveLayout(LAYOUT_MID, 10, 100, 284, 50, 1);
        saveLayout(LAYOUT_BOT, 10, 170, 284, 50, 1);
    }

    // ---- Legacy send (for manual debug) ----

    public void sendBytes(byte[] data) {
        enqueueWrite(data);
    }

    public void send(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        enqueueWrite(data);
    }

    public void destroy() {
        stopScan();
        disconnect();
    }

    private void notifyError(String msg) {
        Log.e(TAG, msg);
        if (callback != null) {
            mainHandler.post(() -> callback.onError(msg));
        }
    }
}
