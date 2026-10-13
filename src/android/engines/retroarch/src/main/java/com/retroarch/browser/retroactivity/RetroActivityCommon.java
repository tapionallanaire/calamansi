// RetroArch v1.22.2 Android frontend; GPL-3.0-or-later, see upstream COPYING.
package com.retroarch.browser.retroactivity;

import org.calamansi.retroarch.BuildConfig;
import org.calamansi.engine.EngineFiles;
import org.calamansi.engine.EngineId;


import android.annotation.TargetApi;
import android.app.NativeActivity;
import android.content.res.Configuration;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.view.accessibility.AccessibilityManager;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.Surface;
import android.view.WindowManager;
import android.app.UiModeManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.util.Log;


import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Class which provides common methods for RetroActivity related classes.
 */
public class RetroActivityCommon extends NativeActivity
{
  static {
    System.loadLibrary("retroarch-activity");
  }

  public static final int FRONTEND_POWERSTATE_NONE = 0;
  public static final int FRONTEND_POWERSTATE_NO_SOURCE = 1;
  public static final int FRONTEND_POWERSTATE_CHARGING = 2;
  public static final int FRONTEND_POWERSTATE_CHARGED = 3;
  public static final int FRONTEND_POWERSTATE_ON_POWER_SOURCE = 4;
  public static final int FRONTEND_ORIENTATION_0 = 0;
  public static final int FRONTEND_ORIENTATION_90 = 1;
  public static final int FRONTEND_ORIENTATION_180 = 2;
  public static final int FRONTEND_ORIENTATION_270 = 3;
  public static final int RETRO_RUMBLE_STRONG = 0;
  public static final int RETRO_RUMBLE_WEAK = 1;
  public static final int REQUEST_CODE_OPEN_DOCUMENT_TREE = 0;
  public boolean sustainedPerformanceMode = true;
  public int screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;


  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent intent)
  {
    if (intent == null)
      return;

    switch (requestCode)
    {
      case REQUEST_CODE_OPEN_DOCUMENT_TREE:
        {
          Uri uri = intent.getData();
          if (uri == null)
            break;
          if (Build.VERSION.SDK_INT >= 19)
            getContentResolver().takePersistableUriPermission(uri, intent.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
          safTreeAdded(uri.toString());
        }
        break;

      default:
        break;
    }
  }

  public void requestOpenDocumentTree()
  {
    startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_CODE_OPEN_DOCUMENT_TREE);
  }

  public void doVibrate(int id, int effect, int strength, int oneShot)
  {
    Vibrator vibrator = null;
    int repeat = 0;
    long[] pattern = {0, 16};
    int[] strengths = {0, strength};

    if (Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN) {
      if (id == -1)
        vibrator = (Vibrator)getSystemService(Context.VIBRATOR_SERVICE);
      else
      {
        InputDevice dev = InputDevice.getDevice(id);

        if (dev != null)
          vibrator = dev.getVibrator();
      }
    }

    if (vibrator == null)
      return;

    if (strength == 0) {
      vibrator.cancel();
      return;
    }

    if (oneShot > 0)
      repeat = -1;
    else
      pattern[1] = 1000;

    if (Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
      if (id >= 0)
        Log.i("RetroActivity", "Vibrate id " + id + ": strength " + strength);

      vibrator.vibrate(VibrationEffect.createWaveform(pattern, strengths, repeat), new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
    }else{
      vibrator.vibrate(pattern, repeat);
    }
  }

  public void doHapticFeedback(int effect)
  {
    getWindow().getDecorView().performHapticFeedback(effect,
        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING | HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
    Log.i("RetroActivity", "Haptic Feedback effect " + effect);
  }

  // The native worker notifies Java; NativeActivity destruction joins it.
  public void onRetroArchExit()
  {
      finish();
  }

  public int getVolumeCount()
  {
    int ret = 0;

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      StorageManager storageManager = (StorageManager) getApplicationContext().getSystemService(Context.STORAGE_SERVICE);
      List<StorageVolume> storageVolumeList = storageManager.getStorageVolumes();

      for (int i = 0; i < storageVolumeList.size(); i++) {
        ret++;
      }
      Log.i("RetroActivity", "volume count: " + ret);
    }

    return (int)ret;
  }

  public String getVolumePath(String input)
  {
    String ret = "";

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      int index = Integer.valueOf(input);
      int j = 0;

      StorageManager storageManager = (StorageManager) getApplicationContext().getSystemService(Context.STORAGE_SERVICE);
      List<StorageVolume> storageVolumeList = storageManager.getStorageVolumes();

      for (int i = 0; i < storageVolumeList.size(); i++) {
        if (i == j) {
          ret = String.valueOf(storageVolumeList.get(index).getDirectory());
        }
      }
      Log.i("RetroActivity", "volume path: " + ret);
    }

    return ret;
  }

  public String[] getPersistedSafTrees()
  {
    if (Build.VERSION.SDK_INT >= 19)
    {
      List<android.content.UriPermission> uriPermissions = getContentResolver().getPersistedUriPermissions();
      List<String> trees = new ArrayList<>();
      for (android.content.UriPermission uriPermission : uriPermissions)
      {
        Uri uri = uriPermission.getUri();
        if (uri != null)
          trees.add(uri.toString());
      }
      return trees.toArray(new String[0]);
    }
    else
      return new String[0];
  }

