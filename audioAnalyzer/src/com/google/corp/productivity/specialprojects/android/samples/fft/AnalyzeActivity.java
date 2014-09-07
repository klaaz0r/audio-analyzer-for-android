/* Copyright 2011 Google Inc.
 *
 *Licensed under the Apache License, Version 2.0 (the "License");
 *you may not use this file except in compliance with the License.
 *You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *Unless required by applicable law or agreed to in writing, software
 *distributed under the License is distributed on an "AS IS" BASIS,
 *WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *See the License for the specific language governing permissions and
 *limitations under the License.
 *
 * @author Stephen Uhler
 * @author bewantbe@gmail.com
 */

package com.google.corp.productivity.specialprojects.android.samples.fft;

import com.google.corp.productivity.specialprojects.android.samples.fft.AnalyzeView.Ready;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.support.v4.view.GestureDetectorCompat;
import android.text.Html;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.GestureDetector;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Audio "FFT" analyzer.
 * @author suhler@google.com (Stephen Uhler)
 */

public class AnalyzeActivity extends Activity
    implements OnLongClickListener, OnClickListener,
               OnItemClickListener, Ready {
  static final String TAG="AnalyzeActivity";

  private AnalyzeView graphView;
  private Looper samplingThread;
  private GestureDetectorCompat mDetector;

  private final static double SAMPLE_VALUE_MAX = 32767.0;   // Maximum signal value
  private final static int RECORDER_AGC_OFF = MediaRecorder.AudioSource.VOICE_RECOGNITION;
  private final static int BYTE_OF_SAMPLE = 2;
  
  private static int fftLen = 2048;
  private static int sampleRate = 8000;
  private static String wndFuncName;
  private static int nFFTAverage = 2;

  private static boolean showLines;
  //private boolean isTesting = false;
  private static int audioSourceId = RECORDER_AGC_OFF;
  private boolean isMeasure = true;
  private boolean isAWeighting = false;
  
  float listItemTextSize = 20;  // XXX define it in res

  PopupWindow popupMenuSampleRate;
  PopupWindow popupMenuFFTLen;
  PopupWindow popupMenuAverage;
  
  public PopupWindow popupMenuCreate(String[] popUpContents, int resId) {
    
    // initialize a pop up window type
    PopupWindow popupWindow = new PopupWindow(this);

    // the drop down list is a list view
    ListView listView = new ListView(this);
    
    // set our adapter and pass our pop up window contents
    ArrayAdapter<String> aa = popupMenuAdapter(popUpContents);
    listView.setAdapter(aa);
    
    // set the item click listener
    listView.setOnItemClickListener(this);

    listView.setTag(resId);  // button res ID, so we can trace back which button is pressed
    
    // get max text width
    Paint mTestPaint = new Paint();
    mTestPaint.setTextSize(listItemTextSize);
    float w = 0;
    for (int i = 0; i < popUpContents.length; i++) {
      String st = popUpContents[i].split("::")[0];
      float wi = mTestPaint.measureText(st);
      if (w < wi) {
        w = wi;
      }
    }
    
    // left and right padding, at least +7, or the whole app will stop respond, don't know why
    w = w + 25;
    if (w < 60) {
      w = 60;
    }

    // some other visual settings
    popupWindow.setFocusable(true);
    popupWindow.setHeight(WindowManager.LayoutParams.WRAP_CONTENT);
    // Set window width according to max text width
    popupWindow.setWidth((int)(w));
    // also set button width
    ((Button) findViewById(resId)).setWidth((int)w);
    // Set the text on button in updatePreferenceSaved()
    
    // set the list view as pop up window content
    popupWindow.setContentView(listView);
    
    return popupWindow;
  }
  
  /*
   * adapter where the list values will be set
   */
  private ArrayAdapter<String> popupMenuAdapter(String itemTagArray[]) {
    ArrayAdapter<String> adapter =
      new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, itemTagArray) {
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
          // setting the ID and text for every items in the list
          String item = getItem(position);
          String[] itemArr = item.split("::");
          String text = itemArr[0];
          String id = itemArr[1];

          // visual settings for the list item
          TextView listItem = new TextView(AnalyzeActivity.this);

          listItem.setText(text);
          listItem.setTag(id);
          listItem.setTextSize(listItemTextSize);
          listItem.setPadding(5, 5, 5, 5);
          listItem.setTextColor(Color.WHITE);
          listItem.setGravity(android.view.Gravity.CENTER);
          
          return listItem;
        }
      };
    return adapter;
  }

  @Override
  public void onCreate(Bundle savedInstanceState) {
//  Debug.startMethodTracing("calc");
    super.onCreate(savedInstanceState);
    setContentView(R.layout.main);
    
    final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
    Log.i(TAG, " max mem = " + maxMemory + "k");
    
    // set and get preferences in PreferenceActivity
    PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
    // Set variable according to the preferences
    updatePreferenceSaved();
    
    graphView = (AnalyzeView) findViewById(R.id.plot);

    // travel Views, and attach ClickListener to the views that contain android:tag="select"  
    visit((ViewGroup) graphView.getRootView(), new Visit() {
      @Override
      public void exec(View view) {
        view.setOnLongClickListener(AnalyzeActivity.this);
        view.setOnClickListener(AnalyzeActivity.this);
        ((TextView) view).setFreezesText(true);
      }
    }, "select");
    
    Resources res = getResources();
    getAudioSourceNameFromIdPrepare(res);
    
    /// initialize pop up window items list
    // http://www.codeofaninja.com/2013/04/show-listview-as-drop-down-android.html
    popupMenuSampleRate = popupMenuCreate( validateAudioRates(
        res.getStringArray(R.array.sample_rates)), R.id.button_sample_rate);
    popupMenuFFTLen = popupMenuCreate(
        res.getStringArray(R.array.fft_len), R.id.button_fftlen);
    popupMenuAverage = popupMenuCreate(
        res.getStringArray(R.array.fft_ave_num), R.id.button_average);
    
    mDetector = new GestureDetectorCompat(this, new AnalyzerGestureListener());

    setTextViewFontSize();
    
    // XXX :
    graphView.switch2Spectrogram(sampleRate, fftLen);
  }

  // Set text font size of textview_cur and textview_peak
  // according to space left
  @SuppressWarnings("deprecation")
  private void setTextViewFontSize() {
    TextView tv = (TextView) findViewById(R.id.textview_cur);

    Paint mTestPaint = new Paint();
    mTestPaint.set(tv.getPaint());
    mTestPaint.setTextSize(tv.getTextSize());
    mTestPaint.setTypeface(Typeface.MONOSPACE);
    
    final String text = "Peak:XXXXX.XHz(AX#+XX) -XXX.XdB";
    Display display = getWindowManager().getDefaultDisplay();
    Resources r = getResources();
    float px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 60, r.getDisplayMetrics());
    px = display.getWidth() - px - 5;  // pixels left
    
    // At this point tv.getWidth(), tv.getLineCount() will return 0
    Log.i(TAG, "  px = " + px);
    Log.i(TAG, "  mTestPaint.measureText(text) = " + mTestPaint.measureText(text));
    
    float fs = tv.getTextSize();
    Log.i(TAG, "  fs_0 = " + fs);
    while (mTestPaint.measureText(text) > px && fs > 5) {
      fs -= 0.5;
      mTestPaint.setTextSize(fs);
    }
    Log.i(TAG, "  fs_1 = " + fs);
    ((TextView) findViewById(R.id.textview_cur)).setTextSize(fs);
    ((TextView) findViewById(R.id.textview_peak)).setTextSize(fs);

  }
  
  @SuppressWarnings("deprecation")
  public void showPopupMenu(View view) {
    // popup menu position
    // In API 19, we can use showAsDropDown(View anchor, int xoff, int yoff, int gravity)
    // The problem in showAsDropDown (View anchor, int xoff, int yoff) is
    // it may show the window in wrong direction (so that we can't see it)
    int[] wl = new int[2];
    view.getLocationInWindow(wl);
    int x_left = wl[0];
    int y_bottom = getWindowManager().getDefaultDisplay().getHeight() - wl[1];
    int gravity = android.view.Gravity.LEFT | android.view.Gravity.BOTTOM;
    Log.i(TAG, " showPupupMenu()");
    Log.i(TAG, " wl = " + wl[0] + ", " + wl[1]);
    
    switch (view.getId()) {
    case R.id.button_sample_rate:
      popupMenuSampleRate.showAtLocation(view, gravity, x_left, y_bottom);
//      popupMenuSampleRate.showAsDropDown(view, 0, 0);
      break;
    case R.id.button_fftlen:
      popupMenuFFTLen.showAtLocation(view, gravity, x_left, y_bottom);
//      popupMenuFFTLen.showAsDropDown(view, 0, 0);
      break;
    case R.id.button_average:
      popupMenuAverage.showAtLocation(view, gravity, x_left, y_bottom);
//      popupMenuAverage.showAsDropDown(view, 0, 0);
      break;
    }
  }

  // popup menu click listener
  // read chosen preference, save the preference, set the state.
  @Override
  public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
    // get the text and set it as the button text
    String selectedItemText = ((TextView) v).getText().toString();

    int buttonId = Integer.parseInt((parent.getTag().toString()));
    Button buttonView = (Button) findViewById(buttonId);
    buttonView.setText(selectedItemText);

    boolean b_need_restart_audio;
    // get the tag, which is the value we are going to use
    String selectedItemTag = ((TextView) v).getTag().toString();

    SharedPreferences sharedPref = this.getPreferences(Context.MODE_PRIVATE);
    SharedPreferences.Editor editor = sharedPref.edit();
    
    // dismiss the pop up
    switch (buttonId) {
    case R.id.button_sample_rate:
      popupMenuSampleRate.dismiss();
      sampleRate = Integer.parseInt(selectedItemTag);
      RectF bounds = graphView.getBounds();
      bounds.right = sampleRate / 2;
      graphView.setBounds(bounds);
      b_need_restart_audio = true;
      editor.putInt("button_sample_rate", sampleRate);
      break;
    case R.id.button_fftlen:
      popupMenuFFTLen.dismiss();
      fftLen = Integer.parseInt(selectedItemTag);
      b_need_restart_audio = true;
      editor.putInt("button_fftlen", fftLen);
      break;
    case R.id.button_average:
      popupMenuAverage.dismiss();
      nFFTAverage = Integer.parseInt(selectedItemTag);
      b_need_restart_audio = false;
      editor.putInt("button_average", nFFTAverage);
      break;
    default:
      Log.w(TAG, "onItemClick(): no this button");
      b_need_restart_audio = false;
    }

