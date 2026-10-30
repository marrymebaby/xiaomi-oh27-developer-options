package com.codex.settingsentry;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity {
    // Palette mirrors the OH27 control-center design (HyperOS Settings style).
    private static final int BG = 0xFFF3F4F6;
    private static final int CARD = 0xFFFFFFFF;
    private static final int SEPARATOR = 0xFFECEDEF;
    private static final int TEXT = 0xFF191919;
    private static final int MUTED = 0xFF999B9F;
    private static final int ACCENT = 0xFF3482FF;
    private static final int NAV_SELECTED = 0xFFE9EAEE;
    private static final int LOG_BG = 0xFF1F252D;
    private static final int LOG_TEXT = 0xFFEBEEF2;

    private static final String SETTINGS_PACKAGE = "com.android.settings";
    private static final String FACTORY_PACKAGE = "com.mediatek.factorymode";
    private static final int EXPORT_SETTINGS = 1001;
    private static final int EXPORT_FACTORY = 1002;

    // Static so a language switch (which rebuilds the UI) keeps the log.
    private static final StringBuilder logText = new StringBuilder();
    private static String lastAssistLog = "";
    private static boolean exporting;

    private LinearLayout candidateList;
    private TextView logView;
    private LinearLayout navColumn;
    private final List<TextView> navRows = new ArrayList<>();
    private final List<View> panes = new ArrayList<>();
    private final Set<String> listedCandidates = new HashSet<>();
    private int selected;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        L.load(this);
        buildUi();
        appendLog(L.t("Settings Entry Helper started", "设置入口助手已启动"));
        appendLog(L.t("No root. Settings export copies APK code only, not app data.",
                "无 root。设置导出仅复制 APK 代码，不包含应用数据。"));
        readState();
        addKnownCandidates();
        scanSettingsActivities();
        showPane(selected);
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncAssistLog();
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private GradientDrawable cardBg() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(CARD);
        drawable.setCornerRadius(dp(24));
        return drawable;
    }

    private GradientDrawable navBg(boolean selectedRow) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(selectedRow ? NAV_SELECTED : Color.TRANSPARENT);
        drawable.setCornerRadius(dp(14));
        return drawable;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);
        setContentView(root);

        navColumn = new LinearLayout(this);
        navColumn.setOrientation(LinearLayout.VERTICAL);
        navColumn.setPadding(dp(14), dp(22), dp(10), dp(18));
        root.addView(navColumn, new LinearLayout.LayoutParams(dp(186),
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setText(L.t("设置入口助手", "Settings Entry Helper"));
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(TEXT);
        navColumn.addView(title);

        TextView version = new TextView(this);
        version.setText("v" + versionName());
        version.setTextSize(13);
        version.setTextColor(MUTED);
        version.setPadding(0, dp(2), 0, dp(16));
        navColumn.addView(version);

        String[] navLabels = {
                "⚙  " + L.t("快捷入口", "Quick entries"),
                "ⓘ  " + L.t("诊断与写入", "Diagnostics"),
                "⬇  " + L.t("导出分析", "Export"),
                "⌕  " + L.t("组件探测", "Probes"),
                "7× " + L.t("连点助手", "Tap assist"),
                "☰  " + L.t("运行日志", "Log")};
        navRows.clear();
        for (int i = 0; i < navLabels.length; i++) {
            final int index = i;
            TextView row = new TextView(this);
            row.setText(navLabels[i]);
            row.setTextSize(15);
            row.setTextColor(MUTED);
            row.setPadding(dp(14), 0, dp(14), 0);
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(44));
            row.setBackground(navBg(false));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showPane(index); }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 0, 0, dp(4));
            navColumn.addView(row, params);
            navRows.add(row);
        }

        View spacer = new View(this);
        navColumn.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));

        TextView langRow = new TextView(this);
        langRow.setText(L.ZH.equals(L.current()) ? "Switch to English" : "切换到中文");
        langRow.setTextSize(14);
        langRow.setTextColor(ACCENT);
        langRow.setBackground(navBg(false));
        langRow.setPadding(dp(14), 0, dp(14), 0);
        langRow.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        langRow.setMinimumHeight(dp(44));
        langRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                L.set(MainActivity.this, L.ZH.equals(L.current()) ? L.EN : L.ZH);
                buildUi();
                showPane(selected);
            }
        });
        navColumn.addView(langRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBg());
        card.setPadding(dp(24), dp(20), dp(24), dp(24));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT);
        cardParams.setMargins(dp(4), dp(14), dp(14), dp(14));
        root.addView(card, cardParams);

        panes.clear();
        panes.add(quickPane());
        panes.add(diagPane());
        panes.add(exportPane());
        panes.add(probePane());
        panes.add(assistPane());
        panes.add(logPane());
        for (View pane : panes) {
            pane.setVisibility(View.GONE);
            card.addView(pane, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private void showPane(int index) {
        selected = index;
        for (int i = 0; i < panes.size(); i++) {
            panes.get(i).setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
        for (int i = 0; i < navRows.size(); i++) {
            TextView row = navRows.get(i);
            row.setBackground(navBg(i == index));
            row.setTextColor(i == index ? TEXT : MUTED);
            row.setTypeface(i == index ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }

    private LinearLayout paneShell(String number, String caption, String note) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(pane, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pane.setTag(scroll);

        TextView header = new TextView(this);
        header.setText(number + "   " + caption);
        header.setTextSize(24);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setTextColor(TEXT);
        pane.addView(header);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBg());
        card.setPadding(dp(22), dp(6), dp(22), dp(10));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, dp(14), 0, 0);
        pane.addView(card, cardParams);

        if (note != null) {
            TextView noteView = new TextView(this);
            noteView.setText(note);
            noteView.setTextSize(13);
            noteView.setTextColor(MUTED);
            noteView.setPadding(0, dp(10), 0, dp(12));
            pane.addView(noteView);
        }
        return pane;
    }

    private LinearLayout cardOf(View pane) {
        return (LinearLayout) ((ViewGroup) pane).getChildAt(1);
    }

    private void addSeparator(LinearLayout card) {
        View line = new View(this);
        line.setBackgroundColor(SEPARATOR);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        params.setMargins(dp(2), 0, dp(2), 0);
        card.addView(line, params);
    }

    private View actionRow(String label, final Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(60));
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextSize(16);
        labelView.setTextColor(TEXT);
        labelView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        row.addView(labelView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextSize(20);
        chevron.setTextColor(MUTED);
        chevron.setPadding(dp(8), 0, 0, 0);
        row.addView(chevron, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private View noteRow(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13);
        view.setTextColor(MUTED);
        view.setPadding(0, dp(12), 0, dp(12));
        return view;
    }

    private View candidateRow(String label, String sub, final ComponentName component) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { launchComponent(component); }
        });

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextSize(15);
        labelView.setTextColor(TEXT);
        row.addView(labelView);

        TextView subView = new TextView(this);
        subView.setText(sub);
        subView.setTextSize(12);
        subView.setTextColor(MUTED);
        subView.setPadding(0, dp(2), 0, 0);
        row.addView(subView);
        return row;
    }

    private LinearLayout quickPane() {
        LinearLayout pane = paneShell("01", L.t("快捷入口", "Quick entries"),
                L.t("打开系统隐藏页面后请观察是否自动关闭；固件路由是实测可用的开发者面板入口。",
                        "Open a system page, then watch whether it closes itself. The firmware route is the verified developer dashboard entry."));
        LinearLayout card = cardOf(pane);
        card.addView(actionRow(L.t("打开开发者面板（OH27 固件路由）",
                "Open developer dashboard (OH27 firmware route)"), new Runnable() {
            @Override public void run() { openFirmwareDeveloperDashboard(); }
        }));
        addSeparator(card);
        card.addView(actionRow(L.t("关于设备 / 本机信息", "About device / Device info"),
                new Runnable() {
            @Override public void run() {
                launchAction(Settings.ACTION_DEVICE_INFO_SETTINGS, "About device");
            }
        }));
        addSeparator(card);
        card.addView(actionRow(L.t("开发者选项（标准 Intent）",
                "Developer options (standard intent)"), new Runnable() {
            @Override public void run() {
                launchAction(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
                        "Developer options");
            }
        }));
        addSeparator(card);
        card.addView(actionRow(L.t("设置主页", "Settings home"), new Runnable() {
            @Override public void run() { launchAction(Settings.ACTION_SETTINGS, "Settings home"); }
        }));
        addSeparator(card);
        card.addView(actionRow(L.t("WLAN 设置", "Wi-Fi settings"), new Runnable() {
            @Override public void run() {
                launchAction(Settings.ACTION_WIRELESS_SETTINGS, "Wi-Fi settings");
            }
        }));
        addSeparator(card);
        card.addView(actionRow(L.t("无障碍设置", "Accessibility settings"), new Runnable() {
            @Override public void run() {
                launchAction(Settings.ACTION_ACCESSIBILITY_SETTINGS,
                        "Accessibility settings");
            }
        }));
        addSeparator(card);
        card.addView(actionRow(L.t("设置应用信息页", "Settings app info"), new Runnable() {
            @Override public void run() { openPackageInfo(SETTINGS_PACKAGE); }
        }));
        return pane;
    }

    private LinearLayout diagPane() {
        LinearLayout pane = paneShell("02", L.t("诊断与写入", "Diagnostics"),
                L.t("写入类操作在未授权时会被系统拒绝并记录到日志；日志内容保持英文原文便于检索。",
                        "Write attempts are rejected by the system without permission and logged; log lines stay in English for searchability."));
        LinearLayout card = cardOf(pane);
        card.addView(actionRow(L.t("读取当前设置状态", "Read current settings state"), new Runnable() {
            @Override public void run() {
                int start = logText.length();
                readState();
                showMessage(L.t("当前设置状态", "Current settings state"),
                        logText.substring(start).trim());
            }
        }));
        addSeparator(card);
        card.addView(actionRow(L.t("尝试写入 DEVELOPMENT_SETTINGS_ENABLED",
                "Try enabling DEVELOPMENT_SETTINGS_ENABLED"), new Runnable() {
            @Override public void run() {
                tryWriteGlobal(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1);
            }
        }));
        addSeparator(card);
        card.addView(actionRow(L.t("尝试写入 ADB_ENABLED", "Try enabling ADB_ENABLED"),
                new Runnable() {
            @Override public void run() { tryWriteGlobal(Settings.Global.ADB_ENABLED, 1); }
        }));
        addSeparator(card);
        card.addView(actionRow(L.t("复制诊断日志", "Copy diagnostic log"), new Runnable() {
            @Override public void run() { copyLog(); }
        }));
        addSeparator(card);
        card.addView(actionRow(L.t("分享诊断日志", "Share diagnostic log"), new Runnable() {
            @Override public void run() { shareLog(); }
        }));
        return pane;
    }

    private LinearLayout exportPane() {
        LinearLayout pane = paneShell("03", L.t("导出分析", "Export"),
                L.t("导出仅包含 APK 代码、组件清单和构建信息，不包含应用数据。",
                        "The export contains APK code, component metadata and build info only, never app data."));
        LinearLayout card = cardOf(pane);
        card.addView(actionRow(L.t("导出 FactoryMode APK + 报告（ZIP）",
                "Export FactoryMode APK + report (ZIP)"), new Runnable() {
            @Override public void run() { choosePackageExport(EXPORT_FACTORY); }
        }));
        addSeparator(card);
        card.addView(actionRow(L.t("导出 Settings APK + 组件报告（ZIP）",
                "Export Settings APK + component report (ZIP)"), new Runnable() {
            @Override public void run() { choosePackageExport(EXPORT_SETTINGS); }
        }));
        return pane;
    }

    private LinearLayout probePane() {
        LinearLayout pane = paneShell("04", L.t("组件探测", "Component probes"),
                L.t("灰色按钮表示组件未启用或不可导出，点击后结果记录在日志里。",
                        "A row may still fail to open; every attempt is recorded in the log."));
        LinearLayout card = cardOf(pane);
        candidateList = new LinearLayout(this);
        candidateList.setOrientation(LinearLayout.VERTICAL);
        card.addView(candidateList);
        return pane;
    }

    private LinearLayout assistPane() {
        LinearLayout pane = paneShell("05", L.t("连点助手", "Tap assist"),
                L.t("备用路径：布防后开启无障碍服务，再打开关于设备页，服务会自动连点版本号七次。主流程（快捷入口第一项）不需要本助手。",
                        "Backup path: arm the assist, enable the accessibility service, then open About device; the service taps the build number seven times. The main firmware route does not need this."));
        LinearLayout card = cardOf(pane);
        card.addView(actionRow(L.t("布防连点助手并打开无障碍设置",
                "Arm tap assist and open Accessibility"), new Runnable() {
            @Override public void run() {
                setBuildNumberAssist(true);
                Toast.makeText(MainActivity.this,
                        L.t("已布防。请在无障碍中开启「Settings Entry Helper tap assist」。",
                                "Assist armed. Enable 'Settings Entry Helper tap assist'."),
                        Toast.LENGTH_LONG).show();
                launchAction(Settings.ACTION_ACCESSIBILITY_SETTINGS,
                        "Accessibility settings for tap assist");
            }
        }));
        addSeparator(card);
        card.addView(actionRow(L.t("解除连点助手", "Disarm build-number tap assist"),
                new Runnable() {
            @Override public void run() {
                setBuildNumberAssist(false);
                Toast.makeText(MainActivity.this,
                        L.t("连点助手已解除", "Build-number assist disarmed"),
                        Toast.LENGTH_SHORT).show();
            }
        }));
        return pane;
    }

    private LinearLayout logPane() {
        LinearLayout pane = paneShell("06", L.t("运行日志", "Log"), null);
        LinearLayout card = cardOf(pane);
        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(LOG_TEXT);
        GradientDrawable logBg = new GradientDrawable();
        logBg.setColor(LOG_BG);
        logBg.setCornerRadius(dp(16));
        logView.setBackground(logBg);
        logView.setPadding(dp(14), dp(12), dp(14), dp(12));
        logView.setTextIsSelectable(true);
        logView.setSingleLine(false);
        logView.setHorizontallyScrolling(false);
        logView.setEllipsize(null);
        logView.setMovementMethod(new ScrollingMovementMethod());
        logView.setVerticalScrollBarEnabled(true);
        logView.setText(logText.toString());
        card.addView(logView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(520)));
        return pane;
    }

    private void addCandidateButton(final ComponentName component, String label) {
        String key = component.flattenToString();
        if (!listedCandidates.add(key) || candidateList == null) {
            return;
        }
        candidateList.addView(candidateRow(label, component.flattenToShortString(), component));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        candidateList.addView(makeSeparator(), params);
    }

    private View makeSeparator() {
        View line = new View(this);
        line.setBackgroundColor(SEPARATOR);
        return line;
    }

    private void addKnownCandidates() {
        addCandidate(L.t("关于设备（AOSP）", "About device (AOSP)"), "com.android.settings",
                "com.android.settings.Settings$MyDeviceInfoActivity");
        addCandidate(L.t("开发者选项（AOSP）", "Developer options (AOSP)"), "com.android.settings",
                "com.android.settings.Settings$DevelopmentSettingsActivity");
        addCandidate(L.t("开发者面板（OEM 命名）", "Developer dashboard (OEM name)"),
                "com.android.settings",
                "com.android.settings.Settings$DevelopmentSettingsDashboardActivity");
        addCandidate(L.t("ADB 扫码配对（AOSP 内部）", "ADB QR scanner (AOSP internal)"),
                "com.android.settings", "com.android.settings.development.AdbQrCodeActivity");
        addCandidate(L.t("无线调试（OEM 命名）", "Wireless debugging (OEM name)"),
                "com.android.settings",
                "com.android.settings.Settings$WirelessDebuggingActivity");
        addCandidate(L.t("无线调试页（OEM 命名）", "Wireless debugging activity (OEM name)"),
                "com.android.settings",
                "com.android.settings.development.WirelessDebuggingActivity");
        addCandidate(L.t("系统面板", "System dashboard"), "com.android.settings",
                "com.android.settings.Settings$SystemDashboardActivity");
        addCandidate(L.t("MIUI 开发者选项（OEM 命名）", "Miui developer options (OEM name)"),
                "com.android.settings",
                "com.android.settings.Settings$MiuiDevelopmentSettingsActivity");
        addCandidate(L.t("MIUI 开发者选项（备用）", "Miui developer options (alternate)"),
                "com.android.settings",
                "com.android.settings.Settings$MiuiDeveloperSettingsActivity");
    }

    private void addCandidate(String label, String pkg, String cls) {
        addCandidateButton(new ComponentName(pkg, cls), label);
    }

    private void scanSettingsActivities() {
        appendLog("\n[SCAN] Enumerating activities in " + SETTINGS_PACKAGE);
        try {
            PackageInfo info = getPackageInfoCompat(SETTINGS_PACKAGE,
                    PackageManager.GET_ACTIVITIES);
            ActivityInfo[] activities = info.activities;
            if (activities == null || activities.length == 0) {
                appendLog("  No activities returned. The OEM may hide package details.");
                return;
            }
            appendLog("  Total activities: " + activities.length);

            List<ActivityInfo> matches = new ArrayList<>();
            for (ActivityInfo activity : activities) {
                if (isInteresting(activity.name)) {
                    matches.add(activity);
                }
            }
            Collections.sort(matches, new Comparator<ActivityInfo>() {
                @Override
                public int compare(ActivityInfo left, ActivityInfo right) {
                    return left.name.compareTo(right.name);
                }
            });
            appendLog("  Matching activities: " + matches.size());
            for (ActivityInfo activity : matches) {
                String exportState = activity.exported ? "exported" : "not-exported";
                String enabledState = activity.enabled ? "enabled" : "disabled";
                addCandidateButton(new ComponentName(SETTINGS_PACKAGE, activity.name),
                        L.t("发现", "Found") + " [" + exportState + ", " + enabledState + "]");
            }
        } catch (PackageManager.NameNotFoundException e) {
            appendLog("  Settings package not found: " + e);
        } catch (SecurityException e) {
            appendLog("  Package query blocked: " + describe(e));
        }
    }

    private boolean isInteresting(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("storagewizard")) {
            return false;
        }
        return lower.contains("development")
                || lower.contains("developer")
                || lower.contains("adb")
                || lower.contains("debug")
                || lower.contains("wireless")
                || lower.contains("buildnumber")
                || lower.contains("engineering")
                || lower.contains("factory")
                || lower.contains("diagnostic")
                || lower.contains("deviceinfo")
                || lower.contains("aboutphone")
                || lower.contains("systemdashboard");
    }

    private void showMessage(String title, String message) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(L.t("好的", "OK"), null)
                .show();
    }

    private void openFirmwareDeveloperDashboard() {
        // OH27's exported host has no fixed fragment metadata. Its allowlist
        // includes the developer dashboard even when the usual activity is off.
        ComponentName host = new ComponentName(SETTINGS_PACKAGE,
                "com.android.settings.Settings$WifiSettingsActivity");
        String fragment = "com.android.settings.development.DevelopmentSettingsDashboardFragment";
        appendLog("\n[FIRMWARE ROUTE] " + host.flattenToString());
        appendLog("  fragment=" + fragment);
        appendLog("  Opening a system UI only; no direct settings writes or shell commands.");
        try {
            ActivityInfo info = getPackageManager().getActivityInfo(host,
                    PackageManager.GET_META_DATA);
            appendLog("  host exported=" + info.exported + " enabled=" + info.enabled
                    + " permission=" + info.permission);
            String fixedFragment = info.metaData == null ? null
                    : info.metaData.getString("com.android.settings.FRAGMENT_CLASS");
            appendLog("  host fixedFragment=" + fixedFragment);
            if (!info.exported || !info.enabled || !info.applicationInfo.enabled
                    || fixedFragment != null) {
                showMessage(L.t("固件路由不可用", "Firmware route unavailable"),
                        L.t("宿主被禁用、不导出或固定了页面；未启动任何其他组件。",
                                "The host is disabled, private, or has a fixed page. No other component was launched."));
                return;
            }
            Intent intent = new Intent();
            intent.setComponent(host);
            intent.putExtra(":settings:show_fragment", fragment);
            intent.putExtra(":settings:show_fragment_title", "Developer options");
            startActivity(intent);
            appendLog("  startActivity=OK; this does not confirm that the fragment loaded.");
        } catch (Exception e) {
            appendLog("  ERROR: " + describe(e));
            showMessage(L.t("固件路由失败", "Firmware route failed"), describe(e));
        }
    }

    private String exportPackageName(int requestCode) {
        return requestCode == EXPORT_FACTORY ? FACTORY_PACKAGE : SETTINGS_PACKAGE;
    }

    private String exportFileName(int requestCode) {
        return requestCode == EXPORT_FACTORY
                ? "OH27-FactoryMode-export.zip" : "OH27-Settings-export.zip";
    }

    private void choosePackageExport(int requestCode) {
        if (exporting) {
            showMessage(L.t("正在导出", "Export in progress"),
                    L.t("请等待当前导出完成。", "Please wait for the current export to finish."));
            return;
        }
        try {
            getPackageInfoCompat(exportPackageName(requestCode), 0);
        } catch (Exception e) {
            showMessage(L.t("包不可用", "Package unavailable"), exportPackageName(requestCode)
                    + "\n" + describe(e));
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, exportFileName(requestCode));
        try {
            startActivityForResult(intent, requestCode);
        } catch (Exception e) {
            appendLog("[EXPORT] File picker unavailable: " + describe(e));
            showMessage(L.t("无法打开保存对话框", "Cannot open save dialog"), describe(e)
                    + "\n" + L.t("系统文件选择器可能被禁用或缺失。",
                            "The system document picker may be disabled or missing."));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != EXPORT_SETTINGS && requestCode != EXPORT_FACTORY) {
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            Toast.makeText(this, L.t("已取消导出", "Export cancelled"), Toast.LENGTH_SHORT).show();
            return;
        }
        final Uri destination = data.getData();
        final String packageName = exportPackageName(requestCode);
        final String fileName = exportFileName(requestCode);
        exporting = true;
        Toast.makeText(this, L.t("正在导出", "Exporting") + " " + packageName + ". "
                + L.t("请稍候。", "Please wait."), Toast.LENGTH_LONG).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String report = exportPackage(destination, packageName);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            exporting = false;
                            appendLog("\n[EXPORT] Success\n" + report);
                            showMessage(L.t("导出已保存", "Export saved"),
                                    L.t("ZIP 内包含 APK 代码、组件元数据和构建信息，不包含应用数据。",
                                            "The ZIP contains APK code, component metadata and build information, not app data.")
                                    + "\n\n" + L.t("请将", "Copy ") + " " + fileName + " "
                                    + L.t("拷贝到电脑用于分析。", "to the Mac for analysis.")
                                    + "\n\n" + report);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            exporting = false;
                            appendLog("\n[EXPORT] Failed: " + describe(e));
                            showMessage(L.t("导出失败", "Export failed"), describe(e)
                                    + "\n" + L.t("重试前请先删除不完整的 ZIP。",
                                            "Delete any incomplete ZIP before retrying."));
                        }
                    });
                }
            }
        }, "package-apk-export").start();
    }

    private String exportPackage(Uri destination, String packageName) throws Exception {
        PackageInfo info = getPackageInfoCompat(packageName,
                PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES
                        | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS
                        | PackageManager.GET_META_DATA
                        | PackageManager.MATCH_DISABLED_COMPONENTS);
        if (info.applicationInfo == null || info.applicationInfo.sourceDir == null) {
            throw new IOException("APK path is unavailable for " + packageName);
        }
        StringBuilder report = new StringBuilder();
        report.append("package=").append(info.packageName)
                .append("\nversionName=").append(info.versionName)
                .append("\nmodel=").append(Build.MODEL)
                .append("\nandroid=").append(Build.VERSION.RELEASE)
                .append("\nsdk=").append(Build.VERSION.SDK_INT)
                .append("\nfingerprint=").append(Build.FINGERPRINT)
                .append("\n\nSHA-256 and source paths:\n");
        OutputStream output = getContentResolver().openOutputStream(destination, "wt");
        if (output == null) {
            throw new IOException("Cannot open selected destination");
        }
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            copyApk(zip, info.applicationInfo.sourceDir, "apks/base.apk", report);
            String[] splits = info.applicationInfo.splitSourceDirs;
            if (splits != null) {
                for (int i = 0; i < splits.length; i++) {
                    copyApk(zip, splits[i], "apks/split-" + i + ".apk", report);
                }
            }
            StringBuilder components = new StringBuilder();
            appendComponents(components, "ACTIVITY", info.activities);
            appendComponents(components, "SERVICE", info.services);
            appendComponents(components, "RECEIVER", info.receivers);
            appendComponents(components, "PROVIDER", info.providers);
            writeZipText(zip, "components.txt", components.toString());
            writeZipText(zip, "report.txt", report.toString());
        }
        return report.toString();
    }

    private void appendComponents(StringBuilder text, String kind,
            android.content.pm.ComponentInfo[] components) {
        if (components == null) {
            return;
        }
        for (android.content.pm.ComponentInfo component : components) {
            text.append(kind).append(' ').append(component.name)
                    .append(" exported=").append(component.exported)
                    .append(" enabled=").append(component.enabled).append('\n');
        }
    }

    private void copyApk(ZipOutputStream zip, String path, String entry,
            StringBuilder report) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        zip.putNextEntry(new ZipEntry(entry));
        try (FileInputStream input = new FileInputStream(path)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) {
                zip.write(buffer, 0, count);
                digest.update(buffer, 0, count);
                total += count;
            }
        }
        zip.closeEntry();
        StringBuilder hash = new StringBuilder();
        for (byte value : digest.digest()) {
            hash.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        report.append(entry).append(" bytes=").append(total)
                .append("\nsha256=").append(hash)
                .append("\nsource=").append(path).append("\n\n");
    }

    private void writeZipText(ZipOutputStream zip, String name, String text)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private void readState() {
        appendLog("\n[STATE]");
        appendLog("  Build.MANUFACTURER=" + Build.MANUFACTURER);
        appendLog("  Build.MODEL=" + Build.MODEL);
        appendLog("  Build.VERSION.RELEASE=" + Build.VERSION.RELEASE);
        appendLog("  Build.VERSION.SDK_INT=" + Build.VERSION.SDK_INT);
        appendLog("  Build.DISPLAY=" + Build.DISPLAY);
        appendLog("  DEVELOPMENT_SETTINGS_ENABLED="
                + readGlobal(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED));
        appendLog("  ADB_ENABLED=" + readGlobal(Settings.Global.ADB_ENABLED));
        appendLog("  Default HOME=" + resolveHome());
        appendLog("  build_number_assist=" + isBuildNumberAssistEnabled());
    }

    private String resolveHome() {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo info = getPackageManager().resolveActivity(
                    intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (info == null || info.activityInfo == null) {
                return "not resolved";
            }
            return info.activityInfo.packageName + "/" + info.activityInfo.name;
        } catch (Exception e) {
            return "error: " + describe(e);
        }
    }

    private void launchAction(String action, String label) {
        appendLog("\n[ACTION] " + label);
        appendLog("  action=" + action);
        Intent intent = new Intent(action);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ResolveInfo info = getPackageManager().resolveActivity(
                    intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (info != null && info.activityInfo != null) {
                appendLog("  resolves=" + info.activityInfo.packageName
                        + "/" + info.activityInfo.name
                        + " exported=" + info.activityInfo.exported);
            } else {
                appendLog("  resolves=no handler");
            }
            startActivity(intent);
            appendLog("  startActivity=OK (the target may still close immediately)");
        } catch (ActivityNotFoundException e) {
            appendLog("  ActivityNotFoundException: " + describe(e));
        } catch (SecurityException e) {
            appendLog("  SecurityException: " + describe(e));
        } catch (Exception e) {
            appendLog("  ERROR: " + describe(e));
        }
    }

    private void launchComponent(ComponentName component) {
        appendLog("\n[COMPONENT] " + component.flattenToString());
        try {
            ActivityInfo info = getPackageManager().getActivityInfo(component, 0);
            appendLog("  exists=true exported=" + info.exported
                    + " enabled=" + info.enabled);
        } catch (PackageManager.NameNotFoundException e) {
            appendLog("  exists=false");
        } catch (SecurityException e) {
            appendLog("  component query blocked: " + describe(e));
        }

        Intent intent = new Intent();
        intent.setComponent(component);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
            appendLog("  startActivity=OK (the target may still close immediately)");
        } catch (ActivityNotFoundException e) {
            appendLog("  ActivityNotFoundException: " + describe(e));
        } catch (SecurityException e) {
            appendLog("  SecurityException: " + describe(e));
        } catch (Exception e) {
            appendLog("  ERROR: " + describe(e));
        }
    }

    private void tryWriteGlobal(String key, int value) {
        appendLog("\n[WRITE] " + key + "=" + value);
        try {
            boolean changed = Settings.Global.putInt(getContentResolver(), key, value);
            appendLog("  putInt=" + changed + " readback=" + readGlobal(key));
            if (changed) {
                appendLog("  Write accepted. Re-reading the Settings page now.");
                launchAction(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
                        "Developer options after write");
            } else {
                appendLog("  Write rejected by the provider.");
            }
        } catch (SecurityException e) {
            appendLog("  SecurityException: " + describe(e));
            appendLog("  This is expected for a sideloaded app without WRITE_SECURE_SETTINGS.");
        } catch (Exception e) {
            appendLog("  ERROR: " + describe(e));
        }
    }

    private int readGlobal(String key) {
        try {
            return Settings.Global.getInt(getContentResolver(), key, -1);
        } catch (Exception e) {
            appendLog("  read " + key + " failed: " + describe(e));
            return -999;
        }
    }

    private void openPackageInfo(String packageName) {
        appendLog("\n[APP INFO] " + packageName);
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + packageName));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            appendLog("  startActivity=OK");
        } catch (Exception e) {
            appendLog("  ERROR: " + describe(e));
        }
    }

    private void copyLog() {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("Settings Entry Helper log",
                    logText.toString()));
            Toast.makeText(this, L.t("日志已复制", "Log copied"), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            appendLog("Clipboard error: " + describe(e));
        }
    }

    private void shareLog() {
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, logText.toString());
            startActivity(Intent.createChooser(intent,
                    L.t("分享诊断日志", "Share diagnostic log")));
        } catch (Exception e) {
            appendLog("Share error: " + describe(e));
        }
    }

    private void setBuildNumberAssist(boolean enabled) {
        SharedPreferences.Editor editor = getSharedPreferences(
                BuildNumberAssistService.PREFS, MODE_PRIVATE).edit()
                .putBoolean(BuildNumberAssistService.KEY_BUILD_NUMBER_ASSIST, enabled);
        if (enabled) {
            editor.putString(BuildNumberAssistService.KEY_ASSIST_LOG, "");
            lastAssistLog = "";
        }
        editor.apply();
        appendLog("\n[ASSIST] build_number_assist=" + enabled);
        if (enabled) {
            appendLog("  Enable 'Settings Entry Helper tap assist' in Accessibility.");
            appendLog("  Then open About device. The service first uses semantic clicks;");
            appendLog("  if the setting does not change, it retries with touch gestures.");
        }
    }

    private void syncAssistLog() {
        String assistLog = getSharedPreferences(
                BuildNumberAssistService.PREFS, MODE_PRIVATE)
                .getString(BuildNumberAssistService.KEY_ASSIST_LOG, "");
        if (assistLog == null || assistLog.length() == 0
                || assistLog.equals(lastAssistLog)) {
            return;
        }
        lastAssistLog = assistLog;
        appendLog("\n[ASSIST SERVICE LOG]");
        appendLog(assistLog.trim());
    }

    private boolean isBuildNumberAssistEnabled() {
        SharedPreferences prefs = getSharedPreferences(
                BuildNumberAssistService.PREFS, MODE_PRIVATE);
        return prefs.getBoolean(BuildNumberAssistService.KEY_BUILD_NUMBER_ASSIST, false);
    }

    private void appendLog(String line) {
        logText.append(line).append('\n');
        if (logView != null) {
            logView.setText(logText.toString());
            logView.post(new Runnable() {
                @Override
                public void run() {
                    int scrollY = logView.getLayout() == null
                            ? 0
                            : Math.max(0,
                                    logView.getLayout().getHeight() - logView.getHeight());
                    logView.scrollTo(0, scrollY);
                }
            });
        }
    }

    private String describe(Throwable throwable) {
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName()
                + (message == null || message.length() == 0 ? "" : ": " + message);
    }

    private PackageInfo getPackageInfoCompat(String packageName, int flags)
            throws PackageManager.NameNotFoundException {
        if (Build.VERSION.SDK_INT >= 33) {
            return getPackageManager().getPackageInfo(
                    packageName, PackageManager.PackageInfoFlags.of(flags));
        }
        return getPackageManager().getPackageInfo(packageName, flags);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
