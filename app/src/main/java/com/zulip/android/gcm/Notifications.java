package com.zulip.android.gcm;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences.Editor;
import android.os.AsyncTask;
import android.os.SystemClock;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.gcm.GoogleCloudMessaging;
import com.zulip.android.ZulipApp;
import com.zulip.android.activities.ZulipActivity;
import com.zulip.android.networking.HTTPRequest;
import com.zulip.android.util.ZLog;

import java.io.IOException;

// This class manages registering and unregistering for GCM notifications.
// When a notification is received, it is processed by GcmBroadcastReceiver.
public class Notifications {

    // Project Number from the Google Cloud Services console
    private static final String SENDER_ID = "835904834568";

    private static final int PLAY_SERVICES_RESOLUTION_REQUEST = 9000;

    private static final String TAG = "GCM";

    private ZulipApp app;
    private GoogleCloudMessaging gcm;
    private String regid;
    private Activity activity;

    public Notifications(ZulipActivity activity) {
        this.activity = activity;
        this.app = (ZulipApp) activity.getApplication();
    }

    public void register() {
        if (checkPlayServices()) {
            gcm = GoogleCloudMessaging.getInstance(app);
            regid = getRegistrationId();

            if (regid.isEmpty()) {
                registerInBackground();
            } else {
                Log.i(TAG, "Already registered for GCM: " + regid);
            }
        } else {
            Log.i(TAG, "No valid Google Play Services APK found.");
        }
    }

    private boolean checkPlayServices() {
        int resultCode = GooglePlayServicesUtil
                .isGooglePlayServicesAvailable(app);
        if (resultCode != ConnectionResult.SUCCESS) {
            if (GooglePlayServicesUtil.isUserRecoverableError(resultCode)) {
                GooglePlayServicesUtil.getErrorDialog(resultCode, activity,
                        PLAY_SERVICES_RESOLUTION_REQUEST).show();
            } else {
                Log.i(TAG, "This device is not supported.");
            }
            return false;
        }
        return true;
    }

    private String getRegistrationId() {
        String regID = app.getSettings().getString("gcm_reg_id", "");

        long registeredVersion = app.getSettings()
                .getLong("gcm_reg_last_version", 0);
        long currentVersion = app.getAppVersion();

        if (registeredVersion != currentVersion) {
            Log.i(TAG, "App version changed.");
            return "";
        }

        return regID;
    }

    private void storeRegistrationId(Context context, String regId) {
        Editor ed = app.getSettings().edit();
        ed.putString("gcm_reg_id", regId);
        ed.putLong("gcm_reg_last_version", app.getAppVersion());
        ed.apply();
    }

    private void registerInBackground() {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                try {
                    if (gcm == null) {
                        gcm = GoogleCloudMessaging.getInstance(app);
                    }

                    int failures = 0;
                    while (true) {
                        try {
                            regid = gcm.register(SENDER_ID);
                            break;
                        } catch (IOException e) {
                            ZLog.logException(e);
                            failures += 1;
                            long backoff = (long) (Math.exp(failures / 2.0) * 1000);
                            Log.e("GCM", "Failure " + failures
                                    + ", sleeping for " + backoff);
                            SystemClock.sleep(backoff);
                        }
                    }

                    Log.i("GCM", "Device registered, registration ID=" + regid);

                    HTTPRequest request = new HTTPRequest(app);
                    request.setProperty("token", regid);
                    request.setMethodAndUrl("POST", "v1/users/me/android_gcm_reg_id");
                    request.execute();

                    // Persist the regID - no need to register again.
                    storeRegistrationId(app, regid);
                } catch (IOException ex) {
                    ZLog.logException(ex);
                }
                return null;
            }
        }.execute(null, null, null);
    }

    public AsyncTask<Void, Void, Void> logOut(final Runnable callback) {
        return new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                try {
                    // TODO: if this request fails (say, you log out while
                    // offline), the server will keep sending you notifications.
                    // There's not much we can do about that, but we may
                    // want the receiver to test for logged in status and try
                    // this request again if we get a push message when logged
                    // out (or logged in as the wrong user). The complication is
                    // that we no longer have the API key to make an
                    // authenticated request.

                    HTTPRequest request = new HTTPRequest(app);
                    request.setProperty("token", regid);
                    request.setMethodAndUrl("DELETE", "v1/users/me/android_gcm_reg_id");
                    request.execute();

                    storeRegistrationId(app, "");
                    regid = "";
                } catch (IOException ex) {
                    ZLog.logException(ex);
                }
                return null;
            }

            @Override
            protected void onPostExecute(Void v) {
                callback.run();
            }
        }.execute(null, null, null);
    }
}
