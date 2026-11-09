package ua.aura.home;

import android.app.*;
import android.app.role.RoleManager;
import android.content.*;
import android.content.pm.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.*;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import android.appwidget.*;
import java.text.*;
import java.util.*;
import java.util.concurrent.*;

/** A small, offline HOME app. No overlay, accessibility, network or device-admin access. */
public class MainActivity extends Activity {
    private final int ink = Color.rgb(35, 47, 37);
    private int accent, paper, soft;
    private final int[][] palettes = {{0xff465d43,0xffedf1e5,0xffd4e4c4},{0xff5b527b,0xfff0ecf7,0xffe0d6f0},{0xff395d78,0xffe9f1f6,0xffcfe3ee},{0xff83583e,0xfffaf0e5,0xfff1dac2}};
    private FrameLayout root;
    private LinearLayout page;
    private String screen = "home";
    private android.content.SharedPreferences prefs;
    private final ArrayList<AppEntry> apps = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean appsLoaded;
    private boolean appsLoading;
    private long lastAppLoad;
    private int appLoadGeneration;
    private float touchX, touchY;
    private boolean gestureHandled;
    private boolean dragging;
    private AppWidgetHost widgetHost;
    private int pendingWidget=-1;
    private LinearLayout appRows;
    private EditText search;
    private TextView wifiSubtitle;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView dateView;
    private final Runnable clockTick = new Runnable() { public void run() {
        if (dateView != null) dateView.setText(new SimpleDateFormat("EEEE, d MMMM", new Locale("ru")).format(new Date()));
        handler.postDelayed(this, 30000);
    }};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("aura", MODE_PRIVATE);
        widgetHost=new AppWidgetHost(this,1024);
        if(state!=null) pendingWidget=state.getInt("pendingWidget",-1);
        loadColors();
        rememberHome();
        showHome();
        if (!isDefaultHome() && !prefs.getBoolean("homePromptV2", false)) showWelcome();
        UpdateService.schedule(this);
        if(getIntent().getBooleanExtra("updates",false)) showUpdates();
    }
    private void loadColors() {
        int[] p = palettes[Math.floorMod(prefs.getInt("palette",0),palettes.length)];
        accent=p[0]; paper=p[1]; soft=p[2];
        getWindow().setStatusBarColor(paper);
        getWindow().setNavigationBarColor(paper);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }
    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(clockTick); handler.post(clockTick);
        if ("quick".equals(screen)) showQuick();
        else if ("settings".equals(screen)) showSettings();
        loadApps();
    }
    @Override protected void onStart(){ super.onStart(); widgetHost.startListening(); }
    @Override protected void onStop(){ widgetHost.stopListening(); super.onStop(); }
    @Override protected void onSaveInstanceState(Bundle b){ b.putInt("pendingWidget",pendingWidget); super.onSaveInstanceState(b); }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(clockTick); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); worker.shutdownNow(); super.onDestroy(); }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); if(intent.getBooleanExtra("updates",false)) showUpdates(); else showHome(); }
    @Override public void onBackPressed() { if (!"home".equals(screen)) showHome(); else returnHome(); }

    private int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density + .5f); }
    private TextView label(String s, float size, int color) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); t.setFontFeatureSettings("kern"); return t;
    }
    private GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        if (Color.alpha(color)<255 && Color.alpha(color)>0) {
            d.setOrientation(GradientDrawable.Orientation.TL_BR);
            d.setColors(new int[]{0x75ffffff,0x28ffffff});
            d.setStroke(dp(1),0xafffffff);
        } else d.setColor(color);
        d.setCornerRadius(dp(radius)); return d;
    }
    private void clickSurface(View v, int color, int radius) {
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22465d43),round(color,radius),null));
        v.setClickable(true); v.setFocusable(true);
    }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private void gap(LinearLayout l, int h) { l.addView(new View(this),new LinearLayout.LayoutParams(1,dp(h))); }
    private void base(String name, boolean wallpaper) {
        screen=name; dateView=null; wifiSubtitle=null; search=null; appRows=null;
        root=new FrameLayout(this); root.setBackgroundColor(paper);
        Wallpaper backdrop=new Wallpaper();
        if (!wallpaper && Build.VERSION.SDK_INT>=31) backdrop.setRenderEffect(RenderEffect.createBlurEffect(dp(22),dp(22),Shader.TileMode.CLAMP));
        root.addView(backdrop,new FrameLayout.LayoutParams(-1,-1));
        page=column(); page.setPadding(dp(22),dp(10),dp(22),dp(12));
        if(wallpaper) page.setOnLongClickListener(v->{ customize(); return true; });
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false); scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(page,new ScrollView.LayoutParams(-1,-2));
        root.addView(scroll,new FrameLayout.LayoutParams(-1,-1)); setContentView(root);
    }
    private void header(String title) {
        LinearLayout r=row(); TextView back=label("‹",38,ink); back.setGravity(Gravity.CENTER); back.setContentDescription("На главный экран"); clickSurface(back,0x66ffffff,24);
        r.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48))); back.setOnClickListener(v->showHome());
        TextView t=label(title,24,ink); t.setPadding(dp(14),0,0,0); t.setTypeface(null,Typeface.BOLD); r.addView(t,new LinearLayout.LayoutParams(0,-2,1)); page.addView(r); gap(page,22);
    }
    private TextView button(String title, boolean primary, Runnable action) {
        TextView t=label(title,16,primary?Color.WHITE:ink); t.setTypeface(null,Typeface.BOLD); t.setGravity(Gravity.CENTER); t.setPadding(dp(16),dp(15),dp(16),dp(15)); t.setMinHeight(dp(52));
        clickSurface(t,primary?accent:0xcfffffff,28); t.setOnClickListener(v->action.run()); return t;
    }
    private void addButton(String title, boolean primary, Runnable action) { page.addView(button(title,primary,action),new LinearLayout.LayoutParams(-1,-2)); gap(page,10); }

    private void showHome() {
        base("home",true);
        LinearLayout brand=row(); TextView name=label("aura",22,accent); name.setTypeface(null,Typeface.BOLD); brand.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        TextView panel=button("Управление",false,()->showQuick()); panel.setTextSize(13); brand.addView(panel); page.addView(brand);
        gap(page,30);
        dateView=label(new SimpleDateFormat("EEEE, d MMMM",new Locale("ru")).format(new Date()),16,ink); dateView.setGravity(Gravity.CENTER); page.addView(dateView);
        TextClock clock=new TextClock(this); clock.setFormat12Hour("HH:mm"); clock.setFormat24Hour("HH:mm"); clock.setTextSize(72); clock.setTextColor(accent); clock.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL)); clock.setGravity(Gravity.CENTER); page.addView(clock,new LinearLayout.LayoutParams(-1,-2)); clock.setOnLongClickListener(v->{customize();return true;});
        TextView hello=label("Твой телефон. Твой ритм.",14,accent); hello.setGravity(Gravity.CENTER); page.addView(hello); gap(page,26);
        TextView find=button("⌕   Поиск приложений",false,()->showApps(true)); find.setGravity(Gravity.CENTER_VERTICAL); find.setPadding(dp(22),dp(16),dp(22),dp(16)); page.addView(find);
        page.addView(new View(this),new LinearLayout.LayoutParams(1,dp(24),1));
        addWidgets();
        addPinnedApps();
        LinearLayout top=row();
        shortcut(top,"Настройки","settings",()->showSettings());
        shortcut(top,"Камера","camera",()->open(new Intent("android.media.action.STILL_IMAGE_CAMERA")));
        shortcut(top,"Фото","photo",()->open(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY)));
        shortcut(top,"Обычный\nтелефон","exit",()->returnHome()); page.addView(top);
        gap(page,18);
        LinearLayout dock=row(); dock.setPadding(dp(6),dp(12),dp(6),dp(6)); dock.setBackground(round(0xb8ffffff,34));
        shortcut(dock,"Телефон","phone",()->open(new Intent(Intent.ACTION_DIAL)));
        shortcut(dock,"Сообщения","message",()->open(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING)));
        shortcut(dock,"Браузер","globe",()->open(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)));
        shortcut(dock,"Все","apps",()->showApps(false)); page.addView(dock);
        gap(page,12); TextView tip=label("↑ Приложения     ·     ↓ Быстрые настройки",11,accent); tip.setGravity(Gravity.CENTER); page.addView(tip);
        gap(page,8); page.addView(button("Обои и виджеты",false,()->customize()));
    }
    private void shortcut(LinearLayout parent,String title,String symbol,Runnable action) {
        LinearLayout item=column(); item.setGravity(Gravity.CENTER); item.setPadding(dp(1),dp(4),dp(1),dp(4));
        Icon icon=new Icon(symbol,accent); icon.setBackground(round(soft,23)); item.addView(icon,new LinearLayout.LayoutParams(dp(54),dp(54)));
        TextView text=label(title,11,ink); text.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL); text.setMinLines(2); text.setMaxLines(2); text.setEllipsize(TextUtils.TruncateAt.END); text.setPadding(0,dp(7),0,0); item.addView(text,new LinearLayout.LayoutParams(-1,-2));
        clickSurface(item,Color.TRANSPARENT,18); item.setContentDescription(title.replace('\n',' ')); item.setOnClickListener(v->action.run()); parent.addView(item,new LinearLayout.LayoutParams(0,-2,1));
    }
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (ev.getActionMasked()==MotionEvent.ACTION_DOWN) { touchX=ev.getX(); touchY=ev.getY(); gestureHandled=false; }
        if (gestureHandled) return true;
        if ((ev.getActionMasked()==MotionEvent.ACTION_MOVE || ev.getActionMasked()==MotionEvent.ACTION_UP) && "home".equals(screen) && !dragging && getIds("widgets").isEmpty() && getIds("pins").size()<9) {
            float dy=ev.getY()-touchY, dx=ev.getX()-touchX;
            if (Math.abs(dy)>dp(55) && Math.abs(dy)>Math.abs(dx)*1.5) {
                MotionEvent cancel=MotionEvent.obtain(ev); cancel.setAction(MotionEvent.ACTION_CANCEL); super.dispatchTouchEvent(cancel); cancel.recycle();
                gestureHandled=true;
                if (dy>0) showQuick(); else showApps(false); return true;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private void showWelcome() {
        new AlertDialog.Builder(this).setTitle("Сделать Aura основным экраном?")
          .setMessage("Тогда кнопка «Домой» будет открывать Aura вместо прежнего рабочего стола. На следующем экране Android выбери Aura Home.\n\nСвайп вверх — приложения. Свайп вниз — панель управления. Кнопка «Обычный телефон» позволяет вернуться.")
          .setPositiveButton("Сделать основным",(d,w)->{ prefs.edit().putBoolean("homePromptV2",true).apply(); requestHome(); })
          .setNeutralButton("Сначала попробовать",(d,w)->prefs.edit().putBoolean("homePromptV2",true).apply())
          .setNegativeButton("Обычный телефон",(d,w)->returnHome())
          .setCancelable(false).show();
    }
    private void requestHome() {
        RoleManager rm=getSystemService(RoleManager.class);
        if(rm!=null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
            if(rm.isRoleHeld(RoleManager.ROLE_HOME)) { toast("Aura уже выбрана главным экраном"); return; }
            try { startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME),17); return; } catch (RuntimeException ignored) {}
        }
        settings(Settings.ACTION_HOME_SETTINGS);
    }
    private Intent homeIntent() { return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME); }
    private void rememberHome() {
        ResolveInfo ri=getPackageManager().resolveActivity(homeIntent(),PackageManager.MATCH_DEFAULT_ONLY);
        if(ri!=null && ri.activityInfo!=null && !getPackageName().equals(ri.activityInfo.packageName) && !"android".equals(ri.activityInfo.packageName)) {
            prefs.edit().putString("previousHome",new ComponentName(ri.activityInfo.packageName,ri.activityInfo.name).flattenToString()).apply();
        }
    }
    private void returnHome() {
        java.util.List<ResolveInfo> candidates=getPackageManager().queryIntentActivities(homeIntent(),0);
        ArrayList<ResolveInfo> homes=new ArrayList<>();
        for (ResolveInfo r:candidates) if (!getPackageName().equals(r.activityInfo.packageName)) homes.add(r);
        String saved=prefs.getString("previousHome","");
        Collections.sort(homes,(a,b)->Boolean.compare(new ComponentName(b.activityInfo.packageName,b.activityInfo.name).flattenToString().equals(saved),new ComponentName(a.activityInfo.packageName,a.activityInfo.name).flattenToString().equals(saved)));
        ArrayList<String> names=new ArrayList<>(); for(ResolveInfo r:homes) names.add("Открыть: "+r.loadLabel(getPackageManager()));
        names.add("Сменить главный экран по умолчанию");
        new AlertDialog.Builder(this).setTitle("Вернуться в обычный телефон")
            .setItems(names.toArray(new String[0]),(dialog,index)->{
                if(index==homes.size()) { settings(Settings.ACTION_HOME_SETTINGS); return; }
                ResolveInfo r=homes.get(index);
                Intent i=homeIntent().setComponent(new ComponentName(r.activityInfo.packageName,r.activityInfo.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                if(open(i)) { if(isDefaultHome()) toast("Для постоянного возврата смените главный экран в настройках"); finish(); }
            }).setNegativeButton("Отмена",null).show();
    }
    private boolean isDefaultHome() { RoleManager rm=getSystemService(RoleManager.class); return rm!=null && rm.isRoleHeld(RoleManager.ROLE_HOME); }
    private void showSettings() {
        base("settings",false); header("Настройки");
        TextView intro=label("Твой Aura",36,accent); intro.setTypeface(null,Typeface.BOLD); page.addView(intro); gap(page,6); page.addView(label("Выбери, каким будет твой главный экран.",15,ink)); gap(page,22);
        setting("home","Главный экран",isDefaultHome()?"Aura уже выбрана по умолчанию":"Выбрать Aura при нажатии «Домой»",()->requestHome());
        setting("apps","Обновления","Проверить новую версию Aura",()->showUpdates());
        setting("photo","Обои и виджеты","Живые фоны и оформление рабочего стола",()->customize());
        setting("exit","Обычный телефон","Открыть прежний экран или сменить основной",()->returnHome());
        setting("palette","Цвет и настроение","Шалфей · лаванда · небо · песок",()->choosePalette());
        setting("sun","Системная яркость",Settings.System.canWrite(this)?"Разрешение уже предоставлено":"Разрешить ползунку менять яркость телефона",()->brightnessPermission());
        setting("wifi","Подключения","Wi-Fi, мобильная сеть и Bluetooth",()->showQuick());
        setting("settings","Настройки Android","Все параметры твоего телефона",()->settings(Settings.ACTION_SETTINGS));
        gap(page,14); TextView info=label("Aura Home 1.0\nОформление в духе Material 3. Это лаунчер, а не обновление до Android 17. Системная шторка, экран блокировки и интерфейс других приложений остаются под управлением Android.",13,accent); info.setLineSpacing(dp(3),1); page.addView(info);
    }
    private void setting(String symbol,String title,String sub,Runnable action) {
        LinearLayout r=row(); r.setPadding(dp(16),dp(16),dp(12),dp(16)); clickSurface(r,0xcfffffff,22);
        r.addView(new Icon(symbol,accent),new LinearLayout.LayoutParams(dp(32),dp(32)));
        LinearLayout texts=column(); texts.setPadding(dp(14),0,dp(8),0); TextView t=label(title,17,ink); t.setTypeface(null,Typeface.BOLD); texts.addView(t); gap(texts,3); texts.addView(label(sub,12,accent)); r.addView(texts,new LinearLayout.LayoutParams(0,-2,1)); r.addView(label("›",24,accent)); r.setOnClickListener(v->action.run()); page.addView(r); gap(page,8);
    }
    private void choosePalette() {
        new AlertDialog.Builder(this).setTitle("Цвет главного экрана").setSingleChoiceItems(new String[]{"Шалфей — зелёный","Лаванда — сиреневый","Небо — голубой","Песок — тёплый"},prefs.getInt("palette",0),(d,n)->{ prefs.edit().putInt("palette",n).apply(); loadColors(); d.dismiss(); showHome(); }).setNegativeButton("Отмена",null).show();
    }
    private void showQuick() {
        base("quick",false); header("Пункт управления");
        TextClock clock=new TextClock(this); clock.setFormat12Hour("HH:mm"); clock.setFormat24Hour("HH:mm"); clock.setTextSize(52); clock.setTextColor(accent); page.addView(clock); gap(page,20);
        LinearLayout a=row(); wifiSubtitle=tile(a,"wifi","Интернет",wifiState(),true,()->settings(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)); tile(a,"bluetooth","Bluetooth","Открыть настройки",false,()->settings(Settings.ACTION_BLUETOOTH_SETTINGS)); page.addView(a); gap(page,10);
        LinearLayout b=row(); tile(b,"plane","Авиарежим",Settings.Global.getInt(getContentResolver(),Settings.Global.AIRPLANE_MODE_ON,0)==1?"Включён":"Выключен",false,()->settings(Settings.ACTION_AIRPLANE_MODE_SETTINGS)); tile(b,"sun","Экран","Яркость и поворот",false,()->settings(Settings.ACTION_DISPLAY_SETTINGS)); page.addView(b); gap(page,18);
        LinearLayout brightness=column(); brightness.setPadding(dp(20),dp(18),dp(20),dp(18)); brightness.setBackground(round(0x88ffffff,28)); brightness.addView(label("Яркость",18,ink));
        boolean global=Settings.System.canWrite(this);
        TextView explanation=label(global?"Яркость всего телефона":"Пока меняется только яркость Aura",12,accent); brightness.addView(explanation); gap(brightness,12);
        SeekBar bar=new SeekBar(this); bar.setMax(245); int value=Settings.System.getInt(getContentResolver(),Settings.System.SCREEN_BRIGHTNESS,128);
        float local=getWindow().getAttributes().screenBrightness; if(!global && local>=0) value=Math.round(local*255);
        bar.setProgress(Math.max(0,value-10)); bar.setContentDescription("Яркость экрана"); bar.setProgressTintList(ColorStateList.valueOf(accent)); bar.setThumbTintList(ColorStateList.valueOf(accent)); brightness.addView(bar,new LinearLayout.LayoutParams(-1,dp(48)));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s,int p,boolean user) { if(user) { WindowManager.LayoutParams attrs=getWindow().getAttributes(); attrs.screenBrightness=(p+10)/255f; getWindow().setAttributes(attrs); } }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {
                if(Settings.System.canWrite(MainActivity.this)) try {
                    Settings.System.putInt(getContentResolver(),Settings.System.SCREEN_BRIGHTNESS_MODE,Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                    Settings.System.putInt(getContentResolver(),Settings.System.SCREEN_BRIGHTNESS,s.getProgress()+10);
                    WindowManager.LayoutParams attrs=getWindow().getAttributes(); attrs.screenBrightness=-1; getWindow().setAttributes(attrs);
                } catch (RuntimeException e) { toast("Откройте системные настройки яркости"); }
            }
        });
        if(!global) { gap(brightness,8); brightness.addView(button("Разрешить для всего телефона",false,()->brightnessPermission())); }
        page.addView(brightness); gap(page,18);
        addButton("Все настройки",true,()->showSettings());
        addButton("Вернуться в обычный телефон",false,()->returnHome());
        gap(page,4); TextView note=label("Wi-Fi и Bluetooth управляются через системные панели. Эта шторка открывается свайпом вниз на главном экране Aura.",13,accent); note.setLineSpacing(dp(3),1); page.addView(note);
    }
    private TextView tile(LinearLayout r,String symbol,String title,String subtitle,boolean selected,Runnable action) {
        LinearLayout box=column(); box.setPadding(dp(18),dp(16),dp(12),dp(16)); clickSurface(box,selected?accent:0x88ffffff,28);
        int color=selected?Color.WHITE:ink; box.addView(new Icon(symbol,color),new LinearLayout.LayoutParams(dp(28),dp(28))); gap(box,14);
        TextView titleView=label(title,16,color); titleView.setTypeface(null,Typeface.BOLD); box.addView(titleView); gap(box,4); TextView sub=label(subtitle,11,color); box.addView(sub);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1); lp.setMargins(0,0,r.getChildCount()==0?dp(10):0,0); r.addView(box,lp); box.setOnClickListener(v->action.run()); return sub;
    }
    private String wifiState() { try { WifiManager wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE); return wm!=null && wm.isWifiEnabled()?"Wi-Fi включён":"Wi-Fi выключен"; } catch (RuntimeException e) { return "Открыть подключения"; } }
    private void brightnessPermission() { if(Settings.System.canWrite(this)) { settings(Settings.ACTION_DISPLAY_SETTINGS); return; } open(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,Uri.parse("package:"+getPackageName()))); }
    private void settings(String action) { if(!open(new Intent(action)) && !Settings.ACTION_SETTINGS.equals(action)) open(new Intent(Settings.ACTION_SETTINGS)); }
    private boolean open(Intent intent) { try { startActivity(intent); return true; } catch (RuntimeException e) { toast("На телефоне нет приложения для этого действия"); return false; } }
    private void toast(String s) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }

    private static class AppEntry { String label; ComponentName component; Drawable icon; }
    private void loadApps() {
        if(worker.isShutdown() || appsLoading || (appsLoaded && SystemClock.elapsedRealtime()-lastAppLoad<60000)) return;
        appsLoading=true;
        final int generation=++appLoadGeneration;
        worker.execute(()->{
            ArrayList<AppEntry> found=new ArrayList<>(); PackageManager pm=getPackageManager();
            try {
                for(ResolveInfo r:pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)) {
                    if(r.activityInfo.packageName.equals(getPackageName())) continue;
                    try { AppEntry entry=new AppEntry(); entry.label=r.loadLabel(pm).toString(); entry.component=new ComponentName(r.activityInfo.packageName,r.activityInfo.name); found.add(entry); } catch(RuntimeException ignored) {}
                }
                Collator collator=Collator.getInstance(new Locale("ru")); Collections.sort(found,(a,b)->collator.compare(a.label,b.label));
                runOnUiThread(()->{ appsLoading=false; if(isDestroyed() || generation!=appLoadGeneration) return; apps.clear(); apps.addAll(found); appsLoaded=true; lastAppLoad=SystemClock.elapsedRealtime(); if("apps".equals(screen) && search!=null) populateApps(search.getText().toString()); });
            } catch(RuntimeException e) { runOnUiThread(()->{ appsLoading=false; if(!isDestroyed()) toast("Не удалось обновить список приложений"); }); }
        });
    }
    private void showApps(boolean focus) {
        base("apps",false); header("Приложения"); search=new EditText(this); search.setSingleLine(true); search.setTextSize(16); search.setTextColor(ink); search.setHintTextColor(accent); search.setHint("Найти приложение"); search.setPadding(dp(20),dp(10),dp(20),dp(10)); search.setBackground(round(Color.WHITE,28)); search.setContentDescription("Поиск приложений"); page.addView(search,new LinearLayout.LayoutParams(-1,dp(54))); gap(page,20);
        appRows=column(); page.addView(appRows); populateApps("");
        search.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s,int a,int c,int f) {} public void onTextChanged(CharSequence s,int a,int b,int c) { populateApps(s.toString()); } public void afterTextChanged(Editable e) {} });
        if(focus) { search.requestFocus(); search.postDelayed(()->{ if(search!=null && "apps".equals(screen)) ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(search,InputMethodManager.SHOW_IMPLICIT); },150); }
    }
    private void populateApps(String query) {
        if(appRows==null) return; appRows.removeAllViews(); String q=query.trim().toLowerCase(Locale.ROOT);
        ArrayList<AppEntry> selected=new ArrayList<>(); for(AppEntry a:apps) if(a.label.toLowerCase(Locale.ROOT).contains(q)) selected.add(a);
        if(selected.isEmpty()) { appRows.addView(label(appsLoaded?"Ничего не найдено":"Загружаем приложения…",16,accent)); return; }
        for(int i=0;i<selected.size();i+=4) {
            LinearLayout r=row(); r.setGravity(Gravity.TOP);
            for(int j=i;j<i+4;j++) {
                if(j>=selected.size()) { r.addView(new View(this),new LinearLayout.LayoutParams(0,dp(96),1)); continue; }
                final AppEntry a=selected.get(j); LinearLayout cell=column(); cell.setGravity(Gravity.CENTER); cell.setPadding(dp(4),dp(8),dp(4),dp(8)); clickSurface(cell,Color.TRANSPARENT,18);
                ImageView icon=new ImageView(this); icon.setImageDrawable(a.icon); cell.addView(icon,new LinearLayout.LayoutParams(dp(48),dp(48))); TextView text=label(a.label,11,ink); text.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL); text.setMaxLines(2); text.setEllipsize(TextUtils.TruncateAt.END); text.setPadding(0,dp(8),0,0); cell.addView(text,new LinearLayout.LayoutParams(-1,dp(40)));
                cell.setContentDescription(a.label); cell.setOnClickListener(v->open(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(a.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)));
                if(a.icon==null) {
                    icon.setImageDrawable(getPackageManager().getDefaultActivityIcon());
                    worker.execute(()->{
                        Drawable loaded;
                        try { loaded=getPackageManager().getActivityIcon(a.component); } catch(Exception e) { return; }
                        runOnUiThread(()->{ if(isDestroyed()) return; a.icon=loaded; if(icon.isAttachedToWindow()) icon.setImageDrawable(loaded); });
                    });
                }
                cell.setOnLongClickListener(v->{new AlertDialog.Builder(this).setTitle(a.label).setItems(new String[]{"На главный экран","О приложении"},(d,n)->{if(n==0){ArrayList<String> ids=getIds("pins");String id=a.component.flattenToString();if(!ids.contains(id))ids.add(id);saveIds("pins",ids);showHome();}else open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+a.component.getPackageName())));}).show();return true;});
                r.addView(cell,new LinearLayout.LayoutParams(0,-2,1));
            } appRows.addView(r);
        }
    }

    private ArrayList<String> getIds(String key){
        ArrayList<String> out=new ArrayList<>(); String raw=prefs.getString(key,"");
        if(!raw.isEmpty()) Collections.addAll(out,raw.split("\\|")); return out;
    }
    private void saveIds(String key,ArrayList<String> ids){prefs.edit().putString(key,TextUtils.join("|",ids)).apply();}
    private void customize(){
        new AlertDialog.Builder(this).setTitle("Твой рабочий стол").setItems(new String[]{"Добавить приложения","Добавить виджет","Обои","Живые обои","Обновления"},(d,n)->{
            if(n==0)showApps(false);if(n==1)pickWidget();if(n==2)chooseWallpaper(false);if(n==3)chooseWallpaper(true);if(n==4)showUpdates();
        }).show();
    }
    private void chooseWallpaper(boolean live){
        new AlertDialog.Builder(this).setTitle(live?"Живые обои":"Обои").setItems(new String[]{"Северное сияние","Океан","Персиковый закат","Космос"},(d,n)->{
            prefs.edit().putInt("wallpaper",n).putBoolean("live",live).apply();showHome();
        }).setNegativeButton("Отмена",null).show();
    }
    private void addPinnedApps(){
        ArrayList<String> ids=getIds("pins"); if(ids.isEmpty())return;
        TextView hint=label("Мои приложения · удерживай и перетаскивай",12,accent);page.addView(hint);gap(page,8);
        LinearLayout line=null;
        for(int index=0;index<ids.size();index++){
            String id=ids.get(index); ComponentName component=ComponentName.unflattenFromString(id);if(component==null)continue;
            if(index%4==0){line=row();line.setGravity(Gravity.TOP);page.addView(line);}
            LinearLayout cell=column();cell.setGravity(Gravity.CENTER);cell.setPadding(dp(3),dp(8),dp(3),dp(10));clickSurface(cell,0x50ffffff,20);
            ImageView icon=new ImageView(this);String title;
            try{ActivityInfo ai=getPackageManager().getActivityInfo(component,0);title=ai.loadLabel(getPackageManager()).toString();icon.setImageDrawable(ai.loadIcon(getPackageManager()));}catch(Exception e){title="Удалено";icon.setImageDrawable(getPackageManager().getDefaultActivityIcon());}
            cell.addView(icon,new LinearLayout.LayoutParams(dp(48),dp(48)));TextView name=label(title,11,ink);name.setGravity(Gravity.CENTER);name.setMaxLines(2);name.setMinLines(2);name.setEllipsize(TextUtils.TruncateAt.END);cell.addView(name);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(dp(3),0,dp(3),dp(6));line.addView(cell,lp);
            cell.setOnClickListener(v->open(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            cell.setOnLongClickListener(v->{dragging=true;boolean ok=v.startDragAndDrop(ClipData.newPlainText("aura-pin",id),new View.DragShadowBuilder(v),id,0);if(!ok)dragging=false;return ok;});
            cell.setOnDragListener((v,event)->{
                if(!(event.getLocalState() instanceof String))return false;
                if(event.getAction()==DragEvent.ACTION_DRAG_ENTERED)v.setAlpha(.5f);
                if(event.getAction()==DragEvent.ACTION_DRAG_EXITED)v.setAlpha(1);
                if(event.getAction()==DragEvent.ACTION_DROP){ArrayList<String> order=getIds("pins");String from=(String)event.getLocalState();int target=order.indexOf(id);if(order.contains(from)&&target>=0){order.remove(from);order.add(Math.min(target,order.size()),from);saveIds("pins",order);handler.post(()->showHome());}}
                if(event.getAction()==DragEvent.ACTION_DRAG_ENDED){dragging=false;v.setAlpha(1);}return true;
            });
        }
        if(line!=null)while(line.getChildCount()<4)line.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        TextView remove=button("Перетащи сюда, чтобы убрать значок",false,()->toast("Зажми значок и перетащи его сюда"));
        remove.setTextSize(12);remove.setOnDragListener((v,e)->{if(!(e.getLocalState() instanceof String))return false;if(e.getAction()==DragEvent.ACTION_DROP){ArrayList<String> order=getIds("pins");order.remove((String)e.getLocalState());saveIds("pins",order);handler.post(()->showHome());}if(e.getAction()==DragEvent.ACTION_DRAG_ENDED)dragging=false;return true;});page.addView(remove);gap(page,16);
    }
    private void pickWidget(){
        java.util.List<AppWidgetProviderInfo> providers=AppWidgetManager.getInstance(this).getInstalledProviders();
        ArrayList<AppWidgetProviderInfo> eligible=new ArrayList<>();for(AppWidgetProviderInfo p:providers)if((p.widgetCategory&AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)!=0)eligible.add(p);
        Collections.sort(eligible,(a,b)->a.loadLabel(getPackageManager()).compareToIgnoreCase(b.loadLabel(getPackageManager())));
        String[] names=new String[eligible.size()];for(int i=0;i<names.length;i++)names[i]=eligible.get(i).loadLabel(getPackageManager());
        if(names.length==0){toast("На телефоне нет доступных виджетов");return;}
        new AlertDialog.Builder(this).setTitle("Добавить виджет").setItems(names,(d,n)->{
            if(pendingWidget!=-1)widgetHost.deleteAppWidgetId(pendingWidget);
            pendingWidget=widgetHost.allocateAppWidgetId();AppWidgetProviderInfo info=eligible.get(n);
            if(AppWidgetManager.getInstance(this).bindAppWidgetIdIfAllowed(pendingWidget,info.provider))configureWidget();
            else try{startActivityForResult(new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,pendingWidget).putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER,info.provider),41);}catch(RuntimeException e){discardWidget();toast("Не удалось запросить доступ к виджету");}
        }).setNegativeButton("Отмена",null).show();
    }
    private void discardWidget(){if(pendingWidget!=-1)widgetHost.deleteAppWidgetId(pendingWidget);pendingWidget=-1;}
    private void configureWidget(){
        AppWidgetProviderInfo info=AppWidgetManager.getInstance(this).getAppWidgetInfo(pendingWidget);
        if(info==null){discardWidget();return;}
        if(info.configure!=null)try{widgetHost.startAppWidgetConfigureActivityForResult(this,pendingWidget,0,42,null);}catch(Exception e){discardWidget();toast("Настройка виджета недоступна");}
        else finishWidget();
    }
    private void finishWidget(){ArrayList<String> ids=getIds("widgets");String id=String.valueOf(pendingWidget);if(!ids.contains(id))ids.add(id);saveIds("widgets",ids);pendingWidget=-1;showHome();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==41||request==42){if(result!=RESULT_OK||pendingWidget==-1){discardWidget();return;}if(request==41)configureWidget();else finishWidget();}}
    private void addWidgets(){
        for(String raw:getIds("widgets"))try{
            int id=Integer.parseInt(raw);AppWidgetProviderInfo info=AppWidgetManager.getInstance(this).getAppWidgetInfo(id);if(info==null)continue;
            LinearLayout shell=column();shell.setPadding(dp(8),dp(8),dp(8),dp(8));shell.setBackground(round(0x80ffffff,24));
            AppWidgetHostView view=widgetHost.createView(this,id,info);view.setAppWidget(id,info);
            int width=Math.max(160,(int)(getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density)-60);
            int height=prefs.getInt("widgetHeight"+id,Math.max(140,Math.min(320,(int)(info.minHeight/getResources().getDisplayMetrics().density))));
            view.updateAppWidgetSize(null,width,height,width,height);shell.addView(view,new LinearLayout.LayoutParams(-1,dp(height)));
            TextView edit=button("Размер / убрать виджет",false,()->new AlertDialog.Builder(this).setTitle(info.loadLabel(getPackageManager())).setItems(new String[]{"Компактный","Средний","Большой","Убрать"},(d,n)->{if(n==3){ArrayList<String> ids=getIds("widgets");ids.remove(raw);saveIds("widgets",ids);widgetHost.deleteAppWidgetId(id);}else prefs.edit().putInt("widgetHeight"+id,new int[]{140,220,320}[n]).apply();showHome();}).show());edit.setTextSize(11);shell.addView(edit);page.addView(shell);gap(page,14);
        }catch(RuntimeException ignored){}
    }
    private void showUpdates(){
        base("updates",false);header("Обновления Aura");
        page.addView(label("Версия 1.3",32,ink));gap(page,16);
        TextView state=label("Проверяй обновления здесь или включи уведомления. Установка — только по твоему решению.",15,ink);page.addView(state);gap(page,16);
        EditText repo=new EditText(this);repo.setSingleLine(true);repo.setTextSize(16);repo.setHint("имя/aura-home");repo.setText(UpdateService.repository(this));page.addView(repo);gap(page,10);
        addButton("Сохранить источник",false,()->{String value=repo.getText().toString().trim();if(!value.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")){toast("Укажи репозиторий в формате имя/проект");return;}prefs.edit().putString("updateRepo",value).putInt("notifiedVersion",0).apply();UpdateService.schedule(this);toast("Источник обновлений сохранён");});
        addButton("Проверить обновления",true,()->{state.setText("Проверяем…");new Thread(()->{try{UpdateService.Release r=UpdateService.check(this);runOnUiThread(()->{if(isDestroyed()||!"updates".equals(screen))return;if(r==null){state.setText("Установлена актуальная версия");return;}state.setText("Доступна "+r.name+"\n"+r.notes);new AlertDialog.Builder(this).setTitle("Доступно обновление").setMessage(r.name+"\n\n"+r.notes).setPositiveButton("Скачать APK",(d,n)->open(new Intent(Intent.ACTION_VIEW,Uri.parse(r.url)))).setNegativeButton("Позже",null).show();});}catch(Exception e){runOnUiThread(()->state.setText("Не удалось проверить: "+e.getMessage()));}}).start();});
        addButton("Включить уведомления",false,()->{if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},51);UpdateService.schedule(this);});
        addButton("Выключить фоновую проверку",false,()->{prefs.edit().putBoolean("updateChecks",false).apply();getSystemService(android.app.job.JobScheduler.class).cancel(1025);toast("Фоновая проверка выключена");});
        addButton("Включить фоновую проверку",false,()->{prefs.edit().putBoolean("updateChecks",true).apply();UpdateService.schedule(this);toast("Фоновая проверка включена");});
        page.addView(label("Android проверяет новые версии периодически, даже когда Aura не открыта. В режиме экономии энергии проверка может задерживаться. После принудительной остановки открой Aura снова. Репозиторий должен быть публичным, с опубликованным релизом и APK.",13,accent));
    }
    private class Wallpaper extends View {
        Paint paint=new Paint(3); Wallpaper(){super(MainActivity.this); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas c) {
            float w=getWidth(),h=getHeight();
            int choice=prefs.getInt("wallpaper",0);boolean live=prefs.getBoolean("live",false);float phase=live?SystemClock.uptimeMillis()/14000f:0;
            int[][] tones={{0xffb9d8fa,0xffdcd3f5,0xfff6cfdc,0xffe9e9fa},{0xff90d6e9,0xffbdece5,0xffc8e3ff,0xffafcddb},{0xffffd2b4,0xfff7bccc,0xffe5cdf5,0xfffbe5c3},{0xffb6b8e7,0xffb3cae9,0xffdbbde4,0xffc8d7f2}};
            paint.setShader(new LinearGradient(0,0,w,h,tones[Math.floorMod(choice,4)],null,Shader.TileMode.CLAMP));
            c.drawRect(0,0,w,h,paint);
            paint.setShader(new RadialGradient(w*.05f,h*.3f,w*.95f,new int[]{0xff8bcbd9,0x008bcbd9},null,Shader.TileMode.CLAMP)); c.drawRect(0,0,w,h,paint);
            paint.setShader(new RadialGradient(w*.95f,h*.65f,w*.95f,new int[]{0xffb1a3ed,0x00b1a3ed},null,Shader.TileMode.CLAMP)); c.drawRect(0,0,w,h,paint);
            paint.setShader(null); paint.setColor(0x28ffffff);
            c.save(); c.rotate(-30,w/2,h/2); c.drawRoundRect(w*.1f,-h*.2f,w*.48f,h*1.2f,w*.2f,w*.2f,paint); c.restore();
            for(int i=0;i<5;i++){float x=w*(.15f+i*.2f)+(float)Math.sin(phase+i)*w*.16f;float y=h*(.1f+i*.2f)+(float)Math.cos(phase*.7f+i)*h*.1f;paint.setShader(new RadialGradient(x,y,w*.4f,new int[]{0x65ffffff,0x00ffffff},null,Shader.TileMode.CLAMP));c.drawRect(0,0,w,h,paint);}paint.setShader(null);
            if(live&&hasWindowFocus()&&getWindowVisibility()==VISIBLE)postInvalidateDelayed(33);
        }
        @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(focus)invalidate();}
    }
    private class Icon extends View {
        String name; int color; Paint p=new Paint(3);
        Icon(String n,int col){super(MainActivity.this);name=n;color=col;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        void line(Canvas c,float a,float b,float d,float e){c.drawLine(a,b,d,e,p);}
        void circle(Canvas c,float x,float y,float r){c.drawCircle(x,y,r,p);}
        void rect(Canvas c,float a,float b,float d,float e,float r){c.drawRoundRect(a,b,d,e,r,r,p);}
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); Canvas c=canvas; c.save(); float side=Math.min(getWidth(),getHeight()); float scale=side/48f; c.translate((getWidth()-side)/2,(getHeight()-side)/2); c.scale(scale,scale); c.translate(9,9); c.scale(1.25f,1.25f);
            p.setColor(color);p.setStrokeWidth(1.8f);p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
            switch(name) {
                case "settings": circle(c,12,12,6.5f);circle(c,12,12,2.4f);for(int i=0;i<8;i++){c.save();c.rotate(i*45,12,12);line(c,12,3,12,5.5f);c.restore();}break;
                case "camera": rect(c,2,6,22,21,3);rect(c,7,3,15,6,1);circle(c,12,13.5f,4);break;
                case "photo":rect(c,3,3,21,21,3);circle(c,8,8,1.5f);line(c,4,18,10,12);line(c,10,12,14,16);line(c,14,16,17,12);line(c,17,12,21,16);break;
                case "exit":rect(c,3,3,13,21,2);line(c,9,12,22,12);line(c,18,8,22,12);line(c,22,12,18,16);break;
                case "home":line(c,2,11,12,3);line(c,12,3,22,11);line(c,5,10,5,21);line(c,5,21,19,21);line(c,19,21,19,10);rect(c,10,14,14,21,0);break;
                case "apps":for(int x=4;x<=20;x+=8)for(int y=4;y<=20;y+=8){p.setStyle(Paint.Style.FILL);circle(c,x,y,2);}break;
                case "wifi":c.drawArc(1,3,23,25,222,96,false,p);c.drawArc(5,8,19,23,222,96,false,p);c.drawArc(9,13,15,21,225,90,false,p);p.setStyle(Paint.Style.FILL);circle(c,12,20,1.4f);break;
                case "bluetooth":line(c,11,2,11,22);line(c,11,2,18,7);line(c,18,7,5,18);line(c,5,6,18,17);line(c,18,17,11,22);break;
                case "plane":line(c,12,2,12,22);line(c,12,7,3,14);line(c,12,7,21,14);line(c,7,22,12,18);line(c,12,18,17,22);break;
                case "sun":circle(c,12,12,4);for(int i=0;i<8;i++){c.save();c.rotate(i*45,12,12);line(c,12,2,12,4);c.restore();}break;
                case "message":rect(c,2,3,22,18,4);line(c,5,18,5,22);line(c,5,22,10,18);line(c,7,8,17,8);line(c,7,13,14,13);break;
                case "globe":circle(c,12,12,10);c.drawOval(7,2,17,22,p);line(c,2,12,22,12);break;
                case "phone":Path path=new Path();path.moveTo(5,3);path.lineTo(9,7);path.lineTo(7,11);path.quadTo(10,16,14,17);path.lineTo(18,15);path.lineTo(22,19);path.quadTo(19,25,12,20);path.quadTo(3,15,2,7);path.close();c.drawPath(path,p);break;
                case "palette":circle(c,12,12,10);circle(c,8,7,1.2f);circle(c,15,6,1.2f);circle(c,18,12,1.2f);circle(c,7,14,1.2f);break;
            }c.restore();
        }
    }
}
