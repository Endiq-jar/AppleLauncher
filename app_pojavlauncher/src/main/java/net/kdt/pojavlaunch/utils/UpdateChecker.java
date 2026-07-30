package net.kdt.pojavlaunch.utils;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import net.kdt.pojavlaunch.PojavApplication;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks GitHub Releases for a newer build of the app and, if found, offers to
 * download and install it. Relies on the release tag being formatted "v2.<runNumber>"
 * (as produced by the CI workflow) and on {@code versionCode} being seeded from that
 * same run number at build time (see build.gradle's "buildVersionCode" property).
 */
public class UpdateChecker {

    /** Owner/repo on GitHub to check for releases. Update if the repo ever moves. */
    private static final String GITHUB_REPO = "Endiq-jar/AppleLauncher";
    private static final String RELEASES_LATEST_URL =
            "https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest";
    private static final Pattern RUN_NUMBER_PATTERN = Pattern.compile("v2\\.(\\d+)");

    private static BroadcastReceiver sDownloadReceiver;

    /**
     * Silently checks for an update in the background. If one is found, shows a
     * dialog on the UI thread offering to download/install it. Safe to call from
     * onCreate() — does nothing visible if there's no update or the check fails
     * (e.g. offline), unless {@code showFeedbackToast} is true.
     */
    public static void checkForUpdate(Activity activity, boolean showFeedbackToast) {
        PojavApplication.sExecutorService.execute(() -> {
            try {
                int currentVersionCode = getCurrentVersionCode(activity);

                JSONObject release = fetchLatestRelease();
                if (release == null) {
                    if (showFeedbackToast) toast(activity, "Couldn't check for updates — try again later.");
                    return;
                }

                String tagName = release.optString("tag_name", "");
                Matcher m = RUN_NUMBER_PATTERN.matcher(tagName);
                if (!m.find()) {
                    if (showFeedbackToast) toast(activity, "Up to date (" + tagName + ")");
                    return;
                }
                int remoteRunNumber = Integer.parseInt(m.group(1));
                int remoteVersionCode = 10000000 + remoteRunNumber;

                if (remoteVersionCode <= currentVersionCode) {
                    if (showFeedbackToast) toast(activity, "You're on the latest version.");
                    return;
                }

                String apkUrl = findApkAssetUrl(release);
                if (apkUrl == null) {
                    if (showFeedbackToast) toast(activity, "Update found but no APK asset was attached to it.");
                    return;
                }

                String releaseNotes = release.optString("body", "");

                activity.runOnUiThread(() ->
                        showUpdateDialog(activity, tagName, releaseNotes, apkUrl));

            } catch (Exception e) {
                if (showFeedbackToast) toast(activity, "Update check failed: " + e.getMessage());
            }
        });
    }

    private static void toast(Activity activity, String msg) {
        activity.runOnUiThread(() -> Toast.makeText(activity, msg, Toast.LENGTH_LONG).show());
    }

    private static int getCurrentVersionCode(Context ctx) throws PackageManager.NameNotFoundException {
        PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return (int) info.getLongVersionCode();
        } else {
            //noinspection deprecation
            return info.versionCode;
        }
    }

    @Nullable
    private static JSONObject fetchLatestRelease() throws Exception {
        URL url = new URL(RELEASES_LATEST_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            int code = conn.getResponseCode();
            if (code != 200) return null;

            InputStream in = conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }
            return new JSONObject(sb.toString());
        } finally {
            conn.disconnect();
        }
    }

    @Nullable
    private static String findApkAssetUrl(JSONObject release) {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) return null;
        // Prefer a DEBUG apk (always signed with the committed debug key, installs
        // directly). Fall back to any other .apk asset if that's not present.
        String fallback = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String name = asset.optString("name", "");
            String downloadUrl = asset.optString("browser_download_url", null);
            if (downloadUrl == null || !name.endsWith(".apk")) continue;
            if (name.contains("DEBUG")) return downloadUrl;
            if (fallback == null) fallback = downloadUrl;
        }
        return fallback;
    }

    private static void showUpdateDialog(Activity activity, String tagName, String releaseNotes, String apkUrl) {
        if (activity.isFinishing()) return;
        String message = releaseNotes == null || releaseNotes.trim().isEmpty()
                ? "A new build (" + tagName + ") is available."
                : "A new build (" + tagName + ") is available:\n\n" + releaseNotes;

        new AlertDialog.Builder(activity)
                .setTitle("Update Available")
                .setMessage(message)
                .setPositiveButton("Download & Install", (d, w) -> downloadAndInstall(activity, apkUrl, tagName))
                .setNegativeButton("Later", null)
                .setCancelable(true)
                .show();
    }

    private static void downloadAndInstall(Activity activity, String apkUrl, String tagName) {
        try {
            DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) {
                Toast.makeText(activity, "Download service unavailable.", Toast.LENGTH_LONG).show();
                return;
            }

            String fileName = "AppleLauncher-" + tagName + ".apk";
            File updatesDir = new File(activity.getExternalFilesDir(null), "updates");
            if (!updatesDir.exists()) //noinspection ResultOfMethodCallIgnored
                updatesDir.mkdirs();
            File destFile = new File(updatesDir, fileName);
            if (destFile.exists()) //noinspection ResultOfMethodCallIgnored
                destFile.delete();

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
            request.setTitle("Apple Launcher update");
            request.setDescription("Downloading " + tagName);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS + "/updates", fileName);

            long downloadId = dm.enqueue(request);
            Toast.makeText(activity, "Downloading update…", Toast.LENGTH_SHORT).show();

            registerInstallReceiver(activity, dm, downloadId);
        } catch (Exception e) {
            Toast.makeText(activity, "Couldn't start download: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static void registerInstallReceiver(Activity activity, DownloadManager dm, long expectedId) {
        if (sDownloadReceiver != null) {
            try { activity.getApplicationContext().unregisterReceiver(sDownloadReceiver); } catch (Exception ignored) {}
        }
        sDownloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != expectedId) return;

                Uri localUri = dm.getUriForDownloadedFile(id);
                context.unregisterReceiver(this);
                sDownloadReceiver = null;

                if (localUri == null) {
                    Toast.makeText(context, "Update download failed.", Toast.LENGTH_LONG).show();
                    return;
                }
                promptInstall(activity, localUri);
            }
        };
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getApplicationContext().registerReceiver(sDownloadReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            activity.getApplicationContext().registerReceiver(sDownloadReceiver, filter);
        }
    }

    private static void promptInstall(Activity activity, Uri downloadManagerUri) {
        // DownloadManager returns a content://downloads/... URI which the package
        // installer can't read directly on modern Android — re-wrap the actual file
        // through our own FileProvider instead.
        File updatesDir = new File(activity.getExternalFilesDir(null), "updates");
        File[] apkFiles = updatesDir.listFiles((dir, name) -> name.endsWith(".apk"));
        File apkFile = (apkFiles != null && apkFiles.length > 0) ? apkFiles[0] : null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(activity,
                    "Allow \"Install unknown apps\" for Apple Launcher, then tap the update notification again.",
                    Toast.LENGTH_LONG).show();
            Intent settingsIntent = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(settingsIntent);
            return;
        }

        Uri installUri = apkFile != null
                ? FileProvider.getUriForFile(activity, activity.getPackageName() + ".updateprovider", apkFile)
                : downloadManagerUri;

        Intent installIntent = new Intent(Intent.ACTION_VIEW);
        installIntent.setDataAndType(installUri, "application/vnd.android.package-archive");
        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(installIntent);
    }
}
