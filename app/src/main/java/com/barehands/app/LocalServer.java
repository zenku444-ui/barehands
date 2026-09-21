package com.barehands.app;

import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class LocalServer {
    private final AssetManager assets;
    private volatile boolean running;
    private ServerSocket socket;
    private Thread thread;
    private volatile String scene = "{\"cursors\":[],\"items\":[]}";
    private final List<String> commands = new ArrayList<>();

    LocalServer(AssetManager assets) { this.assets = assets; }

    void start() {
        thread = new Thread(() -> {
            try {
                socket = new ServerSocket(8794, 20);
                running = true;
                while (running) new Thread(() -> handle(accept()), "barehands-request").start();
            } catch (IOException ignored) { }
        }, "barehands-server");
        thread.start();
    }

    private Socket accept() {
        try { return socket.accept(); } catch (IOException e) { return null; }
    }

    private void handle(Socket client) {
        if (client == null) return;
        try (Socket c = client) {
            InputStream in = c.getInputStream();
            String request = readHeaders(in);
            if (request.isEmpty()) return;
            String[] first = request.split("\\r?\\n", 2)[0].split(" ");
            String method = first[0];
            String path = first.length > 1 ? first[1] : "/";
            int length = 0;
            for (String line : request.split("\\r?\\n")) {
                if (line.toLowerCase().startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
            }
            byte[] body = new byte[length];
            int read = 0;
            while (read < length) { int n = in.read(body, read, length - read); if (n < 0) break; read += n; }
            String bodyText = new String(body, StandardCharsets.UTF_8);
            Response response = route(method, path, bodyText);
            OutputStream out = c.getOutputStream();
            out.write(("HTTP/1.1 " + response.status + "\r\nContent-Type: " + response.type +
                    "\r\nContent-Length: " + response.body.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(response.body);
            out.flush();
        } catch (Exception ignored) { }
    }

    private static String readHeaders(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int previous = -1, current;
        while ((current = in.read()) != -1) {
            out.write(current);
            if (previous == '\r' && current == '\n') {
                byte[] b = out.toByteArray();
                int n = b.length;
                if (n >= 4 && b[n - 4] == '\r' && b[n - 3] == '\n' && b[n - 2] == '\r' && b[n - 1] == '\n') break;
            }
            previous = current;
        }
        return out.toString("UTF-8");
    }

    private synchronized Response route(String method, String rawPath, String body) {
        String path = rawPath;
        String query = "";
        int q = path.indexOf('?');
        if (q >= 0) { query = path.substring(q + 1); path = path.substring(0, q); }
        if (method.equals("POST") && path.equals("/state")) {
            scene = body.isEmpty() ? scene : body;
            String result = commands.isEmpty() ? "[]" : "[" + commands.remove(0) + "]";
            return json(result);
        }
        if (method.equals("GET") && path.equals("/state")) return json(scene);
        if (method.equals("POST") && path.equals("/cmd")) { commands.add(body); return json("{\"ok\":true}"); }
        if (method.equals("GET") && path.equals("/config")) return json("{\"name\":\"Assistant\",\"port\":8794,\"orbs\":[{\"title\":\"Notes\",\"path\":\"sample-notes\",\"kind\":\"notes\"},{\"title\":\"Props\",\"path\":\"media\",\"kind\":\"media\"}]}");
        if (method.equals("GET") && path.equals("/orb")) return json("{\"state\":\"idle\"}");
        if (method.equals("GET") && path.equals("/tree")) return json(tree("sample-notes", false));
        if (method.equals("GET") && path.equals("/props")) return json(tree("media", true));
        if (method.equals("GET") && path.equals("/note")) {
            String f = parameter(query, "f");
            if (f.startsWith("0/")) return assetText("sample-notes/" + f.substring(2), "");
        }
        if (method.equals("GET")) {
            String assetPath = path.equals("/") ? "stage.html" : path.substring(1);
            return asset(assetPath);
        }
        return new Response("404 Not Found", "text/plain", new byte[0]);
    }

    private String tree(String root, boolean props) {
        StringBuilder dirs = new StringBuilder("[]");
        StringBuilder leaves = new StringBuilder("[]");
        try { collect(root, "", props, dirs, leaves); } catch (IOException ignored) { }
        return "{\"name\":\"\",\"dirs\":[] ,\"" + (props ? "items" : "notes") + " :" + leaves + "}";
    }

    private void collect(String root, String relative, boolean props, StringBuilder dirs, StringBuilder leaves) throws IOException {
        String path = relative.isEmpty() ? root : root + "/" + relative;
        for (String name : assets.list(path)) {
            String rel = relative.isEmpty() ? name : relative + "/" + name;
            String full = root + "/" + rel;
            if (assets.list(full).length > 0) collect(root, rel, props, dirs, leaves);
            else if (props || name.endsWith(".md")) {
                if (leaves.length() > 2) leaves.append(',');
                String key = props ? "src" : "file";
                leaves.append("{\"title\":\"").append(escape(name)).append("\",\"").append(key).append("\":\"").append(escape((props ? "" : "0/") + rel)).append("\"}");
            }
        }
    }

    private Response asset(String path) {
        try (InputStream in = assets.open(path)) {
            return new Response("200 OK", mime(path), readAll(in));
        } catch (IOException e) { return new Response("404 Not Found", "text/plain", new byte[0]); }
    }

    private Response assetText(String path, String fallback) {
        Response r = asset(path);
        return r.body.length == 0 ? new Response("404 Not Found", "text/plain", fallback.getBytes(StandardCharsets.UTF_8)) : r;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int n;
        while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    private static String parameter(String query, String name) {
        for (String item : query.split("&")) { String[] p = item.split("=", 2); if (p.length == 2 && p[0].equals(name)) try { return URLDecoder.decode(p[1], "UTF-8"); } catch (Exception ignored) { } }
        return "";
    }

    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
    private static Response json(String value) { return new Response("200 OK", "application/json; charset=utf-8", value.getBytes(StandardCharsets.UTF_8)); }
    private static String mime(String path) { if (path.endsWith(".html")) return "text/html; charset=utf-8"; if (path.endsWith(".js")) return "text/javascript"; if (path.endsWith(".css")) return "text/css"; if (path.endsWith(".json")) return "application/json"; if (path.endsWith(".png")) return "image/png"; if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg"; return "application/octet-stream"; }

    void stop() { running = false; try { if (socket != null) socket.close(); } catch (IOException ignored) { } }
    private static final class Response { final String status, type; final byte[] body; Response(String s, String t, byte[] b) { status=s; type=t; body=b; } }
}
