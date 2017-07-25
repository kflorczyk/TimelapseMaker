package com.loony.timelapsemaker;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.TargetApi;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.NumberPicker;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.loony.timelapsemaker.camera.Camera;
import com.loony.timelapsemaker.camera.CameraService;
import com.loony.timelapsemaker.camera.Resolution;
import com.loony.timelapsemaker.camera.TimelapseConfig;
import com.loony.timelapsemaker.camera.exceptions.CameraNotAvailableException;
import com.loony.timelapsemaker.dialog_settings.DialogOption;
import com.loony.timelapsemaker.dialog_settings.DialogSettingsAdapter;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.regex.Pattern;

public class NewActivity extends AppCompatActivity {
    public static final int REQUEST_PERMISSIONS = 0x1;
    public static final String PARCEL_TIMELAPSE_CONFIG = "parcelTimelapseConfig";

    private Camera camera;
    private Resolution pictureSize;

    private SurfaceView surfaceView;
    private SurfaceHolder.Callback surfaceHolderCallback;
    private ImageButton btnStartTimelapse;
    //private ImageButton btnSettings;
    private FloatingActionButton fab;
    private LinearLayout statsPanel;
    private TextView webAccessTxt, intervalTxt, photosCapturedTxt, nextCaptureTxt, resolutionTxt;

    private TimelapseConfig timelapseConfig;
    private boolean isDoingTimelapse;

    // camera service
    private boolean cameraServiceBound;
    private CameraService cameraService;
    private ServiceConnection cameraConnection;


    // statsPanel vars
    private Thread threadCountdown;
    private long lastPhotoTakenAtMilisTime;

    // vars which are setting by (dialog & shared prefs)
    private @Nullable Resolution[] supportedResolutions;
    private Resolution choosenSize;

    private int photoResolutionSelectedIndex;
    private int intervalMiliseconds;
    private int amountOfPhotos;

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

                    //nextCaptureTxt.post();

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            nextCaptureTxt.setText("Next capture:\n" + seconds + "s");
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

    private void updateUIphotosCaptured(int captured) {
        photosCapturedTxt.setText(String.format("Captured:\n%d of %d", captured, timelapseConfig.getPhotosLimit()));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_new); // should be some ButterKnife, maybe later
        surfaceView = (SurfaceView) findViewById(R.id.surface);
        btnStartTimelapse = (ImageButton) findViewById(R.id.btnStartTimelapse);
        //btnSettings = (ImageButton) findViewById(R.id.btnSettings);
        fab = (FloatingActionButton) findViewById(R.id.fab);
        statsPanel = (LinearLayout) findViewById(R.id.statsPanel);
        webAccessTxt = (TextView) findViewById(R.id.webAccessTxt);
        intervalTxt = (TextView) findViewById(R.id.intervalTxt);
        photosCapturedTxt = (TextView) findViewById(R.id.photosCapturedTxt);
        nextCaptureTxt = (TextView) findViewById(R.id.nextCaptureTxt);
        resolutionTxt = (TextView) findViewById(R.id.resolutionTxt);

        LocalBroadcastManager.getInstance(this).registerReceiver(mMessageReceiver, new IntentFilter(Util.BROADCAST_FILTER));