// https://stackoverflow.com/questions/4553650/how-to-check-device-natural-default-orientation-on-android-i-e-get-landscape/4555528#4555528
  public int getDeviceDefaultOrientation() {
    WindowManager windowManager = (WindowManager)getSystemService(Context.WINDOW_SERVICE);
    Configuration config = getResources().getConfiguration();
    int rotation = windowManager.getDefaultDisplay().getRotation();

    if (((rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) &&
        config.orientation == Configuration.ORIENTATION_LANDSCAPE)
        || ((rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) &&
        config.orientation == Configuration.ORIENTATION_PORTRAIT))
    {
      return Configuration.ORIENTATION_LANDSCAPE;
    }else{
      return Configuration.ORIENTATION_PORTRAIT;
    }
  }

  public void setScreenOrientation(int orientation)
  {
    int naturalOrientation = getDeviceDefaultOrientation();
    int newOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

    // We assume no device has a natural orientation that is reversed
    switch (naturalOrientation) {
      case Configuration.ORIENTATION_PORTRAIT:
      {
        if (orientation == FRONTEND_ORIENTATION_0) {
          newOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        }else if (orientation == FRONTEND_ORIENTATION_90) {
          newOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        }else if (orientation == FRONTEND_ORIENTATION_180) {
          newOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
        }else if (orientation == FRONTEND_ORIENTATION_270) {
          newOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
        }
        break;
      }
      case Configuration.ORIENTATION_LANDSCAPE:
      {
        if (orientation == FRONTEND_ORIENTATION_0) {
          newOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        }else if (orientation == FRONTEND_ORIENTATION_90) {
          newOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
        }else if (orientation == FRONTEND_ORIENTATION_180) {
          newOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
        }else if (orientation == FRONTEND_ORIENTATION_270) {
          newOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        }
        break;
      }
    }

    screenOrientation = newOrientation;

    Log.i("RetroActivity", "setting new orientation to " + screenOrientation);

    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        setRequestedOrientation(screenOrientation);
      }
    });
  }

  public String getUserLanguageString()
  {
    String lang = Locale.getDefault().getLanguage();
    String country = Locale.getDefault().getCountry();

    if (lang.length() == 0)
      return "en";

    if (country.length() == 0)
      return lang;

    return lang + '_' + country;
  }

  @TargetApi(24)
  public void setSustainedPerformanceMode(boolean on)
  {
    sustainedPerformanceMode = on;

    if (Build.VERSION.SDK_INT >= 24) {
      if (isSustainedPerformanceModeSupported()) {
        // Never wait for the UI: it may be waiting for NativeActivity's pause/destroy acknowledgement.
        runOnUiThread(() -> getWindow().setSustainedPerformanceMode(on));
      }
    }
  }

  @TargetApi(24)
  public boolean isSustainedPerformanceModeSupported()
  {
    boolean supported = false;

    if (Build.VERSION.SDK_INT >= 24)
    {
      PowerManager powerManager = (PowerManager)getSystemService(Context.POWER_SERVICE);

      if (powerManager.isSustainedPerformanceModeSupported())
        supported = true;
    }

    Log.i("RetroActivity", "isSustainedPerformanceModeSupported? " + supported);

    return supported;
  }

  public int getBatteryLevel()
  {
    IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
    // This doesn't actually register anything (or need to) because we know this particular intent is sticky and we do not specify a BroadcastReceiver anyway
    Intent batteryStatus = registerReceiver(null, ifilter);
    int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
    int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, 100);

    float percent = ((float)level / (float)scale) * 100.0f;

    Log.i("RetroActivity", "battery: level = " + level + ", scale = " + scale + ", percent = " + percent);

    return (int)percent;
  }

  public int getPowerstate()
  {
    IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
    // This doesn't actually register anything (or need to) because we know this particular intent is sticky and we do not specify a BroadcastReceiver anyway
    Intent batteryStatus = registerReceiver(null, ifilter);
    int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
    boolean hasBattery = batteryStatus.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false);
    boolean isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING);
    boolean isCharged = (status == BatteryManager.BATTERY_STATUS_FULL);
    int powerstate = FRONTEND_POWERSTATE_NONE;

    if (isCharged)
      powerstate = FRONTEND_POWERSTATE_CHARGED;
    else if (isCharging)
      powerstate = FRONTEND_POWERSTATE_CHARGING;
    else if (!hasBattery)
      powerstate = FRONTEND_POWERSTATE_NO_SOURCE;
    else
      powerstate = FRONTEND_POWERSTATE_ON_POWER_SOURCE;

    Log.i("RetroActivity", "power state = " + powerstate);

    return powerstate;
  }

  public boolean isAndroidTV()
  {
    Configuration config = getResources().getConfiguration();
    UiModeManager uiModeManager = (UiModeManager)getSystemService(UI_MODE_SERVICE);

    if (uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION)
    {
      Log.i("RetroActivity", "isAndroidTV == true");
      return true;
    }
    else
    {
      Log.i("RetroActivity", "isAndroidTV == false");
      return false;
    }
  }

  @Override
  public void onConfigurationChanged(Configuration newConfig) {
    int oldOrientation = 0;
    boolean hasOldOrientation = false;

    super.onConfigurationChanged(newConfig);

    Log.i("RetroActivity", "onConfigurationChanged: orientation is now " + newConfig.orientation);

    SharedPreferences prefs = EngineFiles.INSTANCE.preferences(this, EngineId.RETROARCH);
    SharedPreferences.Editor edit = prefs.edit();

    hasOldOrientation = prefs.contains("ORIENTATION");

    if (hasOldOrientation)
      oldOrientation = prefs.getInt("ORIENTATION", 0);

    edit.putInt("ORIENTATION", newConfig.orientation);
    edit.apply();

    Log.i("RetroActivity", "hasOldOrientation? " + hasOldOrientation + " newOrientation: " + newConfig.orientation + " oldOrientation: " + oldOrientation);
  }

  /**
   * Checks if this version of RetroArch is a Play Store build.
   *
   * @return true if this is a Play Store build, false otherwise
   */
  public boolean isPlayStoreBuild() {
    Log.i("RetroActivity", "isPlayStoreBuild: " + BuildConfig.PLAY_STORE_BUILD);

    return BuildConfig.PLAY_STORE_BUILD;
  }

  /**
   * Gets the list of available cores that can be downloaded as Dynamic Feature Modules.
   *
   * @return the list of available cores
   */
  public String[] getAvailableCores() {
    return new String[] {"gambatte", "mgba", "snes9x", "fceumm"};
  }

  public String[] getInstalledCores() {
    return getAvailableCores();
  }

  public void downloadCore(String name) {
    throw new UnsupportedOperationException("Cores are bundled in Calamansi");
  }

  public void deleteCore(String name) {
    throw new UnsupportedOperationException("Bundled cores cannot be removed");
  }

  /////////////// JNI methods ///////////////



  /**
   * Called when a core install is initiated.
   *
   * @param coreName Name of the core that the install is initiated for.
   * @param successful true if success, false if failure
   */
  public native void coreInstallInitiated(String coreName, boolean successful);

  /**
   * Called when the status of a core install has changed.
   *
   * @param coreNames Names of all cores that are currently being downloaded.
   * @param status One of INSTALL_STATUS_DOWNLOADING, INSTALL_STATUS_INSTALLING,
   *               INSTALL_STATUS_INSTALLED, or INSTALL_STATUS_FAILED
   * @param bytesDownloaded Number of bytes downloaded.
   * @param totalBytesToDownload Total number of bytes to download.
   */
  public native void coreInstallStatusChanged(String[] coreNames, int status, long bytesDownloaded, long totalBytesToDownload);

  /**
   * Called when the user grants access to a Storage Access Framework tree.
   */
  public native void safTreeAdded(String tree);



  public boolean isScreenReaderEnabled() {
    AccessibilityManager accessibilityManager = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
    boolean isAccessibilityEnabled = accessibilityManager.isEnabled();
    boolean isExploreByTouchEnabled = accessibilityManager.isTouchExplorationEnabled();
    return isAccessibilityEnabled && isExploreByTouchEnabled;
  }

  public void inputGrabMouse(boolean state) {
    runOnUiThread(() -> {
      if (state)
        getWindow().getDecorView().requestPointerCapture();
      else
        getWindow().getDecorView().releasePointerCapture();
    });
  }

  public void accessibilitySpeak(String message) {
    runOnUiThread(() -> getWindow().getDecorView().announceForAccessibility(message));
  }
}
