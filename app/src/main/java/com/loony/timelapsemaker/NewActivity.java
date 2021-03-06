package com.loony.timelapsemaker;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.loony.timelapsemaker.camera.Camera;
import com.loony.timelapsemaker.camera.CameraService;
import com.loony.timelapsemaker.camera.CameraVersion;
import com.loony.timelapsemaker.camera.Resolution;
import com.loony.timelapsemaker.camera.TimelapseConfig;
import com.loony.timelapsemaker.camera.exceptions.CameraNotAvailableException;
import com.loony.timelapsemaker.dialog_settings.DialogSettings;
import com.loony.timelapsemaker.http_server.HttpServer;
import com.loony.timelapsemaker.http_server.HttpService;

import java.io.File;
import java.util.Map;

public class NewActivity extends AppCompatActivity {
    public static final int REQUEST_PERMISSIONS = 0x1;
    public static final String PARCEL_TIMELAPSE_CONFIG = "parcelTimelapseConfig";

    private Camera camera;

    private RelativeLayout surfaceContainer;
    private SurfaceView surfaceViewProgrammatically;
//    private SurfaceView surfaceView;
    private SurfaceHolder.Callback surfaceHolderCallback;
    private ImageButton btnStartTimelapse;
    private FloatingActionButton fab;
    private LinearLayout statsPanel;
    private TextView statsWebAccess, statsInterval, statsPhotosCaptured, statsNextCapture, statsResolution;

    private TimelapseConfig timelapseConfig;
    private boolean isDoingTimelapse;
    private boolean isPreviewing;

    // camera service
    private boolean cameraServiceBound;
    private CameraService cameraService;
    private ServiceConnection cameraConnection;

    // http service
    private boolean httpServiceBound;
    private HttpService httpService;
    private ServiceConnection httpConnection;

    private SurfaceHolder obtainedSurfaceHolder;

    // statsPanel vars
    private Thread threadCountdown;
    private long lastPhotoTakenAtMilisTime;

    private int currentTakenPhotos;
    // vars which are setting by (dialog & shared prefs)
    private @Nullable Resolution[] supportedResolutions;
    private Resolution choosenSize;
    private int intervalMiliseconds = 6000;
    private int amountOfPhotos = 1000;
    private boolean webEnabled = true;
    private CameraVersion cameraVersion;

    private boolean DEBUG_doNotPreview = false;  // normally: FALSE
    private boolean DEBUG_doNotStartCameraService_in_startTimelapse = false; // normally: FALSE
    public static final boolean DEBUG_doNotSaveImageInStorageWhenCaptured = false; // normally: FALSE

