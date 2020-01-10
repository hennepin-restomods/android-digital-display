package com.hennepinrestomods.androiddigitaldisplay.ui;


import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.media.Image;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.view.View;
import android.widget.AbsoluteLayout;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.github.anastr.speedviewlib.ImageSpeedometer;
import com.github.pires.obd.commands.ObdCommand;
import com.github.pires.obd.enums.AvailableCommandNames;
import com.hennepinrestomods.androiddigitaldisplay.R;
import com.hennepinrestomods.androiddigitaldisplay.config.ObdConfig;
import com.hennepinrestomods.androiddigitaldisplay.io.AbstractGatewayService;
import com.hennepinrestomods.androiddigitaldisplay.io.LogCSVWriter;
import com.hennepinrestomods.androiddigitaldisplay.io.MockObdGatewayService;
import com.hennepinrestomods.androiddigitaldisplay.io.ObdCommandJob;
import com.hennepinrestomods.androiddigitaldisplay.io.ObdGatewayService;
import com.hennepinrestomods.androiddigitaldisplay.io.ObdProgressListener;
import com.hennepinrestomods.androiddigitaldisplay.net.ObdReading;
import com.hennepinrestomods.androiddigitaldisplay.net.ObdService;
import com.hennepinrestomods.androiddigitaldisplay.trips.TripLog;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import retrofit.RestAdapter;
import retrofit.RetrofitError;
import retrofit.client.Response;
import roboguice.RoboGuice;
import roboguice.activity.RoboActivity;
import roboguice.inject.ContentView;
import roboguice.inject.InjectView;

@ContentView(R.layout.activity_example_dashboard)
public class ExampleDashboardActivity extends RoboActivity implements ObdProgressListener {


    private static final String TAG = MainActivity.class.getName();
    private static final int NO_BLUETOOTH_ID = 0;
    private static final int BLUETOOTH_DISABLED = 1;
    private static final int START_LIVE_DATA = 2;
    private static final int STOP_LIVE_DATA = 3;
    private static final int SETTINGS = 4;
    private static final int REQUEST_ENABLE_BT = 1234;
    private static boolean bluetoothDefaultIsEnable = false;

    public Map<String, String> commandResult = new HashMap<String, String>();

    private LogCSVWriter myCSVWriter;

    static {
        RoboGuice.setUseAnnotationDatabases(false);
    }

    @InjectView(R.id.obd_status)
    private TextView obdStatusTextView;

    @InjectView(R.id.bt_status)
    private TextView btStatusTextView;

    @InjectView(R.id.speedometer)
    private ImageSpeedometer speedometer;

    @InjectView(R.id.tachometer)
    private ImageSpeedometer tachometer;

    @InjectView(R.id.oilpressuregauge)
    private ImageSpeedometer oilTemperature;

    @InjectView(R.id.temperaturegauge)
    private ImageSpeedometer waterTemperature;

    private ArrayList<ImageSpeedometer> gauges = new ArrayList<ImageSpeedometer>();

    @InjectView(R.id.dashboard_view)
    private AbsoluteLayout dashboardView;

    @InjectView(R.id.immersive)
    private Button immersiveButton;

    @InjectView(R.id.back)
    private Button backButton;

    @InjectView(R.id.startData)
    private Button startDataButton;

    @InjectView(R.id.stopData)
    private Button stopDataButton;

    @InjectView(R.id.settings)
    private Button settingsButton;

    //@Inject
    //private SensorManager sensorManager;

    @Inject
    private PowerManager powerManager;
    @Inject
    private SharedPreferences prefs;
    private boolean isServiceBound;
    private AbstractGatewayService service;
    private PowerManager.WakeLock wakeLock = null;
    private boolean preRequisites = true;

