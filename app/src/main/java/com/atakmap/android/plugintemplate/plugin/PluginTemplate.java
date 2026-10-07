
package com.atakmap.android.plugintemplate.plugin;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

import java.util.ArrayList;
import java.util.List;

public class PluginTemplate implements IPlugin {

    private static final long HUD_UPDATE_INTERVAL_MS = 150;

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    Pane templatePane;

    private BleManager bleManager;
    private AtakDataProvider dataProvider;
    private CommandManager commandManager;
    private final Handler hudHandler = new Handler(Looper.getMainLooper());
    private boolean hudRunning = false;

    private ArrayAdapter<String> deviceListAdapter;
    private final List<String> deviceNames = new ArrayList<>();

    // HUD preview lines
    private TextView statusText;
    private TextView hudLine1;
    private TextView hudLine2;
    private TextView hudLine3;

    // Data dashboard panel
    private TextView dataHeading;
    private TextView dataBearing;
    private TextView dataDistWaypoint;
    private TextView dataDistTraveled;
    private TextView dataElevation;
    private Button btnResetDist;

    // BLE controls
    private Button btnScan;
    private Button btnDisconnect;
    private Button btnSend;

    // Command buttons
    private Button btnHold;
    private Button btnMove;
    private Button btnPing;
    private Button btnAbort;

    private ListView deviceList;
    private View sendSection;
    private EditText editMessage;

    public PluginTemplate(IServiceController serviceController) {
        this.serviceController = serviceController;
        final PluginContextProvider ctxProvider = serviceController
                .getService(PluginContextProvider.class);
        if (ctxProvider != null) {
            pluginContext = ctxProvider.getPluginContext();
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        }

        uiService = serviceController.getService(IHostUIService.class);

        toolbarItem = new ToolbarItem.Builder(
                pluginContext.getString(R.string.app_name),
                MarshalManager.marshal(
                        pluginContext.getResources().getDrawable(R.drawable.ic_launcher),
                        android.graphics.drawable.Drawable.class,
                        gov.tak.api.commons.graphics.Bitmap.class))
                .setListener(new ToolbarItemAdapter() {
                    @Override
                    public void onClick(ToolbarItem item) {
                        showPane();
                    }
                })
                .build();
    }

    @Override
    public void onStart() {
        if (uiService == null)
            return;
        uiService.addToolbarItem(toolbarItem);
    }

    @Override
    public void onStop() {
        if (uiService == null)
            return;
        uiService.removeToolbarItem(toolbarItem);
        stopHudUpdates();
        if (bleManager != null) {
            bleManager.destroy();
        }
    }

    private void showPane() {
        if (templatePane == null) {
            View rootView = PluginLayoutInflater.inflate(pluginContext,
                    R.layout.main_layout, null);
            initUi(rootView);

            templatePane = new PaneBuilder(rootView)
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                    .build();
        }

        if (!uiService.isPaneVisible(templatePane)) {
            uiService.showPane(templatePane, null);
            startHudUpdates();
        }
    }

