package com.echo.vocabplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

final class AppData {
    static final String PREFS = "vocab_player";
    static class Word {
        final String english, chinese;
        Word(String english, String chinese) { this.english=english; this.chinese=chinese; }
    }
    static SharedPreferences prefs(Context c){ return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE); }
    static ArrayList<Word> loadWords(Context c){
        ArrayList<Word> out=new ArrayList<>();
        try { JSONArray a=new JSONArray(prefs(c).getString("words","[]"));
            for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);out.add(new Word(o.getString("english"),o.getString("chinese")));}
        } catch(Exception ignored){} return out;
    }
    static void saveWords(Context c,List<Word> words){
        try {JSONArray a=new JSONArray();for(Word w:words){JSONObject o=new JSONObject();o.put("english",w.english);o.put("chinese",w.chinese);a.put(o);}prefs(c).edit().putString("words",a.toString()).apply();}catch(Exception ignored){}
    }
    static ArrayList<Word> parse(String raw){
        ArrayList<Word> output=new ArrayList<>(); String normalized=raw.replaceAll("[，,；;|｜\\t]"," ");
        for(String line:normalized.split("[\\r\\n]+")){ArrayList<String> en=new ArrayList<>(),cn=new ArrayList<>();
            for(String token:line.trim().split("\\s+")){if(token.isEmpty())continue;if(token.matches("[A-Za-z][A-Za-z'’-]*")){if(!cn.isEmpty()){add(output,en,cn);en.clear();cn.clear();}en.add(token);}else if(!en.isEmpty())cn.add(token);}add(output,en,cn);
        } return output;
    }
    private static void add(List<Word> out,List<String> en,List<String> cn){if(!en.isEmpty()&&!cn.isEmpty())out.add(new Word(TextUtils.join(" ",en),TextUtils.join("",cn)));}
    private AppData(){}
}
