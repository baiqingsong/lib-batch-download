# lib-batch-download

Android 批量文件下载库，支持并发下载、失败自动重试、本地缓存校验、智能跳过、列表变更检测等功能。

## 功能特性

- **批量并发下载**：可配置最大并发数（默认 3）
- **失败自动重试**：指数退避策略，可配置最大重试次数
- **持久化记忆**：失败任务自动保存，下次启动可一键重试
- **智能列表比对**：SHA-256 hash 比对列表变更，相同列表直接校验本地
- **本地文件校验**：支持大小校验、MD5 校验、图片完整性校验
- **自动跳过**：已下载且有效的文件自动跳过
- **损坏文件清理**：不完整或校验失败的文件自动删除重下
- **孤立文件清理**：列表变更时自动清理不再需要的旧文件
- **临时文件机制**：下载到 `.downloading` 临时文件，完成后重命名
- **主线程回调**：所有回调自动切换到主线程
- **零外部依赖**：仅需 OkHttp（运行时由 lib-network 提供）

## 环境要求

- AGP 7.4.2
- Gradle 7.6
- compileSdk 34
- minSdk 28
- Java 8

## 引入方式

### JitPack

**Step 1.** 根 `build.gradle` 添加 JitPack 仓库：

```gradle
allprojects {
    repositories {
        ...
        maven { url 'https://jitpack.io' }
    }
}
```

**Step 2.** 添加依赖：

```gradle
dependencies {
    implementation 'com.github.baiqingsong:lib-batch-download:Tag'
}
```

### 运行时依赖

本库以 `compileOnly` 方式依赖 OkHttp，运行时需要宿主工程提供 OkHttp 实例。

推荐配合 lib-network 使用（已包含 OkHttp 4.12.0）：

```gradle
dependencies {
    implementation 'com.github.baiqingsong:lib-batch-download:Tag'
    implementation 'com.github.baiqingsong:lib-network:Tag'
}
```

或单独引入 OkHttp：

```gradle
dependencies {
    implementation 'com.github.baiqingsong:lib-batch-download:Tag'
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
}
```

## API 说明

### BatchDownloadManager

单例管理器，所有操作入口。

| 方法 | 说明 |
|------|------|
| `getInstance(Context)` | 获取单例 |
| `setConfig(BatchDownloadConfig)` | 设置下载配置 |
| `submit(saveDir, items, listener)` | 提交下载任务 |
| `cancel()` | 取消当前任务 |
| `retryFailed(saveDir, listener)` | 重试上次失败项 |
| `hasPendingRetry(saveDir)` | 是否有待重试项 |
| `getPendingRetryItems(saveDir)` | 获取失败项列表 |
| `clearTaskState(saveDir)` | 清除下载记录 |
| `destroy()` | 销毁管理器 |

### BatchDownloadConfig

Builder 模式配置。

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `maxConcurrent` | 3 | 最大并发下载数 |
| `maxRetryCount` | 3 | 最大重试次数 |
| `retryDelayMs` | 2000 | 首次重试延迟（ms），后续指数递增 |
| `connectTimeoutMs` | 30000 | 连接超时（ms） |
| `readTimeoutMs` | 60000 | 读取超时（ms） |
| `validateImages` | false | 是否校验图片完整性 |
| `cleanupOrphanFiles` | true | 列表变更时清理旧文件 |
| `bufferSize` | 8192 | 下载缓冲区大小 |

### DownloadItem

下载项数据模型。

| 构造参数 | 说明 |
|----------|------|
| `url` | 下载地址 |
| `fileName` | 保存文件名（支持子目录） |
| `expectedSize` | 期望文件大小，-1 不校验 |
| `expectedMd5` | 期望 MD5，null 不校验 |

### BatchDownloadListener

回调接口（所有方法为 default，按需覆写）。

| 回调 | 说明 |
|------|------|
| `onTaskStart(total, needDownload, skip)` | 任务开始 |
| `onFileStart(item)` | 单文件开始 |
| `onFileProgress(item, downloaded, total)` | 单文件进度 |
| `onFileComplete(item, localPath)` | 单文件完成 |
| `onFileFailed(item, error, willRetry)` | 单文件失败 |
| `onFileSkipped(item, reason)` | 单文件跳过 |
| `onTaskProgress(completed, total)` | 整体进度 |
| `onTaskComplete(success, fail, skip)` | 任务完成 |
| `onTaskError(error)` | 任务异常 |

## 使用示例

```java
// 1. 初始化
BatchDownloadManager manager = BatchDownloadManager.getInstance(context);

// 2. 配置
manager.setConfig(new BatchDownloadConfig.Builder()
        .maxConcurrent(3)
        .maxRetryCount(3)
        .retryDelayMs(2000)
        .validateImages(true)
        .cleanupOrphanFiles(true)
        .build());

// 3. 构建下载列表
List<DownloadItem> items = new ArrayList<>();
items.add(new DownloadItem("https://example.com/frame1.png", "frame1.png"));
items.add(new DownloadItem("https://example.com/frame2.jpg", "frame2.jpg", 102400));
items.add(new DownloadItem("https://example.com/frame3.png", "frame3.png", -1, "abc123md5"));

// 4. 开始下载
String saveDir = getExternalFilesDir("frames").getAbsolutePath();
manager.submit(saveDir, items, new BatchDownloadListener() {
    @Override
    public void onTaskStart(int total, int needDownload, int skip) {
        Log.d("Download", "开始: 总数=" + total + " 需下载=" + needDownload + " 跳过=" + skip);
    }

    @Override
    public void onFileComplete(DownloadItem item, String localPath) {
        Log.d("Download", "完成: " + item.getFileName());
    }

    @Override
    public void onFileFailed(DownloadItem item, String error, boolean willRetry) {
        Log.w("Download", "失败: " + item.getFileName() + " " + error
                + (willRetry ? " (将重试)" : ""));
    }

    @Override
    public void onTaskComplete(int success, int fail, int skip) {
        Log.d("Download", "全部完成: 成功=" + success + " 失败=" + fail + " 跳过=" + skip);
    }
});

// 5. 取消下载
manager.cancel();

// 6. 重试失败项
if (manager.hasPendingRetry(saveDir)) {
    manager.retryFailed(saveDir, listener);
}

// 7. 销毁（在 Activity onDestroy 中调用）
BatchDownloadManager.destroy();
```

## 下载流程

```
submit(saveDir, items, listener)
  ├─ 计算列表 SHA-256 hash
  ├─ 与上次 hash 比对
  ├─ 清理 .downloading 临时文件
  ├─ 列表变更 → 清理孤立文件
  ├─ 逐个校验本地文件
  │   ├─ 有效 → 跳过
  │   └─ 无效/不存在 → 加入下载队列
  ├─ 并发下载（线程池）
  │   └─ 每个文件
  │       ├─ OkHttp GET → .downloading
  │       ├─ 下载完成 → 重命名
  │       ├─ 文件校验（大小/MD5/图片）
  │       └─ 失败 → 指数退避重试
  ├─ 保存 hash + 失败项
  └─ 通知完成
```

## 注意事项

- 所有回调自动在主线程执行，可直接更新 UI
- `submit()` 会自动取消上一个未完成的任务
- 列表比对基于 url + fileName 的 SHA-256 hash，列表顺序不影响
- `fileName` 支持子目录路径（如 `"subdir/image.jpg"`），会自动创建目录
- 使用 `compileOnly` 依赖 OkHttp，运行时必须由宿主工程或 lib-network 提供
- 图片校验（`validateImages=true`）仅校验图片头信息，不会完整解码
