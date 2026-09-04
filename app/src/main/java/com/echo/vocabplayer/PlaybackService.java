package com.echo.vocabplayer;

import android.app.*;
import android.content.*;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.*;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.util.*;

public class PlaybackService extends Service {
    public static final String ACTION_START="com.echo.vocabplayer.START", ACTION_TOGGLE="com.echo.vocabplayer.TOGGLE", ACTION_STOP="com.echo.vocabplayer.STOP", ACTION_NEXT="com.echo.vocabplayer.NEXT", ACTION_PREVIOUS="com.echo.vocabplayer.PREVIOUS", ACTION_STATE="com.echo.vocabplayer.STATE";
    public static final String EXTRA_INDEX="index", EXTRA_PLAYING="playing", EXTRA_PHASE="phase", EXTRA_ENGLISH="english", EXTRA_CHINESE="chinese", EXTRA_TEST_MODE="test_mode";
    private static final String CHANNEL="vocab_playback"; private static final int NOTIFICATION_ID=17;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private ArrayList<AppData.Word> words=new ArrayList<>();
    private TextToSpeech tts; private boolean ready=false,playing=false,pendingStart=false,inGap=false,retriedOffline=false;
    private int current=0,segment=0,token=0,repeat=3,gapMs=800; private String phase="尚未开始",accent="US",gender="female",quality="natural";
    private android.os.PowerManager.WakeLock wakeLock; private AudioManager audioManager; private AudioFocusRequest focusRequest;

