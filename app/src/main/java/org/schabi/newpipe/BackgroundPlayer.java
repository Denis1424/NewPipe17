package org.schabi.newpipe;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.v7.app.NotificationCompat;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;

/**
 * Created by Adam Howard on 08/11/15.
 * Copyright (c) Adam Howard <achdisposable1@gmail.com> 2015
 *
 * BackgroundPlayer.java is part of NewPipe.
 *
 * NewPipe is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NewPipe is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NewPipe.  If not, see <http://www.gnu.org/licenses/>.
 */

/**Plays the audio stream of videos in the background.*/
public class BackgroundPlayer extends Service /*implements MediaPlayer.OnPreparedListener*/ {

    private static final String TAG = BackgroundPlayer.class.toString();
    private static final String ACTION_STOP = TAG+".STOP";
    private static final String ACTION_PLAYPAUSE = TAG+".PLAYPAUSE";
    //private Looper mServiceLooper;
    //private ServiceHandler mServiceHandler;

    public BackgroundPlayer() {
        super();
    }

    @Override
    public void onCreate() {
        /*PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), 0);*/
        super.onCreate();
    }
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Toast.makeText(this, "Playing in background", Toast.LENGTH_SHORT).show();//todo:translation string

        String source = intent.getDataString();
        //Log.i(TAG, "backgroundPLayer source:"+source);
        String videoTitle = intent.getStringExtra("title");

        //do nearly everything in a separate thread
        PlayerThread player = new PlayerThread(source, videoTitle, this);
        player.start();

