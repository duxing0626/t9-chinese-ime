package io.github.t9ime;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Checks GitHub Releases for a newer version, downloads the APK, verifies its SHA-256 and opens
 * the system installer. Runs only from the settings screen, never from the keyboard itself.
 */
final class Updater {
    static final String REPO = "duxing0626/t9-chinese-ime";
    private static final String LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";
    static final String RELEASES_PAGE = "https://github.com/" + REPO + "/releases/latest";

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ProgressDialog progress;

    private static final class Release {
        String tag;
        String title;
        String notes;
        String apkUrl;
        long apkSize;
        String sha256;
    }

    Updater(Activity activity) {
        this.activity = activity;
    }

    void check() {
        progress = ProgressDialog.show(activity, null, "正在檢查更新…", true, false);
        new Thread("update-check") {
            @Override
            public void run() {
                try {
                    final Release r = fetchLatest();
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            dismissProgress();
                            offer(r);
                        }
                    });
                } catch (final Exception e) {
                    fail("未能連接 GitHub：" + describe(e));
                }
            }
        }.start();
    }

    private void offer(final Release r) {
        String current = currentVersion();
        if (compareVersions(r.tag, current) <= 0) {
            message("已是最新版本", "目前版本 " + current + " 已是最新。");
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle("有新版本 " + r.tag)
                .setMessage((r.title.length() > 0 ? r.title + "\n\n" : "") + plain(r.notes)
                        + "\n\n目前版本：" + current + "\n下載大小：" + (r.apkSize / 1024) + " KB")
                .setPositiveButton("下載並安裝", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        download(r);
                    }
                })
                .setNegativeButton("稍後", null)
                .show();
    }

    private void download(final Release r) {
        progress = new ProgressDialog(activity);
        progress.setMessage("正在下載 " + r.tag + "…");
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setMax((int) Math.max(1, r.apkSize / 1024));
        progress.setProgressNumberFormat("%1d / %2d KB");
        progress.setCancelable(false);
        progress.show();
        new Thread("update-download") {
            @Override
            public void run() {
                try {
                    final File apk = fetchApk(r);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            dismissProgress();
                            install(apk);
                        }
                    });
                } catch (final Exception e) {
                    fail("下載失敗：" + describe(e));
                }
            }
        }.start();
    }

    private void install(File apk) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.fromFile(apk), "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(i);
        } catch (RuntimeException e) {
            message("未能開啟安裝程式", "檔案已下載到：\n" + apk.getPath() + "\n\n請用檔案管理員開啟安裝。");
        }
    }

    // ------------------------------------------------------------------ network

    private Release fetchLatest() throws Exception {
        JSONObject json = new JSONObject(new String(get(LATEST, 0), "UTF-8"));
        Release r = new Release();
        r.tag = json.getString("tag_name");
        r.title = json.optString("name", "");
        if (r.title.equals(r.tag)) r.title = "";
        r.notes = json.optString("body", "");
        JSONArray assets = json.getJSONArray("assets");
        for (int k = 0; k < assets.length(); k++) {
            JSONObject a = assets.getJSONObject(k);
            if (!a.getString("name").toLowerCase(Locale.US).endsWith(".apk")) continue;
            r.apkUrl = a.getString("browser_download_url");
            r.apkSize = a.optLong("size", 0);
            String digest = a.optString("digest", "");
            if (digest.startsWith("sha256:")) r.sha256 = digest.substring(7).toLowerCase(Locale.US);
            break;
        }
        if (r.sha256 == null) {
            // Older releases: the hash is written in the notes as "SHA-256: `...`".
            Matcher m = Pattern.compile("SHA-256:\\s*`?([0-9a-fA-F]{64})").matcher(r.notes);
            if (m.find()) r.sha256 = m.group(1).toLowerCase(Locale.US);
        }
        if (r.apkUrl == null) throw new IOException("這個版本沒有 APK 檔案");
        if (r.sha256 == null) throw new IOException("這個版本沒有 SHA-256 校驗碼，為安全起見不會下載");
        return r;
    }

    private File fetchApk(Release r) throws Exception {
        // App-private storage works without an SD card; before Android 7 the installer can read a
        // world-readable file there as long as the folders can be traversed.
        File dir = new File(activity.getFilesDir(), "update");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("未能建立下載資料夾");
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        File apk = new File(dir, "T9ChineseIME-" + r.tag.replaceAll("[^0-9A-Za-z.]", "") + ".apk");
        byte[] data = get(r.apkUrl, r.apkSize);
        String actual = hex(MessageDigest.getInstance("SHA-256").digest(data));
        if (!actual.equals(r.sha256)) throw new IOException("檔案校驗失敗，已放棄安裝");
        OutputStream out = new FileOutputStream(apk);
        try {
            out.write(data);
        } finally {
            out.close();
        }
        activity.getFilesDir().setExecutable(true, false);
        dir.setExecutable(true, false);
        apk.setReadable(true, false);
        return apk;
    }

    /** Downloads a URL into memory (the APK is a few MB); expected > 0 means a file with progress. */
    private byte[] get(String url, final long expected) throws Exception {
        HttpsURLConnection c = (HttpsURLConnection) new URL(url).openConnection();
        c.setSSLSocketFactory(socketFactory());
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "T9ChineseIME/" + currentVersion());
        if (expected == 0) c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        if (code != 200) throw new IOException("HTTP " + code);
        InputStream in = c.getInputStream();
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(expected > 0 ? (int) expected : 16384);
            byte[] chunk = new byte[16384];
            int n;
            long total = 0;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
                total += n;
                if (expected > 0) {
                    final int kb = (int) (total / 1024);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (progress != null) progress.setProgress(kb);
                        }
                    });
                }
                if (total > 64L * 1024 * 1024) throw new IOException("檔案太大");
            }
            return buf.toByteArray();
        } finally {
            in.close();
            c.disconnect();
        }
    }

    private SSLSocketFactory factory;

    /**
     * Trusts the system's certificate authorities plus a few bundled roots (ISRG Root X1/X2,
     * USERTrust) that GitHub's download servers use but Android 5 does not ship.
     */
    private synchronized SSLSocketFactory socketFactory() throws Exception {
        if (factory != null) return factory;
        TrustManagerFactory sys = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        sys.init((KeyStore) null);
        final X509TrustManager system = x509(sys.getTrustManagers());

        KeyStore roots = KeyStore.getInstance(KeyStore.getDefaultType());
        roots.load(null, null);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        String[] names = activity.getAssets().list("certs");
        for (String name : names) {
            InputStream in = activity.getAssets().open("certs/" + name);
            try {
                roots.setCertificateEntry(name, cf.generateCertificate(in));
            } finally {
                in.close();
            }
        }
        TrustManagerFactory extra = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        extra.init(roots);
        final X509TrustManager bundled = x509(extra.getTrustManagers());

        X509TrustManager both = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                system.checkClientTrusted(chain, authType);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                try {
                    system.checkServerTrusted(chain, authType);
                } catch (CertificateException e) {
                    bundled.checkServerTrusted(chain, authType);
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                X509Certificate[] a = system.getAcceptedIssuers();
                X509Certificate[] b = bundled.getAcceptedIssuers();
                X509Certificate[] all = new X509Certificate[a.length + b.length];
                System.arraycopy(a, 0, all, 0, a.length);
                System.arraycopy(b, 0, all, a.length, b.length);
                return all;
            }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[] {both}, null);
        factory = ctx.getSocketFactory();
        return factory;
    }

    private static X509TrustManager x509(TrustManager[] managers) {
        for (TrustManager m : managers) {
            if (m instanceof X509TrustManager) return (X509TrustManager) m;
        }
        throw new IllegalStateException("no X509TrustManager");
    }

    // ------------------------------------------------------------------ helpers

    private String currentVersion() {
        try {
            return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    /** Compares "v1.10" style versions numerically, part by part. */
    static int compareVersions(String a, String b) {
        String[] x = a.replaceAll("[^0-9.]", "").split("\\.");
        String[] y = b.replaceAll("[^0-9.]", "").split("\\.");
        for (int k = 0; k < Math.max(x.length, y.length); k++) {
            int p = k < x.length && x[k].length() > 0 ? Integer.parseInt(x[k]) : 0;
            int q = k < y.length && y[k].length() > 0 ? Integer.parseInt(y[k]) : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    /** Release notes without the Markdown markers and the hash line. */
    private static String plain(String markdown) {
        return markdown.replaceAll("(?m)^SHA-256:.*$", "")
                .replaceAll("(?m)^#+\\s*", "")
                .replace("**", "")
                .replace("`", "")
                .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
                .trim();
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.US, "%02x", b & 0xff));
        return sb.toString();
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        return m == null || m.length() == 0 ? e.getClass().getSimpleName() : m;
    }

    private void fail(final String text) {
        main.post(new Runnable() {
            @Override
            public void run() {
                dismissProgress();
                new AlertDialog.Builder(activity)
                        .setTitle("更新失敗")
                        .setMessage(text + "\n\n亦可用瀏覽器到以下網址下載：\n" + RELEASES_PAGE)
                        .setPositiveButton("確定", null)
                        .setNeutralButton("開啟網頁", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                try {
                                    activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE)));
                                } catch (RuntimeException ignored) {
                                    // no browser
                                }
                            }
                        })
                        .show();
            }
        });
    }

    private void message(String title, String text) {
        new AlertDialog.Builder(activity).setTitle(title).setMessage(text).setPositiveButton("確定", null).show();
    }

    private void dismissProgress() {
        if (progress != null && progress.isShowing() && !activity.isFinishing()) progress.dismiss();
        progress = null;
    }
}
