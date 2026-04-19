package com.dawn.libbatchdownload;

import android.os.Bundle;
import android.os.Environment;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.dawn.download.BatchDownloadConfig;
import com.dawn.download.BatchDownloadListener;
import com.dawn.download.BatchDownloadManager;
import com.dawn.download.DownloadItem;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private BatchDownloadManager downloadManager;
    private TextView tvStatus;
    private TextView tvLog;
    private String saveDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tv_status);
        tvLog = findViewById(R.id.tv_log);
        Button btnStart = findViewById(R.id.btn_start);
        Button btnCancel = findViewById(R.id.btn_cancel);
        Button btnRetry = findViewById(R.id.btn_retry);

        saveDir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "frames")
                .getAbsolutePath();

        // 初始化管理器
        downloadManager = BatchDownloadManager.getInstance(this);
        downloadManager.setConfig(new BatchDownloadConfig.Builder()
                .maxConcurrent(3)
                .maxRetryCount(3)
                .retryDelayMs(2000)
                .validateImages(true)
                .cleanupOrphanFiles(true)
                .build());

        btnStart.setOnClickListener(v -> startDownload());
        btnCancel.setOnClickListener(v -> {
            downloadManager.cancel();
            tvStatus.setText("已取消");
            appendLog("--- 下载已取消 ---");
        });
        btnRetry.setOnClickListener(v -> {
            if (downloadManager.hasPendingRetry(saveDir)) {
                tvStatus.setText("重试失败项...");
                downloadManager.retryFailed(saveDir, listener);
            } else {
                appendLog("没有需要重试的项目");
            }
        });
    }

    private void startDownload() {
        // 示例下载列表（替换为真实 URL）
        List<DownloadItem> items = new ArrayList<>();
        items.add(new DownloadItem(
                "https://via.placeholder.com/200x200.png",
                "frame_01.png"));
        items.add(new DownloadItem(
                "https://via.placeholder.com/300x300.png",
                "frame_02.png"));
        items.add(new DownloadItem(
                "https://via.placeholder.com/400x400.png",
                "frame_03.png"));

        tvLog.setText("");
        tvStatus.setText("开始下载...");
        appendLog("保存目录: " + saveDir);
        downloadManager.submit(saveDir, items, listener);
    }

    private final BatchDownloadListener listener = new BatchDownloadListener() {
        @Override
        public void onTaskStart(int totalCount, int needDownloadCount, int skipCount) {
            tvStatus.setText("总计: " + totalCount + " 需下载: " + needDownloadCount + " 跳过: " + skipCount);
            appendLog("任务开始 | 总计=" + totalCount + " 需下载=" + needDownloadCount + " 跳过=" + skipCount);
        }

        @Override
        public void onFileStart(DownloadItem item) {
            appendLog("▶ 开始: " + item.getFileName());
        }

        @Override
        public void onFileProgress(DownloadItem item, long downloadedBytes, long totalBytes) {
            // 不频繁输出进度日志
        }

        @Override
        public void onFileComplete(DownloadItem item, String localPath) {
            appendLog("✓ 完成: " + item.getFileName());
        }

        @Override
        public void onFileFailed(DownloadItem item, String error, boolean willRetry) {
            appendLog("✗ 失败: " + item.getFileName() + " | " + error
                    + (willRetry ? " (将重试)" : " (放弃)"));
        }

        @Override
        public void onFileSkipped(DownloadItem item, String reason) {
            appendLog("→ 跳过: " + item.getFileName() + " | " + reason);
        }

        @Override
        public void onTaskProgress(int completed, int total) {
            tvStatus.setText("进度: " + completed + "/" + total);
        }

        @Override
        public void onTaskComplete(int successCount, int failCount, int skipCount) {
            tvStatus.setText("完成 | 成功: " + successCount + " 失败: " + failCount + " 跳过: " + skipCount);
            appendLog("--- 任务完成 | 成功=" + successCount + " 失败=" + failCount + " 跳过=" + skipCount + " ---");
        }

        @Override
        public void onTaskError(String error) {
            tvStatus.setText("错误: " + error);
            appendLog("!!! 错误: " + error);
        }
    };

    private void appendLog(String msg) {
        tvLog.append(msg + "\n");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        BatchDownloadManager.destroy();
    }
}
