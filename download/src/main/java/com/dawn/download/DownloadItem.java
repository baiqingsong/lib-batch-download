package com.dawn.download;

import java.util.Objects;

/**
 * 下载项数据模型
 */
public class DownloadItem {

    private String url;
    private String fileName;
    private long expectedSize;
    private String expectedMd5;

    /**
     * @param url      下载地址
     * @param fileName 保存文件名（支持子目录，如 "subdir/image.jpg"）
     */
    public DownloadItem(String url, String fileName) {
        this(url, fileName, -1, null);
    }

    /**
     * @param url          下载地址
     * @param fileName     保存文件名
     * @param expectedSize 期望文件大小（字节），-1 表示不校验
     */
    public DownloadItem(String url, String fileName, long expectedSize) {
        this(url, fileName, expectedSize, null);
    }

    /**
     * @param url          下载地址
     * @param fileName     保存文件名
     * @param expectedSize 期望文件大小（字节），-1 表示不校验
     * @param expectedMd5  期望文件 MD5，null 表示不校验
     */
    public DownloadItem(String url, String fileName, long expectedSize, String expectedMd5) {
        this.url = url;
        this.fileName = fileName;
        this.expectedSize = expectedSize;
        this.expectedMd5 = expectedMd5;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public long getExpectedSize() {
        return expectedSize;
    }

    public void setExpectedSize(long expectedSize) {
        this.expectedSize = expectedSize;
    }

    public String getExpectedMd5() {
        return expectedMd5;
    }

    public void setExpectedMd5(String expectedMd5) {
        this.expectedMd5 = expectedMd5;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DownloadItem that = (DownloadItem) o;
        return Objects.equals(url, that.url) && Objects.equals(fileName, that.fileName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(url, fileName);
    }

    @Override
    public String toString() {
        return "DownloadItem{" +
                "url='" + url + '\'' +
                ", fileName='" + fileName + '\'' +
                ", expectedSize=" + expectedSize +
                ", expectedMd5='" + expectedMd5 + '\'' +
                '}';
    }
}
