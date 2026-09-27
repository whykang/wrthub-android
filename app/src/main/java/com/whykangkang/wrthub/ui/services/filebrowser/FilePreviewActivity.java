package com.whykangkang.wrthub.ui.services.filebrowser;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.FileBrowserApi;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 常见文件预览:图片和文本。
 *
 * 其余类型不硬撑 —— 直接说明不支持,让用户回去下载。
 * 内容有大小上限,不把几百兆的文件吞进内存。
 */
public class FilePreviewActivity extends AppCompatActivity {

    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_NAME = "name";

    /** 文本预览上限 256 KB;再大界面也滚不动,还占内存 */
    private static final int TEXT_LIMIT = 256 * 1024;
    /** 图片上限 12 MB */
    private static final int IMAGE_LIMIT = 12 * 1024 * 1024;
    /** 解码后的长边上限,超过就按比例降采样 */
    private static final int MAX_IMAGE_EDGE = 2048;

    private static final Set<String> IMAGE_EXT = new HashSet<>(Arrays.asList(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".heic"));

    private static final Set<String> TEXT_EXT = new HashSet<>(Arrays.asList(
            ".txt", ".log", ".conf", ".config", ".cfg", ".ini", ".json", ".xml", ".yaml",
            ".yml", ".toml", ".properties", ".md", ".sh", ".bash", ".py", ".js", ".ts",
            ".css", ".html", ".htm", ".c", ".h", ".cpp", ".java", ".kt", ".go", ".rs",
            ".sql", ".csv", ".env", ".list", ".lua", ".pl", ".rb", ".php"));

    private TextView statusLabel;
    private ImageView imageView;
    private ScrollView textScroll;
    private TextView textView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fb_preview);

        String host = getIntent().getStringExtra(EXTRA_HOST);
        String port = getIntent().getStringExtra(EXTRA_PORT);
        String path = getIntent().getStringExtra(EXTRA_PATH);
        String name = getIntent().getStringExtra(EXTRA_NAME);

        statusLabel = findViewById(R.id.preview_status);
        imageView = findViewById(R.id.preview_image);
        textScroll = findViewById(R.id.preview_text_scroll);
        textView = findViewById(R.id.preview_text);
        ((TextView) findViewById(R.id.preview_name)).setText(name);
        findViewById(R.id.btn_preview_close).setOnClickListener(v -> finish());

        String ext = extensionOf(name);
        boolean isImage = IMAGE_EXT.contains(ext);
        if (!isImage && !isPreviewableText(ext)) {
            statusLabel.setText(getString(R.string.fb_preview_unsupported, ext.isEmpty() ? name : ext));
            return;
        }

        statusLabel.setText(R.string.fb_preview_loading);
        FileBrowserApi api = FileBrowserApi.from(this, host, port);
        api.readBytes(path, isImage ? IMAGE_LIMIT : TEXT_LIMIT,
                new FileBrowserApi.Callback<byte[]>() {
                    @Override
                    public void onSuccess(byte[] bytes) {
                        if (isFinishing() || isDestroyed()) return;
                        if (isImage) {
                            showImage(bytes);
                        } else {
                            showText(bytes);
                        }
                    }

                    @Override
                    public void onFailure(String message) {
                        if (isFinishing() || isDestroyed()) return;
                        statusLabel.setText(getString(R.string.fb_action_failed, message));
                    }
                });
    }

    /** 无扩展名的小文件也按文本试一把 —— 路由器上一堆 conf、passwd 都没后缀 */
    private static boolean isPreviewableText(String ext) {
        return ext.isEmpty() || TEXT_EXT.contains(ext);
    }

    private void showImage(byte[] bytes) {
        // 先量尺寸再按需降采样,避免大图直接 OOM
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null) {
            statusLabel.setText(R.string.fb_preview_broken);
            return;
        }
        statusLabel.setVisibility(View.GONE);
        imageView.setVisibility(View.VISIBLE);
        imageView.setImageBitmap(bitmap);
    }

    /** 可单测:按长边算降采样倍数(2 的幂) */
    static int sampleSize(int width, int height) {
        int longest = Math.max(width, height);
        int sample = 1;
        while (longest / sample > MAX_IMAGE_EDGE) {
            sample *= 2;
        }
        return sample;
    }

    private void showText(byte[] bytes) {
        if (looksBinary(bytes)) {
            statusLabel.setText(R.string.fb_preview_binary);
            return;
        }
        statusLabel.setVisibility(View.GONE);
        textScroll.setVisibility(View.VISIBLE);
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (bytes.length >= TEXT_LIMIT) {
            text = text + "\n\n" + getString(R.string.fb_preview_truncated);
        }
        textView.setText(text);
    }

    /**
     * 可单测:前 512 字节里有 NUL 或控制字符占比过高就当二进制。
     * 没后缀的文件不先判一下就当文本显示,满屏方块。
     */
    static boolean looksBinary(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return false;
        int limit = Math.min(bytes.length, 512);
        int suspicious = 0;
        for (int i = 0; i < limit; i++) {
            int b = bytes[i] & 0xFF;
            if (b == 0) return true;
            if (b < 0x09 || (b > 0x0D && b < 0x20)) suspicious++;
        }
        return suspicious * 20 > limit;
    }

    static String extensionOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot).toLowerCase(Locale.ROOT) : "";
    }
}
