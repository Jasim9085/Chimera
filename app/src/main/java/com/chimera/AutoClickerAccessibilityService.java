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
    AccessibilityNodeInfo rootNode = getRootInActiveWindow();
    if (rootNode == null) {
        return;
    }

    // FIXED: Find the button by its resource ID, which is much more reliable than text.
    // This is the standard ID for the positive button in system dialogs.
    List<AccessibilityNodeInfo> startButtonNodes = rootNode.findAccessibilityNodeInfosByViewId("com.android.systemui:id/button1");

    if (startButtonNodes != null && !startButtonNodes.isEmpty()) {
        for (AccessibilityNodeInfo node : startButtonNodes) {
            if (node != null && node.isClickable()) {
                // To be extra sure, we can check the text as a fallback
                String buttonText = node.getText() != null ? node.getText().toString().toLowerCase() : "";
                if (buttonText.equals("start now") || buttonText.equals("allow")) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    node.recycle();
                    return; // Clicked, our job is done.
                }
            }
        }
    }
    rootNode.recycle();
}


@Override
public void onInterrupt() {
    isServiceEnabled = false;
}

@Override
protected void onServiceConnected() {
    super.onServiceConnected();
    isServiceEnabled = true;
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