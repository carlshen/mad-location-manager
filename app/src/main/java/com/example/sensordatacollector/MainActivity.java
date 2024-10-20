package com.example.sensordatacollector;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.RadioGroup;
import android.widget.TextView;

import com.elvishew.xlog.LogLevel;
import com.elvishew.xlog.XLog;
import com.elvishew.xlog.printer.AndroidPrinter;
import com.elvishew.xlog.printer.Printer;
import com.elvishew.xlog.printer.file.FilePrinter;
import com.elvishew.xlog.printer.file.backup.FileSizeBackupStrategy;
import com.elvishew.xlog.printer.file.naming.FileNameGenerator;
import mad.location.manager.lib.Commons.Utils;
import mad.location.manager.lib.Interfaces.ILogger;
import mad.location.manager.lib.Interfaces.LocationServiceInterface;
import mad.location.manager.lib.Loggers.GeohashRTFilter;
import mad.location.manager.lib.SensorAux.SensorCalibrator;
import mad.location.manager.lib.Services.KalmanLocationService;
import mad.location.manager.lib.Services.ServicesHelper;
import mad.location.manager.lib.Services.Settings;

import com.tencent.map.geolocation.TencentLocation;
import com.tencent.map.geolocation.TencentLocationListener;
import com.tencent.map.geolocation.TencentLocationManager;
import com.tencent.map.geolocation.TencentLocationRequest;
import com.tencent.tencentmap.mapsdk.maps.CameraUpdate;
import com.tencent.tencentmap.mapsdk.maps.CameraUpdateFactory;
import com.tencent.tencentmap.mapsdk.maps.MapView;
import com.tencent.tencentmap.mapsdk.maps.TencentMap;
import com.tencent.tencentmap.mapsdk.maps.model.BitmapDescriptor;
import com.tencent.tencentmap.mapsdk.maps.model.BitmapDescriptorFactory;
import com.tencent.tencentmap.mapsdk.maps.model.LatLng;
import com.tencent.tencentmap.mapsdk.maps.model.MarkerOptions;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Locale;
import java.util.TimeZone;

public class MainActivity extends AppCompatActivity implements LocationServiceInterface, TencentLocationListener, ILogger {

    private SharedPreferences mSharedPref;

    private String xLogFolderPath;
    private static SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    class ChangableFileNameGenerator implements FileNameGenerator {
        private String fileName;
        public void setFileName(String fileName) {
            this.fileName = fileName;
        }
        public ChangableFileNameGenerator() {
        }
        @Override
        public boolean isFileNameChangeable() {
            return true;
        }
        @Override
        public String generateFileName(int logLevel, long timestamp) {
            return fileName;
        }
    }

    ChangableFileNameGenerator xLogFileNameGenerator = new ChangableFileNameGenerator();
    public void initXlogPrintersFileName() {
        sdf.setTimeZone(TimeZone.getDefault());
        String dateStr = sdf.format(System.currentTimeMillis());
        String fileName = dateStr;
        final int secondsIn24Hour = 86400; //I don't think that it's possible to press button more frequently
        for (int i = 0; i < secondsIn24Hour; ++i) {
            fileName = String.format("%s_%d", dateStr, i);
            File f = new File(xLogFolderPath, fileName);
            if (!f.exists())
                break;
        }
        xLogFileNameGenerator.setFileName(fileName);
    }

    @Override
    public void log2file(String format, Object... args) {
        XLog.i(format, args);
    }


    class RefreshTask extends AsyncTask {
        boolean needTerminate = false;
        long deltaT;
        Context owner;
        RefreshTask(long deltaTMs, Context owner) {
            this.owner = owner;
            this.deltaT = deltaTMs;
        }

