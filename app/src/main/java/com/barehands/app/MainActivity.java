package com.barehands.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final int CAMERA_REQUEST = 42;
    private LocalServer server;
    private WebView webView;
    private boolean boardStarted;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        if (android.os.Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
        }
        server = new LocalServer(getAssets());
        server.start();
        webView = createWebView();
        setContentView(webView);
        if (android.os.Build.VERSION.SDK_INT < 23 ||
                checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startBoard();
        }
    }

    private WebView createWebView() {
        WebView view = new WebView(this);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setDatabaseEnabled(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        view.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                Toast.makeText(MainActivity.this, "Ultron could not load: " + description, Toast.LENGTH_LONG).show();
            }
            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                Toast.makeText(MainActivity.this, "Ultron recovered from a browser engine restart.", Toast.LENGTH_LONG).show();
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (!isFinishing()) {
                        webView = createWebView();
                        setContentView(webView);
                        boardStarted = false;
                        startBoard();
                    }
                }, 300);
                return true;
            }
        });
        view.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                    } else {
                        request.deny();
                    }
                });
            }
        });
        return view;
    }

    private void startBoard() {
        if (boardStarted || server == null || webView == null) return;
        boardStarted = true;
        new Thread(() -> {
            if (server.awaitReady(5000)) {
                runOnUiThread(() -> webView.loadUrl("http://127.0.0.1:8794/stage.html?mobile=1&delegate=cpu"));
            } else {
                runOnUiThread(() -> Toast.makeText(this, "Ultron could not start its local board.", Toast.LENGTH_LONG).show());
            }
        }, "ultron-load").start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == CAMERA_REQUEST) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                startBoard();
            } else {
                Toast.makeText(this, "Camera permission is required for hand tracking.", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (server != null) server.stop();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
