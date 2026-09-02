package com.echo.vocabplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.NumberPicker;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    static class Word {
        final String english, chinese;
        Word(String english, String chinese) { this.english = english; this.chinese = chinese; }
        JSONObject json() throws Exception { JSONObject o = new JSONObject(); o.put("english", english); o.put("chinese", chinese); return o; }
    }

    private final ArrayList<Word> words = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private SharedPreferences prefs;
    private EditText input;
    private TextView currentEnglish, currentChinese, phase, listTitle, gapLabel;
    private Button playButton;
    private WordAdapter adapter;
    private int repeat = 3, gapMs = 800, currentIndex = 0, segmentIndex = 0, runToken = 0;
    private boolean playing = false, paused = false, inGap = false, ttsReady = false;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("vocab_player", MODE_PRIVATE);
        loadData();
        buildUi();
        tts = new TextToSpeech(this, this);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(sp); view.setTextColor(color); return view;
    }
    private Button button(String label) {
        Button b = new Button(this); b.setText(label); b.setTextSize(14); b.setAllCaps(false); b.setMinHeight(dp(46)); return b;
    }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }

    private void buildUi() {
        int navy = Color.rgb(19,32,56), blue = Color.rgb(40,86,216), muted = Color.rgb(104,118,140);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(18), dp(18), dp(18), dp(12)); root.setBackgroundColor(Color.rgb(244,247,251));

        TextView title = text("英语单词播放器", 23, navy); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); root.addView(title);
        TextView subtitle = text("粘贴单词，马上开始听", 14, muted); subtitle.setPadding(0, dp(3), 0, dp(14)); root.addView(subtitle);

        input = new EditText(this); input.setHint("支持一行多个或每行一个\nbond 情感羁绊  survival 生存"); input.setTextSize(16); input.setGravity(Gravity.TOP); input.setPadding(dp(14),dp(12),dp(14),dp(12)); input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE); input.setBackgroundColor(Color.WHITE);
        root.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)));

        LinearLayout importRow = row(); importRow.setPadding(0, dp(9), 0, dp(8));
        Button paste = button("粘贴剪贴板"); Button add = button("导入并保存"); add.setTextColor(Color.WHITE); add.setBackgroundColor(blue);
        importRow.addView(paste, new LinearLayout.LayoutParams(0, dp(48), 1)); importRow.addView(add, new LinearLayout.LayoutParams(0, dp(48), 1)); root.addView(importRow);
        paste.setOnClickListener(v -> pasteClipboard()); add.setOnClickListener(v -> importWords());

        LinearLayout settings = row(); settings.setPadding(0, dp(3), 0, dp(7));
        TextView repeatLabel = text("英文重复", 14, navy); settings.addView(repeatLabel);
        NumberPicker picker = new NumberPicker(this); picker.setMinValue(1); picker.setMaxValue(10); picker.setValue(repeat); picker.setWrapSelectorWheel(false); settings.addView(picker, new LinearLayout.LayoutParams(dp(76), dp(62)));
        gapLabel = text("停顿 " + String.format(Locale.CHINA,"%.1f 秒",gapMs/1000f), 14, navy); gapLabel.setPadding(dp(8),0,dp(5),0); settings.addView(gapLabel);
        SeekBar seek = new SeekBar(this); seek.setMax(10); seek.setProgress(gapMs/200); settings.addView(seek, new LinearLayout.LayoutParams(0, dp(48), 1)); root.addView(settings);
        picker.setOnValueChangedListener((p, old, value) -> { repeat=value; saveData(); });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s,int value,boolean fromUser){ gapMs=value*200; gapLabel.setText("停顿 "+String.format(Locale.CHINA,"%.1f 秒",gapMs/1000f)); }
            public void onStartTrackingTouch(SeekBar s){} public void onStopTrackingTouch(SeekBar s){ saveData(); }
        });

        LinearLayout player = new LinearLayout(this); player.setOrientation(LinearLayout.VERTICAL); player.setGravity(Gravity.CENTER); player.setPadding(dp(12),dp(12),dp(12),dp(10)); player.setBackgroundColor(Color.WHITE);
        currentEnglish = text("等待导入", 34, navy); currentEnglish.setTypeface(Typeface.DEFAULT, Typeface.BOLD); currentEnglish.setGravity(Gravity.CENTER); player.addView(currentEnglish);
        currentChinese = text("粘贴单词后即可播放", 16, muted); currentChinese.setGravity(Gravity.CENTER); currentChinese.setPadding(0,dp(4),0,dp(4)); player.addView(currentChinese);
        phase = text("尚未开始", 13, blue); phase.setGravity(Gravity.CENTER); player.addView(phase);
        LinearLayout controls = row(); controls.setGravity(Gravity.CENTER); controls.setPadding(0,dp(7),0,0);
        Button restart=button("↺ 从头"), previous=button("◀ 上一词"); playButton=button("▶ 播放"); Button next=button("下一词 ▶"), stop=button("■ 停止"); playButton.setTextColor(Color.WHITE); playButton.setBackgroundColor(blue);
        for(Button b:new Button[]{restart,previous,playButton,next,stop}) controls.addView(b,new LinearLayout.LayoutParams(0,dp(48),1)); player.addView(controls); root.addView(player);
        restart.setOnClickListener(v->startAt(0)); previous.setOnClickListener(v->startAt(Math.max(0,currentIndex-1))); playButton.setOnClickListener(v->togglePlay()); next.setOnClickListener(v->startAt(Math.min(words.size()-1,currentIndex+1))); stop.setOnClickListener(v->stopPlayback("已停止"));

        LinearLayout listHead = row(); listHead.setPadding(0,dp(12),0,dp(6)); listTitle=text("播放列表 · "+words.size()+" 词",18,navy); listTitle.setTypeface(Typeface.DEFAULT,Typeface.BOLD); listHead.addView(listTitle,new LinearLayout.LayoutParams(0,dp(42),1)); Button clear=button("清空"); listHead.addView(clear,new LinearLayout.LayoutParams(dp(82),dp(42))); root.addView(listHead); clear.setOnClickListener(v->confirmClear());
        ListView list = new ListView(this); list.setDividerHeight(1); adapter=new WordAdapter(this,words); list.setAdapter(adapter); list.setOnItemClickListener((parent,view,position,id)->startAt(position)); root.addView(list,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1));
        setContentView(root); updateCurrent();
    }

    private void pasteClipboard() {
        android.content.ClipboardManager cm=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if(cm.hasPrimaryClip() && cm.getPrimaryClip()!=null) input.setText(cm.getPrimaryClip().getItemAt(0).coerceToText(this)); else Toast.makeText(this,"剪贴板里没有文字",Toast.LENGTH_SHORT).show();
    }

    private void importWords() {
        ArrayList<Word> parsed=parse(input.getText().toString());
        if(parsed.isEmpty()){ Toast.makeText(this,"请按“英文 中文”格式粘贴",Toast.LENGTH_LONG).show(); return; }
        words.addAll(parsed); input.setText(""); adapter.notifyDataSetChanged(); listTitle.setText("播放列表 · "+words.size()+" 词"); saveData(); updateCurrent(); Toast.makeText(this,"已导入 "+parsed.size()+" 个单词",Toast.LENGTH_SHORT).show();
    }

    static ArrayList<Word> parse(String raw) {
        ArrayList<Word> output=new ArrayList<>();
        String normalized=raw.replaceAll("[，,；;|｜\\t]"," ");
        for(String line:normalized.split("[\\r\\n]+")){
            ArrayList<String> en=new ArrayList<>(),cn=new ArrayList<>();
            for(String token:line.trim().split("\\s+")){
                if(token.isEmpty()) continue;
                if(token.matches("[A-Za-z][A-Za-z'’-]*")){ if(!cn.isEmpty()){ addParsed(output,en,cn); en.clear(); cn.clear(); } en.add(token); }
                else if(!en.isEmpty()) cn.add(token);
            }
            addParsed(output,en,cn);
        }
        return output;
    }
    static void addParsed(List<Word> out,List<String> en,List<String> cn){ if(!en.isEmpty()&&!cn.isEmpty()) out.add(new Word(android.text.TextUtils.join(" ",en),android.text.TextUtils.join("",cn))); }

    @Override public void onInit(int status) {
        ttsReady=status==TextToSpeech.SUCCESS;
        if(!ttsReady) Toast.makeText(this,"手机语音服务初始化失败",Toast.LENGTH_LONG).show();
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
            public void onStart(String id){} public void onError(String id){ handler.post(()->stopPlayback("发音失败，请检查手机语音设置")); }
            public void onDone(String id){ int token; try{token=Integer.parseInt(id.split("-")[0]);}catch(Exception e){return;} handler.post(()->{if(token==runToken&&playing){segmentIndex++;playSegment(token);}}); }
        });
    }

    private void startAt(int index){
        if(words.isEmpty()){Toast.makeText(this,"请先导入单词",Toast.LENGTH_SHORT).show();return;}
        if(!ttsReady){Toast.makeText(this,"手机语音服务还没准备好",Toast.LENGTH_SHORT).show();return;}
        runToken++; handler.removeCallbacksAndMessages(null); tts.stop(); currentIndex=Math.max(0,Math.min(index,words.size()-1)); segmentIndex=0; playing=true;paused=false;inGap=false;playButton.setText("Ⅱ 暂停");updateCurrent();playSegment(runToken);
    }

    private void playSegment(int token){
        if(token!=runToken||!playing||words.isEmpty())return;
        int queueLength=repeat+2;
        if(segmentIndex>=queueLength){
            if(currentIndex>=words.size()-1){stopPlayback("播放完成");return;}
            inGap=true;phase.setText("停顿中");handler.postDelayed(()->{if(token==runToken&&playing){currentIndex++;segmentIndex=0;inGap=false;updateCurrent();playSegment(token);}},gapMs);return;
        }
        Word w=words.get(currentIndex); boolean chinese=segmentIndex==0||segmentIndex==queueLength-1; String content=chinese?w.chinese:w.english;
        phase.setText(chinese?(segmentIndex==0?"开头中文":"结尾中文"):"英文 "+segmentIndex+"/"+repeat);
        int language=tts.setLanguage(chinese?Locale.SIMPLIFIED_CHINESE:Locale.US);
        if(language==TextToSpeech.LANG_MISSING_DATA||language==TextToSpeech.LANG_NOT_SUPPORTED){stopPlayback(chinese?"手机缺少中文语音":"手机缺少英文语音");return;}
        tts.setSpeechRate(chinese?0.92f:0.78f);inGap=false;tts.speak(content,TextToSpeech.QUEUE_FLUSH,null,token+"-"+currentIndex+"-"+segmentIndex);
    }

    private void togglePlay(){
        if(playing){runToken++;handler.removeCallbacksAndMessages(null);tts.stop();playing=false;paused=true;playButton.setText("▶ 继续");phase.setText("已暂停");return;}
        if(paused){playing=true;paused=false;playButton.setText("Ⅱ 暂停");int token=++runToken;if(inGap){handler.postDelayed(()->{if(token==runToken&&playing){currentIndex=Math.min(words.size()-1,currentIndex+1);segmentIndex=0;inGap=false;updateCurrent();playSegment(token);}},gapMs);}else playSegment(token);return;}
        startAt(currentIndex);
    }

    private void stopPlayback(String label){runToken++;handler.removeCallbacksAndMessages(null);if(tts!=null)tts.stop();playing=false;paused=false;inGap=false;playButton.setText("▶ 播放");phase.setText(label);}
    private void updateCurrent(){if(words.isEmpty()){currentEnglish.setText("等待导入");currentChinese.setText("粘贴单词后即可播放");}else{Word w=words.get(Math.min(currentIndex,words.size()-1));currentEnglish.setText(w.english);currentChinese.setText(w.chinese);}if(adapter!=null)adapter.notifyDataSetChanged();}
    private void confirmClear(){if(words.isEmpty())return;new AlertDialog.Builder(this).setTitle("清空全部单词？").setMessage("清空后无法恢复").setNegativeButton("取消",null).setPositiveButton("确认清空",(d,w)->{stopPlayback("尚未开始");words.clear();currentIndex=0;adapter.notifyDataSetChanged();listTitle.setText("播放列表 · 0 词");saveData();updateCurrent();}).show();}

    private void saveData(){try{JSONArray a=new JSONArray();for(Word w:words)a.put(w.json());prefs.edit().putString("words",a.toString()).putInt("repeat",repeat).putInt("gap",gapMs).apply();}catch(Exception ignored){}}
    private void loadData(){repeat=prefs.getInt("repeat",3);gapMs=prefs.getInt("gap",800);try{JSONArray a=new JSONArray(prefs.getString("words","[]"));for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);words.add(new Word(o.getString("english"),o.getString("chinese")));}}catch(Exception ignored){}}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);if(tts!=null){tts.stop();tts.shutdown();}super.onDestroy();}

    class WordAdapter extends ArrayAdapter<Word>{WordAdapter(Context c,List<Word>w){super(c,0,w);}@Override public View getView(int p,View v,ViewGroup parent){LinearLayout line=row();line.setPadding(dp(12),dp(9),dp(12),dp(9));line.setBackgroundColor(p==currentIndex?Color.rgb(234,240,255):Color.WHITE);TextView no=text(String.format(Locale.CHINA,"%02d",p+1),12,Color.GRAY);line.addView(no,new LinearLayout.LayoutParams(dp(42),dp(48)));LinearLayout copy=new LinearLayout(MainActivity.this);copy.setOrientation(LinearLayout.VERTICAL);TextView en=text(getItem(p).english,17,Color.rgb(19,32,56));en.setTypeface(Typeface.DEFAULT,Typeface.BOLD);TextView cn=text(getItem(p).chinese,14,Color.rgb(104,118,140));copy.addView(en);copy.addView(cn);line.addView(copy,new LinearLayout.LayoutParams(0,dp(52),1));TextView state=text(p==currentIndex&&playing?"Ⅱ":"▶",15,Color.rgb(40,86,216));line.addView(state);return line;}}
}
