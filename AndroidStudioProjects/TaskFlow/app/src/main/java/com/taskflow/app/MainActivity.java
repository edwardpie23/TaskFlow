package com.taskflow.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.taskflow.app.BuildConfig;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebViewAssetLoader;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_AUDIO_PERMISSION        = 1;
    private static final int REQUEST_NOTIFICATION_PERMISSION = 3;
    private static final String NOTIF_CHANNEL_ID             = "taskflow_channel";

    private WebView webView;
    private WebViewAssetLoader assetLoader;
    private PermissionRequest pendingPermissionRequest;

    // ── Groq API bridge (static — no Activity reference) ──────────────────────
    static class ApiBridge {

        @JavascriptInterface
        public String groqPost(String apiKey, String body) {
            String cleanKey = apiKey == null ? "" : apiKey.trim();
            HttpURLConnection conn = null;
            try {
                URL url = new URL("https://api.groq.com/openai/v1/chat/completions");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setDoInput(true);
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(40000);
                conn.setUseCaches(false);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("Authorization", "Bearer " + cleanKey);
                conn.setRequestProperty("Accept", "application/json");

                byte[] input = body.getBytes(StandardCharsets.UTF_8);
                conn.setRequestProperty("Content-Length", String.valueOf(input.length));
                OutputStream os = conn.getOutputStream();
                os.write(input); os.flush(); os.close();

                int status = conn.getResponseCode();
                java.io.InputStream stream;
                try { stream = conn.getInputStream(); }
                catch (Exception e) { stream = conn.getErrorStream(); }

                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                return "{\"status\":" + status + ",\"body\":" + sb.toString() + "}";

            } catch (Exception e) {
                String msg = e.getMessage() != null
                    ? e.getMessage().replace("\\", "\\\\").replace("\"", "'")
                    : "unknown error";
                return "{\"status\":0,\"body\":{\"error\":{\"message\":\"Java exception: " + msg + "\"}}}";
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        /**
         * Send base64-encoded audio to Groq Whisper for transcription.
         * audioBase64 — raw audio bytes encoded as Base64 (no data-URI prefix).
         * mimeType    — e.g. "audio/webm" or "audio/ogg".
         * Returns JSON {"status":200,"text":"..."} or {"status":0,"error":"..."}.
         */
        @JavascriptInterface
        public String groqWhisper(String apiKey, String audioBase64, String mimeType) {
            String cleanKey = apiKey == null ? "" : apiKey.trim();
            HttpURLConnection conn = null;
            try {
                byte[] audioBytes = android.util.Base64.decode(audioBase64, android.util.Base64.DEFAULT);
                String boundary = "----TFBoundary" + System.currentTimeMillis();

                URL url = new URL("https://api.groq.com/openai/v1/audio/transcriptions");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setDoInput(true);
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(40000);
                conn.setUseCaches(false);
                conn.setRequestProperty("Authorization", "Bearer " + cleanKey);
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

                OutputStream os = conn.getOutputStream();
                String ext = mimeType.contains("ogg") ? ".ogg" : mimeType.contains("mp4") ? ".mp4" : ".webm";

                // -- model field
                String modelPart = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"model\"\r\n\r\n"
                    + "whisper-large-v3-turbo\r\n";
                os.write(modelPart.getBytes(StandardCharsets.UTF_8));

                // -- file field
                String filePart = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"audio" + ext + "\"\r\n"
                    + "Content-Type: " + mimeType + "\r\n\r\n";
                os.write(filePart.getBytes(StandardCharsets.UTF_8));
                os.write(audioBytes);
                os.write("\r\n".getBytes(StandardCharsets.UTF_8));

                // -- close
                String closing = "--" + boundary + "--\r\n";
                os.write(closing.getBytes(StandardCharsets.UTF_8));
                os.flush(); os.close();

                int status = conn.getResponseCode();
                java.io.InputStream stream;
                try { stream = conn.getInputStream(); }
                catch (Exception e) { stream = conn.getErrorStream(); }

                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                return "{\"status\":" + status + ",\"body\":" + sb.toString() + "}";

            } catch (Exception e) {
                String msg = e.getMessage() != null
                    ? e.getMessage().replace("\\", "\\\\").replace("\"", "'")
                    : "unknown error";
                return "{\"status\":0,\"body\":{\"error\":{\"message\":\"Whisper exception: " + msg + "\"}}}";
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        @JavascriptInterface
        public String ping() { return "pong"; }
    }

    // ── Notification bridge ────────────────────────────────────────────────────
    static class NotificationBridge {
        private final Context ctx;

        NotificationBridge(Context context) { this.ctx = context.getApplicationContext(); }

        /**
         * Schedule a notification. triggerTimeMs is epoch milliseconds.
         * Called from JS when a task with dueDate+dueTime+reminderMinutes is saved.
         */
        @JavascriptInterface
        public void scheduleNotification(String taskId, String title, String body, long triggerTimeMs) {
            if (triggerTimeMs <= System.currentTimeMillis()) return;

            Intent intent = new Intent(ctx, NotificationReceiver.class);
            intent.putExtra("title", title);
            intent.putExtra("body", body);
            intent.putExtra("notifId", taskId.hashCode());

            int reqCode = taskId.hashCode();
            PendingIntent pi = PendingIntent.getBroadcast(ctx, reqCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // API 31+
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTimeMs, pi);
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTimeMs, pi);
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // API 23+
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTimeMs, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerTimeMs, pi);
            }
        }

        /** Cancel a previously scheduled notification for a task. */
        @JavascriptInterface
        public void cancelNotification(String taskId) {
            Intent intent = new Intent(ctx, NotificationReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, taskId.hashCode(), intent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (pi == null) return;
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(pi);
            pi.cancel();
        }
    }

    // ── Activity lifecycle ─────────────────────────────────────────────────────
    @SuppressLint({"SetJavaScriptEnabled", "ObsoleteSdkInt"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().setStatusBarColor(Color.parseColor("#f5f5f5"));
            getWindow().setNavigationBarColor(Color.parseColor("#f5f5f5"));
            getWindow().getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            );
        }

        // ── Create notification channel (required on API 26+) ──────────────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                NOTIF_CHANNEL_ID, "TaskFlow Reminders", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Task due-time reminders");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .createNotificationChannel(channel);
        }

        // ── Request runtime permissions upfront ───────────────────────────
        // Notifications (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATION_PERMISSION);
            }
        }

        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webview);

        assetLoader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .build();

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/121.0.0.0 Mobile Safari/537.36"
        );

        // Register all bridges
        webView.addJavascriptInterface(new ApiBridge(),                    "Android");
        webView.addJavascriptInterface(new NotificationBridge(this), "AndroidNotif");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                WebResourceResponse response = assetLoader.shouldInterceptRequest(request.getUrl());
                return response != null ? response : super.shouldInterceptRequest(view, request);
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                for (String resource : request.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                        if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.RECORD_AUDIO)
                                == PackageManager.PERMISSION_GRANTED) {
                            request.grant(request.getResources());
                        } else {
                            pendingPermissionRequest = request;
                            ActivityCompat.requestPermissions(MainActivity.this,
                                new String[]{Manifest.permission.RECORD_AUDIO},
                                REQUEST_AUDIO_PERMISSION);
                        }
                        return;
                    }
                }
                request.deny();
            }
        });

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_AUDIO_PERMISSION && pendingPermissionRequest != null) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pendingPermissionRequest.grant(pendingPermissionRequest.getResources());
            } else {
                pendingPermissionRequest.deny();
            }
            pendingPermissionRequest = null;
        }
        // Notification grants are handled automatically by the bridge methods
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onResume()  { super.onResume();  if (webView!=null) webView.onResume(); }
    @Override protected void onPause()   { super.onPause();   if (webView!=null) webView.onPause(); }
    @Override protected void onDestroy() {
        if (webView != null) { webView.stopLoading(); webView.destroy(); webView = null; }
        super.onDestroy();
    }
}
