package com.dawn.download;

/**
 * 批量下载回调监听，所有回调均在主线程执行
 * <p>
 * 所有方法均为 default，按需覆写即可
 */
public interface BatchDownloadListener {

    /**
     * 任务开始
     *
     * @param totalCount        列表总文件数
     * @param needDownloadCount 需要下载的文件数
     * @param skipCount         跳过的文件数（本地已存在且有效）
     */
    default void onTaskStart(int totalCount, int needDownloadCount, int skipCount) {}

    /**
     * 单个文件开始下载
     */
    default void onFileStart(DownloadItem item) {}

    /**
     * 单个文件下载进度
     *
     * @param downloadedBytes 已下载字节数
     * @param totalBytes      总字节数，-1 表示未知
     */
    default void onFileProgress(DownloadItem item, long downloadedBytes, long totalBytes) {}

    /**
     * 单个文件下载完成
     *
     * @param localPath 本地保存路径
     */
    default void onFileComplete(DownloadItem item, String localPath) {}

    /**
     * 单个文件下载失败
     *
     * @param error     错误描述
     * @param willRetry 是否即将重试
     */
    default void onFileFailed(DownloadItem item, String error, boolean willRetry) {}

    /**
     * 单个文件被跳过（本地已存在且有效）
     *
     * @param reason 跳过原因
     */
    default void onFileSkipped(DownloadItem item, String reason) {}

    /**
     * 整体下载进度
     *
     * @param completed 已完成（成功+失败）的文件数
     * @param total     需要下载的文件总数
     */
    default void onTaskProgress(int completed, int total) {}

    /**
     * 任务全部完成
     *
     * @param successCount 下载成功数
     * @param failCount    下载失败数
     * @param skipCount    跳过数
     */
    default void onTaskComplete(int successCount, int failCount, int skipCount) {}

    /**
     * 任务异常中断
     */
    default void onTaskError(String error) {}
}