        // If we get killed after returning here, don't restart
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        // We don't provide binding (yet?), so return null
        return null;
    }

    @Override
    public void onDestroy() {
        //Toast.makeText(this, "service done", Toast.LENGTH_SHORT).show();
    }

    private class PlayerThread extends Thread {
        MediaPlayer mediaPlayer;
        private String source;
        private String title;
        private int noteID = TAG.hashCode();
        private BackgroundPlayer owner;
        private NotificationManager noteMgr;
        private NotificationCompat.Builder noteBuilder;
        private WifiManager.WifiLock wifiLock;

        public PlayerThread(String src, String title, BackgroundPlayer owner) {
            this.source = src;
            this.title = title;
            this.owner = owner;
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        }
        @Override
        public void run() {
            mediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);//cpu lock
            try {
                mediaPlayer.setDataSource(source);
                mediaPlayer.prepare(); //We are already in a separate worker thread,
                //so calling the blocking prepare() method should be ok

                //alternatively:
                //mediaPlayer.setOnPreparedListener(this);
                //mediaPlayer.prepareAsync(); //prepare async to not block main thread
            } catch (IOException ioe) {
                ioe.printStackTrace();
                Log.e(TAG, "video source:" + source);
                Log.e(TAG, "video title:" + title);
                //can't do anything useful without a file to play; exit early
                return;
            }

            WifiManager wifiMgr = ((WifiManager)getSystemService(Context.WIFI_SERVICE));
            wifiLock = wifiMgr.createWifiLock(WifiManager.WIFI_MODE_FULL, TAG);

            mediaPlayer.setOnCompletionListener(new EndListener(wifiLock));//listen for end of video

            //get audio focus
            /*
            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            int result = audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);

            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                // could not get audio focus.
            }*/
            wifiLock.acquire();
            mediaPlayer.start();

            //mediaPlayer.getCurrentPosition()
            int vidLength = mediaPlayer.getDuration();

            //Intent genericIntent = new Intent(owner, owner.getClass());

            //PendingIntent playPI = PendingIntent.getService(owner, noteID, genericIntent, 0);
            PendingIntent playPI = PendingIntent.getBroadcast(owner, noteID, new Intent(ACTION_PLAYPAUSE), PendingIntent.FLAG_UPDATE_CURRENT);

            NotificationCompat.Action.Builder buttonBuilder =
                    new NotificationCompat.Action.Builder(R.drawable.ic_play_arrow_black,
                                                    "Play", playPI);//todo:translatable string
            NotificationCompat.Action playButton = buttonBuilder.build();

            PendingIntent stopPI = PendingIntent.getBroadcast(owner, noteID,
                    new Intent(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT);

            IntentFilter filter = new IntentFilter();
            filter.setPriority(Integer.MAX_VALUE);
            filter.addAction(ACTION_PLAYPAUSE);
            filter.addAction(ACTION_STOP);
            registerReceiver(broadcastReceiver, filter);

            //playPauseButton
            //todo: make it so that tapping the notification brings you back to the Video's DetailActivity
            //using setContentIntent
            noteBuilder = new NotificationCompat.Builder(owner);
            noteBuilder
                    .setPriority(Notification.PRIORITY_LOW)
                    .setCategory(Notification.CATEGORY_TRANSPORT)
                    .setContentTitle(title)
                    .setContentText("NewPipe is playing in the background")//todo: translation string
                    //.setAutoCancel(!mediaPlayer.isPlaying())
                    .setOngoing(true)
                    .setDeleteIntent(stopPI)
                    //.setProgress(vidLength, 0, false) //doesn't fit with Notification.MediaStyle
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setTicker(title + " - NewPipe")
                    .addAction(playButton);
/*                  .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setLargeIcon(cover)*/

            noteBuilder.setStyle(new NotificationCompat.MediaStyle()
                            //.setMediaSession(mMediaSession.getSessionToken())
                            .setShowActionsInCompactView(new int[] {0})
                            .setShowCancelButton(true)
                            .setCancelButtonIntent(stopPI)
            );

            startForeground(noteID, noteBuilder.build());

            //currently decommissioned progressbar looping update code - works, but doesn't fit inside
            //Notification.MediaStyle Notification layout.
            noteMgr = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            /*
            //update every 2s or 4 times in the video, whichever is shorter
            int sleepTime = Math.min(2000, (int)((double)vidLength/4));
            while(mediaPlayer.isPlaying()) {
                noteBuilder.setProgress(vidLength, mediaPlayer.getCurrentPosition(), false);
                noteMgr.notify(noteID, noteBuilder.build());
                try {
                    Thread.sleep(sleepTime);
                } catch (InterruptedException e) {
                    Log.d(TAG, "sleep failure");
                }
            }*/

        }
/*          MediaMetadataCompat metaData = mMediaSession.getController().getMetadata();
            String title = metaData.getString(MediaMetadataCompat.METADATA_KEY_TITLE);
            String artist = metaData.getString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST);
            String album = metaData.getString(MediaMetadataCompat.METADATA_KEY_ALBUM);
            Bitmap cover = metaData.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART);
            if (cover == null)
                cover = BitmapFactory.decodeResource(VLCApplication.getAppContext().getResources(), R.drawable.icon);

            Notification notification;
//set up switch-back-to functionality
            PendingIntent pendingIntent;
            if (canSwitchToVideo()) {
                // Resume VideoPlayerActivity from ACTION_REMOTE_SWITCH_VIDEO intent
                final Intent notificationIntent = new Intent(ACTION_REMOTE_SWITCH_VIDEO);
                pendingIntent = PendingIntent.getBroadcast(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);
            } else {
                // Resume AudioPlayerActivity

                final Intent notificationIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
                notificationIntent.setAction(AudioPlayerContainerActivity.ACTION_SHOW_PLAYER);
                notificationIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);
            }
            builder.setContentIntent(pendingIntent);

            //set up and add media control buttons
            PendingIntent piBackward = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_REMOTE_BACKWARD), PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent piPlay = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_REMOTE_PLAYPAUSE), PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent piForward = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_REMOTE_FORWARD), PendingIntent.FLAG_UPDATE_CURRENT);

            builder.addAction(R.drawable.ic_previous_w, getString(R.string.previous), piBackward);
            if (mMediaPlayer.isPlaying())
                builder.addAction(R.drawable.ic_pause_w, getString(R.string.pause), piPlay);
            else
                builder.addAction(R.drawable.ic_play_w, getString(R.string.play), piPlay);
            builder.addAction(R.drawable.ic_next_w, getString(R.string.next), piForward);

            //post-start service
            if (!AndroidUtil.isLolliPopOrLater() || mMediaPlayer.isPlaying())
                startForeground(3, notification);
            else {
                stopForeground(false);
                NotificationManagerCompat.from(this).notify(3, notification);
            }
        }*/

        private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                Log.i(TAG, "received broadcast action:"+action);
                if(action.equals(ACTION_PLAYPAUSE)) {
                    if(mediaPlayer.isPlaying()) {
                        mediaPlayer.pause();
                    }
                    else {
                        mediaPlayer.start();
                    }
                }
                else if(action.equals(ACTION_STOP)) {
                    mediaPlayer.stop();
                    afterPlayCleanup();
                }
            }
        };

        private void afterPlayCleanup() {
            //noteBuilder.setProgress(0, 0, false);//remove progress bar
            noteMgr.cancel(noteID);//remove notification
            unregisterReceiver(broadcastReceiver);
            mediaPlayer.release();//release system resources


            wifiLock.release();//release wifilock
            stopForeground(true);//remove foreground status of service; make us killable

            stopSelf();
            //todo:release cpu lock
        }

        private class EndListener implements MediaPlayer.OnCompletionListener {
            private WifiManager.WifiLock wl;
            public EndListener(WifiManager.WifiLock wifiLock) {
                this.wl = wifiLock;
            }

            @Override
            public void onCompletion(MediaPlayer mp) {
                afterPlayCleanup();
            }
        }
    }
/*
    private class ListenerThread extends Thread implements AudioManager.OnAudioFocusChangeListener {
        @Override
        public void onAudioFocusChange(int focusChange) {

        }
    }*/
}