    private void startCountDownToNextPhoto() {
        Util.log("startCountDownToNextPhoto() called");
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                while(true) {
                    long differenceMs = (lastPhotoTakenAtMilisTime + timelapseConfig.getMilisecondsInterval()) - System.currentTimeMillis();
                    final int seconds;

                    if(differenceMs > 0)
                        seconds = (int) Math.ceil(differenceMs / 1000L);
                    else seconds = 0;


                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            //statsNextCapture.setText("Next capture:\n" + seconds + "s");
                            updateUInextPhotoCaptureTime(seconds);
                        }
                    });

                    if(Thread.currentThread().isInterrupted()) {
                        return;
                    }

                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        Util.log("Problem here with Thread.sleep");
                    }
                }
            }
        };
        threadCountdown = new Thread(runnable);
        threadCountdown.start();
    }

    private void stopCountDownToNextPhoto() {
        Util.log("stopCountDownToNextPhoto() called");
        threadCountdown.interrupt();
        threadCountdown = null;
        Util.log("stopCountDownToNextPhoto() called 2/2");
    }

    private void updateUIResolution() {
        if(choosenSize == null)
            statsResolution.setText("not received");
        else
            statsResolution.setText(String.format("%dx%d", choosenSize.getWidth(), choosenSize.getHeight()));
    }

    private void updateUIInterval() {
        statsInterval.setText(String.format("%.1fs", intervalMiliseconds/1000f));
    }

    private void updateUIphotosCaptured() {
        statsPhotosCaptured.setText(String.format("%d/%d", currentTakenPhotos, amountOfPhotos));
    }

    private void updateUInextPhotoCaptureTime(int secondsToCapture) {
        statsNextCapture.setText(secondsToCapture == -1 ? "-" : secondsToCapture+"s");
    }

    private void updateUIWebAccess() {
        if(isDoingTimelapse) {
            if(webEnabled) {
                    String ip = Util.getLocalIpAddress(true);
                    statsWebAccess.setText(ip+":"+HttpServer.PORT);
                    statsWebAccess.setTextColor(this.getResources().getColor(R.color.statsPanel_enabled));
            } else {
                statsWebAccess.setText(R.string.text_disabled);
                statsWebAccess.setTextColor(this.getResources().getColor(R.color.statsPanel_disabled));
            }
        } else {
            statsWebAccess.setText(webEnabled ? R.string.text_enabled : R.string.text_disabled);
            statsWebAccess.setTextColor(this.getResources().getColor(webEnabled ? R.color.statsPanel_enabled : R.color.statsPanel_disabled));
        }
    }

    private void updateUIentire() {
        updateUIResolution();
        updateUIInterval();
        updateUIphotosCaptured();
        updateUInextPhotoCaptureTime(-1);
        updateUIWebAccess();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Util.log("[NewActivity::onCreate] This device has android with SDK LEVEL %d", Build.VERSION.SDK_INT);

//        String[] sds = StorageManager.getStorageDirectories();
//        Util.log("Available sd directories:");
//        for(String d : sds)
//            Util.log("Directory: " + d);

//        File f = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
//        Util.log("externalStoragePublicDic: " + f.getAbsolutePath());

//        Map<String, File> externalLocations = ExternalStorage.getAllStorageLocations();
//        File sdCard = externalLocations.get(ExternalStorage.SD_CARD);
//        File externalSdCard = externalLocations.get(ExternalStorage.EXTERNAL_SD_CARD);
//
//        Util.log("[ExternalLocations] sdCard: %s; extSdCard: %s", sdCard==null ? "null" : sdCard.getAbsolutePath(), externalSdCard==null ? "null" : externalSdCard.getAbsolutePath());

//        File f = new File("/storage/4542-1EE5/");
//        Util.log("extSd EXISTS: " + f.exists());




        setContentView(R.layout.activity_new); // should be some ButterKnife, maybe later
        surfaceContainer = (RelativeLayout) findViewById(R.id.surfaceContainer);
        btnStartTimelapse = (ImageButton) findViewById(R.id.btnStartTimelapse);
        fab = (FloatingActionButton) findViewById(R.id.fab);
        statsPanel = (LinearLayout) findViewById(R.id.statsPanel);
        statsWebAccess = (TextView) findViewById(R.id.webAccessTxtContent);
        statsInterval = (TextView) findViewById(R.id.intervalTxtContent);
        statsPhotosCaptured = (TextView) findViewById(R.id.photosCapturedTxtContent);
        statsNextCapture = (TextView) findViewById(R.id.nextCaptureTxtContent);
        statsResolution = (TextView) findViewById(R.id.resolutionTxtContent);

        MySharedPreferences p = new MySharedPreferences(this);
        webEnabled = p.getWebEnabled();
        cameraVersion = p.getCameraApi();

        LocalBroadcastManager.getInstance(this).registerReceiver(mMessageReceiver, new IntentFilter(Util.BROADCAST_FILTER));

        Util.log("NewActivity::onCreate&HttpService running (should TRUE if activated): " + Util.isMyServiceRunning(this, HttpService.class));

        if(Util.isMyServiceRunning(this, CameraService.class)) {
            Util.log("NewActivity::onCreate&CameraService running, need UI refresh...");
            Toast.makeText(this, "NewActivity::onCreate&CameraService running, need UI refresh...", Toast.LENGTH_LONG).show();
            // REMOVED BINDING
            // so IS NOT possible to directly get timelapse state. Need to use some BroadcastReceiver

            //bindToCameraService();
//            if(cameraService.getTimelapseState() == CameraService.TimelapseState.NOT_FINISHED) {
//                Util.log("NewActivity::onCreate, savedInstanceState not null=crash, CameraService is running, TimelapseController is also running 3/3");
//                isDoingTimelapse = true;
//                btnStartTimelapse.setImageResource(R.drawable.stop);
//                updateUIentire();
//            }
        } else {
            if(!Util.checkPermissions(Util.NECESSARY_PERMISSIONS_START_APP, this)) {
                ActivityCompat.requestPermissions(this, Util.NECESSARY_PERMISSIONS_START_APP, REQUEST_PERMISSIONS);
            } else {
                getSupportedResolutions();
                updateUIentire();
            }
        }

//        if(savedInstanceState == null) { // FIRST START APP; OTHERWISE AFTER CRASH
//            if(!Util.checkPermissions(Util.NECESSARY_PERMISSIONS_START_APP, this)) {
//                ActivityCompat.requestPermissions(this, Util.NECESSARY_PERMISSIONS_START_APP, REQUEST_PERMISSIONS);
//            } else {
//                getSupportedResolutions();
//                updateUIentire();
//            }
//        } else {
//            Util.log("NewActivity::onCreate, savedInstanceState not null=crash 1/3");
//            if(Util.isMyServiceRunning(this, CameraService.class)) {
//                Util.log("NewActivity::onCreate, savedInstanceState not null=crash, CameraService is running 2/3");
//                bindToCameraService();
//                if(cameraService.getTimelapseState() == CameraService.TimelapseState.NOT_FINISHED) {
//                    Util.log("NewActivity::onCreate, savedInstanceState not null=crash, CameraService is running, TimelapseController is also running 3/3");
//                    isDoingTimelapse = true;
//                    btnStartTimelapse.setImageResource(R.drawable.stop);
//                    updateUIentire();
//                }
//            } else {
//                getSupportedResolutions();
//                updateUIentire();
//            }
//
//            if(Util.isMyServiceRunning(this, HttpService.class)) {
//                bindToHttpService();
//            }
//        }
    }

    @Override // startCountDownToNextPhoto
    protected void onStart() {
        super.onStart();

        if(isDoingTimelapse && threadCountdown == null) {
            startCountDownToNextPhoto();
        }

        Util.log("NewActivity___onStart");
    }

    @Override // stopCountDownToNextPhoto
    protected void onStop() {
        super.onStop();

        if(threadCountdown != null) {
            stopCountDownToNextPhoto();
        }

        Util.log("NewActivity ___onStop");
    }

    @Override // just startPreview() in SurfaceView callback
    protected void onResume() {
        super.onResume();
        Util.log("NewActivity___onResume");


        if(!isDoingTimelapse && !Util.isMyServiceRunning(this, CameraService.class)) {
            startPreview();

        } else {
            // todo #1: force retrieve information about current timelapse session statistics? [interval, capturedPhotos, etc]
            // this is not necessary because Broadcasts are running
        }
    }

    @Override // just stopPreviewIfDoes() if previewing
    protected void onPause() {
        super.onPause();
        Util.log("NewActivity___onPause");

        if(camera != null) {
            stopPreviewIfDoes();
        }
    }

    private void getSupportedResolutions() {
        Util.log("[NewActivity::getSupportedResolutions]");
        Camera camera = Util.getAppropriateCamera(cameraVersion);
        try {
            camera.prepare(this);
            this.supportedResolutions = camera.getSupportedPictureSizes();
            choosenSize = supportedResolutions[0].getWidth() > supportedResolutions[supportedResolutions.length-1].getWidth() ?
                    supportedResolutions[0] :
                    supportedResolutions[supportedResolutions.length-1];
        } catch (CameraNotAvailableException e) {
            e.printStackTrace();
        }

        camera.close();
    }

    // just startTimelapse() or stopTimelapse() call, depending on the 'isDoingTimelapse' value
    public void btnStartTimelapse(View v) {
        if(!isDoingTimelapse) {
            TimelapseConfig config = new TimelapseConfig();
            config.setPhotosLimit(this.amountOfPhotos);
            config.setMilisecondsInterval(this.intervalMiliseconds);
            config.setPictureSize(this.choosenSize);
            config.setCameraApiVersion(cameraVersion);

            startTimelapse(config);
        } else {
            stopTimelapse();
        }
    }

    public void btnSettingActionClick(View v) {
        stopPreviewIfDoes();

        Util.log("[NewActivity::btnSettingActionClick]");
        DialogSettings dialogSettings = new DialogSettings(this, fab);
        dialogSettings.giveSupportedResolutions(supportedResolutions, choosenSize);
        dialogSettings.setInterval(intervalMiliseconds);
        dialogSettings.setPhotosLimit(amountOfPhotos);
        dialogSettings.setWebEnabled(webEnabled);
        dialogSettings.setCameraVersion(cameraVersion);
        dialogSettings.setOnDialogSettingChangeListener(new DialogSettings.OnDialogSettingChangeListener() {
            @Override
            public void onChangePhotoResolution(Resolution resolution) {
                NewActivity.this.choosenSize = resolution;
            }

            @Override
            public void onChangeInterval(int intervalMiliseconds) {
                NewActivity.this.intervalMiliseconds = intervalMiliseconds;
            }

            @Override
            public void onChangePhotosLimit(int amount) {
                NewActivity.this.amountOfPhotos = amount;
            }

            @Override
            public void onDialogExit() {
                startPreview();
                updateUIentire();
            }

            @Override
            public void onToggleWebServer(boolean toggle) {
                NewActivity.this.webEnabled = toggle;
                updateUIWebAccess();
            }

            @Override
            public void onCameraApiChange(CameraVersion cameraVersion) {
                NewActivity.this.cameraVersion = cameraVersion;
            }
        });
        dialogSettings.show();
    }

    private BroadcastReceiver mMessageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String msg = intent.getStringExtra(Util.BROADCAST_MESSAGE);
            if(msg != null) {
                if(msg.equals(Util.BROADCAST_MESSAGE_FINISHED)) {
                    Toast.makeText(NewActivity.this, "Timelapse has been done", Toast.LENGTH_LONG).show();
                    stopTimelapse();
                    startPreview();
                } else if(msg.equals(Util.BROADCAST_MESSAGE_FINISHED_FAILED)) {
                    if(isDoingTimelapse)
                        stopTimelapse();

                    startPreview();
                } else if(msg.equals(Util.BROADCAST_MESSAGE_CAPTURED_PHOTO)) {
                    lastPhotoTakenAtMilisTime = System.currentTimeMillis();
                    currentTakenPhotos = intent.getIntExtra(Util.BROADCAST_MESSAGE_CAPTURED_PHOTO_AMOUNT, -1);
                    byte[] lastImg = intent.getByteArrayExtra("imageBytes");
                    updateUIphotosCaptured();

                    if(obtainedSurfaceHolder != null) {
                        Canvas c = obtainedSurfaceHolder.lockCanvas();
                        if (c != null) {
                            Bitmap bitmap = BitmapFactory.decodeByteArray(lastImg, 0, lastImg.length);
                            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, c.getWidth(), c.getHeight(), true);
                            c.drawBitmap(scaled, 0, 0, null);
                            obtainedSurfaceHolder.unlockCanvasAndPost(c);
                        }
                    }
                }
            }
        }
    };

    // stopPreviewIfDoes(), startCameraService(), bindToCameraService(), set 'isDoingTimelapse' flag, UI: change icon
    private void startTimelapse(TimelapseConfig timelapseConfig) {
        this.timelapseConfig = timelapseConfig;
        stopPreviewIfDoes();

        if(!DEBUG_doNotStartCameraService_in_startTimelapse) {
            startCameraService(timelapseConfig);
            //bindToCameraService();
            Util.log("NewActivity::startTimelapse() after starting and binding to CameraService. Checking if service is running - should appear below 'CameraService'");
            Util.isMyServiceRunning(this, CameraService.class);
        }

        if(webEnabled) {
            startHttpService(timelapseConfig);
//            bindToHttpService();
        }

        isDoingTimelapse = true;
        btnStartTimelapse.setImageResource(R.drawable.stop);
        statsPanel.setVisibility(View.VISIBLE);

        currentTakenPhotos = 0;
        lastPhotoTakenAtMilisTime = System.currentTimeMillis();
        updateUIentire();
        startCountDownToNextPhoto();

        fab.setVisibility(View.GONE);
        Util.log("Started timelapse");
    }

    // stopService(), unbindService(), set 'isDoingTimelapse' flag, UI: change icon
    private void stopTimelapse() {
        Util.log("NewActivity::stopTimelapse() called");

        if(threadCountdown != null)
            stopCountDownToNextPhoto();

        btnStartTimelapse.setImageResource(R.drawable.record);

        if(cameraServiceBound) {
            //unbindService(cameraConnection);
            cameraServiceBound = false; // because cameraConnection callback#onDisconnect does not execute immediately - at the same time Activity gets
            // broadcast message about finishing timelapse, and that also calls stopTimelapse() where 'cameraServiceBound' is TRUE still! which provides to exceptions
        }
        stopCameraService();

//        if(httpServiceBound) {
//            unbindService(httpConnection);
//            httpServiceBound = false;
//        }
        stopHttpService();

        isDoingTimelapse = false;
        updateUIentire();

        fab.setVisibility(View.VISIBLE);
    }

    private void startPreview() {
        if(DEBUG_doNotPreview)
            return;

        if(isPreviewing)
            return;

        if(surfaceContainer.getChildCount() > 0) {
            surfaceContainer.removeView(surfaceViewProgrammatically);
        }

        surfaceViewProgrammatically = new SurfaceView(this);
        surfaceViewProgrammatically.setLayoutParams(new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));
        surfaceContainer.addView(surfaceViewProgrammatically);

        surfaceViewProgrammatically.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder surfaceHolder) {
                camera = Util.getAppropriateCamera(NewActivity.this.cameraVersion);
                try {
                    obtainedSurfaceHolder = surfaceHolder;
                    camera.prepare(NewActivity.this);
                    Util.log("startPreview with resolution " + choosenSize.getWidth() + "x" + choosenSize.getHeight()); // todo: bug, choosenSize is NULL after start
                    camera.setOutputSize(choosenSize);
                    surfaceHolder.setFixedSize(choosenSize.getWidth(), choosenSize.getHeight());
                    camera.openForPreview(surfaceHolder);
                    isPreviewing = true;
                } catch (CameraNotAvailableException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder surfaceHolder, int i, int i1, int i2) {

            }

            @Override
            public void surfaceDestroyed(SurfaceHolder surfaceHolder) {

            }
        });
    }

    private void stopPreviewIfDoes() {
        try {
            camera.close();
            camera = null;
            isPreviewing = false;
        } catch(NullPointerException e) {}
    }

    private void startCameraService(TimelapseConfig timelapseConfig) {
        Intent intentCamera = new Intent(this, CameraService.class);
        intentCamera.putExtra(PARCEL_TIMELAPSE_CONFIG, timelapseConfig);
        startService(intentCamera);
    }

    private void startHttpService(TimelapseConfig timelapseConfig) {
        Intent intentHttp = new Intent(this, HttpService.class);
        intentHttp.putExtra(PARCEL_TIMELAPSE_CONFIG, timelapseConfig);
        startService(intentHttp);
    }

    private void bindToCameraService() {
        Intent intentCamera = new Intent(this, CameraService.class);
        cameraConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName componentName, IBinder service) {
                Util.log("[CameraService] NewActivity::onServiceConnected() called");
                CameraService.LocalBinder binder = (CameraService.LocalBinder) service;
                cameraService = binder.getService();
                cameraServiceBound = true;
            }

            @Override
            public void onServiceDisconnected(ComponentName componentName) {
                Util.log("[CameraService] NewActivity::onServiceDisconnected() called");
                cameraServiceBound = false;
                cameraService = null;
                cameraConnection = null;
            }
        };
        bindService(intentCamera, cameraConnection, 0);
    }

    private void bindToHttpService() {
        Intent intentHttp = new Intent(this, HttpService.class);
        httpConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName componentName, IBinder service) {
                Util.log("[HttpService] NewActivity::onServiceConnected() called");
                HttpService.LocalBinder binder = (HttpService.LocalBinder) service;
                httpService = binder.getService();
                httpServiceBound = true;
            }

            @Override
            public void onServiceDisconnected(ComponentName componentName) {
                Util.log("[HttpService] NewActivity::onServiceDisconnected() called");
                httpServiceBound = false;
                httpService = null;
                httpConnection = null;
            }
        };
        bindService(intentHttp, httpConnection, 0);
    }

    private void stopCameraService() {
        Intent intentCamera = new Intent(this, CameraService.class);
        stopService(intentCamera);
    }

    private void stopHttpService() {
        Intent intentHttp = new Intent(this, HttpService.class);
        stopService(intentHttp);
        Util.log("~~HttpService stopped~~ (via NewActivity::stopHttpService)");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if(requestCode == REQUEST_PERMISSIONS) {
            if(grantResults.length > 0) {
                for(int grantResult : grantResults) {
                    if (grantResult != PackageManager.PERMISSION_GRANTED) {
                        Toast.makeText(this, "You have to provide permissions to use app.", Toast.LENGTH_LONG).show();
                        return;
                    }
                }
                getSupportedResolutions();
                updateUIentire();
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Util.log("NewAcitivity___onDestroy");

//        if(isDoingTimelapse) {
//            stopCameraService();
//            isDoingTimelapse = false;
//        }

//        if(cameraServiceBound)
//            unbindService(cameraConnection);

//        if(httpServiceBound) {
//            unbindService(httpConnection);
//            stopHttpService();
//        }
    }
}
