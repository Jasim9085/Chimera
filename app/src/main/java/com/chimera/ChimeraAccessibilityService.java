package com.chimera;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public class ChimeraAccessibilityService extends AccessibilityService {

    private static ChimeraAccessibilityService instance;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override public void onInterrupt() { instance = null; }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);
    }

    public static boolean isServiceEnabled() { return instance != null; }

    public static void triggerGlobalAction(int action) { if (isServiceEnabled()) instance.performGlobalAction(action); }

    public static String getScreenContent() {
        if (!isServiceEnabled()) return "Error: Accessibility Service is not enabled.";
        AccessibilityNodeInfo rootNode = instance.getRootInActiveWindow();
        if (rootNode == null) return "Error: Could not get the root node. The screen might be locked or empty.";
        
        StringBuilder sb = new StringBuilder("*--- Screen Content Dump ---*\n");
        dumpNode(rootNode, sb, 0);
        rootNode.recycle();
        return sb.toString();
    }

    private static void dumpNode(AccessibilityNodeInfo node, StringBuilder sb, int depth) {
        if (node == null) return;
        
        CharSequence className = node.getClassName();
        if (className == null) return;

        String friendlyClassName = getFriendlyClassName(className.toString());
        CharSequence text = node.getText();
        CharSequence contentDesc = node.getContentDescription();

        if (friendlyClassName != null || text != null || contentDesc != null) {
            for (int i = 0; i < depth; i++) sb.append("  ");

            if (friendlyClassName != null) {
                sb.append("- *").append(friendlyClassName).append("*");
                if (text != null || contentDesc != null) sb.append(": ");
            }

            if (text != null) {
                sb.append("`").append(text).append("`");
            } else if (contentDesc != null) {
                sb.append("`").append(contentDesc).append("`");
            }

            if (node.isClickable()) {
                sb.append(" (Clickable)");
            }
            sb.append("\n");
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                dumpNode(child, sb, depth + 1);
                child.recycle();
            }
        }
    }

    private static String getFriendlyClassName(String rawClassName) {
        if (rawClassName.contains("Button")) return "Button";
        if (rawClassName.contains("EditText")) return "Input Field";
        if (rawClassName.contains("TextView")) return "Text";
        if (rawClassName.contains("ImageView")) return "Image";
        if (rawClassName.contains("CheckBox")) return "Checkbox";
        if (rawClassName.contains("RadioButton")) return "Radio Button";
        if (rawClassName.contains("Switch")) return "Switch";
        return null;
    }
}