        @Override
        protected Object doInBackground(Object[] objects) {
            while (!needTerminate) {
                try {
                    Thread.sleep(deltaT);
                    publishProgress();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            return null;
        }

        @Override
        protected void onProgressUpdate(Object... values) {
            TextView tvStatus = (TextView) findViewById(R.id.tvStatus);
            TextView tvDistance = (TextView) findViewById(R.id.tvDistance);
            if (m_isLogging) {
                if (m_geoHashRTFilter == null)
                    return;

                tvDistance.setText(String.format(
                        "Distance (geo): %fm\n" +
                                "Distance (geo) HP: %fm\n" +
                                "Distance as is : %fm\n" +
                                "Distance as is HP: %fm",
                        m_geoHashRTFilter.getDistanceGeoFiltered(),
                        m_geoHashRTFilter.getDistanceGeoFilteredHP(),
                        m_geoHashRTFilter.getDistanceAsIs(),
                        m_geoHashRTFilter.getDistanceAsIsHP()));
            } else {
                if (!m_sensorCalibrator.isInProgress())
                    return;

                tvStatus.setText(m_sensorCalibrator.getCalibrationStatus());
                if (m_sensorCalibrator.getDcAbsLinearAcceleration().isCalculated() &&
                        m_sensorCalibrator.getDcLinearAcceleration().isCalculated()) {
                    set_isCalibrating(false, false);
                    tvDistance.setText(m_sensorCalibrator.getDcLinearAcceleration().deviationInfoString());
                }
            }
        }
    }
    /*********************************************************/

    private final static String TAG = "MainActivity";
    private TencentLocationManager mLocationManager; // 声明一个腾讯定位管理器对象
    private MapView m_mapView; // 声明一个地图视图对象
    private TencentMap mTencentMap; // 声明一个腾讯地图对象
    private boolean isFirstLoc = true; // 是否首次定位
//    private MapPresenter m_presenter;
//    private MapboxMap m_map;
//    private MapView m_mapView;

    private GeohashRTFilter m_geoHashRTFilter;
    private SensorCalibrator m_sensorCalibrator = null;
    private boolean m_isLogging = false;
    private boolean m_isCalibrating = false;
    private RefreshTask m_refreshTask = new RefreshTask(1000l, this);

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100) {
            SensorManager sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

            if (sensorManager == null || locationManager == null) {
                System.exit(1);
            }

            m_sensorCalibrator = new SensorCalibrator(sensorManager);
            ServicesHelper.getLocationService(this, value -> {
                set_isLogging(value.IsRunning());
            });
            set_isCalibrating(false, true);
        }
    }

    private void set_isLogging(boolean isLogging) {
        Button btnStartStop = (Button) findViewById(R.id.btnStartStop);
        TextView tvStatus = (TextView) findViewById(R.id.tvStatus);
        Button btnCalibrate = (Button) findViewById(R.id.btnCalibrate);
        Button gpsProvider = (Button) findViewById(R.id.button_gps);
        Button fusedProvider = (Button) findViewById(R.id.button_fused);
        String btnStartStopText;
        String btnTvStatusText;

        if (isLogging) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
//            m_presenter.stop();
//            m_presenter.start();
            m_geoHashRTFilter.stop();
            m_geoHashRTFilter.reset(this);
            Settings.LocationProvider provider = Settings.LocationProvider.GPS;
            if (gpsProvider.isSelected())
            {
                provider =   Settings.LocationProvider.GPS;
            }
            else if (fusedProvider.isSelected())
            {
                provider =  Settings.LocationProvider.FUSED;
            }
            Settings.LocationProvider finalProvider = provider;
            ServicesHelper.getLocationService(this, value -> {
                if (value.IsRunning()) {
                    return;
                }
                value.stop();
                initXlogPrintersFileName();
                Settings settings =
                        new Settings(
                                Utils.ACCELEROMETER_DEFAULT_DEVIATION,
                                Integer.parseInt(mSharedPref.getString("pref_gps_min_distance", "10")),
                                Integer.parseInt(mSharedPref.getString("pref_gps_min_time", "2000")),
                                Integer.parseInt(mSharedPref.getString("pref_position_min_time", "500")),
                                Integer.parseInt(mSharedPref.getString("pref_geohash_precision", "6")),
                                Integer.parseInt(mSharedPref.getString("pref_geohash_min_point", "2")),
                                Double.parseDouble(mSharedPref.getString("pref_sensor_frequency", "10")),
                                this,
                                true,
                                false,
                                true,
                                Utils.DEFAULT_VEL_FACTOR,
                                Utils.DEFAULT_POS_FACTOR,
                                finalProvider
                        );
                value.reset(settings); //warning!! here you can adjust your filter behavior
                value.start();
            });

            btnStartStopText = "Stop tracking";
            btnTvStatusText = "Tracking is in progress";

        } else {
            btnStartStopText = "Start tracking";
            btnTvStatusText = "Paused";
//            m_presenter.stop();
            ServicesHelper.getLocationService(this, value -> {
                value.stop();
            });
        }

        if (btnStartStop != null)
            btnStartStop.setText(btnStartStopText);
        if (tvStatus != null)
            tvStatus.setText(btnTvStatusText);

