package dev.local.firewall;

import android.Manifest;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Process;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import dev.local.firewall.shizuku.Shizuku;

/**
 * GrapheneOS turns android.permission.INTERNET into a runtime permission shown as "Network" in
 * the permission UI. Revoking it is enforced by the OS itself (no VPN slot is used), and the app
 * sees networking as simply unavailable. Only privileged callers (system, shell)
 * may grant or revoke it, so changes go through Shizuku's shell-uid process.
 */
final class NetworkPermission {

    static final String PERMISSION = Manifest.permission.INTERNET;

    private static final Pattern PACKAGE_NAME = Pattern.compile("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+");

    private NetworkPermission() {
    }

    static List<AppEntry> loadApps(Context context, boolean includeSystem) {
        PackageManager pm = context.getPackageManager();
        String self = context.getPackageName();
        List<PackageInfo> packages = pm.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS));
        List<AppEntry> result = new ArrayList<>();
        for (PackageInfo info : packages) {
            if (self.equals(info.packageName) || info.applicationInfo == null) {
                continue;
            }
            int index = indexOfInternet(info);
            if (index < 0) {
                continue;
            }
            ApplicationInfo ai = info.applicationInfo;
            boolean system = (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            if (system && !includeSystem) {
                continue;
            }
            boolean granted = (info.requestedPermissionsFlags[index] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
            result.add(new AppEntry(info.packageName, ai.loadLabel(pm).toString(), ai.loadIcon(pm),
                    system, ai.uid, granted));
        }
        final Collator collator = Collator.getInstance();
        Collections.sort(result, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                int c = collator.compare(a.label, b.label);
                return c != 0 ? c : a.packageName.compareTo(b.packageName);
            }
        });
        return result;
    }

    /** Reads the current grant state straight from the package manager. */
    static boolean isAllowed(Context context, String packageName) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS));
            int index = indexOfInternet(info);
            return index >= 0 && (info.requestedPermissionsFlags[index] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Grants or revokes the Network permission. The USER_SET flag is applied afterwards (best effort)
     * so the choice is treated like one made in Settings and isn't reset by the system.
     */
    static Shizuku.Result set(String packageName, boolean allow) throws Exception {
        if (!PACKAGE_NAME.matcher(packageName).matches()) {
            throw new IllegalArgumentException("Invalid package name: " + packageName);
        }
        int userId = Process.myUid() / 100000;
        String verb = allow ? "grant" : "revoke";
        String script = "pm " + verb + " --user " + userId + " " + packageName + " " + PERMISSION
                + " && { pm set-permission-flags --user " + userId + " " + packageName + " " + PERMISSION
                + " user-set >/dev/null 2>&1; true; }";
        return Shizuku.exec("sh", "-c", script);
    }

    private static int indexOfInternet(PackageInfo info) {
        String[] requested = info.requestedPermissions;
        if (requested == null || info.requestedPermissionsFlags == null) {
            return -1;
        }
        for (int i = 0; i < requested.length; i++) {
            if (PERMISSION.equals(requested[i])) {
                return i;
            }
        }
        return -1;
    }
}
