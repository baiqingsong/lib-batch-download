package com.dawn.download.internal;

import android.content.Context;
import android.content.SharedPreferences;

import com.dawn.download.DownloadItem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 下载任务持久化存储
 * <p>
 * 基于 SharedPreferences，存储任务列表 hash 和失败项，
 * 用于下次启动时自动恢复未完成的下载。
 */
public class DownloadTaskStore {

    private static final String PREFS_NAME = "batch_download_tasks";
    private static final String KEY_HASH_PREFIX = "hash_";
    private static final String KEY_FAILED_PREFIX = "failed_";

    private final SharedPreferences prefs;

    public DownloadTaskStore(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 获取指定目录上次的任务 hash
     */
    public String getTaskHash(String saveDir) {
        return prefs.getString(KEY_HASH_PREFIX + safeKey(saveDir), "");
    }

    /**
     * 保存任务 hash
     */
    public void saveTaskHash(String saveDir, String hash) {
        prefs.edit().putString(KEY_HASH_PREFIX + safeKey(saveDir), hash).apply();
    }

    /**
     * 获取上次失败的下载项
     */
    public List<DownloadItem> getFailedItems(String saveDir) {
        String json = prefs.getString(KEY_FAILED_PREFIX + safeKey(saveDir), "");
        List<DownloadItem> items = new ArrayList<>();
        if (json.isEmpty()) return items;

        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                DownloadItem item = new DownloadItem(
                        obj.optString("url"),
                        obj.optString("fileName"),
                        obj.optLong("expectedSize", -1),
                        obj.optString("expectedMd5", null)
                );
                items.add(item);
            }
        } catch (Exception ignored) {
        }
        return items;
    }

    /**
     * 保存失败的下载项（下次启动时自动重试）
     */
    public void saveFailedItems(String saveDir, List<DownloadItem> items) {
        if (items == null || items.isEmpty()) {
            clearFailedItems(saveDir);
            return;
        }

        try {
            JSONArray array = new JSONArray();
            for (DownloadItem item : items) {
                JSONObject obj = new JSONObject();
                obj.put("url", item.getUrl());
                obj.put("fileName", item.getFileName());
                obj.put("expectedSize", item.getExpectedSize());
                if (item.getExpectedMd5() != null) {
                    obj.put("expectedMd5", item.getExpectedMd5());
                }
                array.put(obj);
            }
            prefs.edit().putString(KEY_FAILED_PREFIX + safeKey(saveDir), array.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /**
     * 清除失败记录
     */
    public void clearFailedItems(String saveDir) {
        prefs.edit().remove(KEY_FAILED_PREFIX + safeKey(saveDir)).apply();
    }

    /**
     * 清除指定目录的所有记录
     */
    public void clear(String saveDir) {
        String key = safeKey(saveDir);
        prefs.edit()
                .remove(KEY_HASH_PREFIX + key)
                .remove(KEY_FAILED_PREFIX + key)
                .apply();
    }

    /**
     * 将目录路径转换为安全的 SharedPreferences key
     */
    private String safeKey(String path) {
        if (path == null) return "default";
        return path.replace("/", "_").replace("\\", "_").replace(":", "_");
    }
}
