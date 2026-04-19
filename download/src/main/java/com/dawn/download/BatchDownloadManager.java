package com.dawn.download;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.dawn.download.internal.DownloadEngine;
import com.dawn.download.internal.DownloadTaskStore;
import com.dawn.download.internal.FileValidator;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 批量下载管理器（单例）
 * <p>
 * 功能特性：
 * <ul>
 *   <li>批量并发下载，可配置并发数</li>
 *   <li>失败自动重试（指数退避）</li>
 *   <li>持久化记忆：失败任务保存到本地，下次启动自动重新下载</li>
 *   <li>智能比对：对比列表 hash，相同列表直接校验本地文件</li>
 *   <li>本地文件校验：已有且完整的文件自动跳过</li>
 *   <li>损坏/不完整文件自动删除重新下载</li>
 *   <li>临时文件机制：下载到 .downloading 文件，完成后重命名</li>
 *   <li>列表变更时自动清理不再需要的旧文件</li>
 *   <li>所有回调自动切换到主线程</li>
 * </ul>
 *
 * <pre>
 * // 使用示例
 * BatchDownloadManager manager = BatchDownloadManager.getInstance(context);
 * manager.setConfig(new BatchDownloadConfig.Builder()
 *         .maxConcurrent(3)
 *         .maxRetryCount(3)
 *         .validateImages(true)
 *         .build());
 *
 * List&lt;DownloadItem&gt; items = new ArrayList&lt;&gt;();
 * items.add(new DownloadItem("https://example.com/a.jpg", "a.jpg"));
 * items.add(new DownloadItem("https://example.com/b.png", "b.png"));
 *
 * manager.submit("/sdcard/frames/", items, new BatchDownloadListener() {
 *     &#64;Override
 *     public void onTaskComplete(int success, int fail, int skip) {
 *         Log.d("Download", "完成: 成功=" + success + " 失败=" + fail + " 跳过=" + skip);
 *     }
 * });
 * </pre>
 */
public class BatchDownloadManager {

    private static final String TAG = "BatchDownloadManager";

    private static volatile BatchDownloadManager instance;

    private final Context appContext;
    private final Handler mainHandler;
    private final DownloadTaskStore taskStore;
    private final ExecutorService coordinatorExecutor;

    private BatchDownloadConfig config;
    private DownloadEngine currentEngine;
    private final Object lock = new Object();

