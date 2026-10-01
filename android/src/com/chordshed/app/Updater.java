package com.chordshed.app;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * In-app update: download the published APK, prove it is the right file, and hand
 * it to Android's own installer, which shows the standard "update this app?" screen.
 *
 * Everything comes from one fixed host. The page can ask for an update but cannot
 * say where from: the version file is read here, not passed in, so a compromised
 * page could at worst reinstall the current release.
 *
 * Three independent checks before anything is installed:
 *  1. SHA-256 of the download matches version.json (catches a truncated or stale file).
 *  2. The archive is this package, at a higher versionCode than the installed one.
 *  3. It is signed by the same certificate as the installed app. Android enforces this
 *     too, but would only say so after the confirm screen, as a vague "App not installed".
 */
final class Updater {

    static final String SITE = "https://chord-shed.vercel.app";
    static final String ACTION_STATUS = "com.chordshed.app.INSTALL_STATUS";

    private static final Pattern APK_PATH = Pattern.compile("^/dl/[A-Za-z0-9._-]+\\.apk$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final long MAX_APK = 50L * 1024 * 1024;

    /** Reports progress to the page. phase: checking, downloading, verifying,
        permission, installing, confirm, error. pct is -1 when not meaningful. */
    interface Listener {
        void onStatus(String phase, int pct, String message);
    }

    private final Context ctx;
    private final Listener out;
    private volatile boolean busy = false;
    /** A verified APK waiting for the user to allow installs from this app. */
    private volatile File ready = null;
    /** The install session we committed; status broadcasts for any other id are ignored. */
    private volatile int session = -1;

    Updater(Context ctx, Listener out) {
        this.ctx = ctx.getApplicationContext();
        this.out = out;
    }

    boolean canInstall() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || ctx.getPackageManager().canRequestPackageInstalls();
    }

    /** True only for the session this app committed. Below Android 13 the status
        receiver is reachable by other apps, so this is what keeps a forged
        broadcast from making us launch an arbitrary intent. */
    boolean ownsSession(int id) {
        return id != -1 && id == session;
    }

    boolean hasReady() {
        return ready != null && ready.exists();
    }

