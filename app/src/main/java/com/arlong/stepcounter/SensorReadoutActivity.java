package com.arlong.stepcounter;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PointF;
import android.graphics.drawable.AnimationDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ExpandableListView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.j256.ormlite.android.apptools.OpenHelperManager;
import com.j256.ormlite.dao.Dao;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;

import filebrowser.FileBrowser;

public class SensorReadoutActivity extends Activity implements  OnClickListener,RefreshableView, LocationChangeListener{

    // Sensor 配置
    private static final String TAG = "SensorScan";
    private SensorManager sensorManager;

    private Sensor accSensor;
    private Sensor gyroSensor;

    // 计步参数
    private int accNo = 0;
    public int stepNo = 0;
    public float stepLength;
    public float heading;
    private float[] accold1 = new float[2];
    private float[] accold2 = new float[2];
    private float[] accnew = new float[2];
    private float[] peakmax = new float[2];
    private float[] peakmin = new float[2];
    private float deltaTime = 0.15f;
    private float deltaA = 1.4f;
    private boolean peakmaxReady;
    private float locationx, locationy;
    //采样频率
    public static final int sampleRate = 20;
    public static boolean getInitAngle = false;

    // UI 配置
    private Button btnScanStart;
    private Button btnScanStop;
    private Button btnClear;
    private TextView tvStep;
    private TextView tvStepLength;
    private TextView tvHeading;
    private TextView tvLocation;
    private MultiTouchView multiTouchView;

    //平均滤波参数
    private int N_windows = 5;
    private float[] value_buf = new float[N_windows];
    private int i_filter=0;

    // 读取传感器数据时用到的变量
    long timeStart=0;// the timestamp of the first sample，因为event.timestamp格式为long，这里是为了保证相减前不丢数据，否则后面间隔可能为负
    float timestamp;// 距离first sample 的时间间隔

    public float beta = 0.3f;								// 2 * proportional gain (Kp)
    public float q0 = 1.0f, q1 = 0.0f, q2 = 0.0f, q3 = 0.0f;	// quaternion of sensor frame relative to auxiliary frame

    protected SiteMapDrawable map;
    protected float scalerDistance;
    /**
     * @uml.property name="site"
     * @uml.associationEnd
     */
    protected ProjectSite site;
    /**
     * @uml.property name="databaseHelper"
     * @uml.associationEnd
     */
    protected DatabaseHelper databaseHelper = null;
    /**
     * @uml.property name="stepDetectionProvider"
     * @uml.associationEnd
     */
    protected StepDetectionProvider stepDetectionProvider = null;
    protected Dao<ProjectSite, Integer> projectSiteDao = null;
    public static final String SITE_KEY = "SITE";
    /**
     * @uml.property name="scaler"
     * @uml.associationEnd
     */
    protected ScaleLineDrawable scaler = null;
    /**
     * @uml.property name="user"
     * @uml.associationEnd
     */
    protected UserDrawable user;
    protected NorthDrawable northDrawable = null;
    protected Logger log = new Logger(SensorReadoutActivity.class);
    private Thread ticker;
    private int xTick = 0;
    private Madgwick mMadgwick = new Madgwick();

