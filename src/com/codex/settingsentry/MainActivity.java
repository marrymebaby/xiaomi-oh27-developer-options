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
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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
    private static final String SETTINGS_PACKAGE = "com.android.settings";
    private static final String FACTORY_PACKAGE = "com.mediatek.factorymode";
    private static final String TAG = "SettingsEntryHelper";
    private static final int EXPORT_SETTINGS = 1001;
    private static final int EXPORT_FACTORY = 1002;

    private LinearLayout root;
    private LinearLayout candidateList;
    private TextView logView;
    private final StringBuilder logText = new StringBuilder();
    private final Set<String> listedCandidates = new HashSet<>();
    private String lastAssistLog = "";
    private boolean exporting;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        appendLog("Settings Entry Helper started");
        appendLog("No root. Settings export copies APK code only, not app data.");
        readState();
        addKnownCandidates();
        scanSettingsActivities();
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncAssistLog();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(246, 244, 238));

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28), dp(24), dp(28), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("Settings Entry Helper 1.4");
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(25, 30, 36));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Direct probes for hidden Android Settings pages. "
                + "Watch the log after each button: a page can exist but still close itself.");
        subtitle.setTextSize(15);
        subtitle.setTextColor(Color.rgb(70, 76, 84));
        subtitle.setPadding(0, dp(8), 0, dp(18));
        root.addView(subtitle);

        addSection("Quick probes");
        addActionButton("Open developer dashboard (OH27 firmware route)", new Runnable() {
            @Override
            public void run() {
                openFirmwareDeveloperDashboard();
            }
        });
        addActionButton("Export FactoryMode APK + report (ZIP)", new Runnable() {
            @Override
            public void run() {
                choosePackageExport(EXPORT_FACTORY);
            }
        });
        addActionButton("Export Settings APK + component report (ZIP)", new Runnable() {
            @Override
            public void run() {
                choosePackageExport(EXPORT_SETTINGS);
            }
        });
        addActionButton("Read current settings state", new Runnable() {
            @Override
            public void run() {
                int start = logText.length();
                readState();
                showMessage("Current settings state", logText.substring(start).trim());
            }
        });
        addActionButton("Open About device / Device info", new Runnable() {
            @Override
            public void run() {
                launchAction(Settings.ACTION_DEVICE_INFO_SETTINGS, "About device");
            }
        });
        addActionButton("Open Developer options (standard intent)", new Runnable() {
            @Override
            public void run() {
                launchAction(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
                        "Developer options");
            }
        });
        addActionButton("Try enabling DEVELOPMENT_SETTINGS_ENABLED", new Runnable() {
            @Override
            public void run() {
                tryWriteGlobal(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1);
            }
        });
        addActionButton("Try enabling ADB_ENABLED", new Runnable() {
            @Override
            public void run() {
                tryWriteGlobal(Settings.Global.ADB_ENABLED, 1);
            }
        });
        addActionButton("Open Settings home", new Runnable() {
            @Override
            public void run() {
                launchAction(Settings.ACTION_SETTINGS, "Settings home");
            }
        });
        addActionButton("Open Wi-Fi settings", new Runnable() {
            @Override
            public void run() {
                launchAction(Settings.ACTION_WIRELESS_SETTINGS, "Wi-Fi settings");
            }
        });
        addActionButton("Open Accessibility settings", new Runnable() {
            @Override
            public void run() {
                launchAction(Settings.ACTION_ACCESSIBILITY_SETTINGS,
                        "Accessibility settings");
            }
        });
        addActionButton("Open Settings app info", new Runnable() {
            @Override
            public void run() {
                openPackageInfo(SETTINGS_PACKAGE);
            }
        });
        addActionButton("Copy diagnostic log", new Runnable() {
            @Override
            public void run() {
                copyLog();
            }
        });
        addActionButton("Share diagnostic log", new Runnable() {
            @Override
            public void run() {
                shareLog();
            }
        });

        addSection("Optional build-number tap assist");
        addActionButton("Arm tap assist and open Accessibility", new Runnable() {
            @Override
            public void run() {
                setBuildNumberAssist(true);
                Toast.makeText(MainActivity.this,
                        "Assist armed. Enable Settings Entry Helper tap assist.",
                        Toast.LENGTH_LONG).show();
                launchAction(Settings.ACTION_ACCESSIBILITY_SETTINGS,
                        "Accessibility settings for tap assist");
            }
        });
        addActionButton("Disarm build-number tap assist", new Runnable() {
            @Override
            public void run() {
                setBuildNumberAssist(false);
                Toast.makeText(MainActivity.this,
                        "Build-number assist disarmed",
                        Toast.LENGTH_SHORT).show();
            }
        });

        addSection("Known component probes");
        candidateList = new LinearLayout(this);
        candidateList.setOrientation(LinearLayout.VERTICAL);
        root.addView(candidateList);

        addSection("Log");
        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(Color.rgb(235, 238, 242));
        logView.setBackgroundColor(Color.rgb(31, 37, 45));
        logView.setPadding(dp(14), dp(12), dp(14), dp(12));
        logView.setTextIsSelectable(true);
        logView.setSingleLine(false);
        logView.setHorizontallyScrolling(false);
        logView.setEllipsize(null);
        logView.setMovementMethod(new ScrollingMovementMethod());
        logView.setVerticalScrollBarEnabled(true);
        root.addView(logView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(260)));
    }

    private void addSection(String label) {
        TextView section = new TextView(this);
        section.setText(label);
        section.setTextSize(20);
        section.setTypeface(Typeface.DEFAULT_BOLD);
        section.setTextColor(Color.rgb(25, 30, 36));
        section.setPadding(0, dp(20), 0, dp(10));
        root.addView(section);
    }

    private void addActionButton(String label, final Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setPadding(dp(18), 0, dp(18), 0);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        params.setMargins(0, 0, 0, dp(10));
        root.addView(button, params);
    }

    private void addCandidateButton(final ComponentName component, String label) {
        String key = component.flattenToString();
        if (!listedCandidates.add(key)) {
            return;
        }
        Button button = new Button(this);
        button.setText(label + "\n" + component.flattenToShortString());
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setPadding(dp(16), dp(8), dp(16), dp(8));
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchComponent(component);
            }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(8));
        candidateList.addView(button, params);
    }

    private void addKnownCandidates() {
        addCandidate("About device (AOSP)", "com.android.settings",
                "com.android.settings.Settings$MyDeviceInfoActivity");
        addCandidate("Developer options (AOSP)", "com.android.settings",
                "com.android.settings.Settings$DevelopmentSettingsActivity");
        addCandidate("Developer dashboard (OEM name)", "com.android.settings",
                "com.android.settings.Settings$DevelopmentSettingsDashboardActivity");
        addCandidate("ADB QR scanner (AOSP internal)", "com.android.settings",
                "com.android.settings.development.AdbQrCodeActivity");
        addCandidate("Wireless debugging (OEM name)", "com.android.settings",
                "com.android.settings.Settings$WirelessDebuggingActivity");
        addCandidate("Wireless debugging activity (OEM name)", "com.android.settings",
                "com.android.settings.development.WirelessDebuggingActivity");
        addCandidate("System dashboard", "com.android.settings",
                "com.android.settings.Settings$SystemDashboardActivity");
        addCandidate("Miui developer options (OEM name)", "com.android.settings",
                "com.android.settings.Settings$MiuiDevelopmentSettingsActivity");
        addCandidate("Miui developer options (alternate)", "com.android.settings",
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
                        "Found [" + exportState + ", " + enabledState + "]");
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
                .setPositiveButton("OK", null)
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
                showMessage("Firmware route unavailable", "The host is disabled, private, "
                        + "or has a fixed page. No other component was launched.");
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
            showMessage("Firmware route failed", describe(e));
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
            showMessage("Export in progress", "Please wait for the current export to finish.");
            return;
        }
        try {
            getPackageInfoCompat(exportPackageName(requestCode), 0);
        } catch (Exception e) {
            showMessage("Package unavailable", exportPackageName(requestCode)
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
            showMessage("Cannot open save dialog", describe(e)
                    + "\nThe system document picker may be disabled or missing.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != EXPORT_SETTINGS && requestCode != EXPORT_FACTORY) {
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            Toast.makeText(this, "Export cancelled", Toast.LENGTH_SHORT).show();
            return;
        }
        final Uri destination = data.getData();
        final String packageName = exportPackageName(requestCode);
        final String fileName = exportFileName(requestCode);
        exporting = true;
        Toast.makeText(this, "Exporting " + packageName + ". Please wait.",
                Toast.LENGTH_LONG).show();
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
                            showMessage("Export saved", "The ZIP contains APK code, "
                                    + "component metadata and build information, not app data.\n\n"
                                    + "Copy " + fileName + " to the Mac for analysis.\n\n"
                                    + report);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            exporting = false;
                            appendLog("\n[EXPORT] Failed: " + describe(e));
                            showMessage("Export failed", describe(e)
                                    + "\nDelete any incomplete ZIP before retrying.");
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
            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            appendLog("Clipboard error: " + describe(e));
        }
    }

    private void shareLog() {
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, logText.toString());
            startActivity(Intent.createChooser(intent, "Share diagnostic log"));
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
