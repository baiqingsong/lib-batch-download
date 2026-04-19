package com.dawn.download.internal;

import android.os.Handler;
import android.util.Log;

import com.dawn.download.BatchDownloadConfig;
import com.dawn.download.BatchDownloadListener;
import com.dawn.download.DownloadItem;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 批量下载执行引擎
 * <p>
 * 内部实现：
 * 1. 使用 OkHttp 同步下载（在线程池中执行）
 * 2. 下载到 .downloading 临时文件，完成后重命名
 * 3. 每个文件独立重试（指数退避）
 * 4. 支持取消（volatile 标志 + OkHttp dispatcher cancel）
 */
public class DownloadEngine {

    private static final String TAG = "DownloadEngine";
    private static final String TMP_SUFFIX = ".downloading";
    private static final int MAX_BACKOFF_SHIFT = 4;

    private final BatchDownloadConfig config;
    private final Handler mainHandler;
    private final OkHttpClient client;
    private volatile boolean cancelled = false;
    private ExecutorService downloadPool;

    public DownloadEngine(BatchDownloadConfig config, Handler mainHandler) {
        this.config = config;
        this.mainHandler = mainHandler;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(config.getConnectTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(config.getReadTimeoutMs(), TimeUnit.MILLISECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
    }

    /**
     * 取消所有正在进行的下载
     */
    public void cancel() {
        cancelled = true;
        client.dispatcher().cancelAll();
        if (downloadPool != null) {
            downloadPool.shutdownNow();
        }
    }

    /**
     * 是否已取消
     */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * 执行批量下载（阻塞，在调用线程等待全部完成）
     *
     * @param saveDir  保存目录
     * @param items    需要下载的文件列表
     * @param listener 回调
     * @return 下载结果
     */
    public Result execute(String saveDir, List<DownloadItem> items, BatchDownloadListener listener) {
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicInteger completedCount = new AtomicInteger(0);
        List<DownloadItem> failedItems = Collections.synchronizedList(new ArrayList<>());

        int concurrency = Math.min(config.getMaxConcurrent(), items.size());
        downloadPool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch latch = new CountDownLatch(items.size());

        for (DownloadItem item : items) {
            downloadPool.execute(() -> {
                try {
                    if (cancelled) return;

                    notifyFileStart(listener, item);
                    boolean success = downloadWithRetry(saveDir, item, listener);

                    if (success) {
                        successCount.incrementAndGet();
                        String path = new File(saveDir, item.getFileName()).getAbsolutePath();
                        notifyFileComplete(listener, item, path);
                    } else {
                        failCount.incrementAndGet();
                        failedItems.add(item);
                    }

                    int completed = completedCount.incrementAndGet();
                    notifyTaskProgress(listener, completed, items.size());
                } finally {
                    latch.countDown();
                }
            });
        }

        // 等待所有任务完成
        try {
            latch.await();
        } catch (InterruptedException e) {
            Log.e(TAG, "下载等待被中断", e);
            Thread.currentThread().interrupt();
        }

        downloadPool.shutdown();
        return new Result(successCount.get(), failCount.get(), failedItems);
    }

    /**
     * 下载单个文件，支持失败重试
     */
    private boolean downloadWithRetry(String saveDir, DownloadItem item, BatchDownloadListener listener) {
        String lastError = "";
        for (int attempt = 0; attempt <= config.getMaxRetryCount(); attempt++) {
            if (cancelled) return false;

            try {
                downloadFile(saveDir, item, listener);

                // 下载完成，校验文件
                File file = new File(saveDir, item.getFileName());
                if (FileValidator.isFileValid(file, item, config.isValidateImages())) {
                    return true;
                } else {
                    file.delete();
                    throw new IOException("文件校验失败: " + item.getFileName());
                }
            } catch (Exception e) {
                lastError = e.getMessage();
                boolean willRetry = attempt < config.getMaxRetryCount() && !cancelled;
                notifyFileFailed(listener, item, lastError, willRetry);

                if (willRetry) {
                    long delay = config.getRetryDelayMs() * (1L << Math.min(attempt, MAX_BACKOFF_SHIFT));
                    Log.w(TAG, "第" + (attempt + 1) + "/" + config.getMaxRetryCount() + "次重试: "
                            + item.getFileName() + " 延迟: " + delay + "ms");
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }

        Log.e(TAG, "下载最终失败: " + item.getFileName() + " 原因: " + lastError);
        return false;
    }

    /**
     * 执行单个文件下载
     * <p>
     * 流程：
     * 1. 创建 OkHttp 请求
     * 2. 下载到 .downloading 临时文件
     * 3. 下载完成后重命名为最终文件名
     */
    private void downloadFile(String saveDir, DownloadItem item, BatchDownloadListener listener) throws IOException {
        File finalFile = new File(saveDir, item.getFileName());
        File tmpFile = new File(saveDir, item.getFileName() + TMP_SUFFIX);

        // 确保父目录存在（支持子目录 fileName 如 "subdir/image.jpg"）
        File parentDir = finalFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        // 清理旧的临时文件
        if (tmpFile.exists()) {
            tmpFile.delete();
        }

        Request request = new Request.Builder()
                .url(item.getUrl())
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ": " + response.message());
            }

            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("响应体为空");
            }

            long totalBytes = body.contentLength();

            try (InputStream in = body.byteStream();
                 FileOutputStream out = new FileOutputStream(tmpFile)) {
                byte[] buffer = new byte[config.getBufferSize()];
                long downloadedBytes = 0;
                int bytesRead;

                while ((bytesRead = in.read(buffer)) != -1) {
                    if (cancelled) {
                        tmpFile.delete();
                        throw new IOException("下载已取消");
                    }
                    out.write(buffer, 0, bytesRead);
                    downloadedBytes += bytesRead;
                    notifyFileProgress(listener, item, downloadedBytes, totalBytes);
                }
                out.flush();
                out.getFD().sync();
            }
        } catch (IOException e) {
            // 下载失败，清理临时文件
            if (tmpFile.exists()) {
                tmpFile.delete();
            }
            throw e;
        }

        // 重命名：临时文件 → 最终文件
        if (finalFile.exists()) {
            finalFile.delete();
        }
        if (!tmpFile.renameTo(finalFile)) {
            tmpFile.delete();
            throw new IOException("重命名临时文件失败: " + item.getFileName());
        }
    }

