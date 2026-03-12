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
import android.location.Location;
import android.location.LocationManager;
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
    private static final int REQUEST_LOCATION_PERMISSION     = 2;
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

        @JavascriptInterface
        public String ping() { return "pong"; }
    }

    // ── Location bridge ────────────────────────────────────────────────────────
    static class LocationBridge {
        private final Context ctx;

        LocationBridge(Context context) { this.ctx = context.getApplicationContext(); }

        /** Returns JSON {lat, lng} or {error: "reason"} */
        @JavascriptInterface
        public String getLocation() {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                return "{\"error\":\"permission_denied\"}";
            }
            try {
                LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
                Location loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (loc == null) loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (loc == null) loc = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
                if (loc == null) return "{\"error\":\"no_location\"}";
                return "{\"lat\":" + loc.getLatitude() + ",\"lng\":" + loc.getLongitude() + "}";
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage().replace("\"","'") : "error";
                return "{\"error\":\"" + msg + "\"}";
            }
        }

        /** Geocodes an address string to {lat, lng} or {error: "reason"} */
        @JavascriptInterface
        public String geocodeAddress(String address) {
            if (address == null || address.trim().isEmpty()) return "{\"error\":\"empty\"}";
            if (!android.location.Geocoder.isPresent()) return "{\"error\":\"geocoder_unavailable\"}";
            try {
                android.location.Geocoder geocoder =
                    new android.location.Geocoder(ctx, java.util.Locale.getDefault());
                @SuppressWarnings("deprecation")
                java.util.List<android.location.Address> list =
                    geocoder.getFromLocationName(address.trim(), 1);
                if (list == null || list.isEmpty()) return "{\"error\":\"not_found\"}";
                android.location.Address addr = list.get(0);
                return "{\"lat\":" + addr.getLatitude() + ",\"lng\":" + addr.getLongitude() + "}";
            } catch (java.io.IOException e) {
                String msg = e.getMessage() != null ? e.getMessage().replace("\"","'") : "io_error";
                return "{\"error\":\"" + msg + "\"}";
            } catch (Exception e) {
                return "{\"error\":\"unknown\"}";
            }
        }
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
        // Location
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                },
                REQUEST_LOCATION_PERMISSION);
        }
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
        webView.addJavascriptInterface(new LocationBridge(this),     "AndroidLocation");
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
        // Location + notification grants are handled automatically by the bridge methods
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
