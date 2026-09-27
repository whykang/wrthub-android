package com.whykangkang.wrthub.model;

import java.util.Locale;

/** FTP 目录条目,对应 iOS FTPFileBrowserViewController.FileItem */
public class FileItem {

    public final String name;
    public final String path;
    public final boolean isDirectory;
    public final long size;

    public FileItem(String name, String path, boolean isDirectory, long size) {
        this.name = name;
        this.path = path;
        this.isDirectory = isDirectory;
        this.size = size;
    }

    /** 目录不显示大小,文件按 B/KB/MB/GB */
    public String sizeString() {
        if (isDirectory) return "";
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Locale.US, "%.1f KB", size / 1024.0);
        if (size < 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", size / 1048576.0);
        }
        return String.format(Locale.US, "%.2f GB", size / 1073741824.0);
    }

    /** 扩展名大写,无扩展名返回 null */
    public String extension() {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return null;
        return name.substring(dot + 1).toUpperCase(Locale.ROOT);
    }
}
