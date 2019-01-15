package com.danikula.videocache.sample;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.SeekBar;
import android.widget.TextView;

import com.danikula.videocache.CacheListener;
import com.danikula.videocache.HttpProxyCacheServer;

import org.androidannotations.annotations.AfterViews;
import org.androidannotations.annotations.EActivity;
import org.androidannotations.annotations.SeekBarTouchStop;
import org.androidannotations.annotations.ViewById;
import org.videolan.vlc.VlcVideoView;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by cengruilin on 2019/1/14.
 */

@EActivity(R.layout.activity_vlc_player)
public class VlcPlayerActivity extends Activity implements CacheListener {
    private static final String LOG_TAG = VlcPlayerActivity.class.getSimpleName();
    private PowerManager.WakeLock wakeLock;
    private SharedPreferences sharedPreferences;
    private String url;

    private final VlcPlayerActivity.VideoProgressUpdater updater = new VlcPlayerActivity.VideoProgressUpdater();

    @ViewById
    VlcVideoView vlcPlayerView;

    @ViewById
    TextView playerStatusTextView;

    @ViewById
    SeekBar progressBar;

    private boolean firstLoad = true;

    private GestureDetector gestureDetector;

    protected int screenWidthPixels;

    private AudioManager audioManager;
    /***音量的最大值***/
    private int mMaxVolume;
    /*** 亮度值 ***/
    private float brightness = -1;
    /**** 当前音量  ***/
    private int volume = -1;

    private boolean showControlView = false;
    private long lastShowControlViewAt = -1;
    private long maxShowControlViewTime = 10 * 1000;

    private void showControlView() {
        if (showControlView) {
            return;
        }

        lastShowControlViewAt = System.currentTimeMillis();
        showControlView = true;
        progressBar.setVisibility(View.VISIBLE);
    }

    private void hideControlView() {
        if (!showControlView) {
            return;
        }
        showControlView = false;
        progressBar.setVisibility(View.INVISIBLE);
    }

    @AfterViews
    public void initViews() {
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK, VlcPlayerActivity.class.getSimpleName());

        String dataStr = getIntent().getDataString();
        if (null == dataStr || dataStr.isEmpty()) {
            finish();
            return;
        }

        Map<String, String> params = resolveParameters(dataStr);
        url = params.get("url");
        if (null == url || url.isEmpty()) {
            finish();
            return;
        }

        percentFormat.setMaximumFractionDigits(0);

        screenWidthPixels = getResources().getDisplayMetrics().widthPixels;
        gestureDetector = new GestureDetector(this, new PlayerGestureListener(this));

        audioManager = (AudioManager) this.getSystemService(Context.AUDIO_SERVICE);
        assert audioManager != null;
        mMaxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        ((View) vlcPlayerView.getParent()).setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                if (gestureDetector.onTouchEvent(motionEvent)) {
                    return true;
                }
                // 处理手势结束
                switch (motionEvent.getAction() & MotionEvent.ACTION_MASK) {
                    case MotionEvent.ACTION_UP:
                        endGesture();
                        break;
                    default:
                }
                return false;
            }
        });
        vlcPlayerView.setLoop(false);

        checkCachedState();

        HttpProxyCacheServer proxy = App.getProxy(this);
        proxy.registerCacheListener(this, url);
        String proxyUrl = proxy.getProxyUrl(url);
        Log.d(LOG_TAG, "Use proxy url " + proxyUrl + " instead of original url " + url);
        firstLoad = true;
        vlcPlayerView.setPath(proxyUrl);
        vlcPlayerView.startPlay();

        showControlView();

    }

    private void checkCachedState() {
        HttpProxyCacheServer proxy = App.getProxy(this);
        boolean fullyCached = proxy.isCached(url);
//        setCachedState(fullyCached);
        if (fullyCached) {
            progressBar.setSecondaryProgress(100);
        }
    }

    @NonNull
    private Map<String, String> resolveParameters(String dataStr) {
        String[] dataArray = dataStr.split("#");
        String paramsStr = dataArray[1];
        Map<String, String> params = new HashMap<>();
        String[] paramsArray = paramsStr.split("&");
        for (String paramStr : paramsArray) {
            String[] paramArray = paramStr.split("=");
            params.put(paramArray[0], paramArray[1]);
        }
        return params;
    }


    @Override
    protected void onResume() {
        super.onResume();
        wakeLock.acquire();
        vlcPlayerView.start();
        updater.start();


    }

    @Override
    protected void onPause() {
        wakeLock.release();
        if (vlcPlayerView.isPlaying()) {
            vlcPlayerView.pause();
        }
        updater.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        vlcPlayerView.onDestory();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        long position = vlcPlayerView.getCurrentPosition();
        sharedPreferences.edit().putLong(url, position).apply();
        vlcPlayerView.onStop();
        super.onBackPressed();
    }

    private void updateVideoProgress() {
        if (vlcPlayerView.getDuration() == 0) {
            progressBar.setProgress(0);
        } else {
            int videoProgress = vlcPlayerView.getCurrentPosition() * 100 / vlcPlayerView.getDuration();
            progressBar.setProgress(videoProgress);
        }

    }

    @SeekBarTouchStop(R.id.progressBar)
    void seekVideo() {
        int videoPosition = vlcPlayerView.getDuration() * progressBar.getProgress() / 100;
        vlcPlayerView.seekTo(videoPosition);
    }

    @Override
    public void onCacheAvailable(File cacheFile, String url, int percentsAvailable) {
        progressBar.setSecondaryProgress(percentsAvailable);
        Log.d(LOG_TAG, String.format("onCacheAvailable. percents: %d, file: %s, url: %s", percentsAvailable, cacheFile, url));
    }


    private final class VideoProgressUpdater extends Handler {

        public void start() {
            sendEmptyMessage(0);
        }

        public void stop() {
            removeMessages(0);
        }

        @Override
        public void handleMessage(Message msg) {
            updateVideoProgress();
            sendEmptyMessageDelayed(0, 500);

            if (firstLoad && vlcPlayerView.getCurrentPosition() > 0) {
                long position = sharedPreferences.getLong(url, (long) 0);
                if (0 != position)
                    vlcPlayerView.seekTo(position);
                firstLoad = false;
            }

            if (showControlView) {
                //检查是否该消失
                if (lastShowControlViewAt + maxShowControlViewTime < System.currentTimeMillis()) {
                    hideControlView();
                }
            }
        }
    }

    /**
     * 手势结束
     */
    private void endGesture() {
        volume = -1;
        brightness = -1f;
//        if (newPosition >= 0) {
//            simpleExoPlayer.seekTo(newPosition);
//            newPosition = -1;
//        }
        playerStatusTextView.setVisibility(View.INVISIBLE);
        toggleControlView();
    }

    private void toggleControlView() {
        if (showControlView) {
            hideControlView();
        } else {
            showControlView();
        }
    }

    /**
     * 滑动改变声音大小
     *
     * @param percent percent 滑动
     */
    private void showVolumeDialog(float percent) {
        if (volume == -1) {
            volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (volume < 0) {
                volume = 0;
            }
        }
        int index = (int) (percent * mMaxVolume) + volume;
        if (index > mMaxVolume) {
            index = mMaxVolume;
        } else if (index < 0) {
            index = 0;
        }
        // 变更进度条 // int i = (int) (index * 1.5 / mMaxVolume * 100);
        //  String s = i + "%";  // 变更声音
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0);

        playerStatusTextView.setText("" + index);
