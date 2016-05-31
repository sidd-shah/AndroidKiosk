package com.brainchair.androidkiosk;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.provider.Settings;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.WindowManager;

public class ScreenLockActivity extends AppCompatActivity {

    public static final int DEVICE_ADMIN_REQUEST_CODE = 100;
    private static CustomViewGroup sStatusBarOverlay;

    private boolean mIsInLockMode;
    private boolean mIsServiceBound = false;

    private ComponentName mDeviceAdmin;
    private DevicePolicyManager mDevicePolicyManager;
    private WindowManager mWindowManager;
    private Intent mScreenLockServiceIntent;
    private Messenger mMessenger;
    private IBinder mBinderService;
    private ServiceConnection mServiceConnection;

    public static String getKioskModePassword() {
        //TODO Override this method
        return null;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        mWindowManager = (WindowManager) getApplicationContext().getSystemService(WINDOW_SERVICE);
        mDevicePolicyManager = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        mDeviceAdmin = new ComponentName(this, DeviceAdmin.class);
        mServiceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                // Create the Messenger object
                mBinderService = service;
                mMessenger = new Messenger(service);
                mIsServiceBound = true;
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                mMessenger = null;
                mIsServiceBound = false;
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);


            /* Launch the intent to enable device administrator if it is not activated yet otherwise
             launch the main activity */
            if (!mDevicePolicyManager.isAdminActive(mDeviceAdmin)) {
                Intent enableDeviceAdminIntent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                enableDeviceAdminIntent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, mDeviceAdmin);
                enableDeviceAdminIntent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivityForResult(enableDeviceAdminIntent, DEVICE_ADMIN_REQUEST_CODE);

                if (isKioskModeEnabled() && !mIsInLockMode) {
                    notifyDeviceAdminActivationStatus(true);
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && !isUsageStatsEnabled()) {
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            }
            if (isKioskModeEnabled() && !mIsInLockMode) {
                enterKioskMode();
            }
        } catch (Exception e) {
            e.printStackTrace();
        } catch (Error e) {
            e.printStackTrace();
        }
    }
    @Override
    protected void onPause() {
        super.onPause();
        if (isKioskModeEnabled() && mIsInLockMode) {
            ActivityManager activityManager = (ActivityManager) getApplicationContext()
                    .getSystemService(Context.ACTIVITY_SERVICE);
            activityManager.moveTaskToFront(getTaskId(), 0);
        }
    }

    public void exitKioskMode() {
        mIsInLockMode = false;
        removeCustomOverlay();
        unbindService(mServiceConnection);
        mIsServiceBound = false;
        Intent i = new Intent(getString(R.string.kill_service));
        sendBroadcast(i);
        mDevicePolicyManager.resetPassword("", 0);
    }

    public void enterKioskMode() {
        mIsInLockMode = true;
        if (sStatusBarOverlay == null) {
            sStatusBarOverlay = new CustomViewGroup(this);
            addCustomOverlay();
        }
        mScreenLockServiceIntent = new Intent(this, ScreenLockService.class);
        startService(mScreenLockServiceIntent);
        mIsServiceBound = bindService(mScreenLockServiceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
        try {
            mDevicePolicyManager.resetPassword(getString(R.string.text_password), 0);
        } catch (SecurityException se) {
            mIsInLockMode = false;
            se.printStackTrace();
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private boolean isUsageStatsEnabled() {
        try {
            PackageManager packageManager = getPackageManager();
            ApplicationInfo applicationInfo = packageManager.getApplicationInfo(getPackageName(), 0);
            AppOpsManager appOpsManager = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = appOpsManager.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, applicationInfo.uid, applicationInfo.packageName);
            return (mode == AppOpsManager.MODE_ALLOWED);

        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case DEVICE_ADMIN_REQUEST_CODE:
                if (resultCode == -1) { //When user activates Device Admin
                    notifyDeviceAdminActivationStatus(false);
                } else if (resultCode == 0) { //When user cancels Device Admin Dialog
                    mIsInLockMode = false;
                }
                break;
            default:
                break;
        }
    }

    public void addCustomOverlay() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.type = WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
        params.gravity = Gravity.TOP;
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height = (int) (50 * getResources().getDisplayMetrics().scaledDensity);
        params.format = PixelFormat.TRANSPARENT;
            /* Add a view on top of the status bar to intercept touch events and disable the default status bar functionality */
        mWindowManager.addView(sStatusBarOverlay, params);
    }

    public void removeCustomOverlay() {
        mWindowManager.removeView(sStatusBarOverlay);
        sStatusBarOverlay = null;
    }

    public Messenger getMessenger() {
        if (mMessenger == null) {
            if (mBinderService != null) {
                mMessenger = new Messenger(mBinderService);
            } else {
                if (isKioskModeEnabled() && !mIsInLockMode) {
                    enterKioskMode();
                }
            }
        }
        return mMessenger;
    }

    public boolean isInLockMode() {
        return mIsInLockMode;
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void notifyDeviceAdminActivationStatus(boolean status) {
        Message message = Message.obtain();
        message.what = ScreenLockService.DEVICE_ADMIN_ACTIVATION;
        Bundle bundle = new Bundle();
        bundle.putBoolean(getString(R.string.is_device_admin_activated), status);
        message.setData(bundle);
        try {
            getMessenger().send(message);
        } catch (RemoteException e) {
            e.printStackTrace();
        } catch (NullPointerException npe) {
            npe.printStackTrace();
        }
    }

    public boolean isKioskModeEnabled() {
        return true;
    }
    class CustomViewGroup extends ViewGroup {

        public CustomViewGroup(Context context) {
            super(context);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {

        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            return true;
        }


    }
}
