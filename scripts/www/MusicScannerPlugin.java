package com.shengu.musicplayer;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import androidx.activity.result.ActivityResult;

import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/**
 * 本地音乐扫描插件
 *
 * 通过系统媒体库（MediaStore.Audio）读取手机上所有被系统收录的音乐文件，
 * 返回歌名、歌手、时长、真实文件路径等信息，供网页端构建播放列表。
 *
 * 权限说明：
 *  - Android 13 (API 33) 及以上使用 READ_MEDIA_AUDIO
 *  - Android 12 及以下使用 READ_EXTERNAL_STORAGE
 */
@CapacitorPlugin(
    name = "MusicScanner",
    permissions = {
        @Permission(alias = "audio", strings = { "android.permission.READ_MEDIA_AUDIO" }),
        @Permission(alias = "storage", strings = { Manifest.permission.READ_EXTERNAL_STORAGE })
    }
)
public class MusicScannerPlugin extends Plugin {

    /** 根据系统版本选择正确的权限别名 */
    private String permissionAlias() {
        return Build.VERSION.SDK_INT >= 33 ? "audio" : "storage";
    }

    /**
     * 扫描本地全部音乐
     * 返回：{ songs: [ {id, title, artist, album, duration, size, path, contentUri, mimeType, fileName} ], count }
     */
    @PluginMethod
    public void scan(PluginCall call) {
        String alias = permissionAlias();
        if (getPermissionState(alias) != PermissionState.GRANTED) {
            requestPermissionForAlias(alias, call, "permissionCallback");
            return;
        }
        doScan(call);
    }

    @PermissionCallback
    private void permissionCallback(PluginCall call) {
        if (getPermissionState(permissionAlias()) == PermissionState.GRANTED) {
            doScan(call);
        } else {
            call.reject("未授予音乐读取权限，请在系统设置中开启后重试");
        }
    }

    /** 只查询权限状态，供网页端提前判断 */
    @PluginMethod
    public void checkPermission(PluginCall call) {
        String alias = permissionAlias();
        JSObject ret = new JSObject();
        ret.put("granted", getPermissionState(alias) == PermissionState.GRANTED);
        ret.put("alias", alias);
        ret.put("sdkInt", Build.VERSION.SDK_INT);
        call.resolve(ret);
    }