    private void initUi(View root) {
        // HUD preview
        statusText = root.findViewById(R.id.status_text);
        hudLine1 = root.findViewById(R.id.hud_line1);
        hudLine2 = root.findViewById(R.id.hud_line2);
        hudLine3 = root.findViewById(R.id.hud_line3);

        // Data dashboard
        dataHeading = root.findViewById(R.id.data_heading);
        dataBearing = root.findViewById(R.id.data_bearing);
        dataDistWaypoint = root.findViewById(R.id.data_dist_waypoint);
        dataDistTraveled = root.findViewById(R.id.data_dist_traveled);
        dataElevation = root.findViewById(R.id.data_elevation);
        btnResetDist = root.findViewById(R.id.btn_reset_dist);

        // BLE controls
        btnScan = root.findViewById(R.id.btn_scan);
        btnDisconnect = root.findViewById(R.id.btn_disconnect);
        btnSend = root.findViewById(R.id.btn_send);

        // Command buttons
        btnHold = root.findViewById(R.id.btn_hold);
        btnMove = root.findViewById(R.id.btn_move);
        btnPing = root.findViewById(R.id.btn_ping);
        btnAbort = root.findViewById(R.id.btn_abort);

        deviceList = root.findViewById(R.id.device_list);
        sendSection = root.findViewById(R.id.send_section);
        editMessage = root.findViewById(R.id.edit_message);

        deviceListAdapter = new ArrayAdapter<>(pluginContext,
                android.R.layout.simple_list_item_1, deviceNames);
        deviceList.setAdapter(deviceListAdapter);

        dataProvider = new AtakDataProvider();
        bleManager = new BleManager(pluginContext);
        bleManager.setCallback(bleCallback);

        commandManager = new CommandManager();
        commandManager.setListener(commandListener);

        // Command buttons
        btnHold.setOnClickListener(v ->
                commandManager.toggleCommand(CommandManager.CMD_HOLD));
        btnMove.setOnClickListener(v ->
                commandManager.toggleCommand(CommandManager.CMD_MOVE));
        btnPing.setOnClickListener(v ->
                commandManager.toggleCommand(CommandManager.CMD_PING));
        btnAbort.setOnClickListener(v ->
                commandManager.toggleCommand(CommandManager.CMD_ABORT));

        // Reset distance traveled
        btnResetDist.setOnClickListener(v -> {
            if (dataProvider != null) {
                dataProvider.resetDistanceTraveled();
                if (dataDistTraveled != null) dataDistTraveled.setText("0m");
            }
        });

        // BLE controls
        btnScan.setOnClickListener(v -> {
            if (bleManager.isScanning()) {
                bleManager.stopScan();
            } else {
                deviceNames.clear();
                deviceListAdapter.notifyDataSetChanged();
                bleManager.startScan();
                btnScan.setText("Stop Scan");
                statusText.setText("Scanning...");
            }
        });

        btnDisconnect.setOnClickListener(v -> bleManager.disconnect());

        btnSend.setOnClickListener(v -> {
            String msg = editMessage.getText().toString();
            if (!msg.isEmpty() && bleManager.isConnected()) {
                bleManager.displayText(BleManager.LAYOUT_BOT, 10, 170, msg);
            }
        });

        deviceList.setOnItemClickListener((AdapterView<?> parent, View view,
                int position, long id) -> {
            List<BluetoothDevice> devices = bleManager.getDiscoveredDevices();
            if (position < devices.size()) {
                statusText.setText("Connecting...");
                bleManager.connect(devices.get(position));
            }
        });
    }

    // --- Command handling ---

    private final CommandManager.Listener commandListener =
            (commandId, label) -> updateCommandButtonStates(commandId);

    private void updateCommandButtonStates(int activeId) {
        setCommandButtonSelected(btnHold, activeId == CommandManager.CMD_HOLD);
        setCommandButtonSelected(btnMove, activeId == CommandManager.CMD_MOVE);
        setCommandButtonSelected(btnPing, activeId == CommandManager.CMD_PING);
        setCommandButtonSelected(btnAbort, activeId == CommandManager.CMD_ABORT);
    }

    private void setCommandButtonSelected(Button btn, boolean selected) {
        btn.setAlpha(selected ? 1.0f : 0.5f);
    }

    // --- HUD update timer (150ms / ~6.7 Hz) ---

    private void startHudUpdates() {
        if (hudRunning) return;
        hudRunning = true;
        hudHandler.post(hudUpdateRunnable);
    }

    private void stopHudUpdates() {
        hudRunning = false;
        hudHandler.removeCallbacks(hudUpdateRunnable);
    }

    private final Runnable hudUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            if (!hudRunning) return;

            AtakDataProvider.HudData hud = dataProvider.getHudData();