    //Dialog
    protected static final int  DIALOG_SET_BACKGROUND = 1, DIALOG_SET_SCALE_OF_MAP = 2, DIALOG_ASK_CHANGE_SCALE = 3, DIALOG_ASK_FOR_NORTH = 4;
    protected Handler messageHandler;
    protected static final int MESSAGE_REFRESH = 1, MESSAGE_START_WIFISCAN = 2, MESSAGE_PERSIST_RESULT = 3;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try{
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Log.d(TAG, "on Create");
        Intent intent = this.getIntent();

        int siteId = intent.getExtras().getInt(SITE_KEY,-1);
        if (siteId == -1) {
            throw new SiteNotFoundException("ProjectSiteActivity called without a correct site ID!");
        }

        databaseHelper = OpenHelperManager.getHelper(this, DatabaseHelper.class);
        projectSiteDao = databaseHelper.getDao(ProjectSite.class);
        site = projectSiteDao.queryForId(siteId);

        if (site == null) {
            throw new SiteNotFoundException("The ProjectSite Id could not be found in the database!");
        }

        MultiTouchDrawable.setGridSpacing(site.getGridSpacingX(), site.getGridSpacingY());

        map = new SiteMapDrawable(this, this);
        map.setAngleAdjustment(site.getNorth());
        //MultiTouchDrawable.setGridSpacing(100, 100);
        if (site.getWidth() == 0 || site.getHeight() == 0) {
            // the site has never been loaded
            site.setSize(map.getWidth(), map.getHeight());
        } else {
            map.setSize(site.getWidth(), site.getHeight());
        }
        if (site.getBackgroundBitmap() != null) {
            map.setBackgroundImage(site.getBackgroundBitmap());
        }
        //map = new SiteMapDrawable(this,this);
       // map.setScale(30, 30);
       // map.setPos(0, 0, 30, 30, (float) Math.PI, true);
       // map.setSize(map.width / 50, map.height / 50);
       // multiTouchView.setRearrangable(false);
       // multiTouchView.addDrawable(map);

        user = new UserDrawable(this, map);

        if (site.getLastLocation() != null) {
            user.setRelativePosition(site.getLastLocation().getX(), site.getLastLocation().getY());
        } else {
            user.setRelativePosition(map.getWidth() / 2, map.getHeight() / 2);
        }
        LocationServiceFactory.getLocationService().setRelativeNorth(site.getNorth());
        LocationServiceFactory.getLocationService().setGridSpacing(site.getGridSpacingX(), site.getGridSpacingY());
        stepDetectionProvider = new StepDetectionProvider(this);
        stepDetectionProvider.setLocationChangeListener(this);

        messageHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MESSAGE_REFRESH:
                    /* Refresh UI */
                        if (multiTouchView != null)
                            multiTouchView.invalidate();
                        break;
                }
            }
        };
        initUI();
        initSensors();
        } catch (Exception ex) {
            log.error("Failed to create ProjectSiteActivity: " + ex.getMessage(), ex);
            Toast.makeText(this, R.string.project_site_load_failed, Toast.LENGTH_LONG).show();
            this.finish();
        }
    }
    /*
 * (non-Javadoc)
 *
 * @see android.app.Activity#onDestroy()
 */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (databaseHelper != null) {
            OpenHelperManager.releaseHelper();
            databaseHelper = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        log.debug("setting context");

        multiTouchView.loadImages(this);
        map.load();
        // stepDetectionProvider.start();

    }
    //Sensor 函数
    //按键监听
    public void onClick(View v) {
        switch (v.getId())
        {
            case R.id.scanStart:
                try {
                    doStartScan();
                }catch (Exception e){
                    e.printStackTrace();
                }
                break;
            case R.id.scanStop:
                try {
                    doStopScan();
                }catch (Exception e){
                    e.printStackTrace();
                }
                break;
            case R.id.btnClear:
                try {
                    doClear();
                }catch (Exception e){
                    e.printStackTrace();
                }
                break;

            case R.id.project_site_toggle_autorotate:

                ToggleButton button = (ToggleButton) findViewById(R.id.project_site_toggle_autorotate);

                if (button.isChecked()) {
                    map.startAutoRotate();
                    Logger.d("Started autorotate.");
                } else {
                    map.stopAutoRotate();
                    Logger.d("Stopped autorotate.");
                }

                break;
        }
    }


    //****************************************/
    // 初始化函数
    //******************************************/
    private void initUI(){
        ((Button)findViewById(R.id.scanStart)).setOnClickListener(this);
        ((Button)findViewById(R.id.scanStop)).setOnClickListener(this);
        ((Button)findViewById(R.id.btnClear)).setOnClickListener(this);
        ((ToggleButton) findViewById(R.id.project_site_toggle_autorotate)).setOnClickListener(this);
        tvStep = (TextView)findViewById(R.id.tvStep);
        tvStepLength = (TextView)findViewById(R.id.tvStepLength);
        tvHeading = (TextView)findViewById(R.id.tvHeading);
        tvLocation = (TextView)findViewById(R.id.tvLocation);
        multiTouchView = ((MultiTouchView) findViewById(R.id.mapView));
        multiTouchView.setRearrangable(false);

        multiTouchView.addDrawable(map);
        // start configuration dialog
        Dialogshow(DIALOG_SET_BACKGROUND);
    }
    private void initSensors(){
        sensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
        accSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        //magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
    }

    //****************************************/
    // 计步函数
    //******************************************/
    private boolean stepDetecter(float Time,float acc){
        float[] normAcc = new float[2];
        boolean isStep;
        isStep = false;
        normAcc[1] = acc;
        normAcc[0] = Time;

        if(xTick == 0)
            accold1 = normAcc;
        else if(accNo == 1)
            accold2 = normAcc;
        else{
            accnew = normAcc;
            if(accold1[1]<accold2[1] && accold2[1] > accnew[1]){
                if (accold2[0]-peakmin[0]> deltaTime)
                    peakmax = accold2;
                    peakmaxReady = true;
            }
            else if(accold1[1]>accold2[1] && accold2[1] < accnew[1]){
                if (accold2[0] - peakmax[0] >deltaTime  && peakmaxReady){
                    peakmin = accold2;
                    if(peakmax[1] - peakmin[1] >deltaA){
                        stepLength =(float)( 0.5 * Math.pow(peakmax[1] - peakmin[1],0.25) );
                        String str = String.format("%.2f",stepLength);
                        tvStepLength.setText(str);
                        isStep = true;
                        peakmaxReady = false;
                    }
                }

            }
            accold1 = accold2;
            accold2 = accnew;
        }
        return isStep;
    }
    //****************************************/
    // 平滑滤波
    //******************************************/

    private float filter(float data)
    {
        int count;
        float  sum=0;
        value_buf[i_filter] = data;
        i_filter++;
        if ( i_filter == N_windows )   i_filter = 0;
        for ( count=0;count<N_windows;count++){
            sum = sum + value_buf[count];
        }
        return (sum/N_windows);
    }
    //****************************************/
    // 传感器数据读取开始与停止函数
    //******************************************/
    private void doStartScan() throws Exception{
        Log.d(TAG, "Create File");

        timeStart = 0;//开始时时间归零
        peakmax[0] = 0;peakmin[0] = 0;
        peakmax[1] = 0;peakmin[1] =0;
        Log.d(TAG, "Start Listener");
        if (!stepDetectionProvider.isRunning()) {
            stepDetectionProvider.start();
        }
        doNotify("Scan Starting");

    }
    private void doStopScan() throws Exception {
        Log.d(TAG, "Stop Scan");
        Log.d(TAG, "Stop Listener");
        doNotify("Scan Stop!");
    }
    private void doClear() throws Exception{
        xTick = 0;
        timeStart=0;
        stepNo = 0;
        q0 = 1.0f; q1 = 0.0f; q2 = 0.0f; q3 = 0.0f;
        tvStep.setText(String.format("%s", stepNo));
    }

    //****************************************/
    // Lagrange 二次插值
    //******************************************/
    public float Lagrange2_interpolation(float[] x, float[] y, float xk){
        float x0 = x[0];
        float y0 = y[0];
        float x1 = x[1];
        float y1 = y[1];
        float x2 = x[2];
        float y2 = y[2];
        float yk;
        yk = y0 * ( (xk-x1)*(xk-x2)/(x0-x1)/(x0-x2) ) + y1 * ( (xk-x0)*(xk-x2)/(x1-x0)/(x1-x2) ) + y2 * ( (xk-x0)*(xk-x1)/(x2-x0)/(x2-x1) );
        return yk;
    }

    //****************************************/
    // 屏幕显示通知函数的简化
    //******************************************/
    public void doNotify(String message) {
        doNotify(message, false);
    }
    public void doNotify(String message, boolean longMessage) {
        (Toast.makeText(this, message, longMessage ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT)).show();
        Log.d(TAG, "Notify: " + message);
    }

    /**
     * Periodically called by the ticker
     *
     * @param accEvent,gyroEvent
     *          current sensor data.
     */
    protected void doPdrDraw(SensorEvent accEvent, SensorEvent gyroEvent) {

        float NS2S=1.0f/1000000000.0f;//纳秒转为秒
        if(timeStart==0){
            timeStart=accEvent.timestamp;
            timestamp=0;
        }
        else
            timestamp=(accEvent.timestamp-timeStart)*NS2S;
        float yvalue = (float)Math.sqrt(accEvent.values[0]*accEvent.values[0]+accEvent.values[1]*accEvent.values[1]+accEvent.values[2]*accEvent.values[2]);
        float lpyvalue = 0;
        if(xTick+1>N_windows)
            lpyvalue = filter(yvalue);
        else
            value_buf[xTick] = yvalue;
        float[] euler = mMadgwick.rotMat2euler(mMadgwick.quatern2rotMat(mMadgwick.MadgwickAHRSupdateGyro(gyroEvent.values[0], gyroEvent.values[1], gyroEvent.values[2])));
        heading = euler[2];
        tvHeading.setText(String.format("%.2f", heading * 180 / Math.PI));
        //Log.d(TAG,String.format("%.2f,%f",timestamp,lpyvalue));
        if (stepDetecter(timestamp,lpyvalue)){
            stepNo= stepNo+1;
            if(stepNo==1) {
                locationx = (float)(0f + stepLength * Math.sin(heading));
                locationy = (float)(0f + stepLength * Math.cos(heading));
            }
            else {
                locationx = (float)(locationx + stepLength * Math.sin(heading));
                locationy = (float)(locationy + stepLength * Math.cos(heading));
            }
            tvStep.setText(String.format("%s",stepNo));
            tvLocation.setText(String.format("( %.2f, %.2f)", locationx, locationy));
            map.addStep(new PointF(-locationx,locationy));
            map.setPos(-locationx * map.getScaleX(), locationy * map.getScaleY(), 30, 30, (float) Math.PI, true);
            multiTouchView.invalidate();
        }
        xTick++;
    }
    @Override
    public void invalidate() {
        if (multiTouchView != null) {
            multiTouchView.invalidate();
        }
    }
    protected TextView backgroundPathTextView;
    protected final Context context = this ;
    protected static final int FILEBROWSER_REQUEST =1;

    protected void setBackgroundImage(String path) {

        try {
            Bitmap bmp = BitmapFactory.decodeFile(path);
            site.setBackgroundBitmap(bmp);
            map.setBackgroundImage(bmp);
            site.setSize(bmp.getWidth(), bmp.getHeight());
            map.setSize(bmp.getWidth(), bmp.getHeight());
            user.setRelativePosition(bmp.getWidth() / 2, bmp.getHeight() / 2);
            multiTouchView.invalidate();
            Toast.makeText(context, "set " + path + " as new background image!", Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            Logger.e("could not set background", e);
            Toast.makeText(context, getString(R.string.project_site_set_background_failed, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }
    protected Dialog CreateDialog(int id) {
        switch (id) {

            case DIALOG_SET_BACKGROUND:

                AlertDialog.Builder bckgAlert = new AlertDialog.Builder(this);
                bckgAlert.setTitle(R.string.project_site_dialog_background_title);
                bckgAlert.setMessage(R.string.project_site_dialog_background_message);

                LinearLayout bckgLayout = new LinearLayout(this);
                bckgLayout.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                bckgLayout.setGravity(Gravity.CENTER);
                bckgLayout.setOrientation(LinearLayout.VERTICAL);
                bckgLayout.setPadding(5, 5, 5, 5);

                final TextView pathTextView = new TextView(this);
                backgroundPathTextView = pathTextView;
                pathTextView.setText(R.string.project_site_dialog_background_default_path);
                pathTextView.setPadding(10, 0, 10, 10);

                bckgLayout.addView(pathTextView);

                Button pathButton = new Button(this);
                pathButton.setText(R.string.project_site_dialog_background_path_button);
                pathButton.setOnClickListener(new View.OnClickListener() {

                    @Override
                    public void onClick(View v) {
                        Intent i = new Intent(context, FileBrowser.class);
                        i.putExtra(FileBrowser.EXTRA_MODE, FileBrowser.MODE_LOAD);
                        i.putExtra(FileBrowser.EXTRA_ALLOWED_EXTENSIONS, "jpg,png,gif,jpeg,bmp");
                        startActivityForResult(i, FILEBROWSER_REQUEST);
                    }

                });

                bckgLayout.addView(pathButton);

                bckgAlert.setView(bckgLayout);

                bckgAlert.setPositiveButton(getString(R.string.button_ok), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        setBackgroundImage(pathTextView.getText().toString());
                            Dialogshow(DIALOG_ASK_CHANGE_SCALE);
                    }
                });

                bckgAlert.setNegativeButton(getString(R.string.button_cancel), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        // Canceled.
                        //freshSite = false;
                    }
                });

                Dialog bckgDialog = bckgAlert.create();
                bckgDialog.setCanceledOnTouchOutside(true);

                return bckgDialog;

            case DIALOG_SET_SCALE_OF_MAP:
                AlertDialog.Builder scaleOfMapDialog = new AlertDialog.Builder(this);

                scaleOfMapDialog.setTitle(R.string.project_site_dialog_scale_of_map_title);
                scaleOfMapDialog.setMessage(R.string.project_site_dialog_scale_of_map_message);

                // Set an EditText view to get user input
                final EditText scaleInput = new EditText(this);
                scaleInput.setSingleLine(true);
                scaleInput.setRawInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
                scaleOfMapDialog.setView(scaleInput);

                scaleOfMapDialog.setPositiveButton(getString(R.string.button_ok), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {

                        try {
                            float value = Float.parseFloat(scaleInput.getText().toString());
                            setScaleOfMap(value);
                        } catch (NumberFormatException nfe) {
                            Logger.w("Wrong number format format!");
                            Toast.makeText(context, getString(R.string.not_a_number, scaleInput.getText()), Toast.LENGTH_SHORT).show();
                        }
                    }
                });

                scaleOfMapDialog.setNegativeButton(getString(R.string.button_cancel), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        // Canceled.
                    }
                });

                return scaleOfMapDialog.create();

            case DIALOG_ASK_CHANGE_SCALE:

                AlertDialog.Builder askScaleBuilder = new AlertDialog.Builder(context);
                askScaleBuilder.setTitle(R.string.project_site_dialog_ask_change_scale_title);
                askScaleBuilder.setMessage(R.string.project_site_dialog_ask_change_scale_message);

                askScaleBuilder.setPositiveButton(getString(R.string.button_yes), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        scaleOfMap();
                    }

                });

                askScaleBuilder.setNegativeButton(getString(R.string.button_no), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        // Canceled.
                        //freshSite = false;
                    }
                });

                return askScaleBuilder.create();

            case DIALOG_ASK_FOR_NORTH:

                AlertDialog.Builder askNorthBuilder = new AlertDialog.Builder(context);
                askNorthBuilder.setTitle(R.string.project_site_dialog_ask_north_title);
                askNorthBuilder.setMessage(R.string.project_site_dialog_ask_north_message);

                askNorthBuilder.setPositiveButton(getString(R.string.button_yes), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        setMapNorth();
                        //freshSite = false;
                    }

                });

                askNorthBuilder.setNegativeButton(getString(R.string.button_no), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        // Canceled.
                        //freshSite = false;
                    }
                });

                return askNorthBuilder.create();
            default:
                return CreateDialog(id);
        }
    }
    protected void setScaleOfMap(float scale) {
        float mapScale = scalerDistance / scale;
        site.setGridSpacingX(mapScale);
        site.setGridSpacingY(mapScale);
        LocationServiceFactory.getLocationService().setGridSpacing(site.getGridSpacingX(), site.getGridSpacingY());
        MultiTouchDrawable.setGridSpacing(mapScale, mapScale);
        multiTouchView.invalidate();
        Toast.makeText(this, getString(R.string.project_site_mapscale_changed, mapScale), Toast.LENGTH_SHORT).show();
            Dialogshow(DIALOG_ASK_FOR_NORTH);
    }
    protected void onMapScaleSelected() {
        scalerDistance = scaler.getSliderDistance();
        scaler.removeScaleSliders();
        map.removeSubDrawable(scaler);
        scaler = null;
        invalidate();
        Dialogshow(DIALOG_SET_SCALE_OF_MAP);
    }
    protected void scaleOfMap() {
        if (scaler == null) {
            scaler = new ScaleLineDrawable(context, map, new OkCallback() {

                @Override
                public void onOk() {
                    onMapScaleSelected();
                }
            });
            scaler.getSlider(1).setRelativePosition(user.getRelativeX() - 80, user.getRelativeY());
            scaler.getSlider(2).setRelativePosition(user.getRelativeX() + 80, user.getRelativeY());
            multiTouchView.invalidate();
        } else {
            onMapScaleSelected();
        }
    }
    protected void setMapNorth() {
        if (northDrawable == null) {
            // Stop auto-rotate when map north is set
            ((ToggleButton) findViewById(R.id.project_site_toggle_autorotate)).setChecked(false);
            map.stopAutoRotate();

            // create the icon the set the north
            northDrawable = new NorthDrawable(this, map, site) {

                /*
                 * (non-Javadoc)
                 *
                 * @see at.fhstp.wificompass.view.NorthDrawable#onOk()
                 */
                @Override
                public void onOk() {
                    super.onOk();
                    northDrawable = null;
                    site.setNorth(ToolBox.normalizeAngle(adjustmentAngle));
                    map.setAngleAdjustment(site.getNorth());

                    LocationServiceFactory.getLocationService().setRelativeNorth(site.getNorth());
                    Logger.d("set adjustment angle of map to " + site.getNorth());
                    Toast.makeText(ctx, R.string.project_site_nort_set, Toast.LENGTH_SHORT).show();
                    //saveProjectSite();
                    getInitAngle = true;
                }

            };
            northDrawable.setRelativePosition(site.getWidth() / 2, site.getHeight() / 2);
            northDrawable.setAngle(map.getAngle() + site.getNorth());

        } else {
            map.removeSubDrawable(northDrawable);
            // do not set the angle, if the menu option is clicked
            // site.setNorth(northDrawable.getAngle());
            // LocationServiceFactory.getLocationService().setRelativeNorth(site.getNorth());
            northDrawable = null;
        }

        multiTouchView.invalidate();

    }
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {

        Logger.d("Activity result of " + requestCode + " " + resultCode + " " + (data != null ? data.toString() : ""));

        switch (requestCode) {
            case FILEBROWSER_REQUEST:

                if (resultCode == Activity.RESULT_OK && data != null) {
                    String path = data.getExtras().getString(FileBrowser.EXTRA_PATH);

                    if (backgroundPathTextView != null) {
                        backgroundPathTextView.setText(path);
                    } else {
                        Logger.w("the background image dialog textview should not be null?!?");
                    }
                }
                break;

            default:
                super.onActivityResult(requestCode, resultCode, data);
                break;
        }

    }
    @Override
    public void onLocationChange(Location loc) {
        // info from StepDetectionProvider, that the location changed.
        user.setRelativePosition(loc.getX(), loc.getY());
        map.addStep(new PointF(loc.getX(), loc.getY()));
        messageHandler.sendEmptyMessage(MESSAGE_REFRESH);
    }

    public void refreshUI(float heading){
        tvHeading.setText(String.format("%.2f",heading));
    }

    protected void Dialogshow(int id){
        CreateDialog(id).show();
    }
}