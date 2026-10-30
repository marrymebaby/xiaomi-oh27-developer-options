package com.codex.settingsentry;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class BuildNumberAssistService extends AccessibilityService {
    private static final String TAG = "SettingsEntryAssist";
    public static final String PREFS = "settings_entry_helper";
    public static final String KEY_BUILD_NUMBER_ASSIST = "build_number_assist";
    public static final String KEY_ASSIST_LOG = "assist_log";

    private static final long COOLDOWN_MS = 5000;
    private static final int CLICK_COUNT = 7;
    private static final long CLICK_DELAY_MS = 450;
    private static final int MAX_SCROLL_ATTEMPTS = 10;
    private static final long SCROLL_DELAY_MS = 700;
    private static final int MAX_LOG_CHARS = 24000;
    private static final int TAP_ACTION = 0;
    private static final int TAP_GESTURE = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running;
    private long lastAttempt;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!isAssistEnabled() || running || event.getPackageName() == null) {
            return;
        }
        String packageName = event.getPackageName().toString();
        if (!"com.android.settings".equals(packageName)
                && !"com.miui.securitycenter".equals(packageName)) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        if (now - lastAttempt < COOLDOWN_MS) {
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }
        if (findBuildNumber(root) == null && !isAboutPage(root)) {
            return;
        }

        lastAttempt = now;
        running = true;
        Log.i(TAG, "Starting assist for " + packageName + "/" + event.getClassName());
        appendAssistLog("event package=" + packageName
                + " class=" + event.getClassName()
                + " development_settings_enabled=" + readDevelopmentSetting());
        logNodeTree(root);
        Toast.makeText(this, "Looking for the build-number row", Toast.LENGTH_SHORT).show();
        scrollAndSearch(0);
    }

    private void scrollAndSearch(final int attempt) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo buildNumber = findBuildNumber(root);
        if (buildNumber != null && hasUsableBounds(buildNumber)) {
            Log.i(TAG, "Found build-number node");
            appendAssistLog("build-number node: " + describeNode(buildNumber));
            Toast.makeText(this, "Build-number assist started", Toast.LENGTH_SHORT).show();
            AccessibilityNodeInfo clickable = findClickableAncestor(buildNumber);
            AccessibilityNodeInfo target = clickable != null ? clickable : buildNumber;
            final Rect targetBounds = findGestureBounds(buildNumber);
            final AccessibilityNodeInfo cachedTarget = target;
            appendAssistLog("tap target bounds=" + targetBounds.toShortString()
                    + " clickable ancestor=" + describeNode(target));
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    tapSequence(0, TAP_ACTION, targetBounds, cachedTarget);
                }
            }, 350);
            return;
        }
        if (attempt >= MAX_SCROLL_ATTEMPTS) {
            logNodeTree(root);
            finish(false, "Build number was not found after scrolling");
            return;
        }

        AccessibilityNodeInfo scrollable = findScrollableNode(root);
        boolean scrolled = scrollable != null
                && scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        if (!scrolled) {
            scrolled = swipeUp(root);
        }
        if (!scrolled) {
            logNodeTree(root);
            finish(false, "Could not scroll to build number");
            return;
        }
        Log.i(TAG, "Scrolling for build number, attempt " + (attempt + 1)
                + " target=" + describeNode(scrollable));

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                scrollAndSearch(attempt + 1);
            }
        }, SCROLL_DELAY_MS);
    }

    private void tapSequence(final int index, final int strategy, final Rect cachedBounds,
            final AccessibilityNodeInfo cachedTarget) {
        if (isDevelopmentEnabled()) {
            finish(true, "Developer options are enabled");
            return;
        }
        if (index >= CLICK_COUNT) {
            if (strategy == TAP_ACTION) {
                appendAssistLog("semantic clicks did not change the setting; "
                        + "falling back to touch gestures");
                Toast.makeText(this, "Trying touch gestures", Toast.LENGTH_SHORT).show();
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        tapSequence(0, TAP_GESTURE, cachedBounds, cachedTarget);
                    }
                }, CLICK_DELAY_MS);
            } else {
                finish(false, "Build-number taps did not enable developer options");
            }
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo buildNumber = findBuildNumber(root);
        AccessibilityNodeInfo target = cachedTarget;
        Rect targetBounds = new Rect(cachedBounds);
        if (buildNumber != null) {
            AccessibilityNodeInfo clickable = findClickableAncestor(buildNumber);
            if (clickable != null) {
                target = clickable;
            }
            Rect freshBounds = findGestureBounds(buildNumber);
            if (!freshBounds.isEmpty()) {
                targetBounds = freshBounds;
            }
        }
        if (targetBounds.isEmpty()) {
            finish(false, "Build number has no usable screen bounds");
            return;
        }

        boolean performed;
        if (strategy == TAP_ACTION && target != null) {
            target.refresh();
            performed = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } else {
            performed = tapBounds(targetBounds);
        }

        appendAssistLog("tap " + (index + 1) + "/" + CLICK_COUNT
                + " strategy=" + (strategy == TAP_ACTION ? "ACTION_CLICK" : "GESTURE")
                + " performed=" + performed
                + " target=" + describeNode(target)
                + " cachedBounds=" + targetBounds.toShortString());

        if (!performed && strategy == TAP_ACTION) {
            finish(false, "Build number could not be clicked");
            return;
        }

        final Rect nextBounds = targetBounds;
        final AccessibilityNodeInfo nextTarget = target;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                appendAssistLog("after tap " + (index + 1)
                        + " development_settings_enabled=" + readDevelopmentSetting());
                tapSequence(index + 1, strategy, nextBounds, nextTarget);
            }
        }, CLICK_DELAY_MS);
    }

    private AccessibilityNodeInfo findBuildNumber(AccessibilityNodeInfo node) {
        if (node == null) {
            return null;
        }
        if (matchesBuildNumber(node.getText())
                || matchesBuildNumber(node.getContentDescription())) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findBuildNumber(node.getChild(i));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private boolean matchesBuildNumber(CharSequence value) {
        if (value == null) {
            return false;
        }
        String text = value.toString().trim().toLowerCase(Locale.ROOT);
        return text.contains("\u7248\u672c\u53f7")
                || text.contains("\u5185\u90e8\u7248\u672c\u53f7")
                || text.contains("\u7248\u672c\u4fe1\u606f")
                || text.contains("\u7cfb\u7edf\u7248\u672c")
                || text.contains("\u8f6f\u4ef6\u7248\u672c")
                || text.contains("\u56fa\u4ef6\u7248\u672c")
                || text.contains("miui\u7248\u672c")
                || text.contains("hyperos\u7248\u672c")
                || text.contains("os\u7248\u672c")
                || text.contains("build number")
                || text.contains("build\u53f7");
    }

    private boolean isAboutPage(AccessibilityNodeInfo root) {
        return containsText(root, "about")
                || containsText(root, "about phone")
                || containsText(root, "about device")
                || containsText(root, "device info")
                || containsText(root, "\u5173\u4e8e\u624b\u673a")
                || containsText(root, "\u5173\u4e8e\u672c\u673a")
                || containsText(root, "\u5173\u4e8e\u7535\u89c6")
                || containsText(root, "\u5173\u4e8e")
                || containsText(root, "\u4ea7\u54c1\u4fe1\u606f")
                || containsText(root, "\u7cfb\u7edf\u4fe1\u606f")
                || containsText(root, "\u8bbe\u5907\u4fe1\u606f");
    }

    private boolean containsText(AccessibilityNodeInfo node, String needle) {
        if (node == null) {
            return false;
        }
        CharSequence text = node.getText();
        CharSequence description = node.getContentDescription();
        String lowerNeedle = needle.toLowerCase(Locale.ROOT);
        if (text != null
                && text.toString().toLowerCase(Locale.ROOT).contains(lowerNeedle)) {
            return true;
        }
        if (description != null
                && description.toString().toLowerCase(Locale.ROOT).contains(lowerNeedle)) {
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (containsText(node.getChild(i), needle)) {
                return true;
            }
        }
        return false;
    }

    private AccessibilityNodeInfo findScrollableNode(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo[] best = new AccessibilityNodeInfo[] {null};
        int[] bestScore = new int[] {-1};
        findBestScrollable(node, 0, best, bestScore);
        return best[0];
    }

    private void findBestScrollable(AccessibilityNodeInfo node, int depth,
            AccessibilityNodeInfo[] best, int[] bestScore) {
        if (node == null) {
            return;
        }
        if (node.isScrollable()) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            String className = safe(node.getClassName()).toLowerCase(Locale.ROOT);
            int score = Math.max(0, bounds.width()) * Math.max(0, bounds.height());
            if (className.contains("recyclerview")) {
                score += 10000000;
            } else if (className.contains("listview")) {
                score += 5000000;
            }
            score += depth * 1000;
            if (score > bestScore[0]) {
                bestScore[0] = score;
                best[0] = node;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            findBestScrollable(node.getChild(i), depth + 1, best, bestScore);
        }
    }

    private boolean tapCenter(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        return tapBounds(bounds);
    }

    private Rect findGestureBounds(AccessibilityNodeInfo node) {
        Rect fallback = new Rect();
        if (node == null) {
            return fallback;
        }
        node.getBoundsInScreen(fallback);
        AccessibilityNodeInfo parent = node.getParent();
        if (parent == null) {
            return fallback;
        }
        for (int i = 0; i < parent.getChildCount(); i++) {
            AccessibilityNodeInfo sibling = parent.getChild(i);
            if (sibling == null || sibling.equals(node)) {
                continue;
            }
            String id = safe(sibling.getViewIdResourceName()).toLowerCase(Locale.ROOT);
            CharSequence text = sibling.getText();
            if ((id.contains("summary") || text != null && text.length() > 0)
                    && !matchesBuildNumber(text)) {
                Rect bounds = new Rect();
                sibling.getBoundsInScreen(bounds);
                if (!bounds.isEmpty() && bounds.width() > 20 && bounds.height() > 20) {
                    return bounds;
                }
            }
        }
        return fallback;
    }

    private boolean tapBounds(Rect bounds) {
        if (bounds.isEmpty()) {
            appendAssistLog("gesture skipped: empty target bounds");
            return false;
        }

        Path path = new Path();
        path.moveTo(bounds.centerX(), bounds.centerY());
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 40, 80))
                .build();
        boolean accepted = dispatchGesture(gesture, null, null);
        appendAssistLog("gesture center=" + bounds.centerX() + "," + bounds.centerY()
                + " accepted=" + accepted);
        return accepted;
    }

    private boolean swipeUp(AccessibilityNodeInfo root) {
        if (root == null) {
            return false;
        }
        Rect bounds = new Rect();
        root.getBoundsInScreen(bounds);
        if (bounds.isEmpty()) {
            return false;
        }
        int x = bounds.centerX();
        int startY = bounds.top + Math.round(bounds.height() * 0.78f);
        int endY = bounds.top + Math.round(bounds.height() * 0.30f);
        Path path = new Path();
        path.moveTo(x, startY);
        path.lineTo(x, endY);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 500))
                .build();
        boolean accepted = dispatchGesture(gesture, null, null);
        appendAssistLog("scroll gesture " + startY + "->" + endY
                + " accepted=" + accepted);
        return accepted;
    }

    private boolean hasUsableBounds(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        return !bounds.isEmpty();
    }

    private AccessibilityNodeInfo findClickableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; i < 6 && current != null; i++) {
            if (current.isClickable()) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private boolean isDevelopmentEnabled() {
        return readDevelopmentSetting() == 1;
    }

    private int readDevelopmentSetting() {
        try {
            return Settings.Global.getInt(
                    getContentResolver(),
                    Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                    -1);
        } catch (Exception e) {
            appendAssistLog("read development setting failed: " + describe(e));
            return -999;
        }
    }

    private void finish(boolean success, String message) {
        running = false;
        handler.removeCallbacksAndMessages(null);
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_BUILD_NUMBER_ASSIST, false)
                .apply();
        appendAssistLog((success ? "SUCCESS: " : "STOPPED: ") + message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void logNodeTree(AccessibilityNodeInfo root) {
        if (root == null) {
            appendAssistLog("[TREE] root=null");
            return;
        }
        StringBuilder tree = new StringBuilder("[TREE]\n");
        int[] remaining = new int[] {80};
        appendTree(root, 0, tree, remaining);
        if (remaining[0] <= 0) {
            tree.append("... tree truncated ...\n");
        }
        appendAssistLog(tree.toString());
    }

    private void appendTree(AccessibilityNodeInfo node, int depth,
            StringBuilder tree, int[] remaining) {
        if (node == null || remaining[0] <= 0) {
            return;
        }
        remaining[0]--;
        tree.append(depth)
                .append(' ')
                .append(describeNode(node))
                .append('\n');
        for (int i = 0; i < node.getChildCount(); i++) {
            appendTree(node.getChild(i), depth + 1, tree, remaining);
        }
    }

    private String describeNode(AccessibilityNodeInfo node) {
        if (node == null) {
            return "null";
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        return "class=" + node.getClassName()
                + " text=\"" + safe(node.getText()) + "\""
                + " desc=\"" + safe(node.getContentDescription()) + "\""
                + " id=" + safe(node.getViewIdResourceName())
                + " clickable=" + node.isClickable()
                + " enabled=" + node.isEnabled()
                + " visible=" + node.isVisibleToUser()
                + " bounds=" + bounds.toShortString();
    }

    private String safe(CharSequence value) {
        if (value == null) {
            return "";
        }
        return value.toString().replace('\n', ' ').replace('\r', ' ');
    }

    private String describe(Throwable throwable) {
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName()
                + (message == null || message.length() == 0 ? "" : ": " + message);
    }

    private void appendAssistLog(String message) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String oldLog = prefs.getString(KEY_ASSIST_LOG, "");
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.ROOT)
                .format(new Date());
        String nextLog = oldLog + timestamp + " " + message + "\n";
        if (nextLog.length() > MAX_LOG_CHARS) {
            nextLog = nextLog.substring(nextLog.length() - MAX_LOG_CHARS);
        }
        prefs.edit().putString(KEY_ASSIST_LOG, nextLog).apply();
    }

    private boolean isAssistEnabled() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        return prefs.getBoolean(KEY_BUILD_NUMBER_ASSIST, false);
    }

    @Override
    public void onInterrupt() {
        running = false;
        handler.removeCallbacksAndMessages(null);
    }
}