            // Line 3 priority:
            //   1. Active signal command (S:H / S:M / S:P / S:A)
            //   2. Distance to waypoint (if nav active)
            //   3. Elevation (fallback, already in hud.line3 when no waypoint)
            String cmdOverride = commandManager != null
                    ? commandManager.getHudOverride() : null;
            String line3 = cmdOverride != null ? cmdOverride : hud.line3;

            // Update HUD preview in panel
            if (hudLine1 != null) hudLine1.setText(hud.line1);
            if (hudLine2 != null) hudLine2.setText(hud.line2);
            if (hudLine3 != null) {
                hudLine3.setText(line3);
                hudLine3.setTextColor(cmdOverride != null
                        ? Color.parseColor("#FF4444")
                        : Color.parseColor("#00FF00"));
            }

            // Update data dashboard
            if (dataHeading != null) dataHeading.setText(hud.line1);
            if (dataBearing != null) dataBearing.setText(hud.line2);
            if (dataDistWaypoint != null) dataDistWaypoint.setText(hud.distToWaypoint);
            if (dataDistTraveled != null) dataDistTraveled.setText(hud.distTraveled);
            if (dataElevation != null) dataElevation.setText(hud.elevation);

            // Push to glasses when connected
            if (bleManager != null && bleManager.isConnected()) {
                bleManager.displayText(BleManager.LAYOUT_TOP, 10, 30,  hud.line1);
                bleManager.displayText(BleManager.LAYOUT_MID, 10, 100, hud.line2);
                bleManager.displayText(BleManager.LAYOUT_BOT, 10, 170, line3);
            }

            hudHandler.postDelayed(this, HUD_UPDATE_INTERVAL_MS);
        }
    };

    // --- UI state transitions ---

    private void showConnectedUi(String deviceName) {
        statusText.setText("Connected: " + deviceName);
        btnScan.setVisibility(View.GONE);
        deviceList.setVisibility(View.GONE);
        btnDisconnect.setVisibility(View.VISIBLE);
        sendSection.setVisibility(View.VISIBLE);
    }

    private void showDisconnectedUi() {
        statusText.setText("Disconnected");
        btnScan.setText("Scan for Glasses");
        btnScan.setVisibility(View.VISIBLE);
        deviceList.setVisibility(View.VISIBLE);
        btnDisconnect.setVisibility(View.GONE);
        sendSection.setVisibility(View.GONE);
    }

    // --- BLE callbacks ---

    private final BleManager.Callback bleCallback = new BleManager.Callback() {
        @Override
        public void onDeviceFound(BluetoothDevice device) {
            String name;
            try {
                name = device.getName();
            } catch (SecurityException e) {
                name = null;
            }
            if (name == null || name.isEmpty()) name = "Unknown";
            deviceNames.add(name + "\n" + device.getAddress());
            deviceListAdapter.notifyDataSetChanged();
        }

        @Override
        public void onScanFinished() {
            btnScan.setText("Scan for Glasses");
            if (deviceNames.isEmpty()) {
                statusText.setText("No glasses found");
            } else {
                statusText.setText("Tap a device to connect");
            }
        }

        @Override
        public void onConnected(BluetoothDevice device) {
            String name;
            try {
                name = device.getName();
            } catch (SecurityException e) {
                name = device.getAddress();
            }
            if (name == null || name.isEmpty()) name = device.getAddress();
            statusText.setText("Connected: " + name + " (discovering services...)");
        }

        @Override
        public void onDisconnected() {
            showDisconnectedUi();
        }

        @Override
        public void onServicesReady() {
            String current = statusText.getText().toString();
            showConnectedUi(current.replace("Connected: ", "")
                    .replace(" (discovering services...)", ""));
        }

        @Override
        public void onDataReceived(String message) {
            statusText.setText("Received: " + message);
        }

        @Override
        public void onError(String message) {
            statusText.setText("Error: " + message);
        }
    };
}
