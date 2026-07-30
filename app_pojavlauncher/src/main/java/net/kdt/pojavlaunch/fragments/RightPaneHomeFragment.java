package net.kdt.pojavlaunch.fragments;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.PojavProfile;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.value.MinecraftAccount;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class RightPaneHomeFragment extends Fragment {

    public static final String TAG = "RightPaneHomeFragment";
    public static final String CUSTOM_BG_PATH = Tools.DIR_DATA + "/custom_launcher_bg";

    private RecyclerView mRecyclerView;
    private HomeProfileAdapter mAdapter;

    // Dashboard header views
    private TextView mHeroUsername;
    private TextView mHeroVersion;
    private View mHeroLaunch;
    private TextView mStatProfilesValue;
    private TextView mStatModsValue;
    private TextView mStatMemoryValue;
    private TextView mStatMemoryLabel;
    private TextView mSysInfoJava;
    private TextView mSysInfoOs;
    private TextView mSysInfoArch;
    private TextView mSysInfoMemory;

    /** The most-recently-used profile + its key, kept for the hero Launch button. */
    private String mTopProfileKey;
    private MinecraftProfile mTopProfile;

    public RightPaneHomeFragment() {
        super(R.layout.fragment_right_pane_home);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        loadCustomWallpaper(view);

        mHeroUsername = view.findViewById(R.id.tv_hero_username);
        mHeroVersion = view.findViewById(R.id.tv_hero_version);
        mHeroLaunch = view.findViewById(R.id.btn_hero_launch);
        mStatProfilesValue = view.findViewById(R.id.tv_stat_profiles_value);
        mStatModsValue = view.findViewById(R.id.tv_stat_mods_value);
        mStatMemoryValue = view.findViewById(R.id.tv_stat_memory_value);
        mStatMemoryLabel = view.findViewById(R.id.tv_stat_memory_label);
        mSysInfoJava = view.findViewById(R.id.tv_sysinfo_java);
        mSysInfoOs = view.findViewById(R.id.tv_sysinfo_os);
        mSysInfoArch = view.findViewById(R.id.tv_sysinfo_arch);
        mSysInfoMemory = view.findViewById(R.id.tv_sysinfo_memory);

        bindSystemInfo();
        bindMemoryStat();
        bindHeroUsername();

        mRecyclerView = view.findViewById(R.id.rv_home_profiles);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        mRecyclerView.setNestedScrollingEnabled(false);
        setupProfileAdapter();

        if (mHeroLaunch != null) {
            mHeroLaunch.setOnClickListener(v -> {
                if (mTopProfileKey == null || mTopProfile == null) {
                    // No profile yet — send the user to create one, same as the FAB.
                    View fab = getView() != null ? getView().findViewById(R.id.fab_create_profile) : null;
                    if (fab != null) fab.performClick();
                    return;
                }
                LauncherPreferences.DEFAULT_PREF.edit()
                        .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, mTopProfileKey)
                        .apply();
                ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
            });
        }

        // Floating "+" FAB opens the Version Setup Hub (3-category grid)
        View fab = view.findViewById(R.id.fab_create_profile);
        if (fab != null) {
            // Apply 200ms scale-up reveal with DecelerateInterpolator
            fab.setScaleX(0.6f);
            fab.setScaleY(0.6f);
            fab.setAlpha(0f);
            // Enable hardware layer during animation to reduce jank on low-end devices
            fab.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            fab.animate()
                    .scaleX(1f).scaleY(1f)
                    .alpha(1f)
                    .setDuration(200)
                    .setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> fab.setLayerType(View.LAYER_TYPE_NONE, null))
                    .start();

            fab.setOnClickListener(v -> {
                if (!isAdded() || getContext() == null) return;
                Fragment parent = getParentFragment();
                if (parent instanceof MainMenuFragment) {
                    ((MainMenuFragment) parent).openChildPane(
                            ProfileTypeSelectFragment.class,
                            ProfileTypeSelectFragment.TAG, null);
                } else if (getActivity() != null) {
                    Tools.swapFragment(getActivity(),
                            ProfileTypeSelectFragment.class,
                            ProfileTypeSelectFragment.TAG, null);
                }
            });
        }

        View refreshBtn = view.findViewById(R.id.btn_refresh_profiles);
        if (refreshBtn != null) {
            refreshBtn.setOnClickListener(v -> {
                setupProfileAdapter();
                bindMemoryStat();
                Toast.makeText(getContext(), "Profiles refreshed", Toast.LENGTH_SHORT).show();
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        setupProfileAdapter();
        bindMemoryStat();
    }

    public void reloadBackground() {
        View v = getView();
        if (v != null) loadCustomWallpaper(v);
    }

    private void setupProfileAdapter() {
        // Load profiles on background thread to avoid blocking the UI thread on file I/O
        LauncherProfiles.loadAsync(() -> {
            if (!isAdded() || getContext() == null) return;

            Map<String, MinecraftProfile> profilesMap = LauncherProfiles.mainProfileJson != null
                    ? LauncherProfiles.mainProfileJson.profiles : null;

            // Note: Do NOT drop all cached icons here — doing so recycles the underlying
            // bitmaps while RecyclerView items may still be drawing them, causing
            // "Canvas: trying to use a recycled bitmap" crashes.
            // Per-profile icon invalidation happens in ProfileEditorFragment.dropIcon()
            // when the user actually edits a profile's icon.

            List<String> keys = new ArrayList<>();
            List<MinecraftProfile> profiles = new ArrayList<>();

            if (profilesMap != null) {
                List<Map.Entry<String, MinecraftProfile>> entries =
                        new ArrayList<>(profilesMap.entrySet());
                // Sort by lastUsed descending (most recently used first)
                Collections.sort(entries, (a, b) -> {
                    String ua = a.getValue().lastUsed != null ? a.getValue().lastUsed : "";
                    String ub = b.getValue().lastUsed != null ? b.getValue().lastUsed : "";
                    return ub.compareTo(ua);
                });
                for (Map.Entry<String, MinecraftProfile> entry : entries) {
                    String key = entry.getKey();
                    MinecraftProfile profile = entry.getValue();
                    // Skip invalid/corrupted profiles
                    if (key == null || key.isEmpty()) continue;
                    if (profile == null) continue;
                    if (profile.name == null || profile.name.trim().isEmpty()) continue;
                    keys.add(key);
                    profiles.add(profile);
                }
            }

            // ── Dashboard header: real Quick Stats + hero Launch target ──
            mTopProfileKey = !keys.isEmpty() ? keys.get(0) : null;
            mTopProfile = !profiles.isEmpty() ? profiles.get(0) : null;

            if (mStatProfilesValue != null) {
                mStatProfilesValue.setText(String.valueOf(profiles.size()));
            }
            if (mHeroVersion != null) {
                mHeroVersion.setText(mTopProfile != null && mTopProfile.lastVersionId != null
                        && !mTopProfile.lastVersionId.isEmpty()
                        ? mTopProfile.lastVersionId : "No profile");
            }

            mAdapter = new HomeProfileAdapter(keys, profiles,
                    new HomeProfileAdapter.OnProfileActionListener() {
                @Override
                public void onProfilePlay(String profileKey, MinecraftProfile profile) {
                    LauncherPreferences.DEFAULT_PREF.edit()
                            .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, profileKey)
                            .apply();
                    ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
                }

                @Override
                public void onProfileEdit(String profileKey, MinecraftProfile profile) {
                    LauncherPreferences.DEFAULT_PREF.edit()
                            .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, profileKey)
                            .apply();
                    Tools.swapFragment(requireActivity(),
                            ProfileEditorFragment.class, ProfileEditorFragment.TAG, null);
                }
            });

            // Real aggregate mod count, filled in once the adapter finishes scanning
            // every profile's /mods folder on a background thread.
            mAdapter.setOnModCountsLoadedListener(() -> {
                if (mStatModsValue != null && mAdapter != null) {
                    mStatModsValue.setText(String.valueOf(mAdapter.getTotalModCount()));
                }
            });

            mRecyclerView.setAdapter(mAdapter);
        });
    }

    /** Real device Java runtime / Android version / architecture — no placeholder values. */
    private void bindSystemInfo() {
        if (mSysInfoOs != null) {
            mSysInfoOs.setText("Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        }
        if (mSysInfoArch != null) {
            String[] abis = Build.SUPPORTED_ABIS;
            mSysInfoArch.setText(abis != null && abis.length > 0 ? abis[0] : "Unknown");
        }
        if (mSysInfoJava != null) {
            PojavApplication.sExecutorService.execute(() -> {
                String javaLabel = "Not installed";
                try {
                    List<net.kdt.pojavlaunch.multirt.Runtime> runtimes =
                            net.kdt.pojavlaunch.multirt.MultiRTUtils.getInstalledRuntimes();
                    if (runtimes != null && !runtimes.isEmpty()) {
                        net.kdt.pojavlaunch.multirt.Runtime rt = runtimes.get(0);
                        javaLabel = rt.versionString != null && !rt.versionString.isEmpty()
                                ? rt.name + " " + rt.versionString : rt.name;
                    }
                } catch (Throwable ignored) {}
                String finalLabel = javaLabel;
                Tools.runOnUiThread(() -> {
                    if (isAdded() && mSysInfoJava != null) mSysInfoJava.setText(finalLabel);
                });
            });
        }
    }

    /** Real device memory usage, shown both as a Quick Stat percentage and in System Info. */
    private void bindMemoryStat() {
        if (getContext() == null) return;
        ActivityManager am = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return;
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(info);
        long totalMb = info.totalMem / (1024 * 1024);
        long availMb = info.availMem / (1024 * 1024);
        long usedMb = totalMb - availMb;
        int usedPercent = totalMb > 0 ? (int) ((usedMb * 100) / totalMb) : 0;

        if (mStatMemoryValue != null) mStatMemoryValue.setText(usedPercent + "%");
        if (mSysInfoMemory != null) {
            mSysInfoMemory.setText(String.format(Locale.getDefault(), "%.1f GB", totalMb / 1024f));
        }
    }

    /** Real signed-in account name for the hero greeting, falls back to "Player". */
    private void bindHeroUsername() {
        if (mHeroUsername == null || getContext() == null) return;
        PojavApplication.sExecutorService.execute(() -> {
            String username = "Player";
            try {
                MinecraftAccount account = PojavProfile.getCurrentProfileContent(getContext(), null);
                if (account != null && account.username != null && !account.username.isEmpty()) {
                    username = account.username;
                }
            } catch (Throwable ignored) {}
            String finalUsername = username;
            Tools.runOnUiThread(() -> {
                if (isAdded() && mHeroUsername != null) mHeroUsername.setText(finalUsername);
            });
        });
    }

    private void loadCustomWallpaper(@NonNull View view) {
        ImageView wallpaper = view.findViewById(R.id.right_pane_wallpaper);
        File bgFile = new File(CUSTOM_BG_PATH);
        if (bgFile.exists()) {
            Drawable d = Drawable.createFromPath(bgFile.getAbsolutePath());
            if (d != null) {
                wallpaper.setImageDrawable(d);
                wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
                wallpaper.setBackground(null);
                wallpaper.setVisibility(View.VISIBLE);
                return;
            }
        }
        wallpaper.setImageDrawable(null);
        wallpaper.setBackground(null);
        wallpaper.setVisibility(View.GONE);
    }
}