    private final Runnable mQueueCommands = new Runnable() {
        public void run() {
            if (service != null && service.isRunning() && service.queueEmpty()) {
                queueCommands();

                double lat = 0;
                double lon = 0;
                double alt = 0;
                final int posLen = 7;

                if (prefs.getBoolean(ConfigActivity.UPLOAD_DATA_KEY, false)) {
                    // Upload the current reading by http
                    final String vin = prefs.getString(ConfigActivity.VEHICLE_ID_KEY, "UNDEFINED_VIN");
                    Map<String, String> temp = new HashMap<String, String>();
                    temp.putAll(commandResult);
                    ObdReading reading = new ObdReading(lat, lon, alt, System.currentTimeMillis(), vin, temp);
                    new ExampleDashboardActivity.UploadAsyncTask().execute(reading);

                } else if (prefs.getBoolean(ConfigActivity.ENABLE_FULL_LOGGING_KEY, false)) {
                    // Write the current reading to CSV
                    final String vin = prefs.getString(ConfigActivity.VEHICLE_ID_KEY, "UNDEFINED_VIN");
                    Map<String, String> temp = new HashMap<String, String>();
                    temp.putAll(commandResult);
                    ObdReading reading = new ObdReading(lat, lon, alt, System.currentTimeMillis(), vin, temp);
                    if(reading != null) myCSVWriter.writeLineCSV(reading);
                }
                commandResult.clear();
            }
            // run again in period defined in preferences
            new Handler().postDelayed(mQueueCommands, ConfigActivity.getObdUpdatePeriod(prefs));
        }
    };

