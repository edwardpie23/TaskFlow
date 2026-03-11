package com.taskflow.app;

import android.Manifest;
import android.annotation.SuppressLint;
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

    private static final int REQUEST_AUDIO_PERMISSION = 1;

    private WebView webView;
    private WebViewAssetLoader assetLoader;
    private PermissionRequest pendingPermissionRequest;

    // Static inner class — avoids implicit MainActivity reference issues on background thread
    static class ApiBridge {

        @JavascriptInterface
        public String groqPost(String apiKey, String body) {
            // Trim key to remove any invisible chars, newlines, or spaces
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

                // Set headers explicitly — order matters for some servers
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("Authorization", "Bearer " + cleanKey);
                conn.setRequestProperty("Accept", "application/json");

                // Write body as UTF-8
                byte[] input = body.getBytes(StandardCharsets.UTF_8);
                conn.setRequestProperty("Content-Length", String.valueOf(input.length));
                OutputStream os = conn.getOutputStream();
                os.write(input);
                os.flush();
                os.close();

                int status = conn.getResponseCode();

                // Read response or error stream
                java.io.InputStream stream;
                try {
                    stream = conn.getInputStream();
                } catch (Exception e) {
                    stream = conn.getErrorStream();
                }

                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                String responseBody = sb.toString();

                // Wrap in status envelope — body is raw JSON from Groq
                return "{\"status\":" + status + ",\"body\":" + responseBody + "}";

            } catch (Exception e) {
                String msg = e.getMessage() != null
                    ? e.getMessage().replace("\\", "\\\\").replace("\"", "'")
                    : "unknown error";
                return "{\"status\":0,\"body\":{\"error\":{\"message\":\"Java exception: " + msg + "\"}}}";
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        // Debug method — call from JS to verify bridge is working
        @JavascriptInterface
        public String ping() {
            return "pong";
        }
    }

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

        webView.addJavascriptInterface(new ApiBridge(), "Android");

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