//    Toast.makeText(this, "Dog ID is: " + selectedItemTag, Toast.LENGTH_SHORT).show();
    editor.commit();
    
    if (b_need_restart_audio) {
      reRecur();
      updateAllLabels();
    }
  }

  /**
   * Run processClick() for views, transferring the state in the textView to our
   * internal state, then begin sampling and processing audio data
   */

  @Override
  protected void onResume() {
    super.onResume();
    
    // load preferences
    SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
    boolean keepScreenOn = sharedPref.getBoolean("keepScreenOn", true);
    if (keepScreenOn) {
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    } else {
      getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    float b = graphView.getBounds().bottom;
    b = Float.parseFloat(sharedPref.getString("spectrumRange",
                         Double.toString(b)));
    graphView.setBoundsBottom(b);
    double d = graphView.getLowerBound();
    d = Double.parseDouble(sharedPref.getString("spectrogramRange",
                           Double.toString(d)));
    graphView.setLowerBound(d);

    // travel the views with android:tag="select" to get default setting values  
    visit((ViewGroup) graphView.getRootView(), new Visit() {
      @Override
      public void exec(View view) {
        processClick(view);
      }
    }, "select");
    graphView.setReady(this);

    samplingThread = new Looper();
    samplingThread.start();
  }

  @Override
  protected void onPause() {
    super.onPause();
    samplingThread.finish();
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  }

  @Override
  protected void onDestroy() {
//    Debug.stopMethodTracing();
    super.onDestroy();
  }

  @Override
  public void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
  }

  @Override
  public void onRestoreInstanceState(Bundle bundle) {
    super.onRestoreInstanceState(bundle);
  }
  
  
  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
      MenuInflater inflater = getMenuInflater();
      inflater.inflate(R.menu.info, menu);
      return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
      Log.i(TAG, item.toString());
      switch (item.getItemId()) {
      case R.id.info:
        showInstructions();
        return true;
      case R.id.settings:
        Intent settings = new Intent(getBaseContext(), MyPreferences.class);
        startActivity(settings);
        return true;
      case R.id.info_recoder:
        Intent int_info_rec = new Intent(this, InfoRecActivity.class);
        startActivity(int_info_rec);
      return true;
      default:
          return super.onOptionsItemSelected(item);
      }
  }

  private void showInstructions() {
    TextView tv = new TextView(this);
    tv.setMovementMethod(new ScrollingMovementMethod());
    tv.setText(Html.fromHtml(getString(R.string.instructions_text)));
    new AlertDialog.Builder(this)
      .setTitle(R.string.instructions_title)
      .setView(tv)
      .setNegativeButton(R.string.dismiss, null)
      .create().show();
  }

  void updatePreferenceSaved() {
    SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
    showLines   = sharedPref.getBoolean("showLines", false);
    audioSourceId = Integer.parseInt(sharedPref.getString("audioSource", Integer.toString(RECORDER_AGC_OFF)));
    wndFuncName = sharedPref.getString("windowFunction", "Blackman Harris");
    
    // load as key-value pair
    sharedPref = this.getPreferences(Context.MODE_PRIVATE);
    sampleRate  = sharedPref.getInt("button_sample_rate", 16000);
    fftLen      = sharedPref.getInt("button_fftlen",       2048);
    nFFTAverage = sharedPref.getInt("button_average",         2);
    
    Log.i(TAG, "  updatePreferenceSaved(): sampleRate  = " + sampleRate);
    Log.i(TAG, "  updatePreferenceSaved(): fftLen      = " + fftLen);
    Log.i(TAG, "  updatePreferenceSaved(): nFFTAverage = " + nFFTAverage);
    ((Button) findViewById(R.id.button_sample_rate)).setText(Integer.toString(sampleRate));
    ((Button) findViewById(R.id.button_fftlen     )).setText(Integer.toString(fftLen));
    ((Button) findViewById(R.id.button_average    )).setText(Integer.toString(nFFTAverage));
  }
  
  static String[] as;
  static int[] asid;
  private void getAudioSourceNameFromIdPrepare(Resources res) {
    as   = res.getStringArray(R.array.audio_source);
    String[] sasid = res.getStringArray(R.array.audio_source_id);
    asid = new int[as.length];
    for (int i = 0; i < as.length; i++) {
      asid[i] = Integer.parseInt(sasid[i]);
    }
  }
  
  // XXX, so ugly but work. Tell me if there is better way to do it.
  private static String getAudioSourceNameFromId(int id) {
    for (int i = 0; i < as.length; i++) {
      if (asid[i] == id) {
        return as[i];
      }
    }
    Log.e(TAG, "getAudioSourceName(): no this entry.");
    return "";
  }
  
  // I'm using a old cell phone -- API level 9 (android 2.3.6)
  // http://developer.android.com/guide/topics/ui/settings.html
  @SuppressWarnings("deprecation")
  public static class MyPreferences extends PreferenceActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
      super.onCreate(savedInstanceState);
      addPreferencesFromResource(R.xml.preferences);
      // as soon as the user modifies a preference,
      // the system saves the changes to a default SharedPreferences file
    }

    SharedPreferences.OnSharedPreferenceChangeListener prefListener =
        new SharedPreferences.OnSharedPreferenceChangeListener() {
      public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        Log.i(TAG, key + "=" + prefs);
        if (key == null || key.equals("showLines")) {
          showLines = prefs.getBoolean("showLines", false);
        }
        if (key == null || key.equals("windowFunction")) {
          wndFuncName = prefs.getString("windowFunction", "Blackman Harris");
          Preference connectionPref = findPreference(key);
          connectionPref.setSummary(prefs.getString(key, ""));
        }
        if (key == null || key.equals("audioSource")) {
          String asi = prefs.getString("audioSource", Integer.toString(RECORDER_AGC_OFF));
          audioSourceId = Integer.parseInt(asi);
          Preference connectionPref = findPreference(key);
          connectionPref.setSummary(getAudioSourceNameFromId(audioSourceId));
        }
      }
    };
    @Override
    protected void onResume() {
        super.onResume();
        getPreferenceScreen().getSharedPreferences()
                .registerOnSharedPreferenceChangeListener(prefListener);
    }
    @Override
    protected void onPause() {
        super.onPause();
        getPreferenceScreen().getSharedPreferences()
                .unregisterOnSharedPreferenceChangeListener(prefListener);
    }
  }
  
  private boolean isInGraphView(float x, float y) {
    graphView.getLocationInWindow(windowLocation);
    return x>windowLocation[0] && y>windowLocation[1] && x<windowLocation[0]+graphView.getWidth() && y<windowLocation[1]+graphView.getHeight();
  }
  
  /**
   * Gesture Listener for graphView (and possibly other views)
   * XXX  How to attach these events to the graphView?
   * @author xyy
   */
  class AnalyzerGestureListener extends GestureDetector.SimpleOnGestureListener {
    @Override
    public boolean onDown(MotionEvent event) {
      return true;
    }
    
    @Override
    public boolean onDoubleTap(MotionEvent event) {
      if (isInGraphView(event.getX(0), event.getY(0))) {
        if (isMeasure == false) {  // go from scale mode to measure mode (one way)
          isMeasure = !isMeasure;
          SelectorText st = (SelectorText) findViewById(R.id.graph_view_mode);
          st.performClick();
          Log.d(TAG, "  onDoubleTap(): ");
        } else {
          graphView.resetViewScale();
        }
      }
      return false;
    }

    @Override
    public boolean onFling(MotionEvent event1, MotionEvent event2, 
            float velocityX, float velocityY) {
      Log.d(TAG, "  AnalyzerGestureListener::onFling: " + event1.toString()+event2.toString());
      // Fly the canvas in graphView when in scale mode
      return true;
    }
  }
  
  @Override
  public boolean onTouchEvent(MotionEvent event) {
    this.mDetector.onTouchEvent(event);
    if (isMeasure) {
      measureEvent(event);
    } else {
      scaleEvent(event);
    }
    graphView.invalidate();
    return super.onTouchEvent(event);
  }
  
  /**
   *  Manage cursor for measurement
   */
  private void measureEvent(MotionEvent event) {
    switch (event.getPointerCount()) {
      case 1:
        if (graphView.setCursor(event.getX(), event.getY())) {
          updateAllLabels();
        }
        break;
      case 2:
        if (isInGraphView(event.getX(0), event.getY(0)) && isInGraphView(event.getX(1), event.getY(1))) {
          isMeasure = !isMeasure;
          SelectorText st = (SelectorText) findViewById(R.id.graph_view_mode);
          st.performClick();
        }
    }
  }

  /**
   *  Manage scroll and zoom
   */
  final private static float INIT = Float.MIN_VALUE;
  private boolean isPinching = false;
  private float xShift0 = INIT, yShift0 = INIT;
  float x0, y0;
  int[] windowLocation = new int[2];

  private void scaleEvent(MotionEvent event) {
    if (event.getAction() != MotionEvent.ACTION_MOVE) {
      xShift0 = INIT;
      yShift0 = INIT;
      isPinching = false;
      Log.i(TAG, "scaleEvent(): Skip event " + event.getAction());
      return;
    }
//    Log.i(TAG, "scaleEvent(): switch " + event.getAction());
    switch (event.getPointerCount()) {
      case 2 :
        if (isPinching)  {
          graphView.setShiftScale(event.getX(0), event.getY(0), event.getX(1), event.getY(1));
          updateAllLabels();
        } else {
          graphView.setShiftScaleBegin(event.getX(0), event.getY(0), event.getX(1), event.getY(1));
        }
        isPinching = true;
        break;
      case 1:
        float x = event.getX(0);
        float y = event.getY(0);
        graphView.getLocationInWindow(windowLocation);
//        Log.i(TAG, "scaleEvent(): xy=" + x + " " + y + "  wc = " + wc[0] + " " + wc[1]);
        if (isPinching || xShift0 == INIT) {
          xShift0 = graphView.getXShift();
          x0 = x;
          yShift0 = graphView.getYShift();
          y0 = y;
        } else {
          // when close to the axis, scroll that axis only
          if (x0 < windowLocation[0] + 50) {
            graphView.setYShift(yShift0 + (y0 - y) / graphView.getYZoom());
          } else if (y0 < windowLocation[1] + 50) {
            graphView.setXShift(xShift0 + (x0 - x) / graphView.getXZoom());
          } else {
            graphView.setXShift(xShift0 + (x0 - x) / graphView.getXZoom());
            graphView.setYShift(yShift0 + (y0 - y) / graphView.getYZoom());
          }
          updateAllLabels();
        }
        isPinching = false;
        break;
      default:
        Log.v(TAG, "Invalid touch count");
        break;
    }
  }
  
  @Override
  public boolean onLongClick(View view) {
    vibrate(300);
    Log.i(TAG, "long click: " + view.toString());
    return true;
  }

  // Responds to layout with android:tag="select"
  // Called from SelectorText.super.performClick()
  @Override
  public void onClick(View v) {
//    Log.i(TAG, "onClick(): " + v.toString());
    if (processClick(v)) {
      reRecur();
      updateAllLabels();
    }
  }

  private void reRecur() {
    samplingThread.finish();
    try {
      samplingThread.join();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    samplingThread = new Looper();
    samplingThread.start();
    if (samplingThread.stft != null) {
      samplingThread.stft.setAWeighting(isAWeighting);
    }
  }

  /**
   * Process a click on one of our selectors.
   * @param v   The view that was clicked
   * @return    true if we need to update the graph
   */

  public boolean processClick(View v) {
    String value = ((TextView) v).getText().toString();
    switch (v.getId()) {
      case R.id.run:
        boolean pause = value.equals("stop");
        if (samplingThread != null && samplingThread.getPause() != pause) {
          samplingThread.setPause(pause);
        }
        return false;
      case R.id.graph_view_mode:
        isMeasure = !value.equals("scale");
        return false;
      case R.id.dbA:
        isAWeighting = !value.equals("dB");
        if (samplingThread != null && samplingThread.stft != null) {
          samplingThread.stft.setAWeighting(isAWeighting);
        }
        return false;
      default:
        return true;
    }
  }

  private void updateAllLabels() {
    refreshCursorLabel();
  }

  DecimalFormat dfDB= new DecimalFormat("* ####.0");
  DecimalFormat dfFreq= new DecimalFormat("* #####.0");
  StringBuilder sCent = new StringBuilder("");
  private void refreshCursorLabel() {
    double f1 = graphView.getCursorX();
    freq2Cent(sCent, f1, " ");
    ((TextView) findViewById(R.id.textview_cur))
      .setText("Cur :" + dfFreq.format(f1)+ "Hz(" + sCent + ") " + dfDB.format(graphView.getCursorY()) + "dB");
  }

  /**
   * recompute the spectra "chart"
   * @param data    The normalized FFT output
   */
  double[] cmpDB;
  public void sameTest(double[] data) {
    // test
    if (cmpDB == null || cmpDB.length != data.length) {
      Log.i(TAG, "sameTest(): new");
      cmpDB = new double[data.length];
    } else {
      boolean same = true;
      for (int i=0; i<data.length; i++) {
        if (!Double.isNaN(cmpDB[i]) && !Double.isInfinite(cmpDB[i]) && cmpDB[i] != data[i]) {
          same = false;
          break;
        }
      }
      if (same) {
        Log.i(TAG, "sameTest(): same data row!!");
      }
      for (int i=0; i<data.length; i++) {
        cmpDB[i] = data[i];
      }
    }
  }
  
  long timeToUpdate = SystemClock.uptimeMillis();; 
  public void rePlot() {
    long t = SystemClock.uptimeMillis();
    if (t >= timeToUpdate) {  // limit frame rate
      timeToUpdate += 40;
      if (timeToUpdate < t) {
        timeToUpdate = t+40;
      }
      if (graphView.isBusy() == true) {
        Log.d(TAG, "recompute(): isBusy == true");
      }
      graphView.invalidate();
    }
  }

  public void recompute(double[] data) {
  	if (graphView.isBusy() == true) {
  		Log.d(TAG, "recompute(): isBusy == true");  // seems it's never busy
  	}
    graphView.replotRawSpectrum(data, 1, data.length, showLines);
    graphView.invalidate();
  }
  
  /**
   * Return a verified audio sampling rates.
   * @param requested
   */
  private static String[] validateAudioRates(String[] requested) {
    ArrayList<String> validated = new ArrayList<String>();
    for (String s : requested) {
      int rate;
      String[] sv = s.split("::");
      if (sv.length == 1) {
        rate = Integer.parseInt(sv[0]);
      } else {
        rate = Integer.parseInt(sv[1]);
      }
      if (AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT) != AudioRecord.ERROR_BAD_VALUE) {
        validated.add(s);
      }
    }
    return validated.toArray(new String[0]);
  }

  // Convert frequency to pitch
  // Fill with sFill until length is 6. If sFill=="", do not fill
  final String[] LP = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
  public void freq2Cent(StringBuilder a, double f, String sFill) {
    a.setLength(0);
    if (f<=0 || Double.isNaN(f) || Double.isInfinite(f)) {
      a.append("      ");
      return;
    }
    // A4 = 440Hz
    double p = 69 + 12 * Math.log(f/440.0)/Math.log(2);  // MIDI pitch
    int pi = (int) Math.round(p);
    int po = (int) Math.floor(pi/12.0);
    a.append(LP[pi-po*12]);
    a.append(po-1);
    if (p-pi>0) {
      a.append('+');
    }
    a.append(Math.round(100*(p-pi)));
    while (a.length() < 6 && sFill!=null && sFill.length()>0) {
      a.append(sFill);
    }
  }
  
  /**
   * Read a snapshot of audio data at a regular interval, and compute the FFT
   * @author suhler@google.com
   *         xyy82148@gmail.com
   */
  double[] spectrumDBcopy;
  
  public class Looper extends Thread {
    AudioRecord record;
    boolean isRunning = true;
    boolean isPaused1 = false;
    double dtRMS = 0;
    double dtRMSFromFT = 0;
    double maxAmpDB;
    double maxAmpFreq;
    double actualSampleRate;   // sample rate based on SystemClock.uptimeMillis()
    File filePathDebug;
    FileWriter fileDebug;
    public STFT stft;   // use with care

    public Looper() {
    }

    private void SleepWithoutInterrupt(long millis) {
      try {
        Thread.sleep(millis);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }

    private double baseTimeMs = SystemClock.uptimeMillis();

    private void LimitFrameRate(double updateMs) {
      // Limit the frame rate by wait `delay' ms.
      baseTimeMs += updateMs;
      long delay = (int) (baseTimeMs - SystemClock.uptimeMillis());
//      Log.i(TAG, "delay = " + delay);
      if (delay > 0) {
        try {
          Thread.sleep(delay);
        } catch (InterruptedException e) {
          Log.i(TAG, "Sleep interrupted");  // seems never reached
        }
      } else {
        baseTimeMs -= delay;  // get current time
        // Log.i(TAG, "time: cmp t="+Long.toString(SystemClock.uptimeMillis())
        //            + " v.s. t'=" + Long.toString(baseTimeMs));
      }
    }
    
    DoubleSineGen sineGen1;
    DoubleSineGen sineGen2;
    double[] mdata;
    double fq0, amp0;
    
    // generate test data
    private int readTestData(short[] a, int offsetInShorts, int sizeInShorts, int id) {
      Arrays.fill(mdata, 0.0);
      switch (id - 1000) {
        case 1:
          sineGen2.getSamples(mdata);
        case 0:
          sineGen1.addSamples(mdata);
          for (int i = 0; i < sizeInShorts; i++) {
            a[offsetInShorts + i] = (short) Math.round(mdata[i]);
          }
          break;
        case 2:
          for (int i = 0; i < sizeInShorts; i++) {
            a[i] = (short) (SAMPLE_VALUE_MAX * (2.0*Math.random() - 1));
          }
          break;
        default:
          Log.w(TAG, "readTestData(): No this source id = " + audioSourceId);
      }
      LimitFrameRate(1000.0*sizeInShorts / sampleRate);
      return sizeInShorts;
    }
    
    @Override
    public void run() {
      setupView();
      // Wait until previous instance of AudioRecord fully released.
      SleepWithoutInterrupt(500);
      
      int minBytes = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO,
                                                  AudioFormat.ENCODING_PCM_16BIT);
      if (minBytes == AudioRecord.ERROR_BAD_VALUE) {
        Log.e(TAG, "Looper::run(): Invalid AudioRecord parameter.\n");
        return;
      }

      /**
       * Develop -> Reference -> AudioRecord
       *    Data should be read from the audio hardware in chunks of sizes
       *    inferior to the total recording buffer size.
       */
      // Determine size of each read() operation
      int readChunkSize    = fftLen/2;  // /2 due to overlapped analyze window
      readChunkSize = Math.min(readChunkSize, 2048);
      int bufferSampleSize = Math.max(minBytes / BYTE_OF_SAMPLE, fftLen/2) * 2;
      // tolerate up to 1 sec.
      bufferSampleSize = (int)Math.ceil(1.0 * sampleRate / bufferSampleSize) * bufferSampleSize; 

      // Use the mic with AGC turned off. e.g. VOICE_RECOGNITION
      // The buffer size here seems not relate to the delay.
      // So choose a larger size (~1sec) so that overrun is unlikely.
      if (audioSourceId < 1000) {
        record = new AudioRecord(audioSourceId, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                                 AudioFormat.ENCODING_PCM_16BIT, BYTE_OF_SAMPLE * bufferSampleSize);
      } else {
        record = new AudioRecord(RECORDER_AGC_OFF, sampleRate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, BYTE_OF_SAMPLE * bufferSampleSize);
      }
      Log.i(TAG, "Looper::Run(): Starting recorder... \n" +
        "  source          : " + (audioSourceId<1000?getAudioSourceNameFromId(audioSourceId):audioSourceId) + "\n" +
        String.format("  sample rate     : %d Hz (request %d Hz)\n", record.getSampleRate(), sampleRate) +
        String.format("  min buffer size : %d samples, %d Bytes\n", minBytes / BYTE_OF_SAMPLE, minBytes) +
        String.format("  buffer size     : %d samples, %d Bytes\n", bufferSampleSize, BYTE_OF_SAMPLE*bufferSampleSize) +
        String.format("  read chunk size : %d samples, %d Bytes\n", readChunkSize, BYTE_OF_SAMPLE*readChunkSize) +
        String.format("  FFT length      : %d\n", fftLen));
      sampleRate = record.getSampleRate();
      actualSampleRate = sampleRate;

      if (record == null || record.getState() == AudioRecord.STATE_UNINITIALIZED) {
        Log.e(TAG, "Looper::run(): Fail to initialize AudioRecord()");
        // If failed somehow, leave the user a chance to change preference.
        return;
      }
      
      // Signal source for testing
      double fq0 = Double.parseDouble(getString(R.string.test_signal_1_freq1));
      double amp0 = Math.pow(10, 1/20.0 * Double.parseDouble(getString(R.string.test_signal_1_db1)));
      double fq1 = Double.parseDouble(getString(R.string.test_signal_2_freq1));
      double fq2 = Double.parseDouble(getString(R.string.test_signal_2_freq2));
      double amp1 = Math.pow(10, 1/20.0 * Double.parseDouble(getString(R.string.test_signal_2_db1)));
      double amp2 = Math.pow(10, 1/20.0 * Double.parseDouble(getString(R.string.test_signal_2_db2)));
      if (audioSourceId == 1000) {
        sineGen1 = new DoubleSineGen(fq0, sampleRate, SAMPLE_VALUE_MAX * amp0);
      } else {
        sineGen1 = new DoubleSineGen(fq1, sampleRate, SAMPLE_VALUE_MAX * amp1);
      }
      sineGen2 = new DoubleSineGen(fq2, sampleRate, SAMPLE_VALUE_MAX * amp2);
      mdata = new double[readChunkSize];

      short[] audioSamples = new short[readChunkSize];
      int numOfReadShort;

      stft = new STFT(fftLen, sampleRate, wndFuncName);
      stft.setAWeighting(isAWeighting);
      spectrumDBcopy = new double[fftLen/2+1];

      // Variables for count FPS, and Debug
      long timeNow;
      long timeDebugInterval = 2000;                     // output debug information per timeDebugInterval ms 
      long time4SampleCount = SystemClock.uptimeMillis();
      int frameCount = 0;

      // Start recording
      record.startRecording();
      long startTimeMs = SystemClock.uptimeMillis();     // time of recording start
      long nSamplesRead = 0;         // It's will overflow after millions of years of recording

      while (isRunning) {
        // Read data
        if (audioSourceId >= 1000) {  // switch test mode need restart
          numOfReadShort = readTestData(audioSamples, 0, readChunkSize, audioSourceId);
        } else {
          numOfReadShort = record.read(audioSamples, 0, readChunkSize);   // pulling
        }
        // Log.i(TAG, "Read: " + Integer.toString(numOfReadShort) + " samples");
        timeNow = SystemClock.uptimeMillis();
        if (nSamplesRead == 0) {      // get overrun checker synchronized
          startTimeMs = timeNow - numOfReadShort*1000/sampleRate;
        }
        nSamplesRead += numOfReadShort;
        if (isPaused1) {
          continue;  // keep reading data, so overrun checker data still valid
        }
        stft.feedData(audioSamples, numOfReadShort);

        // If there is new spectrum data, do plot
        if (stft.nElemSpectrumAmp() >= nFFTAverage) {
          // compute Root-Mean-Square
          dtRMS = stft.getRMS();

          // Update graph plot
          final double[] spectrumDB = stft.getSpectrumAmpDB();
          System.arraycopy(spectrumDB, 0, spectrumDBcopy, 0, spectrumDB.length);
          update(spectrumDBcopy);
          frameCount++;

          // Count and show peak amplitude
          maxAmpDB  = 20 * Math.log10(0.5/32768);
          maxAmpFreq = 0;
          for (int i = 1; i < spectrumDB.length; i++) {  // skip the direct current term
            if (spectrumDB[i] > maxAmpDB) {
              maxAmpDB  = spectrumDB[i];
              maxAmpFreq = i;
            }
          }
          maxAmpFreq = maxAmpFreq * sampleRate / fftLen;
          dtRMSFromFT = stft.getRMSFromFT();
        }

        // Show debug information
        if (time4SampleCount + timeDebugInterval <= timeNow) {
          // Count and show FPS
          double fps = 1000 * (double) frameCount / (timeNow - time4SampleCount);
          Log.i(TAG, "FPS: " + Math.round(10*fps)/10.0 +
                " (" + frameCount + "/" + (timeNow - time4SampleCount) + "ms)");
          time4SampleCount += timeDebugInterval;
          frameCount = 0;
          // Check whether buffer overrun occur
          long nSamplesFromTime = (long)((timeNow - startTimeMs) * actualSampleRate / 1000);
          double f1 = (double) nSamplesRead / actualSampleRate;
          double f2 = (double) nSamplesFromTime / actualSampleRate;
//          Log.i(TAG, "Buffer"
//              + " should read " + nSamplesFromTime + " (" + Math.round(f2*1000)/1000.0 + "s),"
//              + " actual read " + nSamplesRead + " (" + Math.round(f1*1000)/1000.0 + "s)\n"
//              + " diff " + (nSamplesFromTime-nSamplesRead) + " (" + Math.round((f2-f1)*1000)/1e3 + "s)"
//              + " sampleRate = " + Math.round(actualSampleRate*100)/100.0);
          if (nSamplesFromTime > bufferSampleSize + nSamplesRead) {
            Log.w(TAG, "Buffer Overrun occured !\n"
                + " should read " + nSamplesFromTime + " (" + Math.round(f2*1000)/1000.0 + "s),"
                + " actual read " + nSamplesRead + " (" + Math.round(f1*1000)/1000.0 + "s)\n"
                + " diff " + (nSamplesFromTime-nSamplesRead) + " (" + Math.round((f2-f1)*1000)/1e3 + "s)"
                + " sampleRate = " + Math.round(actualSampleRate*100)/100.0
                + "\n Overrun counter reseted.");
            // XXX log somewhere to the file or notify the user
            notifyOverrun();
            nSamplesRead = 0;  // start over
          }
          // Update actual sample rate
          if (nSamplesRead > 10*sampleRate) {
            actualSampleRate = 0.9*actualSampleRate + 0.1*(nSamplesRead * 1000.0 / (timeNow - startTimeMs));
            if (Math.abs(actualSampleRate-sampleRate) > 0.0145*sampleRate) {  // 0.0145 = 25 cent
              Log.w(TAG, "Looper::run(): Sample rate inaccurate, possible hardware problem !\n");
              nSamplesRead = 0;
            }
          }
        }
      }
      Log.i(TAG, "Looper::Run(): Stopping and releasing recorder.");
      record.stop();
      record.release();
      record = null;
    }
    
    long lastTimeNotifyOverrun = 0;
    private void notifyOverrun() {
      long t = SystemClock.uptimeMillis();
      if (t - lastTimeNotifyOverrun > 6000) {
        lastTimeNotifyOverrun = t;
        AnalyzeActivity.this.runOnUiThread(new Runnable() {
          @Override
          public void run() {
            Context context = getApplicationContext();
            String text = "Recorder buffer overrun!\nYour cell phone is too slow.\nTry lower sampling rate or higher average number.";
            Toast toast = Toast.makeText(context, text, Toast.LENGTH_LONG);
            toast.show();
          }
        });
      }
    }

    private void update(final double[] data) {
      // data is synchronized here
      graphView.replotRawSpectrum(spectrumDBcopy, 1, spectrumDBcopy.length, showLines);
      AnalyzeActivity.this.runOnUiThread(new Runnable() {
        @Override
        public void run() {
          // data will get out of synchronize here
          AnalyzeActivity.this.rePlot();
          // RMS
          TextView tv = (TextView) findViewById(R.id.textview_RMS);
          tv.setText("RMS:dB \n" + dfDB.format(20*Math.log10(dtRMSFromFT)));
          tv.invalidate();
          // peak frequency
          freq2Cent(sCent, maxAmpFreq, " ");
          tv = (TextView) findViewById(R.id.textview_peak);
          tv.setText("Peak:" + dfFreq.format(maxAmpFreq)+ "Hz(" + sCent + ") " + dfDB.format(maxAmpDB) + "dB");
          tv.invalidate();
        }
      });
    }
    
    private void setupView() {
      if (graphView.getShowMode() == 1) {
        graphView.switch2Spectrogram(sampleRate, fftLen);
      }
    }

    public void setPause(boolean pause) {
      this.isPaused1 = pause;
      // Note: When paused (or not), it is not allowed to change the recorder (sample rate, fftLen etc.)
    }

    public boolean getPause() {
      return this.isPaused1;
    }
    
    public void finish() {
      isRunning = false;
      interrupt();
    }
    
    /* Checks if external storage is available for read and write */
    public boolean isExternalStorageWritable() {
      String state = Environment.getExternalStorageState();
      if (Environment.MEDIA_MOUNTED.equals(state)) {
        return true;
      }
      return false;
    }
  }

  private void vibrate(int ms) {
    //((Vibrator) getSystemService(Context.VIBRATOR_SERVICE)).vibrate(ms);
  }

  /**
   * Visit all subviews of this view group and run command
   * @param group   The parent view group
   * @param cmd     The command to run for each view
   * @param select  The tag value that must match. Null implies all views
   */

  private void visit(ViewGroup group, Visit cmd, String select) {
    exec(group, cmd, select);
    for (int i = 0; i < group.getChildCount(); i++) {
      View c = group.getChildAt(i);
      if (c instanceof ViewGroup) {
        visit((ViewGroup) c, cmd, select);
      } else {
        exec(c, cmd, select);
      }
    }
  }

  private void exec(View v, Visit cmd, String select) {
    if (select == null || select.equals(v.getTag())) {
        cmd.exec(v);
    }
  }

  /**
   * Interface for view hierarchy visitor
   */
  interface Visit {
    public void exec(View view);
  }

  /**
   * The graph view size has been determined - update the labels accordingly.
   */
  @Override
  public void ready() {
    updateAllLabels();
  }
}