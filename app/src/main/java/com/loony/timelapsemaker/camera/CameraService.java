package com.loony.timelapsemaker.camera;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;

import com.loony.timelapsemaker.InfinityFixedList;
import com.loony.timelapsemaker.NewActivity;
import com.loony.timelapsemaker.R;
import com.loony.timelapsemaker.Util;
import com.loony.timelapsemaker.camera.exceptions.CameraNotAvailableException;

/**
 * Created by Kamil on 7/19/2017.
 */

public class CameraService extends Service {
    private static final int NOTIFICATION_ID = 1;
    private static final int NOTIFICATION_TYPE_START = 1;
    private static final int NOTIFICATION_TYPE_CAPTURE = 2;

    private final IBinder mBinder = new LocalBinder();
    private Worker worker;

    private TimelapseController timelapseController;
    private TimelapseConfig timelapseConfig;

    public enum TimelapseState {
        NOT_FINISHED,
        FINISHED_FAIL,
        FINISHED
    }

    private TimelapseState timelapseState = TimelapseState.NOT_FINISHED;

    public TimelapseState getTimelapseState() {
        return timelapseState;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NOTIFICATION_ID, getMyNotification(NOTIFICATION_TYPE_START, -1, -1));
        Util.log("=== CameraService::onCreate");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) { // THREAD == MAIN
        if(intent == null) {
            Util.log("CameraService::onStartCommand is null, fatal exception; have a look at this");
            stopSelf();
            return START_NOT_STICKY;
//            return START_STICKY;
        }

        Util.log("=== CameraService::onStartCommand");

        //Intent i = getSendingMessageIntent(Util.BROADCAST_MESSAGE_FINISHED);
        //LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(i);

        timelapseState = TimelapseState.NOT_FINISHED;
        timelapseConfig = intent.getExtras().getParcelable(NewActivity.PARCEL_TIMELAPSE_CONFIG);

        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                try {
                    timelapseController = new TimelapseController(getApplicationContext(), timelapseConfig);
                    timelapseController.start(new OnTimelapseStateChangeListener() {
                        @Override
                        public void onInit(String timelapseDirectory) {
                            Intent i = getSendingMessageIntent(Util.BROADCAST_MESSAGE_INIT_TIMELAPSE_CONTROLLER);
                            i.putExtra("timelapseDirectory", timelapseDirectory);
                            LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(i);
                        }

                        @Override
                        public void onComplete() {
                            Util.log("CameraService::onComplete");
                            timelapseState = TimelapseState.FINISHED;
                            timelapseController.stop();
                            Intent i = getSendingMessageIntent(Util.BROADCAST_MESSAGE_FINISHED);
                            LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(i);
                        }

                        @Override
                        public void onFail() {
                            timelapseState = TimelapseState.FINISHED_FAIL;
                            timelapseController.stop();
                            Intent i = getSendingMessageIntent(Util.BROADCAST_MESSAGE_FINISHED_FAILED);
                            LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(i);
                        }

                        @Override
                        public void onProgress(int capturedPhotos, byte[] capturedImage) {
                            Intent i = getSendingMessageIntent(Util.BROADCAST_MESSAGE_CAPTURED_PHOTO);
                            i.putExtra(Util.BROADCAST_MESSAGE_CAPTURED_PHOTO_AMOUNT, capturedPhotos);
                            i.putExtra("imageBytes", capturedImage);
                            LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(i);
                            updateNotificationMadePhotos(capturedPhotos, timelapseConfig.getPhotosLimit());
                        }
                    });
                } catch (CameraNotAvailableException e) {
                    e.printStackTrace();
                }
            }
        };

        worker = new Worker("WorkerThread");
        worker.start();
        worker.waitUntilReady();
        worker.handler.post(runnable);

//        return super.onStartCommand(intent, flags, startId);
        return START_NOT_STICKY;
    }

    private Notification getMyNotification(int type, int capturedPhotos, int maxPhotos) {
        Intent notificationIntent = new Intent(this, NewActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

        String text = "";
        switch(type) {
            case NOTIFICATION_TYPE_START:
                text = getApplicationContext().getResources().getString(R.string.foreground_preparing);
                break;
            case NOTIFICATION_TYPE_CAPTURE:
                text = String.format(getApplicationContext().getResources().getString(R.string.foreground_capturing), capturedPhotos, maxPhotos);
                break;
        }

        Notification notification = new NotificationCompat.Builder(this)
                .setSmallIcon(Build.VERSION.SDK_INT >= 21 ? R.mipmap.my_icon_white : R.mipmap.my_icon)
                .setContentTitle("TimelapseMaker")
                .setContentText(text)
                .setContentIntent(pendingIntent).build();
        return notification;
    }

    private void updateNotificationMadePhotos(int photosCaptured, int maxPhotos) {
        Notification notification = getMyNotification(NOTIFICATION_TYPE_CAPTURE, photosCaptured, maxPhotos);

        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID, notification);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    private Intent getSendingMessageIntent(String message) {
        Intent intent = new Intent(Util.BROADCAST_FILTER);
        intent.putExtra(Util.BROADCAST_MESSAGE, message);
        return intent;
    }

    private class Worker extends HandlerThread {
        public Handler handler;

        public Worker(String name) {
            super(name);
        }

        public synchronized void waitUntilReady() {
            handler = new Handler(getLooper());
        }
    }

    @Override
    public void onDestroy() {
        if(timelapseController != null) {
            timelapseController.stop();
        }

        stopForeground(true);
        if(worker != null) worker.quit();
        super.onDestroy();
        Util.log("=== CameraService::onDestroy() called"); //ok
    }

    @Override
    public void onLowMemory() {
        Util.log("CameraService::onLowMemory()");
        super.onLowMemory();
    }

    public class LocalBinder extends Binder {
        public CameraService getService() {
            return CameraService.this;
        }
    }
}
