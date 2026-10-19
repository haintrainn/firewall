package dev.local.firewall;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.local.firewall.shizuku.Shizuku;

public final class MainActivity extends Activity implements Shizuku.Listener {

    private static final String PREFS = "settings";
    private static final String PREF_SHOW_SYSTEM = "show_system";
    private static final String SHIZUKU_URL = "https://shizuku.rikka.app/download/";

    private static final int FILTER_ALL = 0;
    private static final int FILTER_ALLOWED = 1;
    private static final int FILTER_BLOCKED = 2;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private ListView list;
    private View topBar;
    private TextView smallTitle;
    private TextView bigTitle;
    private ProgressBar progress;
    private View banner;
    private TextView bannerTitle;
    private TextView bannerSummary;
    private Button bannerAction;
    private TextView summaryBlocked;
    private TextView summaryTotal;
    private TextView empty;
    private EditText search;
    private View searchClear;

    private final AppAdapter adapter = new AppAdapter();
    private List<AppEntry> allApps = Collections.emptyList();
    private SharedPreferences prefs;
    private boolean showSystem;
    private int filter = FILTER_ALL;
    private String query = "";
    private int loadGeneration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        showSystem = prefs.getBoolean(PREF_SHOW_SYSTEM, false);

        setContentView(R.layout.activity_main);
        list = findViewById(R.id.list);
        topBar = findViewById(R.id.top_bar);
        smallTitle = findViewById(R.id.small_title);
        progress = findViewById(R.id.progress);

        View header = LayoutInflater.from(this).inflate(R.layout.list_header, list, false);
        bigTitle = header.findViewById(R.id.big_title);
        banner = header.findViewById(R.id.banner);
        bannerTitle = header.findViewById(R.id.banner_title);
        bannerSummary = header.findViewById(R.id.banner_summary);
        bannerAction = header.findViewById(R.id.banner_action);
        summaryBlocked = header.findViewById(R.id.summary_blocked);
        summaryTotal = header.findViewById(R.id.summary_total);
        empty = header.findViewById(R.id.empty);
        search = header.findViewById(R.id.search);
        searchClear = header.findViewById(R.id.search_clear);
        list.addHeaderView(header, null, false);
        list.setAdapter(adapter);

