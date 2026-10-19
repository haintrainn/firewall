package dev.local.firewall.shizuku;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import moe.shizuku.api.BinderContainer;

/**
 * The Shizuku server (running as shell) calls this provider with method "sendBinder" when our
 * process starts or when the server itself starts. Guarded by a permission only shell holds.
 */
public final class ShizukuBinderProvider extends ContentProvider {

    private static final String METHOD_SEND_BINDER = "sendBinder";
    private static final String EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (extras == null || !METHOD_SEND_BINDER.equals(method)) {
            return null;
        }
        extras.setClassLoader(BinderContainer.class.getClassLoader());
        BinderContainer container = extras.getParcelable(EXTRA_BINDER, BinderContainer.class);
        if (container != null && container.binder != null) {
            Shizuku.onBinderReceived(container.binder, getContext().getPackageName());
        }
        return new Bundle();
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
