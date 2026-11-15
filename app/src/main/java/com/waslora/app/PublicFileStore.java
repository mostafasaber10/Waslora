package com.waslora.app;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Publishes app files into the user-visible Download/Waslora folder on Android 10+. */
public final class PublicFileStore {
    private static final String RELATIVE_PATH = Environment.DIRECTORY_DOWNLOADS + "/Waslora";

    private PublicFileStore() {}

    public static Uri publish(Context context, File source) throws Exception {
        if (source == null || !source.isFile() || source.length() <= 0) throw new Exception("الملف غير موجود أو فارغ.");
        if (Build.VERSION.SDK_INT < 29) return null;
        ContentResolver resolver = context.getContentResolver();
        Uri existing = find(resolver, source.getName());
        if (existing != null) return existing;
        String name = source.getName();
        String mime = mimeType(name);
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (destination == null) throw new Exception("تعذر إنشاء ملف ظاهر في مجلد التنزيلات.");
        boolean complete = false;
        try (InputStream input = new FileInputStream(source); OutputStream output = resolver.openOutputStream(destination)) {
            if (output == null) throw new Exception("تعذر فتح ملف التنزيل.");
            byte[] buffer = new byte[512 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            output.flush();
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(destination, ready, null, null);
            complete = true;
            return destination;
        } finally {
            if (!complete) try { resolver.delete(destination, null, null); } catch (Exception ignored) {}
        }
    }

    public static void publishInBackground(Context context, File source) {
        new Thread(() -> {
            try { publish(context, source); } catch (Exception ignored) {}
        }, "Waslora-publish-file").start();
    }

    public static Uri find(Context context, String name) {
        if (Build.VERSION.SDK_INT < 29) return null;
        return find(context.getContentResolver(), name);
    }

    private static Uri find(ContentResolver resolver, String name) {
        if (name == null || name.trim().isEmpty()) return null;
        String[] projection = {MediaStore.MediaColumns._ID};
        String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                + MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                + MediaStore.MediaColumns.IS_PENDING + "=0";
        String[] args = {name, RELATIVE_PATH + "/"};
        try (Cursor cursor = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                long id = cursor.getLong(0);
                return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id);
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static String mimeType(String name) {
        return MediaMimeTypes.forFileName(name);
    }
}
