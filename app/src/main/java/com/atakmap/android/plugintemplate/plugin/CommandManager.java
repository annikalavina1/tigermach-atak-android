package com.atakmap.android.plugintemplate.plugin;

import android.util.Log;

public class CommandManager {

    private static final String TAG = "DragonCmd";

    public static final int CMD_NONE  = 0;
    public static final int CMD_HOLD  = 1;
    public static final int CMD_MOVE  = 2;
    public static final int CMD_PING  = 3;
    public static final int CMD_ABORT = 4;

    private int activeCommand = CMD_NONE;

    public interface Listener {
        void onCommandChanged(int commandId, String label);
    }

    private Listener listener;

    public CommandManager() {
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void toggleCommand(int commandId) {
        if (activeCommand == commandId) {
            clearCommand();
        } else {
            setCommand(commandId);
        }
    }

    public void setCommand(int commandId) {
        activeCommand = commandId;
        Log.i(TAG, "Command: " + getLabel(commandId));
        if (listener != null) {
            listener.onCommandChanged(commandId, getLabel(commandId));
        }
    }

    public void clearCommand() {
        activeCommand = CMD_NONE;
        Log.i(TAG, "Command cleared");
        if (listener != null) {
            listener.onCommandChanged(CMD_NONE, null);
        }
    }

    public int getActiveCommand() {
        return activeCommand;
    }

    public boolean hasActiveCommand() {
        return activeCommand != CMD_NONE;
    }

    /** Returns the signal string for the glasses HUD, or null if none active. */
    public String getHudOverride() {
        if (activeCommand == CMD_NONE) return null;
        return getSignal(activeCommand);
    }

    public static String getLabel(int commandId) {
        switch (commandId) {
            case CMD_HOLD:  return "HOLD";
            case CMD_MOVE:  return "MOVE";
            case CMD_PING:  return "PING";
            case CMD_ABORT: return "ABORT";
            default:        return null;
        }
    }

    public static String getSignal(int commandId) {
        switch (commandId) {
            case CMD_HOLD:  return "S:H";
            case CMD_MOVE:  return "S:M";
            case CMD_PING:  return "S:P";
            case CMD_ABORT: return "S:A";
            default:        return null;
        }
    }
}
