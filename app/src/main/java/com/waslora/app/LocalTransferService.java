package com.waslora.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.net.wifi.WifiManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class LocalTransferService extends Service {
    public static final String ACTION_START = "com.waslora.app.TRANSFER_START";
    public static final String ACTION_STOP = "com.waslora.app.TRANSFER_STOP";
    private static final String PREFS = "waslora_transfer";
    private static final String CHANNEL = "waslora_transfer";
    // Keep the limit well above ordinary phone videos.  The actual available
    // storage remains the final limit, and uploads are written to a .part file
    // until the request completes.
    private static final long MAX_UPLOAD_BYTES = 100L * 1024L * 1024L * 1024L;
    private static final int MAX_HEADER_COUNT = 64;
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final Object UPLOAD_RESERVATION_LOCK = new Object();
    private static final Set<String> RESERVED_UPLOAD_NAMES = ConcurrentHashMap.newKeySet();
    private volatile boolean stopping;
    private volatile ServerSocket serverSocket;
    private ExecutorService clients;
    private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();
    private PowerManager.WakeLock cpuWakeLock;
    private WifiManager.WifiLock wifiLock;

    public static final class Status {
        public final boolean running;
        public final String url;
        public final String message;
        Status(boolean running, String url, String message) { this.running = running; this.url = url; this.message = message; }
    }

    public static Status status(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean running = prefs.getBoolean("running", false);
        int port = prefs.getInt("port", -1);
        String token = prefs.getString("token", "");
        String ip = localIpv4(context);
        if (!running || port < 0 || token.isEmpty()) {
            String error = prefs.getString("error", "");
            if (!error.isEmpty()) return new Status(false, "", "تعذر تشغيل النقل المحلي: " + error);
            return new Status(false, "", "شغّل النقل المحلي، ووصل الهاتف واللابتوب بنفس شبكة Wi‑Fi.");
        }
        if (ip.isEmpty()) return new Status(false, "", "لم أجد عنوان شبكة محلية. وصّل الهاتف واللابتوب بنفس Wi‑Fi وأوقف VPN أو شبكة الضيوف.");
        return new Status(true, "http://" + ip + ":" + port + "/" + token + "/", "");
    }

    @Override public void onCreate() {
        super.onCreate();
        // A bounded handoff pool prevents an attacker on the LAN from filling
        // an unbounded executor queue with idle sockets.
        clients = new ThreadPoolExecutor(0, 8, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(), new ThreadPoolExecutor.AbortPolicy());
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopServer();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(3001, buildNotification());
        acquireTransferLocks();
        if (serverSocket == null || serverSocket.isClosed()) startServer();
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void startServer() {
        stopping = false;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("running", false).remove("error").apply();
        Thread thread = new Thread(() -> {
            ServerSocket listener = null;
            try {
                String bindAddress = localIpv4(this);
                if (bindAddress.isEmpty()) throw new Exception("لم أجد عنوان Wi-Fi أو Ethernet صالحًا.");
                // Bind only to the active local interface. This keeps the
                // short-lived HTTP transfer endpoint off unrelated interfaces.
                listener = new ServerSocket(0, 16, InetAddress.getByName(bindAddress));
                listener.setSoTimeout(1000);
                serverSocket = listener;
                String token = randomToken();
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("running", true).putInt("port", listener.getLocalPort()).putString("token", token).remove("error").apply();
                while (!stopping && !listener.isClosed()) {
                    try {
                        Socket socket = listener.accept();
                        socket.setSoTimeout(120000);
                        activeSockets.add(socket);
                        try {
                            clients.execute(() -> serveClient(socket, token));
                        } catch (RejectedExecutionException rejected) {
                            activeSockets.remove(socket);
                            try { socket.close(); } catch (Exception ignored) {}
                        }
                    } catch (java.net.SocketTimeoutException ignored) {}
                }
            } catch (Exception e) {
                if (!stopping) {
                    String message = e.getLocalizedMessage();
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("running", false)
                            .putString("error", message == null || message.isEmpty() ? "تحقق من اتصال Wi‑Fi وإذن الشبكة المحلية ثم أعد المحاولة." : message).apply();
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (!stopping) { releaseTransferLocks(); stopForeground(true); stopSelf(); }
                    });
                }
            } finally {
                if (listener != null) try { listener.close(); } catch (Exception ignored) {}
                serverSocket = null;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("running", false).remove("port").remove("token").apply();
            }
        }, "Waslora-lan-server");
        thread.start();
    }

    private void stopServer() {
        stopping = true;
        ServerSocket current = serverSocket;
        if (current != null) try { current.close(); } catch (Exception ignored) {}
        for (Socket socket : activeSockets) {
            try { socket.close(); } catch (Exception ignored) {}
        }
        activeSockets.clear();
        releaseTransferLocks();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("running", false).remove("port").remove("token").remove("error").apply();
    }

    private void acquireTransferLocks() {
        try {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            if (power != null && (cpuWakeLock == null || !cpuWakeLock.isHeld())) {
                cpuWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Waslora:LocalTransfer");
                cpuWakeLock.setReferenceCounted(false);
                cpuWakeLock.acquire();
            }
        } catch (Exception ignored) {}
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifi != null && (wifiLock == null || !wifiLock.isHeld())) {
                wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Waslora:LocalTransferWifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception ignored) {}
    }

    private void releaseTransferLocks() {
        if (cpuWakeLock != null && cpuWakeLock.isHeld()) try { cpuWakeLock.release(); } catch (Exception ignored) {}
        if (wifiLock != null && wifiLock.isHeld()) try { wifiLock.release(); } catch (Exception ignored) {}
    }

    private void serveClient(Socket socket, String token) {
        try (Socket client = socket;
             BufferedInputStream input = new BufferedInputStream(client.getInputStream());
             BufferedOutputStream output = new BufferedOutputStream(client.getOutputStream())) {
            String requestLine = readLine(input, 8192);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) { respond(output, 400, "Bad Request", "text/plain", "Bad Request".getBytes("UTF-8")); return; }
            String method = parts[0].toUpperCase(Locale.ROOT);
            String target = parts[1];
            if (target.length() > 8192) { respond(output, 414, "URI Too Long", "text/plain", "URI Too Long".getBytes("UTF-8")); return; }
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            String line;
            int headerCount = 0;
            int headerBytes = 0;
            while ((line = readLine(input, 8192)) != null && !line.isEmpty()) {
                headerCount++;
                headerBytes += line.length();
                if (headerCount > MAX_HEADER_COUNT || headerBytes > MAX_HEADER_BYTES) {
                    respond(output, 431, "Request Header Fields Too Large", "text/plain", "Request headers too large".getBytes("UTF-8"));
                    return;
                }
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String headerName = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                    String headerValue = line.substring(colon + 1).trim();
                    if (headers.containsKey(headerName) && ("content-length".equals(headerName) || "transfer-encoding".equals(headerName))) {
                        respond(output, 400, "Bad Request", "text/plain", "Duplicate upload length headers".getBytes("UTF-8"));
                        return;
                    }
                    headers.put(headerName, headerValue);
                }
            }
            if (line == null) { respond(output, 400, "Bad Request", "text/plain", "Bad Request".getBytes("UTF-8")); return; }
            int queryAt = target.indexOf('?');
            String path = queryAt >= 0 ? target.substring(0, queryAt) : target;
            String query = queryAt >= 0 ? target.substring(queryAt + 1) : "";
            String prefix = "/" + token + "/";
            if (!path.startsWith(prefix)) { respond(output, 403, "Forbidden", "text/plain", "Forbidden".getBytes("UTF-8")); return; }
            String route = path.substring(prefix.length());
            if ("GET".equals(method) && (route.isEmpty() || route.equals("index.html"))) { serveHtml(output); return; }
            if ("GET".equals(method) && route.equals("api/files")) { serveFileList(output); return; }
            if ("GET".equals(method) && route.startsWith("file/")) { serveDownload(route.substring(5), headers, output); return; }
            if ("POST".equals(method) && route.equals("upload")) {
                String origin = headers.get("origin");
                String host = headers.get("host");
                if (origin != null && !origin.isEmpty() && (host == null || !origin.equalsIgnoreCase("http://" + host))) {
                    respond(output, 403, "Forbidden", "text/plain", "Cross-origin upload rejected".getBytes("UTF-8"));
                    return;
                }
                receiveUpload(query, headers, input, output);
                return;
            }
            respond(output, 404, "Not Found", "text/plain", "Not Found".getBytes("UTF-8"));
        } catch (Exception ignored) {
        } finally {
            activeSockets.remove(socket);
        }
    }

    private void serveHtml(OutputStream output) throws Exception {
        byte[] bytes;
        try (InputStream in = getAssets().open("laptop.html"); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] part = new byte[8192]; int n;
            while ((n = in.read(part)) != -1) buffer.write(part, 0, n);
            bytes = buffer.toByteArray();
        }
        respond(output, 200, "OK", "text/html; charset=utf-8", bytes);
    }

    private void serveFileList(OutputStream output) throws Exception {
        JSONArray array = new JSONArray();
        File[] files = DirectDownloader.downloadsDirectory(this).listFiles();
        if (files != null) {
            java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File file : files) {
                if (!file.isFile() || file.getName().endsWith(".part")) continue;
                JSONObject object = new JSONObject();
                object.put("name", file.getName()); object.put("size", file.length());
                array.put(object);
            }
        }
        respond(output, 200, "OK", "application/json; charset=utf-8", array.toString().getBytes("UTF-8"));
    }

    private void serveDownload(String encodedName, java.util.Map<String, String> headers, OutputStream output) throws Exception {
        String name = safeName(URLDecoder.decode(encodedName, "UTF-8"));
        File file = safeChild(name);
        if (!file.isFile()) { respond(output, 404, "Not Found", "text/plain", "Not Found".getBytes("UTF-8")); return; }
        long start = 0, end = file.length() - 1;
        String range = headers.get("range");
        boolean partial = false;
        if (range != null && range.startsWith("bytes=")) {
            String[] bounds = range.substring(6).split("-", 2);
            try {
                start = Long.parseLong(bounds[0]);
                if (bounds.length > 1 && !bounds[1].isEmpty()) end = Long.parseLong(bounds[1]);
                if (start < 0 || start >= file.length() || end < start) { respond(output, 416, "Range Not Satisfiable", "text/plain", new byte[0]); return; }
                end = Math.min(end, file.length() - 1); partial = true;
            } catch (Exception ignored) { start = 0; end = file.length() - 1; }
        }
        long length = end - start + 1;
        String fallback = name.replaceAll("[^A-Za-z0-9._-]", "_");
        String disposition = "attachment; filename=\"" + (fallback.isEmpty() ? "download.bin" : fallback) + "\"; filename*=UTF-8''" + URLEncoder.encode(name, "UTF-8").replace("+", "%20");
        String status = partial ? "206 Partial Content" : "200 OK";
        StringBuilder response = new StringBuilder("HTTP/1.1 ").append(status).append("\r\n")
                .append("Content-Type: ").append(PublicFileStore.mimeType(name)).append("\r\n")
                .append("Content-Length: ").append(length).append("\r\n")
                .append("Content-Disposition: ").append(disposition).append("\r\n")
                .append("Accept-Ranges: bytes\r\n");
        response.append("X-Content-Type-Options: nosniff\r\n")
                .append("X-Frame-Options: DENY\r\n")
                .append("Referrer-Policy: no-referrer\r\n")
                .append("Content-Security-Policy: default-src 'none'; frame-ancestors 'none'\r\n");
        if (partial) response.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(file.length()).append("\r\n");
        response.append("Cache-Control: no-store\r\nConnection: close\r\n\r\n");
        output.write(response.toString().getBytes("UTF-8"));
        try (FileInputStream in = new FileInputStream(file)) {
            long skipped = 0;
            while (skipped < start) { long n = in.skip(start - skipped); if (n <= 0) break; skipped += n; }
            byte[] buffer = new byte[512 * 1024]; long remaining = length; int n;
            while (remaining > 0 && (n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining))) != -1) { output.write(buffer, 0, n); remaining -= n; }
        }
        output.flush();
    }

    private void receiveUpload(String query, java.util.Map<String, String> headers, InputStream input, OutputStream output) throws Exception {
        String encoded = queryValue(query, "name");
        if (encoded == null || encoded.trim().isEmpty()) { respond(output, 400, "Bad Request", "application/json", "{\"error\":\"filename required\"}".getBytes("UTF-8")); return; }
        String name = safeName(URLDecoder.decode(encoded, "UTF-8"));
        if (name.isEmpty()) { respond(output, 400, "Bad Request", "application/json", "{\"error\":\"invalid filename\"}".getBytes("UTF-8")); return; }
        File directory = DirectDownloader.downloadsDirectory(this);
        File target = reserveTarget(directory, name);
        File part;
        try { part = File.createTempFile(".waslora-upload-", ".part", directory); }
        catch (Exception error) { releaseTarget(target); throw error; }
        long received = 0;
        try (FileOutputStream file = new FileOutputStream(part)) {
            received = HttpUploadBody.copy(input, file, headers.get("content-length"), headers.get("transfer-encoding"), MAX_UPLOAD_BYTES);
            file.flush();
        } catch (HttpUploadBody.UploadException e) {
            part.delete();
            releaseTarget(target);
            String reason = e.status == 413 ? "Payload Too Large" : e.status == 411 ? "Length Required" : "Bad Request";
            respond(output, e.status, reason, "text/plain", new byte[0]);
            return;
        } catch (Exception e) { part.delete(); releaseTarget(target); throw e; }
        synchronized (UPLOAD_RESERVATION_LOCK) {
            // Rename only after the complete body is written. The filename is
            // reserved in memory, so in-progress uploads never appear in the
            // phone's file list and concurrent uploads cannot collide.
            if (target.exists() || !part.renameTo(target)) {
                part.delete();
                RESERVED_UPLOAD_NAMES.remove(target.getAbsolutePath());
                respond(output, 500, "Internal Server Error", "text/plain", new byte[0]);
                return;
            }
            RESERVED_UPLOAD_NAMES.remove(target.getAbsolutePath());
        }
        PublicFileStore.publishInBackground(this, target);
        JSONObject result = new JSONObject(); result.put("ok", true); result.put("name", target.getName()); result.put("size", received);
        respond(output, 201, "Created", "application/json; charset=utf-8", result.toString().getBytes("UTF-8"));
    }

    private File safeChild(String name) throws Exception {
        File directory = DirectDownloader.downloadsDirectory(this).getCanonicalFile();
        File child = new File(directory, name).getCanonicalFile();
        if (!child.getPath().startsWith(directory.getPath() + File.separator)) throw new SecurityException("invalid path");
        return child;
    }

    private static String queryValue(String query, String name) {
        for (String part : query.split("&")) {
            int at = part.indexOf('=');
            if (at > 0 && part.substring(0, at).equals(name)) return part.substring(at + 1);
        }
        return null;
    }

    private static String safeName(String value) {
        if (value == null) return "";
        String name = value.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", "_").trim();
        while (name.startsWith(".")) name = name.substring(1);
        if (name.length() > 120) name = name.substring(name.length() - 120);
        return name;
    }

    private static File reserveTarget(File directory, String name) throws Exception {
        synchronized (UPLOAD_RESERVATION_LOCK) {
            File file = new File(directory, name);
            if (!file.exists() && RESERVED_UPLOAD_NAMES.add(file.getAbsolutePath())) return file;
            int dot = name.lastIndexOf('.'); String stem = dot > 0 ? name.substring(0, dot) : name; String ext = dot > 0 ? name.substring(dot) : "";
            int i = 1;
            do { file = new File(directory, stem + " (" + i++ + ")" + ext); }
            while (file.exists() || !RESERVED_UPLOAD_NAMES.add(file.getAbsolutePath()));
            return file;
        }
    }

    private static void releaseTarget(File target) {
        if (target != null) RESERVED_UPLOAD_NAMES.remove(target.getAbsolutePath());
    }

    private static void respond(OutputStream output, int code, String status, String type, byte[] body) throws Exception {
        String headers = "HTTP/1.1 " + code + " " + status + "\r\n" +
                "Content-Type: " + type + "\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "X-Content-Type-Options: nosniff\r\n" +
                "X-Frame-Options: DENY\r\n" +
                "Referrer-Policy: no-referrer\r\n" +
                "Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n";
        output.write(headers.getBytes("UTF-8")); output.write(body); output.flush();
    }

    private static String readLine(InputStream input, int max) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int previous = -1;
        while (bytes.size() < max) {
            int current = input.read();
            if (current < 0) return bytes.size() == 0 ? null : bytes.toString("UTF-8");
            if (previous == '\r' && current == '\n') {
                byte[] result = bytes.toByteArray();
                int size = result.length;
                if (size > 0 && result[size - 1] == '\r') bytes.reset();
                return new String(result, 0, size > 0 && result[size - 1] == '\r' ? size - 1 : size, "UTF-8");
            }
            bytes.write(current); previous = current;
        }
        throw new Exception("header line too long");
    }

    private static String randomToken() {
        byte[] bytes = new byte[18]; new SecureRandom().nextBytes(bytes);
        StringBuilder token = new StringBuilder();
        for (byte b : bytes) token.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return token.toString();
    }

    private static String localIpv4(Context context) {
        try {
            ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivity != null) {
                Network active = connectivity.getActiveNetwork();
                NetworkCapabilities capabilities = active == null ? null : connectivity.getNetworkCapabilities(active);
                boolean localTransport = capabilities != null && (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
                if (localTransport) {
                    LinkProperties properties = connectivity.getLinkProperties(active);
                    if (properties != null) {
                        for (LinkAddress link : properties.getLinkAddresses()) {
                            InetAddress address = link.getAddress();
                            if (address instanceof Inet4Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
                                return address.getHostAddress();
                            }
                        }
                    }
                }
            }

            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            java.util.List<Ipv4Candidate> candidates = new java.util.ArrayList<>();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                String interfaceName = network.getName().toLowerCase(Locale.ROOT);
                if (interfaceName.startsWith("rmnet") || interfaceName.startsWith("ccmni") || interfaceName.startsWith("tun") || interfaceName.startsWith("ppp")) continue;
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
                        candidates.add(new Ipv4Candidate(interfaceName, (Inet4Address) address));
                    }
                }
            }
            Collections.sort(candidates, (a, b) -> Integer.compare(a.score(), b.score()));
            return candidates.isEmpty() ? "" : candidates.get(0).address.getHostAddress();
        } catch (Exception e) { return ""; }
    }

    private static final class Ipv4Candidate {
        final String interfaceName;
        final Inet4Address address;
        Ipv4Candidate(String interfaceName, Inet4Address address) { this.interfaceName = interfaceName; this.address = address; }
        int score() {
            String iface = interfaceName.toLowerCase(Locale.ROOT);
            String ip = address.getHostAddress();
            int score = (iface.startsWith("wlan") || iface.startsWith("wifi") || iface.startsWith("ap")) ? 0 : 3;
            if (ip.startsWith("192.168.")) score -= 1;
            else if (ip.startsWith("10.") || ip.matches("172\\.(1[6-9]|2[0-9]|3[01])\\..*")) score += 1;
            return score;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Waslora file transfer", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Local browser transfer session");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("Waslora")
                .setContentText("صفحة النقل المحلي تعمل")
                .setOngoing(true)
                .build();
    }

    @Override public void onDestroy() {
        stopServer();
        releaseTransferLocks();
        if (clients != null) clients.shutdownNow();
        super.onDestroy();
    }
}