    private ServiceConnection serviceConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder binder) {
            Log.d(TAG, className.toString() + " service is bound");
            isServiceBound = true;
            service = ((AbstractGatewayService.AbstractGatewayServiceBinder) binder).getService();
            service.setContext(ExampleDashboardActivity.this);
            Log.d(TAG, "Starting live data");
            try {
                service.startService();
                if (preRequisites)
                    btStatusTextView.setText(getString(R.string.status_bluetooth_connected));
            } catch (IOException ioe) {
                Log.e(TAG, "Failure Starting live data");
                btStatusTextView.setText(getString(R.string.status_bluetooth_error_connecting));
                doUnbindService();
            }
        }

        @Override
        protected Object clone() throws CloneNotSupportedException {
            return super.clone();
        }

        // This method is *only* called when the connection to the service is lost unexpectedly
        // and *not* when the client unbinds (http://developer.android.com/guide/components/bound-services.html)
        // So the isServiceBound attribute should also be set to false when we unbind from the service.
        @Override
        public void onServiceDisconnected(ComponentName className) {
            Log.d(TAG, className.toString() + " service is unbound");
            isServiceBound = false;
        }
    };

    public static String LookUpCommand(String txt) {
        for (AvailableCommandNames item : AvailableCommandNames.values()) {
            if (item.getValue().equals(txt)) return item.name();
        }
        return txt;
    }

    public void updateTextView(final TextView view, final String txt) {
        new Handler().post(new Runnable() {
            public void run() {
                view.setText(txt);
            }
        });
    }

    public void stateUpdate(final ObdCommandJob job) {
        final String cmdName = job.getCommand().getName();
        String cmdResult = "";
        final String cmdID = LookUpCommand(cmdName);

        if (job.getState().equals(ObdCommandJob.ObdCommandJobState.EXECUTION_ERROR)) {
            cmdResult = job.getCommand().getResult();
            if (cmdResult != null && isServiceBound) {
                obdStatusTextView.setText(cmdResult.toLowerCase());
            }
        } else if (job.getState().equals(ObdCommandJob.ObdCommandJobState.BROKEN_PIPE)) {
            if (isServiceBound)
                stopLiveData();
        } else if (job.getState().equals(ObdCommandJob.ObdCommandJobState.NOT_SUPPORTED)) {
            cmdResult = getString(R.string.status_obd_no_support);
        } else {
            cmdResult = job.getCommand().getFormattedResult();
            if(isServiceBound)
                obdStatusTextView.setText(getString(R.string.status_obd_data));
        }

        /*if (dashboardView.findViewWithTag(cmdID) != null) {
            TextView existingTV = (TextView) dashboardView.findViewWithTag(cmdID);
            existingTV.setText(cmdResult);
        }
        */

        if(cmdID.equals("Engine RPM") || cmdID.equals("ENGINE_RPM")){
            tachometer.speedTo(parseRPM(cmdResult));
        }
        else if(cmdID.equals("Vehicle Speed") || cmdID.equals("SPEED")){
            speedometer.speedTo(parseSpeed(cmdResult));
        }
        else if(cmdID.equals("Engine oil temperature") || cmdID.equals("ENGINE_OIL_TEMP")){
            oilTemperature.speedTo(parseTemperature(cmdResult));
        }
        else if(cmdID.equals("Engine Coolant Temperature") || cmdID.equals("ENGINE_COOLANT_TEMP")) {
            waterTemperature.speedTo(parseTemperature(cmdResult));
        }

        commandResult.put(cmdID, cmdResult);
    }

    private float parseRPM(String input) {
        return Float.parseFloat(input.substring(0, input.length() - 3));
    }

    private float parseSpeed(String input) {
        return Float.parseFloat(input.substring(0, input.length() - 4));
    }

    private float parseTemperature(String input) {
        return Float.parseFloat(input.substring(0, input.length() - 2));
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_example_dashboard);

        this.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        final BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter != null)
            bluetoothDefaultIsEnable = btAdapter.isEnabled();

        obdStatusTextView.setText(getString(R.string.status_obd_disconnected));

        immersiveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setImmersive();
            }
        });

        backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View V) {
                backToMain();
            }
        });

        startDataButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startLiveData();
            }
        });

        stopDataButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopLiveData();
            }
        });

        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updateConfig();
            }
        });

        gauges.add(speedometer);
        gauges.add(tachometer);
        gauges.add(oilTemperature);
        gauges.add(waterTemperature);
    }

    private void setImmersive() {
        this.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void backToMain() {
        this.finish();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "Entered onStart...");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        releaseWakeLockIfHeld();
        if (isServiceBound) {
            doUnbindService();
        }

        final BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter != null && btAdapter.isEnabled() && !bluetoothDefaultIsEnable)
            btAdapter.disable();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "Pausing..");
        releaseWakeLockIfHeld();
    }


    private void releaseWakeLockIfHeld() {
        if (wakeLock.isHeld())
            wakeLock.release();
    }

    protected void onResume() {
        super.onResume();
        Log.d(TAG, "Resuming..");
        wakeLock = powerManager.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK,
                "AndroidDigitalDisplay: ");

        // get Bluetooth device
        final BluetoothAdapter btAdapter = BluetoothAdapter
                .getDefaultAdapter();

        preRequisites = btAdapter != null && btAdapter.isEnabled();
        if (!preRequisites &&  false /*prefs.getBoolean(ConfigActivity.ENABLE_BT_KEY, false)*/) {
            preRequisites = btAdapter != null && btAdapter.enable();
        }

        if (!preRequisites) {
            showDialog(BLUETOOTH_DISABLED);
            btStatusTextView.setText(getString(R.string.status_bluetooth_disabled));
        } else {
            btStatusTextView.setText(getString(R.string.status_bluetooth_ok));
        }
    }

    private void updateConfig() {
        startActivity(new Intent(this, ConfigActivity.class));
    }

    private void getTroubleCodes() {
        startActivity(new Intent(this, TroubleCodesActivity.class));
    }

    private void startLiveData() {
        Log.d(TAG, "Starting live data..");

        doBindService();

        for(ImageSpeedometer gauge : gauges){
            gauge.speedTo(0);
        }

        // start command execution
        new Handler().post(mQueueCommands);

        // screen won't turn off until wakeLock.release()
        wakeLock.acquire();

        if (prefs.getBoolean(ConfigActivity.ENABLE_FULL_LOGGING_KEY, false)) {

            // Create the CSV Logger
            long mils = System.currentTimeMillis();
            SimpleDateFormat sdf = new SimpleDateFormat("_dd_MM_yyyy_HH_mm_ss");

            try {
                myCSVWriter = new LogCSVWriter("Log" + sdf.format(new Date(mils)).toString() + ".csv",
                        prefs.getString(ConfigActivity.DIRECTORY_FULL_LOGGING_KEY,
                                getString(R.string.default_dirname_full_logging))
                );
            } catch (FileNotFoundException | RuntimeException e) {
                Log.e(TAG, "Can't enable logging to file.", e);
            }
        }
    }

    private void stopLiveData() {
        Log.d(TAG, "Stopping live data..");

        doUnbindService();

        releaseWakeLockIfHeld();

        final String devemail = prefs.getString(ConfigActivity.DEV_EMAIL_KEY, null);
        if (devemail != null && !devemail.isEmpty()) {
            DialogInterface.OnClickListener dialogClickListener = new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    switch (which) {
                        case DialogInterface.BUTTON_POSITIVE:
                            ObdGatewayService.saveLogcatToFile(getApplicationContext(), devemail);
                            break;

                        case DialogInterface.BUTTON_NEGATIVE:
                            //No button clicked
                            break;
                    }
                }
            };
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setMessage("Where there issues?\nThen please send us the logs.\nSend Logs?").setPositiveButton("Yes", dialogClickListener)
                    .setNegativeButton("No", dialogClickListener).show();
        }

        if (myCSVWriter != null) {
            myCSVWriter.closeLogCSVWriter();
        }
    }

    protected Dialog onCreateDialog(int id) {
        AlertDialog.Builder build = new AlertDialog.Builder(this);
        switch (id) {
            case NO_BLUETOOTH_ID:
                build.setMessage(getString(R.string.text_no_bluetooth_id));
                return build.create();
            case BLUETOOTH_DISABLED:
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
                return build.create();
        }
        return null;
    }

    private void queueCommands() {
        if (isServiceBound) {
            for (ObdCommand Command : ObdConfig.getCommands()) {
                if (prefs.getBoolean(Command.getName(), true))
                    service.queueJob(new ObdCommandJob(Command));
            }
        }
    }

    private void doBindService() {
        if (!isServiceBound) {
            Log.d(TAG, "Binding OBD service..");
            if (preRequisites) {
                btStatusTextView.setText(getString(R.string.status_bluetooth_connecting));
                Intent serviceIntent = new Intent(this, ObdGatewayService.class);
                bindService(serviceIntent, serviceConn, Context.BIND_AUTO_CREATE);
            } else {
                btStatusTextView.setText(getString(R.string.status_bluetooth_disabled));
                Intent serviceIntent = new Intent(this, MockObdGatewayService.class);
                bindService(serviceIntent, serviceConn, Context.BIND_AUTO_CREATE);
            }
        }
    }

    private void doUnbindService() {
        if (isServiceBound) {
            if (service.isRunning()) {
                service.stopService();
                if (preRequisites)
                    btStatusTextView.setText(getString(R.string.status_bluetooth_ok));
            }
            Log.d(TAG, "Unbinding OBD service..");
            unbindService(serviceConn);
            isServiceBound = false;
            obdStatusTextView.setText(getString(R.string.status_obd_disconnected));
        }
    }

    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    public void onProviderEnabled(String provider) {
    }

    public void onProviderDisabled(String provider) {
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == Activity.RESULT_OK) {
                btStatusTextView.setText(getString(R.string.status_bluetooth_connected));
            } else {
                Toast.makeText(this, R.string.text_bluetooth_disabled, Toast.LENGTH_LONG).show();
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /**
     * Uploading asynchronous task
     */
    private class UploadAsyncTask extends AsyncTask<ObdReading, Void, Void> {

        @Override
        protected Void doInBackground(ObdReading... readings) {
            Log.d(TAG, "Uploading " + readings.length + " readings..");
            // instantiate reading service client
            final String endpoint = prefs.getString(ConfigActivity.UPLOAD_URL_KEY, "");
            RestAdapter restAdapter = new RestAdapter.Builder()
                    .setEndpoint(endpoint)
                    .build();
            ObdService service = restAdapter.create(ObdService.class);
            // upload readings
            for (ObdReading reading : readings) {
                try {
                    Response response = service.uploadReading(reading);
                    assert response.getStatus() == 200;
                } catch (RetrofitError re) {
                    Log.e(TAG, re.toString());
                }

            }
            Log.d(TAG, "Done");
            return null;
        }

    }
}