        btnCalibrate.setEnabled(!isLogging);
        m_isLogging = isLogging;
    }

    private void set_isCalibrating(boolean isCalibrating, boolean byUser) {
        Button btnStartStop = (Button) findViewById(R.id.btnStartStop);
        TextView tvStatus = (TextView) findViewById(R.id.tvStatus);
        Button btnCalibrate = (Button) findViewById(R.id.btnCalibrate);
        String btnCalibrateText;
        String tvStatusText;

        if (isCalibrating) {
            btnCalibrateText = "Stop calibration";
            tvStatusText = "Calibrating";
            m_sensorCalibrator.reset();
            m_sensorCalibrator.start();
        } else {
            btnCalibrateText = "Start calibration";
            tvStatusText = byUser ? "Calibration finished by user" : "Calibration finished";
            m_sensorCalibrator.stop();
        }

        btnCalibrate.setText(btnCalibrateText);
        tvStatus.setText(tvStatusText);
        btnStartStop.setEnabled(!isCalibrating);
        m_isCalibrating = isCalibrating;
    }

    public void btnStartStop_click(View v) {
        set_isLogging(!m_isLogging);
    }
    public void btnCalibrate_click(View v) {
        set_isCalibrating(!m_isCalibrating, true);
    }

    private void initActivity() {

        String[] interestedPermissions;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN) {
            interestedPermissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
            };
        } else {
            interestedPermissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            };
        }

        ArrayList<String> lstPermissions = new ArrayList<>(interestedPermissions.length);
        for (String perm : interestedPermissions) {
            if (ActivityCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                lstPermissions.add(perm);
            }
        }

        if (!lstPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, lstPermissions.toArray(new String[0]),
                    100);
        } else {

            SensorManager sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

            if (sensorManager == null || locationManager == null) {
                System.exit(1);
            }

            m_sensorCalibrator = new SensorCalibrator(sensorManager);
            ServicesHelper.getLocationService(this, value -> {
                set_isLogging(value.IsRunning());
            });
            set_isCalibrating(false, true);
        }
    }

    //uncaught exceptions
    private Thread.UncaughtExceptionHandler defaultUEH;
    // handler listener
    private Thread.UncaughtExceptionHandler _unCaughtExceptionHandler = new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread thread, Throwable ex) {
            try {
                XLog.i("UNHANDLED EXCEPTION: %s, stack : %s", ex.toString(), ex.getStackTrace());
            } catch (Exception e) {
                Log.i("SensorDataCollector", String.format("Megaunhandled exception : %s, %s, %s",
                        e.toString(), ex.toString(), ex.getStackTrace()));
            }
            defaultUEH.uncaughtException(thread, ex);
        }
    };

    @Override
    public void locationChanged(Location location) {
//        if (m_map != null && m_presenter != null) {
//            if (!m_map.isMyLocationEnabled()) {
//                m_map.setMyLocationEnabled(true);
//                m_map.getMyLocationViewSettings().setForegroundTintColor(ContextCompat.getColor(this, R.color.red));
//            }
//
//            m_presenter.locationChanged(location, m_map.getCameraPosition());
//        }
    }

    public static final int FILTER_KALMAN_ONLY = 0;
    public static final int FILTER_KALMAN_WITH_GEO = 1;
    public static final int GPS_ONLY = 2;
    private int routeColors[] = {R.color.mapbox_blue, R.color.colorAccent, R.color.green};
    private int routeWidths[] = {1, 3, 1};
//    private Polyline lines[] = new Polyline[3];

//    @Override
//    public void showRoute(List<LatLng> route, int interestedRoute) {
//
//        CheckBox cbGps, cbFilteredKalman, cbFilteredKalmanGeo;
//        cbGps = (CheckBox) findViewById(R.id.cbGPS);
//        cbFilteredKalman = (CheckBox) findViewById(R.id.cbFilteredKalman);
//        cbFilteredKalmanGeo = (CheckBox) findViewById(R.id.cbFilteredKalmanGeo);
//        boolean enabled[] = {cbFilteredKalman.isChecked(), cbFilteredKalmanGeo.isChecked(), cbGps.isChecked()};
//        if (m_map != null) {
//            runOnUiThread(() ->
//                    m_mapView.post(() -> {
//                        if (lines[interestedRoute] != null)
//                            m_map.removeAnnotation(lines[interestedRoute]);
//
//                        if (!enabled[interestedRoute])
//                            route.clear(); //too many hacks here
//
//                        lines[interestedRoute] = m_map.addPolyline(new PolylineOptions()
//                                .addAll(route)
//                                .color(ContextCompat.getColor(this, routeColors[interestedRoute]))
//                                .width(routeWidths[interestedRoute]));
//                    }));
//        }
//    }