        setupInsets();
        setupCollapsingTitle();
        setupSearchAndFilters(header);
        findViewById(R.id.overflow).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showOverflow(v);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        Shizuku.addListener(this);
        updateBanner();
        reload();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Shizuku.removeListener(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    @Override
    public void onShizukuStateChanged() {
        updateBanner();
    }

    // ---- Layout ----------------------------------------------------------------------------

    private void setupInsets() {
        final int topBarBase = topBar.getPaddingTop();
        final int listBottom = dp(24);
        findViewById(R.id.root).setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                topBar.setPadding(topBar.getPaddingLeft(), topBarBase + bars.top, topBar.getPaddingRight(), 0);
                v.setPadding(bars.left, 0, bars.right, 0);
                list.setPadding(0, bars.top + dp(64), 0, Math.max(bars.bottom, ime.bottom) + listBottom);
                return WindowInsets.CONSUMED;
            }
        });
    }

    private void setupCollapsingTitle() {
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            private boolean collapsed;

            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                View first = view.getChildAt(0);
                boolean nowCollapsed = firstVisibleItem > 0 || (first != null
                        && first.getTop() + bigTitle.getBottom() - dp(16) < view.getPaddingTop());
                if (nowCollapsed != collapsed) {
                    collapsed = nowCollapsed;
                    smallTitle.animate().alpha(collapsed ? 1f : 0f).setDuration(150).start();
                    topBar.setBackgroundColor(getColor(collapsed ? R.color.fw_surface_high : R.color.fw_background));
                }
            }
        });
    }

    private void setupSearchAndFilters(View header) {
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                searchClear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                applyFilter();
            }
        });
        searchClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                search.setText("");
            }
        });
        RadioGroup filters = header.findViewById(R.id.filters);
        filters.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                if (checkedId == R.id.filter_allowed) {
                    filter = FILTER_ALLOWED;
                } else if (checkedId == R.id.filter_blocked) {
                    filter = FILTER_BLOCKED;
                } else {
                    filter = FILTER_ALL;
                }
                applyFilter();
            }
        });
    }

    private void showOverflow(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        Menu menu = popup.getMenu();
        menu.add(0, 1, 0, R.string.menu_show_system).setCheckable(true).setChecked(showSystem);
        if (getPackageManager().getLaunchIntentForPackage(Shizuku.MANAGER_PACKAGE) != null) {
            menu.add(0, 2, 1, R.string.menu_open_shizuku);
        }
        menu.add(0, 3, 2, R.string.menu_about);
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        showSystem = !showSystem;
                        prefs.edit().putBoolean(PREF_SHOW_SYSTEM, showSystem).apply();
                        reload();
                        return true;
                    case 2:
                        launchShizuku();
                        return true;
                    case 3:
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle(R.string.about_title)
                                .setMessage(R.string.about_message)
                                .setPositiveButton(R.string.ok, null)
                                .show();
                        return true;
                    default:
                        return false;
                }
            }
        });
        popup.show();
    }

    // ---- Shizuku banner --------------------------------------------------------------------

    private void updateBanner() {
        if (Shizuku.isReady()) {
            banner.setVisibility(View.GONE);
            return;
        }
        banner.setVisibility(View.VISIBLE);
        if (!isInstalled(Shizuku.MANAGER_PACKAGE)) {
            bannerTitle.setText(R.string.shizuku_missing_title);
            bannerSummary.setText(R.string.shizuku_missing_summary);
            bannerAction.setText(R.string.shizuku_missing_action);
            bannerAction.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startSafely(new Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_URL)), "Shizuku");
                }
            });
        } else if (!Shizuku.isRunning()) {
            bannerTitle.setText(R.string.shizuku_stopped_title);
            bannerSummary.setText(R.string.shizuku_stopped_summary);
            bannerAction.setText(R.string.shizuku_stopped_action);
            bannerAction.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    launchShizuku();
                }
            });
        } else {
            bannerTitle.setText(R.string.shizuku_denied_title);
            bannerSummary.setText(R.string.shizuku_denied_summary);
            bannerAction.setText(R.string.shizuku_denied_action);
            bannerAction.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    io.execute(new Runnable() {
                        @Override
                        public void run() {
                            Shizuku.requestPermission();
                        }
                    });
                }
            });
        }
    }

    private void launchShizuku() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(Shizuku.MANAGER_PACKAGE);
        if (intent != null) {
            startSafely(intent, "Shizuku");
        }
    }

    // ---- Data ------------------------------------------------------------------------------

    private void reload() {
        final int generation = ++loadGeneration;
        final boolean includeSystem = showSystem;
        if (allApps.isEmpty()) {
            progress.setVisibility(View.VISIBLE);
        }
        final Context appContext = getApplicationContext();
        io.execute(new Runnable() {
            @Override
            public void run() {
                final List<AppEntry> apps = NetworkPermission.loadApps(appContext, includeSystem);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (generation != loadGeneration || isDestroyed()) {
                            return;
                        }
                        allApps = apps;
                        progress.setVisibility(View.GONE);
                        applyFilter();
                    }
                });
            }
        });
    }

    private void applyFilter() {
        List<AppEntry> shown = new ArrayList<>();
        int blocked = 0;
        for (AppEntry e : allApps) {
            if (!e.allowed) {
                blocked++;
            }
            if (filter == FILTER_ALLOWED && !e.allowed) {
                continue;
            }
            if (filter == FILTER_BLOCKED && e.allowed) {
                continue;
            }
            if (!query.isEmpty() && !e.label.toLowerCase(Locale.ROOT).contains(query)
                    && !e.packageName.toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            shown.add(e);
        }
        summaryBlocked.setText(getString(R.string.count_blocked, blocked));
        summaryTotal.setText(getString(R.string.count_apps, allApps.size()));
        empty.setVisibility(shown.isEmpty() && progress.getVisibility() != View.VISIBLE ? View.VISIBLE : View.GONE);
        adapter.setItems(shown);
    }

    // ---- Toggling --------------------------------------------------------------------------

    private void onToggleRequested(final AppEntry entry) {
        if (entry.pending) {
            return;
        }
        final boolean allow = !entry.allowed;
        if (!Shizuku.isReady()) {
            Toast.makeText(this, getString(R.string.open_permissions_hint, entry.label), Toast.LENGTH_LONG).show();
            openAppSettings(entry);
            return;
        }
        if (!allow && entry.system) {
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.confirm_system_title, entry.label))
                    .setMessage(R.string.confirm_system_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.confirm_block, (d, w) -> applyChange(entry, false))
                    .show();
            return;
        }
        applyChange(entry, allow);
    }

    private void applyChange(final AppEntry entry, final boolean allow) {
        entry.pending = true;
        adapter.notifyDataSetChanged();
        final Context appContext = getApplicationContext();
        io.execute(new Runnable() {
            @Override
            public void run() {
                String error = null;
                try {
                    Shizuku.Result r = NetworkPermission.set(entry.packageName, allow);
                    if (!r.ok()) {
                        error = r.message();
                    }
                } catch (Exception e) {
                    error = e.getMessage() != null ? e.getMessage() : e.toString();
                }
                final boolean actual = NetworkPermission.isAllowed(appContext, entry.packageName);
                if (error == null && actual != allow) {
                    error = "state did not change";
                }
                final String failure = error;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        entry.pending = false;
                        entry.allowed = actual;
                        if (failure != null && !isDestroyed()) {
                            Toast.makeText(MainActivity.this,
                                    getString(R.string.change_failed, entry.label, failure), Toast.LENGTH_LONG).show();
                        }
                        applyFilter();
                    }
                });
            }
        });
    }

    private void openAppSettings(AppEntry entry) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", entry.packageName, null));
        startSafely(intent, entry.label);
    }

    // ---- Helpers ---------------------------------------------------------------------------

    private void startSafely(Intent intent, String what) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, getString(R.string.cannot_open, what), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ---- Adapter ---------------------------------------------------------------------------

    private static final class Holder {
        View row;
        ImageView icon;
        TextView label;
        TextView summary;
        View switchArea;
        Switch toggle;
        AppEntry entry;
    }

    private final class AppAdapter extends BaseAdapter {
        private List<AppEntry> items = Collections.emptyList();

        void setItems(List<AppEntry> items) {
            this.items = items;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public AppEntry getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return items.get(position).packageName.hashCode();
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        @Override
        public boolean isEnabled(int position) {
            return false; // clicks are handled by the row and switch area themselves
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final Holder h;
            if (convertView == null) {
                convertView = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
                h = new Holder();
                h.row = convertView.findViewById(R.id.row);
                h.icon = convertView.findViewById(R.id.icon);
                h.label = convertView.findViewById(R.id.label);
                h.summary = convertView.findViewById(R.id.summary);
                h.switchArea = convertView.findViewById(R.id.switch_area);
                h.toggle = convertView.findViewById(R.id.toggle);
                h.row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openAppSettings(h.entry);
                    }
                });
                h.switchArea.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onToggleRequested(h.entry);
                    }
                });
                h.switchArea.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                    @Override
                    public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        info.setClassName(Switch.class.getName());
                        info.setCheckable(true);
                        info.setChecked(h.entry != null && h.entry.allowed);
                    }
                });
                convertView.setTag(h);
            } else {
                h = (Holder) convertView.getTag();
            }

            AppEntry e = items.get(position);
            boolean rebound = h.entry != e;
            h.entry = e;
            h.icon.setImageDrawable(e.icon);
            h.label.setText(e.label);
            String state = getString(e.pending ? R.string.summary_updating
                    : e.allowed ? R.string.summary_allowed : R.string.summary_blocked);
            h.summary.setText(e.system ? state + " • " + getString(R.string.summary_system_suffix) : state);
            h.toggle.setChecked(e.allowed);
            if (rebound) {
                h.toggle.jumpDrawablesToCurrentState();
            }
            h.switchArea.setEnabled(!e.pending);
            h.toggle.setAlpha(e.pending ? 0.38f : 1f);
            h.switchArea.setContentDescription(e.label);

            int count = items.size();
            int bg = count == 1 ? R.drawable.bg_row_single
                    : position == 0 ? R.drawable.bg_row_top
                    : position == count - 1 ? R.drawable.bg_row_bottom
                    : R.drawable.bg_row_middle;
            h.row.setBackgroundResource(bg);
            return convertView;
        }
    }
}
