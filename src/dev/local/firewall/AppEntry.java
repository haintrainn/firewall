package dev.local.firewall;

import android.graphics.drawable.Drawable;

final class AppEntry {
    final String packageName;
    final String label;
    final Drawable icon;
    final boolean system;
    final int uid;
    boolean allowed;
    boolean pending;

    AppEntry(String packageName, String label, Drawable icon, boolean system, int uid, boolean allowed) {
        this.packageName = packageName;
        this.label = label;
        this.icon = icon;
        this.system = system;
        this.uid = uid;
        this.allowed = allowed;
    }
}