    private void doScan(PluginCall call) {
        JSArray songs = new JSArray();

        try {
            ContentResolver resolver = getContext().getContentResolver();
            Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

            String[] projection = new String[] {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DISPLAY_NAME
            };

            // IS_MUSIC != 0 可过滤掉铃声、录音、提示音等非音乐音频
            String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
            String sortOrder = MediaStore.Audio.Media.DATE_ADDED + " DESC";

            Cursor cursor = resolver.query(collection, projection, selection, null, sortOrder);
            if (cursor == null) {
                call.reject("无法访问系统媒体库");
                return;
            }

            int idxId = cursor.getColumnIndex(MediaStore.Audio.Media._ID);
            int idxTitle = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE);
            int idxArtist = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST);
            int idxAlbum = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM);
            int idxDuration = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION);
            int idxSize = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE);
            int idxData = cursor.getColumnIndex(MediaStore.Audio.Media.DATA);
            int idxMime = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE);
            int idxDate = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED);
            int idxName = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME);

            while (cursor.moveToNext()) {
                long id = idxId >= 0 ? cursor.getLong(idxId) : 0;
                String title = idxTitle >= 0 ? cursor.getString(idxTitle) : null;
                String artist = idxArtist >= 0 ? cursor.getString(idxArtist) : null;
                String album = idxAlbum >= 0 ? cursor.getString(idxAlbum) : null;
                String path = idxData >= 0 ? cursor.getString(idxData) : null;
                String mime = idxMime >= 0 ? cursor.getString(idxMime) : null;
                String fileName = idxName >= 0 ? cursor.getString(idxName) : null;
                long duration = idxDuration >= 0 ? cursor.getLong(idxDuration) : 0;
                long size = idxSize >= 0 ? cursor.getLong(idxSize) : 0;
                long dateAdded = idxDate >= 0 ? cursor.getLong(idxDate) : 0;

                // 过滤掉不支持的格式，减少网页端无效解码
                String lower = fileName != null ? fileName.toLowerCase() : "";
                if (lower.endsWith(".mid") || lower.endsWith(".midi") ||
                    lower.endsWith(".amr") || lower.endsWith(".ac3")) {
                    continue;
                }

                Uri contentUri = ContentUris.withAppendedId(collection, id);

                JSObject song = new JSObject();
                song.put("id", "native-" + id);
                song.put("mediaId", id);
                song.put("title", title != null ? title : stripExtension(fileName));
                song.put("artist", artist != null ? artist : "");
                song.put("album", album != null ? album : "");
                song.put("duration", duration / 1000.0);
                song.put("size", size);
                song.put("path", path != null ? path : "");
                song.put("contentUri", contentUri.toString());
                song.put("mimeType", mime != null ? mime : "");
                song.put("dateAdded", dateAdded);
                song.put("fileName", fileName != null ? fileName : "");
                // 安卓 10+ 分区存储下 DATA 路径可能不可读，这里标注出来，
                // 网页端遇到不可读的会改走 importAudioIds() 复制成 App 私有目录文件
                boolean readable = false;
                if (path != null && !path.isEmpty()) {
                    try {
                        File f = new File(path);
                        readable = f.exists() && f.canRead() && f.length() > 0;
                    } catch (Exception ignored) {}
                }
                song.put("readable", readable);
                songs.put(song);
            }
            cursor.close();
        } catch (Exception e) {
            call.reject("扫描本地音乐失败：" + e.getMessage());
            return;
        }

        JSObject ret = new JSObject();
        ret.put("songs", songs);
        ret.put("count", songs.length());
        call.resolve(ret);
    }

    /**
     * 打开系统文件选择器并支持多选音乐文件。
     * WebView 自带的 <input type="file" multiple> 在部分机型 / 打包壳里会退化成单选甚至打不开，
     * 这里改走原生 ACTION_OPEN_DOCUMENT（EXTRA_ALLOW_MULTIPLE），
     * 选完后把文件复制到 App 私有目录 imported/ 下，返回真实路径供网页端播放与持久化。
     * 返回：{ files: [ {path, name, fileName, size} ], count }
     */
    private static final String[] AUDIO_EXTS = {
            ".mp3", ".flac", ".wav", ".aac", ".ogg", ".m4a", ".wma", ".ape",
            ".opus", ".amr", ".aiff", ".aif", ".mid", ".midi", ".ac3", ".mka"
    };

    /** 判断选中的 Uri 是不是音频：先看 MIME，再退回按扩展名判断 */
    private boolean isAudioUri(Uri uri, String displayName) {
        try {
            String mime = getContext().getContentResolver().getType(uri);
            if (mime != null && mime.toLowerCase().startsWith("audio/")) return true;
        } catch (Exception ignored) {}
        String lower = displayName == null ? "" : displayName.toLowerCase();
        for (String ext : AUDIO_EXTS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    @PluginMethod
    public void pickAudioFiles(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // 用 */* 打开：部分国产 ROM 的文件管理器对 audio/* 会退化成单选，
        // 全类型才给多选；选完在这里按 MIME / 扩展名过滤非音频文件。
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(call, intent, "handlePickResult");
        } catch (Exception e) {
            call.reject("无法打开系统文件选择器：" + (e.getMessage() != null ? e.getMessage() : "未知错误"));
        }
    }

    @ActivityCallback
    private void handlePickResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        JSArray files = new JSArray();
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            JSObject empty = new JSObject();
            empty.put("files", files);
            empty.put("count", 0);
            call.resolve(empty);
            return;
        }

        Intent data = result.getData();
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }

        final List<Uri> picked = uris;
        final ContentResolver resolver = getContext().getContentResolver();
        final File dir = new File(getContext().getFilesDir(), "imported");

        if (picked.isEmpty()) {
            JSObject emptyRet = new JSObject();
            emptyRet.put("files", new JSArray());
            emptyRet.put("count", 0);
            call.resolve(emptyRet);
            return;
        }

        // 多选可能一次几十首，逐首同步复制会卡住 WebView 甚至触发 ANR，
        // 这里放后台线程 + 小并发池一起复制，并保持 call 存活直到全部完成
        call.setKeepAlive(true);
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (!dir.exists() && !dir.mkdirs()) {
                    call.setKeepAlive(false);
                    call.reject("无法创建导入目录");
                    return;
                }

                int threads = Math.min(3, Math.max(1, picked.size()));
                ExecutorService pool = Executors.newFixedThreadPool(threads);
                final AtomicInteger seq = new AtomicInteger(0);
                List<Future<JSObject>> futures = new ArrayList<>();

                for (final Uri uri : picked) {
                    futures.add(pool.submit(new Callable<JSObject>() {
                        @Override
                        public JSObject call() {
                            return copyOneAudio(uri, dir, seq, resolver);
                        }
                    }));
                }
                pool.shutdown();

                JSArray files = new JSArray();
                int okCount = 0;
                for (Future<JSObject> f : futures) {
                    try {
                        JSObject item = f.get();
                        if (item != null) {
                            files.put(item);
                            okCount++;
                        }
                    } catch (Exception ignored) {}
                }

                JSObject ret = new JSObject();
                ret.put("files", files);
                ret.put("count", okCount);
                call.setKeepAlive(false);
                call.resolve(ret);
            }
        }).start();
    }

    /** 复制单个选中的音频 Uri 到 App 私有目录，失败返回 null */
    private JSObject copyOneAudio(Uri uri, File dir, AtomicInteger seq, ContentResolver resolver) {
        String displayName = queryDisplayName(uri);
        if (displayName == null || displayName.trim().isEmpty()) {
            displayName = "audio_" + System.currentTimeMillis();
        }
        if (!isAudioUri(uri, displayName)) return null;

        String safeName = displayName.replaceAll("[\\\\/:*?\"<>|]", "_");
        File outFile = new File(dir, System.currentTimeMillis() + "_" + seq.incrementAndGet() + "_" + safeName);
        try {
            InputStream in = resolver.openInputStream(uri);
            if (in == null) return null;
            FileOutputStream out = new FileOutputStream(outFile);
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            out.flush();
            out.close();
            in.close();
        } catch (Exception e) {
            try { if (outFile.exists()) outFile.delete(); } catch (Exception ignored) {}
            return null;
        }
        if (!outFile.exists() || outFile.length() <= 0) return null;

        JSObject item = new JSObject();
        item.put("path", outFile.getAbsolutePath());
        item.put("name", stripExtension(displayName));
        item.put("fileName", displayName);
        item.put("size", outFile.length());
        return item;
    }

    /**
     * 按媒体库 id 批量导入：把选中的歌曲从 MediaStore 复制到 App 私有目录 imported/，
     * 返回可直接播放的真实文件路径（同时带上歌名 / 歌手 / 时长）。
     *
     * 存在的意义：网页层读不到手机存储，安卓 10+ 分区存储下 DATA 路径也可能不可读，
     * 只有原生侧能通过 ContentResolver 读 content:// 后再落成实体文件。
     *
     * 参数：ids: [ MediaStore.Audio.Media._ID, ... ]
     * 返回：{ files: [ {mediaId, nativeId, path, name, fileName, artist, duration, size} ], count, failed }
     */
    @PluginMethod
    public void importAudioIds(final PluginCall call) {
        JSArray idsArr = call.getArray("ids", new JSArray());
        final List<Long> ids = new ArrayList<>();
        if (idsArr != null) {
            for (int i = 0; i < idsArr.length(); i++) {
                long id = idsArr.optLong(i, -1L);
                if (id > 0) ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            call.reject("没有指定要导入的歌曲");
            return;
        }

        final JSArray files = new JSArray();
        final int[] failed = new int[] { 0 };
        call.setKeepAlive(true);

        // 复制可能耗时（几十首也够久），放到后台线程，避免阻塞 WebView 主线程
        new Thread(new Runnable() {
            @Override
            public void run() {
                ContentResolver resolver = getContext().getContentResolver();
                File dir = new File(getContext().getFilesDir(), "imported");
                if (!dir.exists()) dir.mkdirs();

                String[] proj = new String[] {
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.MIME_TYPE
                };

                for (Long mediaId : ids) {
                    Uri uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId);

                    String title = null, artist = null, displayName = null, mime = null;
                    long durationMs = 0, size = 0;

                    try {
                        Cursor c = resolver.query(uri, proj, null, null, null);
                        if (c != null) {
                            try {
                                if (c.moveToFirst()) {
                                    int iTitle = c.getColumnIndex(MediaStore.Audio.Media.TITLE);
                                    int iArtist = c.getColumnIndex(MediaStore.Audio.Media.ARTIST);
                                    int iDur = c.getColumnIndex(MediaStore.Audio.Media.DURATION);
                                    int iSize = c.getColumnIndex(MediaStore.Audio.Media.SIZE);
                                    int iName = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME);
                                    int iMime = c.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE);
                                    title = iTitle >= 0 ? c.getString(iTitle) : null;
                                    artist = iArtist >= 0 ? c.getString(iArtist) : null;
                                    durationMs = iDur >= 0 ? c.getLong(iDur) : 0;
                                    size = iSize >= 0 ? c.getLong(iSize) : 0;
                                    displayName = iName >= 0 ? c.getString(iName) : null;
                                    mime = iMime >= 0 ? c.getString(iMime) : null;
                                }
                            } finally {
                                c.close();
                            }
                        }
                    } catch (Exception ignored) {}

                    if (displayName == null || displayName.trim().isEmpty()) {
                        displayName = "music_" + mediaId + ".mp3";
                    }
                    String safeName = displayName.replaceAll("[\\\\/:*?\"<>|]", "_");
                    File outFile = new File(dir, mediaId + "_" + safeName);

                    // 已经复制过且大小一致，直接复用，避免重复占空间
                    boolean reused = outFile.exists() && outFile.length() > 0
                            && (size <= 0 || outFile.length() == size);
                    if (!reused) {
                        try {
                            InputStream in = resolver.openInputStream(uri);
                            if (in == null) { failed[0]++; continue; }
                            FileOutputStream out = new FileOutputStream(outFile, false);
                            byte[] buf = new byte[8192];
                            int len;
                            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                            out.flush();
                            out.close();
                            in.close();
                        } catch (Exception e) {
                            try { if (outFile.exists()) outFile.delete(); } catch (Exception ignored) {}
                            failed[0]++;
                            continue;
                        }
                    }

                    if (!outFile.exists() || outFile.length() <= 0) {
                        failed[0]++;
                        continue;
                    }

                    JSObject item = new JSObject();
                    item.put("mediaId", mediaId);
                    item.put("nativeId", "native-" + mediaId);
                    item.put("path", outFile.getAbsolutePath());
                    item.put("name", (title != null && !title.trim().isEmpty())
                            ? title : stripExtension(displayName));
                    item.put("fileName", displayName);
                    item.put("artist", artist != null ? artist : "");
                    item.put("duration", durationMs / 1000.0);
                    item.put("size", outFile.length());
                    item.put("mimeType", mime != null ? mime : "");
                    files.put(item);
                }

                JSObject ret = new JSObject();
                ret.put("files", files);
                ret.put("count", files.length());
                ret.put("failed", failed[0]);
                call.setKeepAlive(false);
                call.resolve(ret);
            }
        }).start();
    }

    private String queryDisplayName(Uri uri) {
        try {
            Cursor cursor = getContext().getContentResolver().query(uri,
                new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null);
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (idx >= 0) return cursor.getString(idx);
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (Exception e) {
            // 忽略，回退到默认命名
        }
        return null;
    }

    private String stripExtension(String name) {
        if (name == null) return "未知歌曲";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