    private BatchDownloadManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.taskStore = new DownloadTaskStore(appContext);
        this.coordinatorExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "BatchDownload-Coordinator");
            t.setDaemon(true);
            return t;
        });
        this.config = new BatchDownloadConfig.Builder().build();
    }

    public static BatchDownloadManager getInstance(Context context) {
        if (instance == null) {
            synchronized (BatchDownloadManager.class) {
                if (instance == null) {
                    instance = new BatchDownloadManager(context);
                }
            }
        }
        return instance;
    }

    /**
     * 设置下载配置
     */
    public void setConfig(BatchDownloadConfig config) {
        this.config = config != null ? config : new BatchDownloadConfig.Builder().build();
    }

    /**
     * 获取当前配置
     */
    public BatchDownloadConfig getConfig() {
        return config;
    }

    /**
     * 提交批量下载任务
     * <p>
     * 内部流程：
     * <ol>
     *   <li>计算列表 hash，与上次比对</li>
     *   <li>清理 .downloading 临时文件</li>
     *   <li>如果列表变更且开启了孤立文件清理，清理不再需要的旧文件</li>
     *   <li>逐个校验本地文件，有效的跳过，无效的删除</li>
     *   <li>对需要下载的文件执行并发下载（含重试）</li>
     *   <li>下载完成后保存状态（hash + 失败项）</li>
     * </ol>
     *
     * @param saveDir  保存目录
     * @param items    下载列表
     * @param listener 回调（主线程）
     */
    public void submit(String saveDir, List<DownloadItem> items, BatchDownloadListener listener) {
        if (saveDir == null || saveDir.isEmpty()) {
            notifyError(listener, "saveDir 不能为空");
            return;
        }
        if (items == null || items.isEmpty()) {
            notifyTaskComplete(listener, 0, 0, 0);
            return;
        }

        // 取消当前任务
        synchronized (lock) {
            if (currentEngine != null) {
                currentEngine.cancel();
            }
            currentEngine = new DownloadEngine(config, mainHandler);
        }

        coordinatorExecutor.execute(() -> {
            try {
                executeTask(saveDir, new ArrayList<>(items), listener);
            } catch (Exception e) {
                Log.e(TAG, "任务执行异常", e);
                notifyError(listener, "任务执行异常: " + e.getMessage());
            }
        });
    }

    /**
     * 取消当前下载任务
     */
    public void cancel() {
        synchronized (lock) {
            if (currentEngine != null) {
                currentEngine.cancel();
                currentEngine = null;
            }
        }
    }

    /**
     * 重试上次失败的项目
     *
     * @param saveDir  保存目录（与上次 submit 相同）
     * @param listener 回调
     */
    public void retryFailed(String saveDir, BatchDownloadListener listener) {
        List<DownloadItem> failedItems = taskStore.getFailedItems(saveDir);
        if (failedItems.isEmpty()) {
            notifyTaskComplete(listener, 0, 0, 0);
            return;
        }
        submit(saveDir, failedItems, listener);
    }

    /**
     * 清除指定目录的所有下载记录
     */
    public void clearTaskState(String saveDir) {
        taskStore.clear(saveDir);
    }

    /**
     * 检查指定目录是否有上次失败未完成的任务
     */
    public boolean hasPendingRetry(String saveDir) {
        return !taskStore.getFailedItems(saveDir).isEmpty();
    }

    /**
     * 获取上次失败的项目列表
     */
    public List<DownloadItem> getPendingRetryItems(String saveDir) {
        return taskStore.getFailedItems(saveDir);
    }

    /**
     * 销毁管理器，释放所有资源
     */
    public static synchronized void destroy() {
        if (instance != null) {
            instance.cancel();
            instance.coordinatorExecutor.shutdownNow();
            instance = null;
        }
    }

    // ==================== 内部任务执行 ====================

    private void executeTask(String saveDir, List<DownloadItem> items, BatchDownloadListener listener) {
        // 1. 确保目录存在
        File dir = new File(saveDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // 2. 清理临时文件
        cleanupTempFiles(dir);

        // 3. 计算任务 hash 并比对
        String newHash = computeTaskHash(items);
        String oldHash = taskStore.getTaskHash(saveDir);
        boolean listChanged = !newHash.equals(oldHash);

        if (listChanged) {
            Log.i(TAG, "下载列表已变更，重新评估");
        }

        // 4. 列表变更时清理孤立文件
        if (listChanged && config.isCleanupOrphanFiles()) {
            cleanupOrphanFiles(dir, items);
        }

        // 5. 过滤：校验本地文件，区分需下载和可跳过的
        List<DownloadItem> needDownload = new ArrayList<>();
        List<DownloadItem> skipped = new ArrayList<>();

        DownloadEngine engine;
        synchronized (lock) {
            engine = currentEngine;
        }

        for (DownloadItem item : items) {
            if (engine == null || engine.isCancelled()) return;

            File file = new File(saveDir, item.getFileName());
            if (file.exists()) {
                if (FileValidator.isFileValid(file, item, config.isValidateImages())) {
                    skipped.add(item);
                    notifyFileSkipped(listener, item, "文件已存在且有效");
                } else {
                    Log.w(TAG, "文件无效，删除: " + item.getFileName());
                    file.delete();
                    needDownload.add(item);
                }
            } else {
                needDownload.add(item);
            }
        }

        // 6. 没有需要下载的文件
        if (needDownload.isEmpty()) {
            taskStore.saveTaskHash(saveDir, newHash);
            taskStore.clearFailedItems(saveDir);
            notifyTaskStart(listener, items.size(), 0, skipped.size());
            notifyTaskComplete(listener, 0, 0, skipped.size());
            Log.i(TAG, "所有文件已存在，无需下载");
            return;
        }

        Log.i(TAG, "开始下载: 总数=" + items.size() + " 需下载=" + needDownload.size()
                + " 跳过=" + skipped.size());

        // 7. 通知开始
        notifyTaskStart(listener, items.size(), needDownload.size(), skipped.size());

        // 8. 执行下载
        if (engine == null || engine.isCancelled()) return;
        DownloadEngine.Result result = engine.execute(saveDir, needDownload, listener);

        // 9. 保存状态
        taskStore.saveTaskHash(saveDir, newHash);
        if (result.failedItems.isEmpty()) {
            taskStore.clearFailedItems(saveDir);
        } else {
            taskStore.saveFailedItems(saveDir, result.failedItems);
        }

        // 10. 通知完成
        notifyTaskComplete(listener, result.successCount, result.failCount, skipped.size());

        Log.i(TAG, "下载完成: 成功=" + result.successCount + " 失败=" + result.failCount
                + " 跳过=" + skipped.size());

        synchronized (lock) {
            if (currentEngine == engine) {
                currentEngine = null;
            }
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 计算任务列表 hash（SHA-256），用于判断列表是否变更
     */
    private String computeTaskHash(List<DownloadItem> items) {
        List<String> keys = new ArrayList<>();
        for (DownloadItem item : items) {
            keys.add(item.getUrl() + "|" + item.getFileName());
        }
        Collections.sort(keys);

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String key : keys) {
                md.update(key.getBytes(StandardCharsets.UTF_8));
            }
            byte[] hash = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(keys.hashCode());
        }
    }

    /**
     * 清理 .downloading 临时文件（上次中断遗留）
     */
    private void cleanupTempFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        int count = 0;
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".downloading")) {
                file.delete();
                count++;
            }
            // 递归清理子目录
            if (file.isDirectory()) {
                cleanupTempFiles(file);
            }
        }
        if (count > 0) {
            Log.i(TAG, "清理临时文件: " + count + " 个");
        }
    }

    /**
     * 清理孤立文件（不在新列表中的旧文件）
     */
    private void cleanupOrphanFiles(File dir, List<DownloadItem> items) {
        Set<String> validNames = new HashSet<>();
        for (DownloadItem item : items) {
            validNames.add(item.getFileName());
        }

        File[] files = dir.listFiles();
        if (files == null) return;
        int count = 0;
        for (File file : files) {
            if (file.isFile() && !file.getName().endsWith(".downloading")
                    && !validNames.contains(file.getName())) {
                file.delete();
                count++;
            }
        }
        if (count > 0) {
            Log.i(TAG, "清理孤立文件: " + count + " 个");
        }
    }

    // ==================== 主线程回调 ====================

    private void notifyTaskStart(BatchDownloadListener listener, int total, int needDownload, int skip) {
        if (listener != null) {
            mainHandler.post(() -> listener.onTaskStart(total, needDownload, skip));
        }
    }

    private void notifyTaskComplete(BatchDownloadListener listener, int success, int fail, int skip) {
        if (listener != null) {
            mainHandler.post(() -> listener.onTaskComplete(success, fail, skip));
        }
    }

    private void notifyError(BatchDownloadListener listener, String error) {
        if (listener != null) {
            mainHandler.post(() -> listener.onTaskError(error));
        }
    }

    private void notifyFileSkipped(BatchDownloadListener listener, DownloadItem item, String reason) {
        if (listener != null) {
            mainHandler.post(() -> listener.onFileSkipped(item, reason));
        }
    }
}