//        if (onGestureVolumeListener != null) {
//            onGestureVolumeListener.setVolumePosition(mMaxVolume, index);
//        } else {
//            getPlayerViewListener().setVolumePosition(mMaxVolume, index);
//        }
    }

    java.text.NumberFormat percentFormat = java.text.NumberFormat.getPercentInstance();

    /**
     * 滑动改变亮度
     *
     * @param percent 值大小
     */
    private synchronized void showBrightnessDialog(float percent) {
        if (brightness < 0) {
            brightness = getWindow().getAttributes().screenBrightness;
            if (brightness <= 0.00f) {
                brightness = 0.50f;
            } else if (brightness < 0.01f) {
                brightness = 0.01f;
            }
        }
        WindowManager.LayoutParams lpa = getWindow().getAttributes();
        lpa.screenBrightness = brightness + percent;
        if (lpa.screenBrightness > 1.0) {
            lpa.screenBrightness = 1.0f;
        } else if (lpa.screenBrightness < 0.01f) {
            lpa.screenBrightness = 0.01f;
        }
        getWindow().setAttributes(lpa);

        playerStatusTextView.setText(percentFormat.format(lpa.screenBrightness));

//        if (onGestureBrightnessListener != null) {
//            onGestureBrightnessListener.setBrightnessPosition(100, (int) (lpa.screenBrightness * 100));
//        } else {
//            getPlayerViewListener().setBrightnessPosition(100, (int) (lpa.screenBrightness * 100));
//        }
    }

    /****
     * 手势监听类
     *****/
    private final class PlayerGestureListener extends GestureDetector.SimpleOnGestureListener {
        private boolean firstTouch;
        private boolean volumeControl;
        private boolean toSeek;
        private WeakReference<VlcPlayerActivity> weakReference;

        private PlayerGestureListener(VlcPlayerActivity gestureVideoPlayer) {
            weakReference = new WeakReference<>(gestureVideoPlayer);
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            return true;
        }

        @Override
        public boolean onDown(MotionEvent e) {
            firstTouch = true;
            weakReference.get().playerStatusTextView.setVisibility(View.VISIBLE);
            return true;
        }

        /**
         * 滑动
         */
        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            if (null == weakReference || weakReference.get() == null) {
                return false;
            }
            float mOldX = e1.getX(), mOldY = e1.getY();
            float deltaY = mOldY - e2.getY();
            float deltaX = mOldX - e2.getX();
            int screenWidthPixels = weakReference.get().screenWidthPixels;
            VlcVideoView player = weakReference.get().vlcPlayerView;
            if (firstTouch) {
                toSeek = Math.abs(distanceX) >= Math.abs(distanceY);
                volumeControl = mOldX > screenWidthPixels * 0.5f;
                firstTouch = false;
            }
            if (toSeek) {
                deltaX = -deltaX;
                long position = player.getCurrentPosition();
                long duration = player.getDuration();
                long newPosition = (int) (position + deltaX * duration / screenWidthPixels);
                if (newPosition > duration) {
                    newPosition = duration;
                } else if (newPosition <= 0) {
                    newPosition = 0;
                }
//                showProgressDialog(newPosition, duration,
//                        Util.getStringForTime(formatBuilder, formatter, newPosition), Util.getStringForTime(formatBuilder, formatter, duration)
//                        "", "");
            } else {
                float percent = deltaY / player.getHeight();
                if (volumeControl) {
                    showVolumeDialog(percent);
                } else {
                    showBrightnessDialog(percent);
                }
            }
            return super.onScroll(e1, e2, distanceX, distanceY);
        }


    }
}