    @Override public void onCreate(){super.onCreate();createChannel();audioManager=(AudioManager)getSystemService(AUDIO_SERVICE);
        android.os.PowerManager pm=(android.os.PowerManager)getSystemService(POWER_SERVICE);wakeLock=pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"VocabPlayer:Playback");wakeLock.setReferenceCounted(false);
        tts=new TextToSpeech(this,status->handler.post(()->finishTtsInit(status)));
    }
    private void finishTtsInit(int status){ready=status==TextToSpeech.SUCCESS&&tts!=null;if(!ready){stopPlayback("手机语音服务初始化失败",true);return;}
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){public void onStart(String id){}public void onDone(String id){onUtteranceFinished(id,false);}public void onError(String id){onUtteranceFinished(id,true);}public void onError(String id,int code){onUtteranceFinished(id,true);}});
        if(pendingStart){pendingStart=false;startAt(current);}
    }
    private void onUtteranceFinished(String id,boolean error){int utteranceToken;try{utteranceToken=Integer.parseInt(id.split("-")[0]);}catch(Exception e){return;}handler.post(()->{
        if(utteranceToken!=token||!playing)return;if(error&&quality.equals("natural")&&!retriedOffline){retriedOffline=true;quality="offline";speakSegment(utteranceToken);return;}if(error){stopPlayback("发音失败，请检查手机语音设置",true);return;}retriedOffline=false;segment++;playSegment(utteranceToken);
    });}

    @Override public int onStartCommand(Intent intent,int flags,int startId){String action=intent==null?null:intent.getAction();if(action==null)return START_STICKY;
        if(ACTION_START.equals(action)){current=Math.max(0,intent.getIntExtra(EXTRA_INDEX,0));loadSettings();words=AppData.loadWords(this);startForeground(NOTIFICATION_ID,notification());if(BuildConfig.DEBUG&&intent.getBooleanExtra(EXTRA_TEST_MODE,false)){playing=true;phase="后台播放测试";acquirePlaybackResources();publish();return START_STICKY;}if(ready)startAt(current);else pendingStart=true;}
        else if(ACTION_TOGGLE.equals(action)){if(playing)pause();else{loadSettings();words=AppData.loadWords(this);startForeground(NOTIFICATION_ID,notification());if(ready)startAt(current);else pendingStart=true;}}
        else if(ACTION_NEXT.equals(action)){words=AppData.loadWords(this);if(!words.isEmpty())startAt(Math.min(words.size()-1,current+1));}
        else if(ACTION_PREVIOUS.equals(action)){words=AppData.loadWords(this);if(!words.isEmpty())startAt(Math.max(0,current-1));}
        else if(ACTION_STOP.equals(action))stopPlayback("已停止",true);return START_STICKY;
    }
    private void loadSettings(){android.content.SharedPreferences p=AppData.prefs(this);repeat=p.getInt("repeat",3);gapMs=p.getInt("gap",800);accent=p.getString("accent","US");gender=p.getString("gender","female");quality=p.getString("quality","natural");}
    private void startAt(int index){if(words.isEmpty()){stopPlayback("请先导入单词",true);return;}if(!ready){pendingStart=true;return;}token++;handler.removeCallbacksAndMessages(null);tts.stop();current=Math.max(0,Math.min(index,words.size()-1));segment=0;playing=true;inGap=false;retriedOffline=false;acquirePlaybackResources();playSegment(token);}
    private void playSegment(int playToken){if(playToken!=token||!playing||words.isEmpty())return;int length=repeat+2;
        if(segment>=length){if(current>=words.size()-1){stopPlayback("播放完成",true);return;}inGap=true;phase="停顿中";publish();handler.postDelayed(()->{if(playToken==token&&playing){current++;segment=0;inGap=false;playSegment(playToken);}},gapMs);return;}
        AppData.Word w=words.get(current);boolean chinese=segment==0||segment==length-1;phase=chinese?(segment==0?"开头中文":"结尾中文"):"英文 "+segment+"/"+repeat;publish();speak(chinese?w.chinese:w.english,chinese,playToken);
    }
    private void speakSegment(int playToken){if(words.isEmpty())return;int length=repeat+2;boolean chinese=segment==0||segment==length-1;AppData.Word w=words.get(current);speak(chinese?w.chinese:w.english,chinese,playToken);}
    private void speak(String value,boolean chinese,int playToken){Locale locale=chinese?Locale.SIMPLIFIED_CHINESE:(accent.equals("UK")?Locale.UK:Locale.US);android.speech.tts.Voice voice=chooseVoice(locale,chinese?"":gender,quality.equals("natural"));
        if(voice!=null)tts.setVoice(voice);else{int result=tts.setLanguage(locale);if(result==TextToSpeech.LANG_MISSING_DATA||result==TextToSpeech.LANG_NOT_SUPPORTED){stopPlayback(chinese?"手机缺少中文语音":"手机缺少所选英语语音",true);return;}}
        tts.setSpeechRate(chinese?0.94f:0.82f);tts.setPitch(gender.equals("male")&&!chinese?0.92f:1.02f);inGap=false;tts.speak(value,TextToSpeech.QUEUE_FLUSH,null,playToken+"-"+current+"-"+segment);
    }
    private android.speech.tts.Voice chooseVoice(Locale locale,String wantedGender,boolean natural){if(tts==null||tts.getVoices()==null)return null;android.speech.tts.Voice best=null;int bestScore=Integer.MIN_VALUE;
        for(android.speech.tts.Voice v:tts.getVoices()){Locale l=v.getLocale();if(l==null||!l.getLanguage().equals(locale.getLanguage()))continue;int score=0;if(l.getCountry().equalsIgnoreCase(locale.getCountry()))score+=300;else if(!locale.getCountry().isEmpty())score-=200;String meta=(v.getName()+" "+v.getFeatures()).toLowerCase(Locale.ROOT);if(!wantedGender.isEmpty()&&meta.contains(wantedGender))score+=100;score+=v.getQuality();if(natural&&v.isNetworkConnectionRequired())score+=180;if(!natural&&!v.isNetworkConnectionRequired())score+=220;if(score>bestScore){best=v;bestScore=score;}}
        return best;
    }
    private void pause(){token++;handler.removeCallbacksAndMessages(null);if(tts!=null)tts.stop();playing=false;pendingStart=false;phase="已暂停";releasePlaybackResources();publish();stopForeground(false);startForeground(NOTIFICATION_ID,notification());}
    private void stopPlayback(String label,boolean removeNotification){token++;handler.removeCallbacksAndMessages(null);if(tts!=null)tts.stop();playing=false;pendingStart=false;inGap=false;phase=label;releasePlaybackResources();publish();if(removeNotification){stopForeground(true);stopSelf();}}
    private void acquirePlaybackResources(){if(wakeLock!=null&&!wakeLock.isHeld())wakeLock.acquire();if(Build.VERSION.SDK_INT>=26){AudioAttributes a=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();focusRequest=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(a).setOnAudioFocusChangeListener(change->{if(change==AudioManager.AUDIOFOCUS_LOSS)handler.post(this::pause);}).build();audioManager.requestAudioFocus(focusRequest);}else audioManager.requestAudioFocus(c->{if(c==AudioManager.AUDIOFOCUS_LOSS)handler.post(this::pause);},AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN);}
    private void releasePlaybackResources(){if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();if(audioManager!=null&&Build.VERSION.SDK_INT>=26&&focusRequest!=null)audioManager.abandonAudioFocusRequest(focusRequest);}
    private void publish(){sendBroadcast(new Intent(ACTION_STATE).setPackage(getPackageName()).putExtra(EXTRA_INDEX,current).putExtra(EXTRA_PLAYING,playing).putExtra(EXTRA_PHASE,phase).putExtra(EXTRA_ENGLISH,words.isEmpty()?"":words.get(Math.min(current,words.size()-1)).english).putExtra(EXTRA_CHINESE,words.isEmpty()?"":words.get(Math.min(current,words.size()-1)).chinese));if(playing)startForeground(NOTIFICATION_ID,notification());else{NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);n.notify(NOTIFICATION_ID,notification());}}
    private PendingIntent serviceAction(String action,int request){Intent i=new Intent(this,PlaybackService.class).setAction(action);return PendingIntent.getService(this,request,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    private Notification notification(){Intent open=new Intent(this,MainActivity.class);PendingIntent content=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);String title=words.isEmpty()?"英语单词播放器":words.get(Math.min(current,words.size()-1)).english;String body=words.isEmpty()?phase:words.get(Math.min(current,words.size()-1)).chinese+" · "+phase;
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);return b.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(title).setContentText(body).setContentIntent(content).setOnlyAlertOnce(true).setOngoing(playing).setCategory(Notification.CATEGORY_TRANSPORT).addAction(android.R.drawable.ic_media_previous,"上一词",serviceAction(ACTION_PREVIOUS,1)).addAction(playing?android.R.drawable.ic_media_pause:android.R.drawable.ic_media_play,playing?"暂停":"继续",serviceAction(ACTION_TOGGLE,2)).addAction(android.R.drawable.ic_media_next,"下一词",serviceAction(ACTION_NEXT,3)).addAction(android.R.drawable.ic_menu_close_clear_cancel,"停止",serviceAction(ACTION_STOP,4)).build();}
    private void createChannel(){if(Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel(CHANNEL,"单词播放",NotificationManager.IMPORTANCE_LOW);c.setDescription("锁屏或切到后台时保持单词播放");((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);}}
    @Override public android.os.IBinder onBind(Intent i){return null;}
    @Override public void onDestroy(){handler.removeCallbacksAndMessages(null);releasePlaybackResources();if(tts!=null){tts.stop();tts.shutdown();}super.onDestroy();}
}
