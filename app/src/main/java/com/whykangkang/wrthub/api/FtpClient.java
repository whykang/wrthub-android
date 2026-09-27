package com.whykangkang.wrthub.api;

import com.whykangkang.wrthub.model.FileItem;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 极简 FTP 客户端(原生 Socket 实现协议),对应 iOS Services/FTPClient.swift。
 *
 * iOS 用 URLSession 的 ftp:// 支持,Android 没有内建 FTP,因此按 RFC 959
 * 自己实现控制连接:USER/PASS → TYPE I → PASV → LIST,数据连接读目录列表。
 * 解析沿用 iOS 的 Unix LIST 格式解析(权限位首字符 d 判目录,第 5 列为大小)。
 */
public class FtpClient {

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public FtpClient(String host, int port, String username, String password) {
        this.host = host;
        this.port = port <= 0 ? 21 : port;
        this.username = username;
        this.password = password;
    }

    public interface Callback {
        void onSuccess(List<FileItem> files);

        void onFailure(String message);
    }

    /** 列目录。回调在调用方传入的 Handler 之外的后台线程,调用方负责切主线程。 */
    public void listDirectory(String path, Callback cb) {
        executor.execute(() -> {
            try {
                List<FileItem> items = listSync(path);
                cb.onSuccess(items);
            } catch (IOException e) {
                cb.onFailure(e.getMessage() == null ? "FTP 连接失败" : e.getMessage());
            }
        });
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private List<FileItem> listSync(String path) throws IOException {
        String cleanPath = normalize(path);
        try (Socket control = new Socket()) {
            control.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            control.setSoTimeout(READ_TIMEOUT_MS);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(control.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = control.getOutputStream();

            expect(readReply(in), 220, "FTP 服务器未就绪");
            send(out, "USER " + username);
            String userReply = readReply(in);
            if (code(userReply) == 331) {
                send(out, "PASS " + password);
                String passReply = readReply(in);
                if (code(passReply) != 230) {
                    throw new IOException("FTP 认证失败");
                }
            } else if (code(userReply) != 230) {
                throw new IOException("FTP 认证失败");
            }

            send(out, "TYPE I");
            readReply(in);

            send(out, "PASV");
            String pasv = readReply(in);
            int[] dataAddr = parsePasv(pasv);
            if (dataAddr == null) {
                throw new IOException("FTP 被动模式协商失败");
            }

            byte[] listing;
            try (Socket data = new Socket()) {
                data.connect(new InetSocketAddress(host, dataAddr[0]), CONNECT_TIMEOUT_MS);
                data.setSoTimeout(READ_TIMEOUT_MS);
                send(out, "LIST " + cleanPath);
                String listReply = readReply(in);
                int c = code(listReply);
                if (c != 150 && c != 125) {
                    throw new IOException("无法访问 FTP 目录 (" + listReply.trim() + ")");
                }
                listing = readAll(data.getInputStream());
            }
            readReply(in);   // 226 Transfer complete
            send(out, "QUIT");

            return parseListing(new String(listing, StandardCharsets.UTF_8), cleanPath);
        }
    }

    // =====================================================================
    // 协议细节
    // =====================================================================

    private static void send(OutputStream out, String command) throws IOException {
        out.write((command + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** 读一条应答,处理 "250-" 形式的多行应答 */
    private static String readReply(BufferedReader in) throws IOException {
        String line = in.readLine();
        if (line == null) throw new IOException("FTP 连接已关闭");
        if (line.length() >= 4 && line.charAt(3) == '-') {
            String prefix = line.substring(0, 3);
            String next;
            while ((next = in.readLine()) != null) {
                if (next.length() >= 4 && next.startsWith(prefix) && next.charAt(3) == ' ') {
                    return next;
                }
            }
            throw new IOException("FTP 应答不完整");
        }
        return line;
    }

    private static int code(String reply) {
        if (reply == null || reply.length() < 3) return -1;
        try {
            return Integer.parseInt(reply.substring(0, 3));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void expect(String reply, int expected, String message) throws IOException {
        if (code(reply) != expected) {
            throw new IOException(message);
        }
    }

    /** 227 Entering Passive Mode (192,168,1,1,200,21) → 端口 = 200*256+21 */
    static int[] parsePasv(String reply) {
        if (reply == null) return null;
        int open = reply.indexOf('(');
        int close = reply.indexOf(')', open + 1);
        if (open < 0 || close < 0) return null;
        String[] parts = reply.substring(open + 1, close).split(",");
        if (parts.length < 6) return null;
        try {
            int p1 = Integer.parseInt(parts[4].trim());
            int p2 = Integer.parseInt(parts[5].trim());
            return new int[]{p1 * 256 + p2};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, n);
        }
        return buffer.toByteArray();
    }

    static String normalize(String path) {
        String p = path == null ? "/" : path;
        while (p.startsWith("//")) {
            p = p.substring(1);
        }
        if (p.isEmpty()) return "/";
        if (!p.startsWith("/")) p = "/" + p;
        return p;
    }

    /**
     * 解析 Unix 风格的 LIST 输出(与 iOS parseFTPListing 逐行对照):
     *   drwxr-xr-x    2 user  group   4096 Jan 01 12:00 dirname
     *   -rw-r--r--    1 user  group  12345 Jan 01 12:00 file.txt
     * 名字可能含空格,取第 9 列在原始行中首次出现的位置往后全部。
     */
    static List<FileItem> parseListing(String listing, String basePath) {
        List<FileItem> files = new ArrayList<>();
        if (listing == null) return files;
        for (String line : listing.split("\r?\n")) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 9) continue;
            boolean isDirectory = parts[0].startsWith("d");
            long size;
            try {
                size = Long.parseLong(parts[4]);
            } catch (NumberFormatException e) {
                size = 0;
            }
            int nameIdx = line.indexOf(parts[8]);
            if (nameIdx < 0) continue;
            String name = line.substring(nameIdx).trim();
            if (name.equals(".") || name.equals("..")) continue;
            String fullPath = (basePath + "/" + name).replace("//", "/");
            files.add(new FileItem(name, fullPath, isDirectory, size));
        }
        // 目录在前,再按名称不区分大小写排序(与 iOS 浏览器排序一致)
        Collections.sort(files, (a, b) -> {
            if (a.isDirectory != b.isDirectory) return a.isDirectory ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        });
        return files;
    }
}