        if(savedInstanceState == null) { // FIRST START APP; OTHERWISE AFTER CRASH
            if(!Util.checkPermissions(Util.NECESSARY_PERMISSIONS_START_APP, this)) {
                ActivityCompat.requestPermissions(this, Util.NECESSARY_PERMISSIONS_START_APP, REQUEST_PERMISSIONS);
                // todo: event get resolution
            } else {
                getSupportedResolutions();
            }
        } else {
            Util.log("NewActivity::onCreate, savedInstanceState not null=crash 1/3");
            if(Util.isMyServiceRunning(this, CameraService.class)) {
                Util.log("NewActivity::onCreate, savedInstanceState not null=crash, CameraService is running 2/3");
                bindToCameraService();
                if(cameraService.getTimelapseState() == CameraService.TimelapseState.NOT_FINISHED) {
                    Util.log("NewActivity::onCreate, savedInstanceState not null=crash, CameraService is running, TimelapseController is also running 3/3");
                    isDoingTimelapse = true;
                    btnStartTimelapse.setImageResource(R.drawable.stop);
                }
            } else {
                getSupportedResolutions();
            }
        }
    }

    @Override // startCountDownToNextPhoto
    protected void onStart() {
        super.onStart();

        if(isDoingTimelapse && threadCountdown == null) {
            startCountDownToNextPhoto();
        }

        Util.log("___onStart");
    }

    @Override // stopCountDownToNextPhoto
    protected void onStop() {
        super.onStop();

        if(threadCountdown != null) {
            stopCountDownToNextPhoto();
        }

        Util.log("___onStop");
    }

    @Override // just startPreview() in SurfaceView callback
    protected void onResume() {
        super.onResume();
        Util.log("___onResume");


        boolean a = true;
        if(a) // todo: remporary for dialog testing
            return;

        if(!isDoingTimelapse) {
            if (surfaceHolderCallback != null) {
                surfaceView.getHolder().removeCallback(surfaceHolderCallback);
            }

            surfaceHolderCallback = new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder surfaceHolder) {
                    startPreview(surfaceHolder);
                }

                @Override
                public void surfaceChanged(SurfaceHolder surfaceHolder, int i, int i1, int i2) {
                }

                @Override
                public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
                }
            };

            surfaceView.getHolder().addCallback(surfaceHolderCallback);
        } else {
            // todo #1: force retrieve information about current timelapse session statistics? [interval, capturedPhotos, etc]
        }
    }

    @Override // just stopPreview() if previewing
    protected void onPause() {
        super.onPause();
        Util.log("___onPause");

        if(camera != null) {
            stopPreview();
        }
    }

    private void getSupportedResolutions() {
        Camera camera = Util.getAppropriateCamera();
        try {
            camera.prepare(this);
            this.supportedResolutions = camera.getSupportedPictureSizes();
            choosenSize = supportedResolutions[0];
        } catch (CameraNotAvailableException e) {
            e.printStackTrace();
        }

        camera.close();
    }

    // just startTimelapse() or stopTimelapse() call, depending on the 'isDoingTimelapse' value
    public void btnStartTimelapse(View v) {
        if(!isDoingTimelapse) {
            TimelapseConfig config = new TimelapseConfig();
            config.setPhotosLimit(7);
            config.setMilisecondsInterval(7000L);
            config.setPictureSize(pictureSize);

            startTimelapse(config);
        } else {
            stopTimelapse();
        }
    }

    public void btnSettingActionClick(View v) {
        Util.log("Settings click");
        final View dialogView = View.inflate(this, R.layout.dialog_settings, null);
        final Dialog dialog = new Dialog(this, R.style.MyAlertDialogStyle);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(dialogView);
        dialog.show();

        final ArrayList<DialogOption> options = new ArrayList<>();
        options.add(new DialogOption(R.drawable.ic_photo_size_select, "Photo resolution", choosenSize.toString()));
        options.add(new DialogOption(R.drawable.ic_interval, "Interval", "Something will be here"));
        options.add(new DialogOption(R.drawable.ic_amount, "Limit", "Amount of photos to capture"));
        options.add(new DialogOption(R.drawable.ic_sd_storage, "Storage", "Storage location for your timalapses"));
        options.add(new DialogOption(R.drawable.ic_remote, "WebAccess", "Access your timelapse progress through a website", DialogOption.Switch.DISABLED));
        final DialogSettingsAdapter adapter = new DialogSettingsAdapter(this, options);

        ListView listView = (ListView) dialogView.findViewById(R.id.optionsList);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int position, long id) {
                switch(position) {
                    case 0: {
                        //final String[] resOptions = new String[]{"4k", "full hd", "hd", "sd", "kalkulator ftw"};

                        final String[] resOptions = new String[supportedResolutions.length];
                        for(int i=0; i<resOptions.length; i++)
                            resOptions[i] = String.format("%dx%d", supportedResolutions[i].getWidth(), supportedResolutions[i].getHeight());

                        final AlertDialog.Builder builder = new AlertDialog.Builder(NewActivity.this);
                        builder.setTitle(R.string.dialog_choose_resolution)
                                .setItems(resOptions, new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int which) {
                                        Util.log("Wybrales " + resOptions[which]);
                                        dialog.dismiss();
                                        options.get(0).description = resOptions[which];
                                        adapter.notifyDataSetChanged();
                                    }
                                });
                        AlertDialog dialog = builder.create();
                        dialog.show();
                        break;
                    }
                    case 1: {
                        final NumberPicker numberPicker = new NumberPicker(NewActivity.this);
                        numberPicker.setMinValue(3);
                        numberPicker.setMaxValue(60 * 5);

                        final AlertDialog.Builder builder = new AlertDialog.Builder(NewActivity.this);
                        builder.setTitle(R.string.dialog_choose_interval)
                            .setView(numberPicker)
                            .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    Util.log("Zaakceptowano z value " + numberPicker.getValue());
                                    dialog.dismiss();
                                }
                            })
                            .setNegativeButton("CANCEL", new DialogInterface.OnClickListener(){
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    dialog.dismiss();
                                }
                        });
                        AlertDialog dialog = builder.create();
                        dialog.show();
                        break;
                    }
                    case 2: {
                        final NumberPicker numberPicker = new NumberPicker(NewActivity.this);
                        numberPicker.setMinValue(3);
                        numberPicker.setMaxValue(1000);

                        final AlertDialog.Builder builder = new AlertDialog.Builder(NewActivity.this);
                        builder.setTitle(R.string.dialog_choose_amount_photos)
                                .setView(numberPicker)
                                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        Util.log("Zaakceptowano z value " + numberPicker.getValue());
                                        dialog.dismiss();
                                    }
                                })
                                .setNegativeButton("CANCEL", new DialogInterface.OnClickListener(){
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        dialog.dismiss();
                                    }
                                });
                        AlertDialog dialog = builder.create();
                        dialog.show();
                        break;
                    }
                }
            }
        });

        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialogInterface) {
                Util.log("CZY TO SIE POKAZUJE ?");
                revealShow(dialogView, true, null);
            }
        });

        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface dialogInterface, int i, KeyEvent keyEvent) {
                if (i == KeyEvent.KEYCODE_BACK){

                    revealShow(dialogView, false, dialog);
                    return true;
                }
                return false;
            }
        });

        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void revealShow(View dialogView, boolean b, final Dialog dialog) {
        Util.log("revealShow executes");
        final View view = dialogView.findViewById(R.id.dialog);

        int w = view.getWidth();
        int h = view.getHeight();

        int endRadius = (int) Math.hypot(w, h);

        int cx = (int) (fab.getX() + (fab.getWidth()/2));
        int cy = (int) (fab.getY())+ fab.getHeight() + 56;

        if(b){
            Animator revealAnimator = ViewAnimationUtils.createCircularReveal(view, cx,cy, 0, endRadius);

            view.setVisibility(View.VISIBLE);
            revealAnimator.setDuration(700);
            revealAnimator.start();

        } else {
            Animator anim =
                    ViewAnimationUtils.createCircularReveal(view, cx, cy, endRadius, 0);

            anim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    dialog.dismiss();
                    view.setVisibility(View.INVISIBLE);

                }
            });
            anim.setDuration(700);
            anim.start();
        }

    }

    private BroadcastReceiver mMessageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String msg = intent.getStringExtra(Util.BROADCAST_MESSAGE);
            if(msg != null) {
                if(msg.equals(Util.BROADCAST_MESSAGE_FINISHED)) {
                    Toast.makeText(NewActivity.this, "Timelapse has been done", Toast.LENGTH_LONG).show();
                    Util.log("Timelapse work is done");
                    stopTimelapse();
                    startPreview(surfaceView.getHolder());
                } else if(msg.equals(Util.BROADCAST_MESSAGE_FINISHED_FAILED)) {
                    Util.log("Timelapse work is done (but with fail)");
                    stopTimelapse();
                    startPreview(surfaceView.getHolder());
                } else if(msg.equals(Util.BROADCAST_MESSAGE_CAPTURED_PHOTO)) {
                    lastPhotoTakenAtMilisTime = System.currentTimeMillis();
                    int capturedPhotos = intent.getIntExtra(Util.BROADCAST_MESSAGE_CAPTURED_PHOTO_AMOUNT, -1);
                    updateUIphotosCaptured(capturedPhotos);
                }
            }
        }
    };

    // stopPreview(), startCameraService(), bindToCameraService(), set 'isDoingTimelapse' flag, UI: change icon
    private void startTimelapse(TimelapseConfig timelapseConfig) {
        this.timelapseConfig = timelapseConfig;
        stopPreview();
        startCameraService(timelapseConfig);
        bindToCameraService();

        isDoingTimelapse = true;
        btnStartTimelapse.setImageResource(R.drawable.stop);
        statsPanel.setVisibility(View.VISIBLE);

        // update UI statsPanel:
        // private TextView webAccessTxt, intervalTxt, photosCapturedTxt, nextCaptureTxt, resolutionTxt;
        webAccessTxt.setText("WebAccess:\nOffline");
        intervalTxt.setText(String.format("Interval:\n%.02f", (float) (timelapseConfig.getMilisecondsInterval()/1000)));
        updateUIphotosCaptured(0);
        lastPhotoTakenAtMilisTime = System.currentTimeMillis();
        startCountDownToNextPhoto();
        resolutionTxt.setText(String.format("Resolution:\n%dx%d", timelapseConfig.getPictureSize().getWidth(), timelapseConfig.getPictureSize().getHeight()));

        Util.log("Started timelapse");
    }

    // stopService(), unbindService(), set 'isDoingTimelapse' flag, UI: change icon
    private void stopTimelapse() {
        Util.log("____NewActivity::stopTimelapse() called");

        if(threadCountdown != null)
            stopCountDownToNextPhoto();

        btnStartTimelapse.setImageResource(R.drawable.record);

        if(cameraServiceBound) {
            unbindService(cameraConnection);
            cameraServiceBound = false; // because cameraConnection callback#onDisconnect does not execute immediately - at the same time Activity gets
            // broadcast message about finishing timelapse, and that also calls stopTimelapse() where 'cameraServiceBound' is TRUE still! which provides to exceptions
        }
        stopCameraService();
        isDoingTimelapse = false;

        Util.log("Trying to stop timelapse session");
    }

    private void startPreview(SurfaceHolder surfaceHolder) {
        camera = Util.getAppropriateCamera();
        try {
            camera.prepare(NewActivity.this);
//            Resolution[] sizes = camera.getSupportedPictureSizes();
//            Resolution choosenSize = sizes[0];
            pictureSize = choosenSize;
            camera.setOutputSize(choosenSize);
            surfaceView.getHolder().setFixedSize(choosenSize.getWidth(), choosenSize.getHeight());
            camera.openForPreview(surfaceHolder);
        } catch (CameraNotAvailableException e) {
            e.printStackTrace();
        }
    }

    private void stopPreview() {
        if(surfaceHolderCallback != null)
            surfaceView.getHolder().removeCallback(surfaceHolderCallback);

        try {
            camera.close();
            camera = null;
        } catch(NullPointerException e) {}
    }

    private void startCameraService(TimelapseConfig timelapseConfig) {
        Intent intentCamera = new Intent(this, CameraService.class);
        intentCamera.putExtra(PARCEL_TIMELAPSE_CONFIG, timelapseConfig);
        startService(intentCamera);
    }

    private void bindToCameraService() {
        Intent intentCamera = new Intent(this, CameraService.class);
        cameraConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName componentName, IBinder service) {
                Util.log("NewActivity::onServiceConnected() called");
                CameraService.LocalBinder binder = (CameraService.LocalBinder) service;
                cameraService = binder.getService();
                cameraServiceBound = true;
            }

            @Override
            public void onServiceDisconnected(ComponentName componentName) {
                Util.log("NewActivity::onServiceDisconnected() called");
                cameraServiceBound = false;
                cameraService = null;
                cameraConnection = null;
            }
        };
        bindService(intentCamera, cameraConnection, 0);
    }

    private void stopCameraService() {
        Intent intentCamera = new Intent(this, CameraService.class);
        stopService(intentCamera);
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
        Util.log("___onDestroy");

        if(isDoingTimelapse) {
            stopCameraService();
            isDoingTimelapse = false;
        }

        if(cameraServiceBound)
            unbindService(cameraConnection);
    }
}
