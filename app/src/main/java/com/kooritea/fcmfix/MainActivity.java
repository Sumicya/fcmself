package com.kooritea.fcmfix;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.kooritea.fcmfix.config.FcmfixConfig;
import com.kooritea.fcmfix.util.IceboxUtils;

/**
 * fcmfix 设置界面：
 * - 管理 FCM 目标应用白名单（写入 LSPosed 远程配置 "config" 组）；
 * - 开关：阻止自动清除通知 / 唤醒被 IceBox 冻结的应用；
 * - 菜单：全选含 FCM 的应用、打开 GMS FCM Diagnostics。
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "fcmfix.MainActivity";
    /** 应用列表加载延时（等待权限/包扫描完成） */
    private static final long APP_LIST_DELAY_MS = 1000L;

    private static final String MENU_DISABLE_AUTO_CLEAN = "阻止应用停止时自动清除通知";
    private static final String MENU_INCLUDE_ICEBOX = "允许唤醒被冰箱冻结的应用";
    private static final String MENU_SELECT_ALL_FCM = "全选包含 FCM 的应用";
    private static final String MENU_OPEN_FCM_DIAGNOSTICS = "打开FCM Diagnostics";

    private AppListAdapter appListAdapter;
    private static XposedService xposedService;
    private final Set<String> allowList = new HashSet<>();
    private final JSONObject config = new JSONObject();

    // ------------------------------------------------------------------
    // 远程配置读写（LSPosed XposedService）
    // ------------------------------------------------------------------

    private SharedPreferences getRemotePreferencesOrNull() {
        if (xposedService == null) {
            return null;
        }
        try {
            return xposedService.getRemotePreferences(FcmfixConfig.REMOTE_PREFS_GROUP);
        } catch (Throwable e) {
            Log.e(TAG, "getRemotePreferences: " + e);
            return null;
        }
    }

    private void initXposedService() {
        try {
            XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
                @Override
                public void onServiceBind(@NonNull XposedService service) {
                    xposedService = service;
                    runOnUiThread(() -> {
                        loadConfigFromRemotePreferences();
                        if (appListAdapter != null) {
                            appListAdapter.notifyDataSetChanged();
                        }
                    });
                }

                @Override
                public void onServiceDied(@NonNull XposedService service) {
                    if (xposedService == service) {
                        xposedService = null;
                    }
                }
            });
        } catch (Throwable e) {
            Log.e(TAG, "initXposedService: " + e);
        }
    }

    private void ensureDefaultConfigValues() {
        try {
            if (!this.config.has(FcmfixConfig.KEY_ALLOW_LIST)) {
                this.config.put(FcmfixConfig.KEY_ALLOW_LIST, new JSONArray());
            }
            if (!this.config.has(FcmfixConfig.KEY_DISABLE_AUTO_CLEAN_NOTIFICATION)) {
                this.config.put(FcmfixConfig.KEY_DISABLE_AUTO_CLEAN_NOTIFICATION, false);
            }
            if (!this.config.has(FcmfixConfig.KEY_INCLUDE_ICEBOX_DISABLE_APP)) {
                this.config.put(FcmfixConfig.KEY_INCLUDE_ICEBOX_DISABLE_APP, false);
            }
            if (!this.config.has(FcmfixConfig.KEY_NO_RESPONSE_NOTIFICATION)) {
                this.config.put(FcmfixConfig.KEY_NO_RESPONSE_NOTIFICATION, false);
            }
        } catch (JSONException e) {
            Log.e(TAG, "ensureDefaultConfig: " + e);
        }
    }

    private void loadConfigFromRemotePreferences() {
        ensureDefaultConfigValues();
        SharedPreferences pref = getRemotePreferencesOrNull();
        if (pref == null) {
            return;
        }
        this.allowList.clear();
        this.allowList.addAll(pref.getStringSet(FcmfixConfig.KEY_ALLOW_LIST, new HashSet<>()));
        try {
            this.config.put(FcmfixConfig.KEY_ALLOW_LIST, new JSONArray(this.allowList));
            this.config.put(FcmfixConfig.KEY_DISABLE_AUTO_CLEAN_NOTIFICATION,
                    pref.getBoolean(FcmfixConfig.KEY_DISABLE_AUTO_CLEAN_NOTIFICATION, false));
            this.config.put(FcmfixConfig.KEY_INCLUDE_ICEBOX_DISABLE_APP,
                    pref.getBoolean(FcmfixConfig.KEY_INCLUDE_ICEBOX_DISABLE_APP, false));
            this.config.put(FcmfixConfig.KEY_NO_RESPONSE_NOTIFICATION,
                    pref.getBoolean(FcmfixConfig.KEY_NO_RESPONSE_NOTIFICATION, false));
        } catch (JSONException e) {
            Log.e(TAG, "loadRemoteConfig: " + e);
        }
    }

    private void updateConfig() {
        try {
            SharedPreferences pref = getRemotePreferencesOrNull();
            if (pref == null) {
                throw new IllegalStateException("XposedService 未连接，无法写入远程配置");
            }
            this.config.put(FcmfixConfig.KEY_ALLOW_LIST, new JSONArray(this.allowList));
            boolean saved = pref.edit()
                    .putBoolean("init", true)
                    .putStringSet(FcmfixConfig.KEY_ALLOW_LIST, new HashSet<>(this.allowList))
                    .putBoolean(FcmfixConfig.KEY_DISABLE_AUTO_CLEAN_NOTIFICATION, this.config.getBoolean(FcmfixConfig.KEY_DISABLE_AUTO_CLEAN_NOTIFICATION))
                    .putBoolean(FcmfixConfig.KEY_INCLUDE_ICEBOX_DISABLE_APP, this.config.getBoolean(FcmfixConfig.KEY_INCLUDE_ICEBOX_DISABLE_APP))
                    .putBoolean(FcmfixConfig.KEY_NO_RESPONSE_NOTIFICATION, this.config.getBoolean(FcmfixConfig.KEY_NO_RESPONSE_NOTIFICATION))
                    .commit();
            if (!saved) {
                throw new IllegalStateException("配置写入失败");
            }
            // 通知各进程（system_server 等）重新加载配置
            this.sendBroadcast(new Intent(FcmfixConfig.ACTION_UPDATE_CONFIG));
        } catch (Throwable e) {
            Log.e(TAG, "updateConfig: " + e);
            new AlertDialog.Builder(this).setTitle("更新配置文件失败").setMessage(e.getMessage()).show();
        }
    }

    // ------------------------------------------------------------------
    // 应用列表
    // ------------------------------------------------------------------

    private class AppInfo {
        public String name;
        public String packageName;
        public Drawable icon;
        public boolean isAllow = false;
        public boolean includeFcm = false;

        public AppInfo(PackageInfo packageInfo) {
            this.name = packageInfo.applicationInfo.loadLabel(getPackageManager()).toString();
            this.packageName = packageInfo.packageName;
            this.icon = packageInfo.applicationInfo.loadIcon(getPackageManager());
        }
    }

    private class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.ViewHolder> {

        private final List<AppInfo> mAppList;

        class ViewHolder extends RecyclerView.ViewHolder {
            View appView;
            ImageView icon;
            TextView name;
            TextView packageName;
            TextView includeFcm;
            CheckBox isAllow;

            public ViewHolder(View view) {
                super(view);
                appView = view;
                icon = view.findViewById(R.id.icon);
                name = view.findViewById(R.id.name);
                packageName = view.findViewById(R.id.packageName);
                includeFcm = view.findViewById(R.id.includeFcm);
                isAllow = view.findViewById(R.id.isAllow);
            }
        }

        public AppListAdapter() {
            Set<String> allowListSet = new HashSet<>(allowList);
            List<AppInfo> allowApps = new ArrayList<>();
            List<AppInfo> notAllowApps = new ArrayList<>();
            List<AppInfo> noFcmApps = new ArrayList<>();
            PackageManager packageManager = getPackageManager();
            for (PackageInfo packageInfo : packageManager.getInstalledPackages(
                    PackageManager.GET_RECEIVERS | PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_UNINSTALLED_PACKAGES)) {
                if (packageInfo.receivers == null) {
                    continue;
                }
                AppInfo appInfo = new AppInfo(packageInfo);
                for (ActivityInfo receiverInfo : packageInfo.receivers) {
                    // 含 Firebase 推送接收器的应用才可能使用 FCM
                    if (receiverInfo.name.equals("com.google.firebase.iid.FirebaseInstanceIdReceiver")
                            || receiverInfo.name.equals("com.google.android.gms.measurement.AppMeasurementReceiver")) {
                        appInfo.includeFcm = true;
                        break;
                    }
                }
                if (allowListSet.contains(appInfo.packageName)) {
                    appInfo.isAllow = true;
                    allowApps.add(appInfo);
                } else if (appInfo.includeFcm) {
                    notAllowApps.add(appInfo);
                } else {
                    noFcmApps.add(appInfo);
                }
            }
            final Comparator<AppInfo> sortName = (a1, a2) ->
                    Collator.getInstance(Locale.getDefault()).compare(a1.name, a2.name);
            allowApps.sort(sortName);
            notAllowApps.sort(sortName);
            noFcmApps.sort(sortName);
            allowApps.addAll(notAllowApps);
            allowApps.addAll(noFcmApps);
            this.mAppList = allowApps;
            if (mAppList.isEmpty()
                    || (mAppList.size() == 1 && FcmfixConfig.SELF_PACKAGE.equals(mAppList.get(0).packageName))) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("请在系统设置中授予读取应用列表权限")
                        .setMessage("或直接编辑" + getApplicationContext().getFilesDir().getAbsolutePath() + "/config.json(需重启生效)")
                        .setPositiveButton("确定", (dialog, which) -> {
                        })
                        .show();
            }
        }

        @SuppressLint("NotifyDataSetChanged")
        @NonNull
        @Override
        public AppListAdapter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.app_item, parent, false);
            final ViewHolder holder = new ViewHolder(view);
            holder.appView.setOnClickListener(v -> {
                int position = holder.getBindingAdapterPosition();
                AppInfo appInfo = mAppList.get(position);
                appInfo.isAllow = !appInfo.isAllow;
                if (appInfo.isAllow) {
                    addAppInAllowList(appInfo.packageName);
                } else {
                    deleteAppInAllowList(appInfo.packageName);
                }
                appListAdapter.notifyDataSetChanged();
            });
            return holder;
        }

        @Override
        public void onBindViewHolder(@NonNull AppListAdapter.ViewHolder holder, int position) {
            AppInfo appInfo = mAppList.get(position);
            holder.icon.setImageDrawable(appInfo.icon);
            holder.name.setText(appInfo.name);
            holder.packageName.setText(appInfo.packageName);
            holder.includeFcm.setVisibility(appInfo.includeFcm ? View.VISIBLE : View.GONE);
            holder.isAllow.setChecked(appInfo.isAllow);
        }

        @Override
        public int getItemCount() {
            return mAppList.size();
        }
    }

    private void addAppInAllowList(String packageName) {
        this.allowList.add(packageName);
        this.updateConfig();
    }

    private void deleteAppInAllowList(String packageName) {
        this.allowList.remove(packageName);
        this.updateConfig();
    }

    // ------------------------------------------------------------------
    // Activity
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        RecyclerView recyclerView = findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        initXposedService();

        try {
            if (ContextCompat.checkSelfPermission(this, IceboxUtils.SDK_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{IceboxUtils.SDK_PERMISSION}, IceboxUtils.REQUEST_CODE);
            }
        } catch (Throwable ignored) {
        }

        new Handler().postDelayed(() -> {
            appListAdapter = new AppListAdapter();
            recyclerView.setAdapter(appListAdapter);
            findViewById(R.id.progress_bar).setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }, APP_LIST_DELAY_MS);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(MENU_DISABLE_AUTO_CLEAN).setCheckable(true);
        menu.add(MENU_INCLUDE_ICEBOX).setCheckable(true);
        menu.add(MENU_SELECT_ALL_FCM);
        menu.add(MENU_OPEN_FCM_DIAGNOSTICS);
        return true;
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public final boolean onPrepareOptionsMenu(Menu menu) {
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            if (MENU_DISABLE_AUTO_CLEAN.equals(item.getTitle())) {
                try {
                    item.setChecked(this.config.getBoolean(FcmfixConfig.KEY_DISABLE_AUTO_CLEAN_NOTIFICATION));
                } catch (JSONException e) {
                    item.setChecked(false);
                }
            }
            if (MENU_INCLUDE_ICEBOX.equals(item.getTitle())) {
                try {
                    item.setChecked(this.config.getBoolean(FcmfixConfig.KEY_INCLUDE_ICEBOX_DISABLE_APP));
                } catch (JSONException e) {
                    item.setChecked(false);
                }
            }
            if (MENU_SELECT_ALL_FCM.equals(item.getTitle())) {
                item.setOnMenuItemClickListener(menuItem -> {
                    for (AppInfo appInfo : appListAdapter.mAppList) {
                        if (appInfo.includeFcm) {
                            addAppInAllowList(appInfo.packageName);
                            appInfo.isAllow = true;
                        }
                    }
                    appListAdapter.notifyDataSetChanged();
                    return false;
                });
            }
            if (MENU_OPEN_FCM_DIAGNOSTICS.equals(item.getTitle())) {
                item.setOnMenuItemClickListener(menuItem -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.setPackage("com.google.android.gms");
                    intent.setComponent(new ComponentName("com.google.android.gms", "com.google.android.gms.gcm.GcmDiagnostics"));
                    startActivity(intent);
                    return false;
                });
            }
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public final boolean onOptionsItemSelected(MenuItem menuItem) {
        String title = menuItem.getTitle().toString();
        if (MENU_DISABLE_AUTO_CLEAN.equals(title)) {
            try {
                this.config.put(FcmfixConfig.KEY_DISABLE_AUTO_CLEAN_NOTIFICATION, !menuItem.isChecked());
                this.updateConfig();
            } catch (JSONException e) {
                Log.e(TAG, "onOptionsItemSelected: " + e);
            }
        }
        if (MENU_INCLUDE_ICEBOX.equals(title)) {
            try {
                this.config.put(FcmfixConfig.KEY_INCLUDE_ICEBOX_DISABLE_APP, !menuItem.isChecked());
                this.updateConfig();
            } catch (JSONException e) {
                Log.e(TAG, "onOptionsItemSelected: " + e);
            }
        }
        return true;
    }
}