    // ==================== 主线程回调通知 ====================

    private void notifyFileStart(BatchDownloadListener listener, DownloadItem item) {
        if (listener != null) {
            mainHandler.post(() -> listener.onFileStart(item));
        }
    }

    private void notifyFileProgress(BatchDownloadListener listener, DownloadItem item,
                                    long downloadedBytes, long totalBytes) {
        if (listener != null) {
            mainHandler.post(() -> listener.onFileProgress(item, downloadedBytes, totalBytes));
        }
    }

    private void notifyFileComplete(BatchDownloadListener listener, DownloadItem item, String path) {
        if (listener != null) {
            mainHandler.post(() -> listener.onFileComplete(item, path));
        }
    }

    private void notifyFileFailed(BatchDownloadListener listener, DownloadItem item,
                                  String error, boolean willRetry) {
        if (listener != null) {
            mainHandler.post(() -> listener.onFileFailed(item, error, willRetry));
        }
    }

    private void notifyTaskProgress(BatchDownloadListener listener, int completed, int total) {
        if (listener != null) {
            mainHandler.post(() -> listener.onTaskProgress(completed, total));
        }
    }

    /**
     * 下载结果
     */
    public static class Result {
        public final int successCount;
        public final int failCount;
        public final List<DownloadItem> failedItems;

        public Result(int successCount, int failCount, List<DownloadItem> failedItems) {
            this.successCount = successCount;
            this.failCount = failCount;
            this.failedItems = failedItems;
        }
    }
}
