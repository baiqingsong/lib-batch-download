package com.dawn.download.internal;

import android.graphics.BitmapFactory;

import com.dawn.download.DownloadItem;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;

/**
 * 文件校验工具
 * <p>
 * 支持：文件大小校验、MD5 校验、图片完整性校验
 */
public class FileValidator {

    private FileValidator() {
    }

    /**
     * 综合校验文件是否有效
     *
     * @param file          本地文件
     * @param item          下载项（含期望大小、MD5）
     * @param validateImage 是否校验图片完整性
     * @return true 表示文件有效
     */
    public static boolean isFileValid(File file, DownloadItem item, boolean validateImage) {
        if (file == null || !file.exists()) return false;
        if (file.length() == 0) return false;

        // 大小校验
        if (item.getExpectedSize() > 0 && file.length() != item.getExpectedSize()) {
            return false;
        }

        // MD5 校验
        String expectedMd5 = item.getExpectedMd5();
        if (expectedMd5 != null && !expectedMd5.isEmpty()) {
            String actualMd5 = computeMd5(file);
            if (!expectedMd5.equalsIgnoreCase(actualMd5)) {
                return false;
            }
        }

        // 图片完整性校验
        if (validateImage && isImageFile(file.getName())) {
            return isImageValid(file);
        }

        return true;
    }

    /**
     * 判断是否为图片文件（根据扩展名）
     */
    public static boolean isImageFile(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".gif")
                || lower.endsWith(".bmp") || lower.endsWith(".webp");
    }

    /**
     * 校验图片是否可解码（快速模式，仅读取头信息）
     */
    public static boolean isImageValid(File file) {
        if (file == null || !file.exists() || file.length() == 0) return false;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            return options.outWidth > 0 && options.outHeight > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 计算文件 MD5
     */
    public static String computeMd5(File file) {
        if (file == null || !file.exists()) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    md.update(buffer, 0, bytesRead);
                }
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
