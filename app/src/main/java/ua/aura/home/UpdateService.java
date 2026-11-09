package ua.aura.home;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.os.Build;
import org.json.*;
import java.net.*;
import java.io.*;
import java.util.concurrent.*;

public class UpdateService extends JobService {
    private ExecutorService executor;
    private volatile boolean stopped;
    static class Release {int code;String name,notes,url;}
    static String repository(Context c){String repo=c.getSharedPreferences("aura",0).getString("updateRepo","markovergun2013-bot/123213123");return repo.equals("markovergun2013-bot/aura-home")?"markovergun2013-bot/123213123":repo;}
    static void schedule(Context c){
        if(!c.getSharedPreferences("aura",0).getBoolean("updateChecks",true))return;
        JobScheduler scheduler=c.getSystemService(JobScheduler.class);
        if(scheduler.getPendingJob(1025)==null)scheduler.schedule(new JobInfo.Builder(1025,new ComponentName(c,UpdateService.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(60*60*1000).setPersisted(true).build());
    }
    static Release check(Context c)throws Exception{
        String repo=repository(c);if(!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))throw new IOException("не указан репозиторий");
        HttpURLConnection conn=(HttpURLConnection)new URL("https://api.github.com/repos/"+repo+"/releases/latest").openConnection();
        conn.setConnectTimeout(12000);conn.setReadTimeout(12000);conn.setRequestProperty("Accept","application/vnd.github+json");conn.setRequestProperty("User-Agent","AuraHome");
        try{
            int status=conn.getResponseCode();if(status==404)throw new IOException("репозиторий или первый релиз ещё не опубликован");if(status!=200)throw new IOException("GitHub: "+status);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=conn.getInputStream()){byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1){if(bytes.size()+n>1024*1024)throw new IOException("слишком большой ответ");bytes.write(buffer,0,n);}}
            JSONObject json=new JSONObject(bytes.toString("UTF-8"));String tag=json.optString("tag_name");if(!tag.matches("build-[0-9]+"))throw new IOException("неверный формат релиза");
            int code=Integer.parseInt(tag.substring(6));long local=c.getPackageManager().getPackageInfo(c.getPackageName(),0).getLongVersionCode();if(code<=local)return null;
            JSONArray assets=json.getJSONArray("assets");for(int i=0;i<assets.length();i++){JSONObject asset=assets.getJSONObject(i);if("Aura-Home.apk".equals(asset.optString("name"))){String url=asset.getString("browser_download_url");if(!url.startsWith("https://github.com/"+repo+"/releases/download/"))throw new IOException("неверный адрес APK");Release r=new Release();r.code=code;r.name=json.optString("name",tag);r.notes=json.optString("body","");if(r.notes.length()>2000)r.notes=r.notes.substring(0,2000);r.url=url;return r;}}
            throw new IOException("APK отсутствует в релизе");
        }finally{conn.disconnect();}
    }
    @Override public boolean onStartJob(JobParameters params){
        stopped=false;executor=Executors.newSingleThreadExecutor();executor.execute(()->{try{
            Release release=check(this);android.content.SharedPreferences prefs=getSharedPreferences("aura",0);
            if(!stopped&&release!=null&&release.code>prefs.getInt("notifiedVersion",0)){
                NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("updates","Обновления Aura",NotificationManager.IMPORTANCE_DEFAULT));
                if(nm.areNotificationsEnabled()){
                    PendingIntent pending=PendingIntent.getActivity(this,1025,new Intent(this,MainActivity.class).putExtra("updates",true),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
                    nm.notify(1025,new Notification.Builder(this,"updates").setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("Доступна "+release.name).setContentText("Нажми, чтобы посмотреть и скачать обновление").setContentIntent(pending).setAutoCancel(true).build());prefs.edit().putInt("notifiedVersion",release.code).apply();
                }
            }
        }catch(Exception ignored){}finally{if(!stopped)jobFinished(params,false);executor.shutdown();}});return true;
    }
    @Override public boolean onStopJob(JobParameters params){stopped=true;if(executor!=null)executor.shutdownNow();return true;}
}
