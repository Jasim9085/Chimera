package com.chimera;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;

public class AutoClickerAccessibilityService extends AccessibilityService {

    private static boolean isServiceEnabled = false;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getSource() == null) {
            return;
        }
        // Get the root node of the active window
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) {
            return;
        }

        // The key text on the screenshot permission dialog button is "Start now"
        // Find all nodes with this text
        List<AccessibilityNodeInfo> startNowNodes = rootNode.findAccessibilityNodeInfosByText("Start now");
        if (startNowNodes != null && !startNowNodes.isEmpty()) {
            for (AccessibilityNodeInfo node : startNowNodes) {
                // Check if the node is clickable and perform the click
                if (node != null && node.isClickable()) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    // Log for debugging
                    ErrorLogger.logError(this, "AutoClicker", new Exception("Successfully clicked 'Start now' button."));
                    node.recycle();
                    break; // We only need to click once
                }
            }
        }
    }

    @Override
    public void onInterrupt() {
        // This method is called when the service is interrupted
        isServiceEnabled = false;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        isServiceEnabled = true;
        // Configure the service
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.DEFAULT | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        this.setServiceInfo(info);
    }

    public static boolean isServiceEnabled() {
        return isServiceEnabled;
    }
}