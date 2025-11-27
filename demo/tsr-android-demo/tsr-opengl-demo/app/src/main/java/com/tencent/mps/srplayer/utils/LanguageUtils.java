package com.tencent.mps.srplayer.utils;

import android.content.Context;
import android.content.res.Configuration;

import com.tencent.mps.srplayer.R;

import java.util.Locale;

public class LanguageUtils {
    /***
     * get string
     * @param context
     * @param language 语言(如：zh)
     * @param country 国家(如：CN)
     * @return
     */
    public static String getLanguage(Context context,String language,String country){
        Locale locale = new Locale(language, country);
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.setLocale(locale);
        return context.createConfigurationContext(configuration).getResources().getString(R.string.app_name);
    }
    /***
     * get strings.xml default string
     * @param context
     * @param rString R.string
     * @return
     */
    public static String getDefault(Context context,int rString){
        Locale locale = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            locale = Locale.forLanguageTag("appDefault");
        }else{
            return "";
        }
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.setLocale(locale);
        return context.createConfigurationContext(configuration).getResources().getString(rString);
    }
}