//    @Override
//    public void moveCamera(CameraPosition position) {
//        runOnUiThread(() ->
//                m_mapView.postDelayed(() -> {
//                    if (m_map != null) {
//                        m_map.animateCamera(CameraUpdateFactory.newCameraPosition(position));
//                    }
//                }, 100));
//    }

//    @Override
//    public void setAllGesturesEnabled(boolean enabled) {
//        if (enabled) {
//            m_mapView.postDelayed(() -> {
//                m_map.getUiSettings().setScrollGesturesEnabled(true);
//                m_map.getUiSettings().setZoomGesturesEnabled(true);
//                m_map.getUiSettings().setDoubleTapGesturesEnabled(true);
//            }, 500);
//        } else {
//            m_map.getUiSettings().setScrollGesturesEnabled(false);
//            m_map.getUiSettings().setZoomGesturesEnabled(false);
//            m_map.getUiSettings().setDoubleTapGesturesEnabled(false);
//        }
//    }

    public void setupMap(@Nullable Bundle savedInstanceState) {
        m_mapView = (MapView) findViewById(R.id.mapView);
//        m_mapView.onCreate(savedInstanceState);

//        m_presenter = new MapPresenter(this, this, m_geoHashRTFilter);
        // just comment as for pass build debug
//        m_mapView.getMapAsync(mapboxMap -> {
//            m_map = mapboxMap;
//            MainActivity this_ = this;
//            ProgressDialog progress = new ProgressDialog(this);
//            progress.setTitle("Loading");
//            progress.setMessage("Wait while map loading...");
//            progress.setCancelable(false); // disable dismiss by tapping outside of the dialog
//            progress.show();
//
//            m_map.setStyleUrl(BuildConfig.lightMapStyle);
//            m_map.setStyleUrl(Style.SATELLITE_STREETS, new MapboxMap.OnStyleLoadedListener() {
//                @Override
//                public void onStyleLoaded(String style) {
//                    m_map.getUiSettings().setLogoEnabled(false);
//                    m_map.getUiSettings().setAttributionEnabled(false);
//                    m_map.getUiSettings().setTiltGesturesEnabled(false);
//
//                    int leftMargin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16, getResources().getDisplayMetrics());
//                    int topMargin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 56, getResources().getDisplayMetrics());
//                    int rightMargin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16, getResources().getDisplayMetrics());
//                    int bottomMargin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16, getResources().getDisplayMetrics());
//                    m_map.getUiSettings().setCompassMargins(leftMargin, topMargin, rightMargin, bottomMargin);
//                    ServicesHelper.addLocationServiceInterface(this_);
//                    m_presenter.getRoute();
//                    progress.dismiss();
//                }
//            });
//        });
    }

    // 初始化视图
    private void initView() {
        RadioGroup rg_type = findViewById(R.id.rg_type);
        rg_type.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rb_common) {
                mTencentMap.setMapType(TencentMap.MAP_TYPE_NORMAL); // 设置普通地图
            } else if (checkedId == R.id.rb_satellite) {
                mTencentMap.setMapType(TencentMap.MAP_TYPE_SATELLITE); // 设置卫星地图
            }
        });
        CheckBox ck_traffic = findViewById(R.id.ck_traffic);
        ck_traffic.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mTencentMap.setTrafficEnabled(isChecked); // 是否显示交通拥堵状况
        });
    }

    // 初始化定位服务
    private void initLocation() {
        m_mapView = findViewById(R.id.mapView);
        mTencentMap = m_mapView.getMap(); // 获取腾讯地图对象
        mLocationManager = TencentLocationManager.getInstance(this);
        // 创建腾讯定位请求对象
        TencentLocationRequest request = TencentLocationRequest.create();
        request.setInterval(30000).setAllowGPS(true);
        request.setRequestLevel(TencentLocationRequest.REQUEST_LEVEL_ADMIN_AREA);
        mLocationManager.requestLocationUpdates(request, this); // 开始定位监听
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        defaultUEH = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(_unCaughtExceptionHandler);
//        Mapbox.getInstance(this, BuildConfig.access_token);
        setContentView(R.layout.activity_main);
        m_geoHashRTFilter = new GeohashRTFilter(Utils.GEOHASH_DEFAULT_PREC, Utils.GEOHASH_DEFAULT_MIN_POINT_COUNT);
//        setupMap(savedInstanceState);
        initLocation(); // 初始化定位服务
        initView(); // 初始化视图

        CheckBox cbGps, cbFilteredKalman, cbFilteredKalmanGeo;
        cbGps = (CheckBox) findViewById(R.id.cbGPS);
        cbFilteredKalman = (CheckBox) findViewById(R.id.cbFilteredKalman);
        cbFilteredKalmanGeo = (CheckBox) findViewById(R.id.cbFilteredKalmanGeo);
        CheckBox cb[] = {cbFilteredKalman, cbFilteredKalmanGeo, cbGps};
        for (int i = 0; i < 3; ++i) {
            if (cb[i] == null)
                continue;
            cb[i].setBackgroundColor(ContextCompat.getColor(this, routeColors[i]));
        }

        File esd = Environment.getExternalStorageDirectory();
        String storageState = Environment.getExternalStorageState();
        if (storageState != null && storageState.equals(Environment.MEDIA_MOUNTED)) {
//            xLogFolderPath = String.format("%s/%s/", esd.getAbsolutePath(), "SensorDataCollector");
            xLogFolderPath = String.format("%s/%s/", getExternalFilesDir(null).getAbsolutePath(), "SensorDataCollector");
            Log.d(KalmanLocationService.TAG, "xLogFolderPath = " + xLogFolderPath);
            Printer androidPrinter = new AndroidPrinter();             // Printer that print the log using android.util.Log
            initXlogPrintersFileName();
            Printer xLogFilePrinter = new FilePrinter
                    .Builder(xLogFolderPath)
                    .fileNameGenerator(xLogFileNameGenerator)
                    .backupStrategy(new FileSizeBackupStrategy(1024 * 1024 * 100)) //100MB for backup files
                    .build();
            XLog.init(LogLevel.ALL, androidPrinter, xLogFilePrinter);
        } else {
            //todo set some status
        }
        Button gpsProvider = (Button) findViewById(R.id.button_gps);
        Button fusedProvider = (Button) findViewById(R.id.button_fused);
        gpsProvider.setSelected(true);
        gpsProvider.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fusedProvider.setSelected(false);
                gpsProvider.setSelected(true);
            }
        });
        fusedProvider.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                gpsProvider.setSelected(false);
                fusedProvider.setSelected(true);
            }
        });
    }

    @Override
    public void onLocationChanged(TencentLocation location, int resultCode, String resultDesc) {
        if (resultCode == TencentLocation.ERROR_OK) { // 定位成功
            if (location != null && isFirstLoc) { // 首次定位
                isFirstLoc = false;
                // 创建一个经纬度对象
                LatLng latLng = new LatLng(location.getLatitude(), location.getLongitude());
                CameraUpdate update = CameraUpdateFactory.newLatLngZoom(latLng, 12);
                mTencentMap.moveCamera(update); // 把相机视角移动到指定地点
                // 从指定图片中获取位图描述
                BitmapDescriptor bitmapDesc = BitmapDescriptorFactory
                        .fromResource(R.drawable.icon_locate);
                MarkerOptions ooMarker = new MarkerOptions(latLng).draggable(false) // 不可拖动
                        .visible(true).icon(bitmapDesc).snippet("这是您的当前位置");
                mTencentMap.addMarker(ooMarker); // 往地图添加标记
            }
        } else { // 定位失败
            Log.d(TAG, "定位失败，错误代码为"+resultCode+"，错误描述为"+resultDesc);
        }
    }

    @Override
    public void onStatusUpdate(String s, int i, String s1) {}

    @Override
    protected void onStart() {
        super.onStart();

        // Set preferences data
        mSharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        mSharedPref = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        initActivity();
        if (m_mapView != null) {
            m_mapView.onStart();
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (m_mapView != null) {
            m_mapView.onStop();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (m_mapView != null) {
            m_mapView.onResume();
        }
        m_refreshTask = new RefreshTask(1000, this);
        m_refreshTask.needTerminate = false;
        m_refreshTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (m_mapView != null) {
            m_mapView.onPause();
        }

        m_refreshTask.needTerminate = true;
        m_refreshTask.cancel(true);
        if (m_sensorCalibrator != null) {
            m_sensorCalibrator.stop();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
//        if (mMapView != null) {
//            mMapView.onSaveInstanceState(outState);
//        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
//        if (mMapView != null) {
//            mMapView.onLowMemory();
//        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mLocationManager.removeUpdates(this); // 移除定位监听
        if (m_mapView != null) {
            m_mapView.onDestroy();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_settings:
                Intent intent = new Intent(this, SettingsActivity.class);
                startActivity(intent);
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

}