    /** Download, verify and install. Runs on its own thread; safe to call twice. */
    synchronized void start() {
        if (busy) return;
        busy = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File apk = fetchAndVerify();
                    ready = apk;
                    installIfAllowed();
                } catch (Exception e) {
                    out.onStatus("error", -1, message(e));
                } finally {
                    busy = false;
                }
            }
        }, "chordshed-update").start();
    }

    /** Called again after the user returns from the "install unknown apps" setting. */
    void installIfAllowed() {
        final File apk = ready;
        if (apk == null || !apk.exists()) return;
        if (!canInstall()) {
            out.onStatus("permission", -1,
                    "Android needs your OK for Chord Shed to install its own updates.");
            return;
        }
        ready = null;
        try {
            out.onStatus("installing", -1, "Handing the update to Android…");
            commit(apk);
        } catch (Exception e) {
            out.onStatus("error", -1, message(e));
        }
    }

    // ---------------------------------------------------------------- download

    private File fetchAndVerify() throws Exception {
        out.onStatus("checking", -1, "Checking the latest release…");
        JSONObject v = new JSONObject(new String(get(SITE + "/version.json?t=" + System.currentTimeMillis()), "UTF-8"));

        long remoteCode = v.optLong("versionCode", -1);
        long localCode = installedVersionCode();
        if (remoteCode <= localCode) {
            throw new UpdateException("You already have the newest version.");
        }
        String path = v.optString("apk", "");
        if (!APK_PATH.matcher(path).matches()) {
            throw new UpdateException("The release points at an unexpected file (" + path + ").");
        }
        String want = v.optString("sha256", "").toLowerCase(Locale.ROOT);
        if (!SHA256.matcher(want).matches()) {
            throw new UpdateException("The release has no checksum, so it cannot be verified.");
        }

        File dir = new File(ctx.getCacheDir(), "update");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        File apk = new File(dir, "chord-shed-" + remoteCode + ".apk");

        // ?v= keeps a CDN copy of the previous APK from being served under the new checksum.
        String have = download(SITE + path + "?v=" + remoteCode, apk);
        out.onStatus("verifying", -1, "Checking the download…");
        if (!have.equals(want)) {
            apk.delete();
            throw new UpdateException("The download did not match its checksum. Try again in a few minutes.");
        }
        verifyArchive(apk, localCode);
        return apk;
    }

    private static byte[] get(String url) throws IOException {
        HttpURLConnection c = open(url);
        try {
            InputStream in = c.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                b.write(buf, 0, n);
                if (b.size() > 64 * 1024) throw new IOException("version file is too large");
            }
            return b.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    /** Streams url into dest and returns the SHA-256 of what was written, in hex. */
    private String download(String url, File dest) throws Exception {
        HttpURLConnection c = open(url);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try {
            long total = c.getContentLengthLong();
            if (total > MAX_APK) throw new IOException("the update is unexpectedly large");
            InputStream in = c.getInputStream();
            OutputStream os = new FileOutputStream(dest);
            try {
                byte[] buf = new byte[16384];
                long got = 0;
                int n, lastPct = -1;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    md.update(buf, 0, n);
                    got += n;
                    if (got > MAX_APK) throw new IOException("the update is unexpectedly large");
                    int pct = total > 0 ? (int) (got * 100 / total) : -1;
                    if (pct != lastPct) {
                        lastPct = pct;
                        out.onStatus("downloading", pct, "Downloading…");
                    }
                }
            } finally {
                os.close();
            }
        } finally {
            c.disconnect();
        }
        return hex(md.digest());
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setUseCaches(false);
        // HttpURLConnection never follows a redirect from https to http, so this stays on TLS.
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new IOException("the server returned " + code);
        }
        if (!"https".equals(c.getURL().getProtocol())) {
            c.disconnect();
            throw new IOException("refusing a non-https download");
        }
        return c;
    }

    // ---------------------------------------------------------------- verify

    private void verifyArchive(File apk, long localCode) throws Exception {
        PackageManager pm = ctx.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo arc = pm.getPackageArchiveInfo(apk.getPath(), flags);
        if (arc == null) {
            apk.delete();
            throw new UpdateException("The download is not a readable Android package.");
        }
        if (!ctx.getPackageName().equals(arc.packageName)) {
            apk.delete();
            throw new UpdateException("The download is a different app (" + arc.packageName + ").");
        }
        if (codeOf(arc) <= localCode) {
            apk.delete();
            throw new UpdateException("The download is not newer than this version.");
        }
        PackageInfo mine = pm.getPackageInfo(ctx.getPackageName(), flags);
        Signature[] a = signers(arc), b = signers(mine);
        // Unreadable signatures are left to Android, which checks at install time anyway.
        if (a != null && b != null && !sameSet(a, b)) {
            apk.delete();
            throw new UpdateException("The download is not signed with this app's key, so Android would refuse it.");
        }
    }

    @SuppressWarnings("deprecation")
    private static Signature[] signers(PackageInfo pi) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return pi.signingInfo == null ? null : pi.signingInfo.getApkContentsSigners();
        }
        return pi.signatures;
    }

    private static boolean sameSet(Signature[] a, Signature[] b) {
        if (a.length == 0 || a.length != b.length) return false;
        String[] x = new String[a.length], y = new String[b.length];
        for (int i = 0; i < a.length; i++) { x[i] = a[i].toCharsString(); y[i] = b[i].toCharsString(); }
        Arrays.sort(x);
        Arrays.sort(y);
        return Arrays.equals(x, y);
    }

    // ---------------------------------------------------------------- install

    private void commit(File apk) throws IOException {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(ctx.getPackageName());
        params.setSize(apk.length());
        int id = pi.createSession(params);
        PackageInstaller.Session s = pi.openSession(id);
        try {
            InputStream in = new FileInputStream(apk);
            OutputStream os = s.openWrite("chord-shed.apk", 0, apk.length());
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                s.fsync(os);
            } finally {
                os.close();
                in.close();
            }
            // Mutable so the installer can attach its status extras; explicit (setPackage)
            // because Android 14 refuses mutable PendingIntents with an implicit intent.
            Intent cb = new Intent(ACTION_STATUS).setPackage(ctx.getPackageName());
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) piFlags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent p = PendingIntent.getBroadcast(ctx, id, cb, piFlags);
            session = id;
            s.commit(p.getIntentSender());
        } catch (IOException | RuntimeException e) {
            s.abandon();
            throw e;
        } finally {
            s.close();
        }
        apk.delete();   // the session holds its own copy now
    }

    // ---------------------------------------------------------------- helpers

    @SuppressWarnings("deprecation")
    private long installedVersionCode() throws PackageManager.NameNotFoundException {
        return codeOf(ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0));
    }

    @SuppressWarnings("deprecation")
    private static long codeOf(PackageInfo pi) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? pi.getLongVersionCode() : pi.versionCode;
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x));
        return s.toString();
    }

    private static String message(Exception e) {
        if (e instanceof UpdateException) return e.getMessage();
        if (e instanceof java.net.UnknownHostException || e instanceof java.net.SocketTimeoutException) {
            return "Could not reach the update server. Check your connection.";
        }
        String m = e.getMessage();
        return "Update failed: " + (m != null ? m : e.getClass().getSimpleName());
    }

    /** A failure whose message is already fit to show the user. */
    static final class UpdateException extends Exception {
        UpdateException(String m) { super(m); }
    }
}
