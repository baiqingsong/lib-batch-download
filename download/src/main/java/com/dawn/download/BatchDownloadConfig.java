package com.dawn.download;

/**
 * 批量下载配置
 */
public class BatchDownloadConfig {

    final int maxConcurrent;
    final int maxRetryCount;
    final long retryDelayMs;
    final long connectTimeoutMs;
    final long readTimeoutMs;
    final boolean validateImages;
    final boolean cleanupOrphanFiles;
    final int bufferSize;

    private BatchDownloadConfig(Builder builder) {
        this.maxConcurrent = builder.maxConcurrent;
        this.maxRetryCount = builder.maxRetryCount;
        this.retryDelayMs = builder.retryDelayMs;
        this.connectTimeoutMs = builder.connectTimeoutMs;
        this.readTimeoutMs = builder.readTimeoutMs;
        this.validateImages = builder.validateImages;
        this.cleanupOrphanFiles = builder.cleanupOrphanFiles;
        this.bufferSize = builder.bufferSize;
    }

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    public int getMaxRetryCount() {
        return maxRetryCount;
    }

    public long getRetryDelayMs() {
        return retryDelayMs;
    }

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public long getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public boolean isValidateImages() {
        return validateImages;
    }

    public boolean isCleanupOrphanFiles() {
        return cleanupOrphanFiles;
    }

    public int getBufferSize() {
        return bufferSize;
    }

    public static class Builder {
        private int maxConcurrent = 3;
        private int maxRetryCount = 3;
        private long retryDelayMs = 2000;
        private long connectTimeoutMs = 30_000;
        private long readTimeoutMs = 60_000;
        private boolean validateImages = false;
        private boolean cleanupOrphanFiles = true;
        private int bufferSize = 8192;

        /**
         * 最大并发下载数，默认 3
         */
        public Builder maxConcurrent(int maxConcurrent) {
            this.maxConcurrent = Math.max(1, maxConcurrent);
            return this;
        }

        /**
         * 最大重试次数，默认 3
         */
        public Builder maxRetryCount(int maxRetryCount) {
            this.maxRetryCount = Math.max(0, maxRetryCount);
            return this;
        }

        /**
         * 首次重试延迟（毫秒），后续按指数退避递增，默认 2000ms
         */
        public Builder retryDelayMs(long retryDelayMs) {
            this.retryDelayMs = Math.max(0, retryDelayMs);
            return this;
        }

        /**
         * 连接超时（毫秒），默认 30000
         */
        public Builder connectTimeoutMs(long connectTimeoutMs) {
            this.connectTimeoutMs = Math.max(1000, connectTimeoutMs);
            return this;
        }

        /**
         * 读取超时（毫秒），默认 60000
         */
        public Builder readTimeoutMs(long readTimeoutMs) {
            this.readTimeoutMs = Math.max(1000, readTimeoutMs);
            return this;
        }

        /**
         * 是否校验图片完整性（通过 BitmapFactory 解码头），默认 false
         */
        public Builder validateImages(boolean validateImages) {
            this.validateImages = validateImages;
            return this;
        }

        /**
         * 列表变更时是否清理不再需要的旧文件，默认 true
         */
        public Builder cleanupOrphanFiles(boolean cleanupOrphanFiles) {
            this.cleanupOrphanFiles = cleanupOrphanFiles;
            return this;
        }

        /**
         * 下载缓冲区大小（字节），默认 8192
         */
        public Builder bufferSize(int bufferSize) {
            this.bufferSize = Math.max(1024, bufferSize);
            return this;
        }

        public BatchDownloadConfig build() {
            return new BatchDownloadConfig(this);
        }
    }
}
