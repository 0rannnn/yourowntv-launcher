package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.text.DateFormat;
import java.util.Collections;
import java.util.Date;

/**
 * The quick panel's Troubleshooting card: what is needed for the computer to reach this TV from anywhere and fix it,
 * and a troubleshooting report.
 * <ul>
 * <li>Tailscale (com.tailscale.ipn), a private network the TV and the computer both join: install, open, and the
 * TV's Tailscale address (100.64.0.0/10) once it is connected.</li>
 * <li>The repair channel: the TV's ADB listening on port 5555 (the setup app, which holds the ADB permission, turns it
 * on: com.gathin.kit.action.REMOTE_ON). With both, the computer runs {@code adb connect <address>:5555}.</li>
 * <li>The report (com.gathin.kit.action.REPORT): tv-report.txt in the theme folder, offered by the phone page.</li>
 * </ul>
 * The lines refresh every 2 seconds while the card is open.
 */
final class TroubleshootCard {
    static final String TAILSCALE = "com.tailscale.ipn";
    static final String KIT = "com.gathin.kit";
    static final String REPORT = "tv-report.txt";

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float density;
    private TextView tailscaleLine, channelLine, reportLine, addressLine;
    private TextView tailscaleButton;
    private volatile boolean channelOpen;
    private volatile String tailscaleAddress;
    private boolean open = true;

    private TroubleshootCard(Activity activity) {
        this.activity = activity;
        density = activity.getResources().getDisplayMetrics().density;
    }

    /** The card, ready to be added; onClose is run by its close button. Stop it with stop() when removed. */
    static TroubleshootCard create(Activity activity) {
        return new TroubleshootCard(activity);
    }

    View build(Runnable onClose) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackgroundResource(R.drawable.vision_card);
        card.setPadding(dp(34), dp(26), dp(34), dp(24));
        card.setElevation(dp(16));

        TextView title = text(activity.getString(R.string.qs_fix_title), 20, R.color.text_primary);
        title.setTypeface(UiFont.get(activity, R.string.font_medium));
        card.addView(title);
        String home = "";
        try {
            home = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        TextView device = text(Build.MANUFACTURER + " " + Build.MODEL + "  ·  Android " + Build.VERSION.RELEASE
                + "  ·  Home " + home, 13, R.color.vision_text);
        device.setTextDirection(View.TEXT_DIRECTION_LTR);
        card.addView(device, margins(6));

        tailscaleLine = text("", 15, R.color.text_primary);
        channelLine = text("", 15, R.color.text_primary);
        reportLine = text("", 15, R.color.text_primary);
        card.addView(tailscaleLine, margins(18));
        card.addView(channelLine, margins(6));
        card.addView(reportLine, margins(6));
        addressLine = text("", 26, R.color.text_primary);
        addressLine.setTypeface(UiFont.get(activity, R.string.font_medium));
        addressLine.setTextDirection(View.TEXT_DIRECTION_LTR);
        card.addView(addressLine, margins(14));

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        tailscaleButton = button("", v -> tailscale());
        buttons.addView(tailscaleButton);
        buttons.addView(button(activity.getString(R.string.qs_fix_channel), v -> ask("com.gathin.kit.action.REMOTE_ON",
                R.string.qs_fix_channel_started)));
        buttons.addView(button(activity.getString(R.string.qs_fix_report), v -> ask("com.gathin.kit.action.REPORT",
                R.string.qs_fix_report_started)));
        buttons.addView(button(activity.getString(R.string.qs_close), v -> onClose.run()));
        card.addView(buttons, margins(18));

        TextView note = text(activity.getString(R.string.qs_fix_note), 12, R.color.vision_text);
        note.setMaxWidth(dp(520));
        note.setAlpha(0.8f);
        card.addView(note, margins(14));

        refresh();
        buttons.getChildAt(0).post(() -> buttons.getChildAt(0).requestFocus());
        return card;
    }

    void stop() {
        open = false;
        main.removeCallbacksAndMessages(null);
    }

    private void refresh() {
        if (!open) return;
        new Thread(() -> {
            channelOpen = portOpen(5555);
            tailscaleAddress = tailscaleAddress();
            main.post(this::show);
        }, "troubleshoot-check").start();
        main.postDelayed(this::refresh, 2000);
    }

    private void show() {
        if (!open) return;
        boolean installed = installed(TAILSCALE);
        String ts = tailscaleAddress;
        tailscaleLine.setText(ts != null ? activity.getString(R.string.qs_fix_ts_on, ts)
                : installed ? activity.getString(R.string.qs_fix_ts_off) : activity.getString(R.string.qs_fix_ts_missing));
        tailscaleButton.setText(installed ? R.string.qs_fix_ts_open : R.string.qs_fix_ts_install);
        channelLine.setText(channelOpen ? R.string.qs_fix_channel_on : R.string.qs_fix_channel_off);
        File report = reportFile();
        reportLine.setText(report != null && report.isFile()
                ? activity.getString(R.string.qs_fix_report_ready,
                DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(report.lastModified())))
                : activity.getString(R.string.qs_fix_report_none));
        addressLine.setText(ts != null && channelOpen ? ts + ":5555" : "");
        addressLine.setVisibility(ts != null && channelOpen ? View.VISIBLE : View.GONE);
    }

    private void tailscale() {
        PackageManager pm = activity.getPackageManager();
        Intent launch = installed(TAILSCALE) ? pm.getLeanbackLaunchIntentForPackage(TAILSCALE) : null;
        if (launch == null && installed(TAILSCALE)) launch = pm.getLaunchIntentForPackage(TAILSCALE);
        if (launch == null) launch = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + TAILSCALE));
        try {
            activity.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Asks the setup app (it holds the TV's ADB permission) for the repair channel or the report. */
    private void ask(String action, int started) {
        if (!installed(KIT)) {
            Toast.makeText(activity, R.string.qs_fix_need_kit, Toast.LENGTH_LONG).show();
            return;
        }
        activity.sendBroadcast(new Intent(action).setPackage(KIT));
        Toast.makeText(activity, started, Toast.LENGTH_SHORT).show();
    }

    private File reportFile() {
        File base = Config.baseDir(activity);
        return base == null ? null : new File(base, REPORT);
    }

    private boolean installed(String pkg) {
        try {
            activity.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean portOpen(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 400);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** The TV's address on Tailscale (100.64.0.0/10), or null when Tailscale is not connected. */
    static String tailscaleAddress() {
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp()) continue;
                for (InetAddress a : Collections.list(nic.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) continue;
                    byte[] b = a.getAddress();
                    if ((b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private TextView text(String value, int sp, int color) {
        TextView t = new TextView(activity);
        t.setText(value);
        t.setTextColor(activity.getColor(color));
        t.setTextSize(sp);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private TextView button(String label, View.OnClickListener click) {
        TextView b = new TextView(activity);
        b.setText(label);
        b.setTextSize(15);
        b.setTextColor(activity.getColorStateList(R.drawable.pill_text));
        b.setBackgroundResource(R.drawable.vision_toggle_bg);
        b.setPadding(dp(20), dp(10), dp(20), dp(10));
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        lp.rightMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private LinearLayout.LayoutParams margins(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(top);
        return lp;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
