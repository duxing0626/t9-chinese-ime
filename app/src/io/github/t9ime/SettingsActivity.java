package io.github.t9ime;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.provider.Settings;
import android.view.inputmethod.InputMethodManager;

public class SettingsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            getFragmentManager().beginTransaction()
                    .replace(android.R.id.content, new Prefs())
                    .commit();
        }
    }

    public static class Prefs extends PreferenceFragment {
        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.prefs);
        }

        @Override
        public void onResume() {
            super.onResume();
            Preference update = findPreference("check_update");
            if (update != null) {
                try {
                    String v = getActivity().getPackageManager()
                            .getPackageInfo(getActivity().getPackageName(), 0).versionName;
                    update.setSummary("目前版本 " + v + "，從 GitHub 下載最新版本");
                } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
                    // keep the default summary
                }
            }
            Preference custom = findPreference("mode_custom");
            if (custom != null) {
                boolean exists = Dicts.customFile().exists();
                custom.setSummary("碼表檔案：" + Dicts.customFile().getPath()
                        + (exists ? "（已找到）" : "（未找到，此模式會被略過）"));
            }
        }

        @Override
        public boolean onPreferenceTreeClick(android.preference.PreferenceScreen screen, Preference pref) {
            String key = pref.getKey();
            Context c = getActivity();
            if ("open_ime_settings".equals(key)) {
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
                return true;
            }
            if ("pick_ime".equals(key)) {
                InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.showInputMethodPicker();
                return true;
            }
            if ("key_test".equals(key)) {
                startActivity(new Intent(c, KeyTestActivity.class));
                return true;
            }
            if ("clear_learning".equals(key)) {
                final Context ctx = c;
                new AlertDialog.Builder(c).setTitle("清除學習記錄")
                        .setMessage("清除已記住的常用字、自建詞語和最近使用的符號？")
                        .setPositiveButton("清除", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int which) {
                                Dicts.user(ctx).clear();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
                return true;
            }
            if ("check_update".equals(key)) {
                new Updater(getActivity()).check();
                return true;
            }
            if ("help".equals(key)) {
                show(c, "按鍵說明", HELP);
                return true;
            }
            if ("about".equals(key)) {
                show(c, "資料來源及授權", ABOUT);
                return true;
            }
            return super.onPreferenceTreeClick(screen, pref);
        }

        private static void show(Context c, String title, String message) {
            new AlertDialog.Builder(c).setTitle(title).setMessage(message).setPositiveButton("確定", null).show();
        }
    }

    static final String HELP =
            "【通用】\n"
            + "# ：切換輸入模式（粵→倉→速→筆→自訂→Abc→abc→ABC→123）\n"
            + "長按 # ：選擇其他輸入法\n"
            + "* ：符號及表情符號（在符號表中再按 * 下一類，# 上一類）　長按 * ：本設定頁\n"
            + "0 或 OK：選第一個（或已反白的）候選字\n"
            + "◀ ▶ ：移動反白　▼ ：進入數字選字（1-9 選字，0 下一頁）　▲ ：返回\n"
            + "刪除鍵 / C 鍵 / 返回鍵：刪除一碼；沒有打字時刪除前一個字\n"
            + "返回鍵：欄位空白時才返回上一頁；長按返回鍵可隨時返回\n"
            + "長按數字鍵：直接輸入該數字\n"
            + "沒有輸入時：0 = 空格，1 = 符號\n\n"
            + "【粵拼】按字母所在的鍵，每個字母按一下。\n"
            + "2 abc　3 def　4 ghi　5 jkl　6 mno　7 pqrs　8 tuv　9 wxyz\n"
            + "例：你好 nei hou = 634 468；1 = 分隔音節。可以連打詞語，或先選前面的字再選後面的。\n"
            + "寬鬆拼寫：可用港式拼法，例：是但 shi dan = 744326，香港 heung gong，有 yau，水 shui。\n"
            + "聲母簡拼：每字只打第一個字母，例：唔該 m g = 64。\n"
            + "懶音：你 lei、我 o、廣 gong 都打得出（可在設定關閉）。\n"
            + "分段選出的詞語會自動記住，下次可以一次過打出。\n\n"
            + "【倉頡／速成】字根按其英文字母所在的鍵：\n"
            + "2 日月金(abc)　3 木水火(def)　4 土竹戈(ghi)\n"
            + "5 十大中(jkl)　6 一弓人(mno)　7 心手口尸(pqrs)\n"
            + "8 廿山女(tuv)　9 田難卜重(wxyz)\n"
            + "1 = 萬用字根（任何字根）\n"
            + "例：你 人弓火 onf = 663。速成只打首尾兩碼。\n"
            + "如想逐個字根準確輸入，可在設定開啟「多按輸入字根」（例：2 按兩下 = 月）。\n\n"
            + "【英文】同一個鍵連按選字母，停一停自動確認。\n"
            + "Abc：小寫、大寫都可選，句首先出大寫（2 = a b c A B C 2，句首 = A B C a b c 2）\n"
            + "abc：只有小寫　ABC：只有大寫　1 = 標點　0 = 空格\n\n"
            + "【筆畫】1 橫　2 豎　3 撇　4 點　5 折　6 萬用\n\n"
            + "【自訂碼表（九方等）】\n"
            + "把碼表放在 /sdcard/T9IME/custom.txt，每行：數字碼 [Tab] 字 [Tab 可選的頻率]，"
            + "第一行可寫 #name=九方 作為模式名稱。";

    static final String ABOUT =
            "粵拼字典及詞頻：rime-cantonese（粵語拼音方案）\n"
            + "https://github.com/rime/rime-cantonese\n授權：CC BY 4.0\n\n"
            + "倉頡五代碼表：Jackchows/Cangjie5\n"
            + "https://github.com/Jackchows/Cangjie5\n授權：MIT\n\n"
            + "筆畫碼表：rime-stroke\n"
            + "https://github.com/rime/rime-stroke\n授權：LGPL-3.0\n\n"
            + "表情符號及其中文關鍵字：Unicode emoji 資料及 CLDR 註解\n"
            + "https://unicode.org　授權：Unicode License v3\n\n"
            + "九方輸入法屬商業產品，其碼表未能隨本程式提供；如你擁有合法碼表，可用「自訂碼表」載入。";
}
