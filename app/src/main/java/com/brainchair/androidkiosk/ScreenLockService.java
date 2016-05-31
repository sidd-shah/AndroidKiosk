package com.brainchair.androidkiosk;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.SystemClock;
import android.support.annotation.Nullable;
import android.util.Log;

import java.util.List;
import java.util.SortedMap;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;

/**
 * Created by siddharth on 27/02/16.
 */
public class ScreenLockService extends Service {


    public static final int NOTIFICATION_ID = 16;
    public static final int WIFI_SETTINGS = 0;
    public static final int DEVICE_ADMIN_ACTIVATION = 1;
    private static final String ALLOWED_APPS = "allowed_apps";
    public static IntentFilter sKillServiceIntentFilter;
    public static boolean sIsSettingsOpen;
    private final String TAG = "ScreenLockService";
    Timer timer;
    Messenger mMessenger = new Messenger(new IncomingHandler());
    private KeyguardManager mKeyGuardManager;
    private DevicePolicyManager mDevicePolicyManager;
    private PowerManager mPowerManager;
    private ComponentName mDeviceAdmin;
    private KeyguardManager.KeyguardLock mLock;
    private Context mContext;
    private boolean mIsPasswordSet;
    private BroadcastReceiver mReceiver = null;

    private String[] mAllowedApps;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        mAllowedApps = intent.getStringArrayExtra(ALLOWED_APPS);
        return mMessenger.getBinder();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, new Notification());
        mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(TAG, "Intent Received");
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        };
        registerReceiver(mReceiver, sKillServiceIntentFilter);
        Log.d(TAG, "Service started");
        return START_NOT_STICKY;
    }

    @Override
    public void onCreate() {
        mContext = getApplicationContext();
        sKillServiceIntentFilter = new IntentFilter(getString(R.string.kill_service));
        mDevicePolicyManager = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        mPowerManager = (PowerManager) getSystemService(POWER_SERVICE);
        mDeviceAdmin = new ComponentName(mContext, DeviceAdmin.class);
        mKeyGuardManager = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        mLock = mKeyGuardManager.newKeyguardLock(getString(R.string.text_mainActivity));

        final Intent launchLockScreenIntent = new Intent(Intent.CATEGORY_LAUNCHER);
        launchLockScreenIntent.setComponent(new ComponentName(mContext.getPackageName(), ScreenLockActivity.class.getName()));
        launchLockScreenIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {

            @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
            @Override
            public void run() {
                try {
                    mLock = mKeyGuardManager.newKeyguardLock(getString(R.string.text_mainActivity));
                    mLock.disableKeyguard();
                    if (mDevicePolicyManager.isAdminActive(mDeviceAdmin)) {

                        if (mKeyGuardManager.isKeyguardLocked()
                                && mPowerManager.isScreenOn()) {
                            try {
                                mDevicePolicyManager.resetPassword("", 0);
                            } catch (Exception e) {
                                e.printStackTrace();
                            } catch (Error e) {
                                e.printStackTrace();
                            }
                            mIsPasswordSet = false;
                            mLock = mKeyGuardManager.newKeyguardLock(getString(R.string.text_mainActivity));
                            mLock.disableKeyguard();
                        } else if (!mIsPasswordSet) {
                            mDevicePolicyManager.resetPassword(ScreenLockActivity.getKioskModePassword(), 0);
                            mIsPasswordSet = true;
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } catch (Error e) {
                    e.printStackTrace();
                }

                String packageName;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    packageName = getPackageNameUsageStats();
                } else {
                    packageName = getTopPackageKitKat();
                }

                if (!packageName.equalsIgnoreCase(getString(R.string.settings_package)) && !packageName.equalsIgnoreCase(getPackageName())) {
                    sIsSettingsOpen = false;
                }
                /* If the packagename is not in the list of allowed apps start intent to the Activity */
                if (!packageName.equalsIgnoreCase(getPackageName()) &&
                        !((sIsSettingsOpen && packageName.equalsIgnoreCase(getString(R.string.settings_package))) || packageName.equalsIgnoreCase(getString(R.string.package_installer))
                                || packageName.equalsIgnoreCase(getString(R.string.teamviewer_package)) || packageName.equalsIgnoreCase(getString(R.string.playstore_package)))) {
                    Intent launchTinyOwlIntent = new Intent(ScreenLockService.this, ScreenLockActivity.class);
                    launchTinyOwlIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(launchTinyOwlIntent);
                }
            }
        }

                , 0, 500);
    }


    @TargetApi(Build.VERSION_CODES.KITKAT)
    private String getTopPackageKitKat() {
        try {
            ActivityManager am = (ActivityManager) mContext.getSystemService(ACTIVITY_SERVICE);
            List<ActivityManager.RunningTaskInfo> taskInfo = am.getRunningTasks(1);
            ComponentName componentInfo = taskInfo.get(0).topActivity;
            return componentInfo.getPackageName();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private String getPackageNameUsageStats() {
        try {
            String topPackageName = getPackageName();
            UsageStatsManager mUsageStatsManager = (UsageStatsManager) getSystemService(getString(R.string.usage_stats));
            long time = System.currentTimeMillis();
            // We get usage stats for the last 10 seconds
            List<UsageStats> stats = mUsageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 10, time);
            // Sort the stats by the last time used
            if (stats != null) {
                SortedMap<Long, UsageStats> mySortedMap = new TreeMap<>();
                for (UsageStats usageStats : stats) {
                    mySortedMap.put(usageStats.getLastTimeUsed(), usageStats);
                }
                if (mySortedMap != null && !mySortedMap.isEmpty()) {
                    topPackageName = mySortedMap.get(mySortedMap.lastKey()).getPackageName();
                }
            }
            return topPackageName;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mReceiver != null) {
            unregisterReceiver(mReceiver);
        }
        timer.cancel();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        try {
            Log.d(TAG, "On Task Removed");
            Intent restartService = new Intent(getApplicationContext(),
                    this.getClass());
            restartService.setPackage(getPackageName());
            PendingIntent restartServicePI = PendingIntent.getService(
                    getApplicationContext(), 1, restartService,
                    PendingIntent.FLAG_ONE_SHOT);

            //Restart the service once it has been killed by android
            AlarmManager alarmService = (AlarmManager) getApplicationContext().getSystemService(Context.ALARM_SERVICE);
            alarmService.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000, restartServicePI);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public boolean isAppInAllowedApps(String appName) {
        for (int i = 0; i < mAllowedApps.length; i++) {
            if (mAllowedApps[i].equalsIgnoreCase(appName)) return true;
        }
        return false;
    }

    /**
     * Incoming messages Handler
     */
    class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            Bundle bundle = msg.getData();
            switch (msg.what) {
                case WIFI_SETTINGS:
                    sIsSettingsOpen = (boolean) bundle.get(getString(R.string.is_setting_open));
                    break;
                case DEVICE_ADMIN_ACTIVATION:
                    sIsSettingsOpen = (boolean) bundle.get(getString(R.string.is_device_admin_activated));
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }
}
