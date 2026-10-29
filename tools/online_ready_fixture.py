"""Build a throw-away same-signature baseline APK, using exported SQL and platform SQLite."""
from pathlib import Path
import json
import os
import re
import shutil
import subprocess
from online_ready_support import ROOT

SCHEMA_ROOT = ROOT / "app/schemas/com.diajarkoding.imfit.data.local.database.IMFITDatabase"

JAVA = r'''
package com.diajarkoding.imfit;
import android.app.Activity;
import android.os.Bundle;
import android.database.sqlite.SQLiteDatabase;
import android.content.ContentValues;
import android.util.Log;
import org.json.*;
import java.io.*;
import java.util.*;
public final class FixtureActivity extends Activity {
 private SQLiteDatabase heldReader;
 private static final long TIME=1761696000000L;
 private String owner, other, fixture;
 private JSONObject schema;
 private final Map<String,String> ids=new HashMap<>();
 private String id(String key) { if(!ids.containsKey(key)) ids.put(key,UUID.nameUUIDFromBytes((fixture+key).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString()); return ids.get(key); }
 private void insert(SQLiteDatabase db,String table,Map<String,Object> values)throws Exception {
  JSONObject entity=null; JSONArray all=schema.getJSONArray("entities");
  for(int n=0;n<all.length();n++) if(all.getJSONObject(n).getString("tableName").equals(table))entity=all.getJSONObject(n);
  if(entity==null)throw new IllegalStateException("Missing exported table "+table);
  Set<String> columns=new HashSet<>(); JSONArray fields=entity.getJSONArray("fields");
  for(int n=0;n<fields.length();n++)columns.add(fields.getJSONObject(n).getString("columnName"));
  ContentValues row=new ContentValues();
  for(Map.Entry<String,Object> entry:values.entrySet()) if(columns.contains(entry.getKey())) {
   Object value=entry.getValue(); if(value==null)row.putNull(entry.getKey()); else if(value instanceof Number)row.put(entry.getKey(),((Number)value).longValue());else row.put(entry.getKey(),value.toString());
  }
  db.insertOrThrow(table,null,row);
 }
 private Map<String,Object> row(Object... entries){Map<String,Object> result=new HashMap<>(); for(int n=0;n<entries.length;n+=2)result.put((String)entries[n],entries[n+1]); return result;}
 private Map<String,Object> base(Object... entries){Map<String,Object> result=row("created_at",TIME,"updated_at",TIME,"sync_status","PENDING_SYNC","pending_operation","CREATE");result.putAll(row(entries));return result;}
 private void seed(SQLiteDatabase db,String who,String key)throws Exception {
  insert(db,"users",base("id",who,"name","Ready "+key,"email","imfit-legacy-"+key+"@example.invalid"));
  insert(db,"workout_templates",base("id",id(key+"template"),"user_id",who,"name","Ready legacy "+key,"is_deleted",0,"remote_revision",1));
  insert(db,"template_exercises",base("template_id",id(key+"template"),"exercise_id",id("exercise"),"order_index",0,"sets",2,"reps",10,"rest_seconds",60));
  insert(db,"workout_logs",base("id",id(key+"workout"),"user_id",who,"template_id",id(key+"template"),"template_name","Ready legacy "+key,"date",TIME,"start_time",TIME,"end_time",TIME+60000,"total_volume","12.345","total_sets",1,"total_reps",1));
  insert(db,"exercise_logs",base("id",id(key+"log"),"workout_log_id",id(key+"workout"),"exercise_id",id("exercise"),"exercise_name","Ready exercise","muscle_category","CHEST","order_index",0,"total_volume","12.345","total_sets",1,"total_reps",1,"input_defaults",null));
  insert(db,"workout_sets",base("id",id(key+"set1"),"exercise_log_id",id(key+"log"),"workout_log_id",id(key+"workout"),"exercise_id",id("exercise"),"set_number",1,"weight","12.345","reps",1,"is_completed",1));
  insert(db,"workout_sets",base("id",id(key+"set2"),"exercise_log_id",id(key+"log"),"workout_log_id",id(key+"workout"),"exercise_id",id("exercise"),"set_number",2,"weight","1.2345","reps",2,"is_completed",0));
 }
 @Override public void onCreate(Bundle saved){super.onCreate(saved);new Thread(()->{try{
  if(android.os.Process.myUid()/100000==0)throw new IllegalStateException("Owner user refused");
  owner=UUID.fromString(getIntent().getStringExtra("owner")).toString();other=UUID.fromString(getIntent().getStringExtra("other")).toString();fixture=UUID.fromString(getIntent().getStringExtra("fixture")).toString();
  String catalogId=new String(android.util.Base64.decode(getIntent().getStringExtra("exerciseIdBase64"),android.util.Base64.DEFAULT),java.nio.charset.StandardCharsets.UTF_8);if(catalogId.isEmpty()||catalogId.length()>255)throw new IllegalStateException("Invalid real catalog identity");ids.put("exercise",catalogId);
  String scenario=getIntent().getStringExtra("scenario");int version=getIntent().getIntExtra("schema",9);
  File marker=new File(getFilesDir(),"online-ready-fixture.json");if(marker.exists())throw new IllegalStateException("Existing data refused");
  JSONObject evidence=new JSONObject().put("fixture",fixture).put("owner",owner).put("scenario",scenario).put("schema",version).put("launchId",getIntent().getStringExtra("launchId"))
   .put("workoutId",id("Aworkout")).put("otherWorkoutId",id("Bworkout")).put("exerciseLogId",id("Alog"))
   .put("setIds",new JSONArray().put(id("Aset1")).put(id("Aset2"))).put("templateId",id("Atemplate")).put("otherTemplateId",id("Btemplate")).put("exerciseId",id("exercise"));
  if(scenario.equals("wal"))evidence.put("activeId",id("active"));
  String remoteSession=getIntent().getStringExtra("remoteSessionId");if(remoteSession!=null)evidence.put("remoteSessionId",remoteSession).put("remoteState",getIntent().getStringExtra("remoteState"));
  String intentEncoded=getIntent().getStringExtra("intentBase64");String intentPayload=null;
  if(intentEncoded!=null){intentPayload=new String(android.util.Base64.decode(intentEncoded,android.util.Base64.DEFAULT),java.nio.charset.StandardCharsets.UTF_8);JSONObject intent=new JSONObject(intentPayload);evidence.put("intentRequestId",intent.getString("p_request_id")).put("intentPayload",intent).put("intentExpectedRevision",2);}
  try(FileOutputStream out=new FileOutputStream(marker)){out.write(evidence.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getFD().sync();}
  if(!scenario.equals("fresh")){
   File file=getDatabasePath("imfit_database");file.getParentFile().mkdirs();if(file.exists())throw new IllegalStateException("Existing database refused");
   if(scenario.equals("corrupt")){try(FileOutputStream out=new FileOutputStream(file)){out.write("synthetic corrupt legacy database".getBytes());out.getFD().sync();}}
   else {
    String text;try(InputStream in=getAssets().open("schemas/"+(version==4||version==8?9:version)+".json")){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);text=out.toString("UTF-8");}
    schema=new JSONObject(text).getJSONObject("database");SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(file,null);
    JSONArray entities=schema.getJSONArray("entities");for(int n=0;n<entities.length();n++){JSONObject e=entities.getJSONObject(n);db.execSQL(e.getString("createSql").replace("${TABLE_NAME}",e.getString("tableName")));JSONArray indices=e.optJSONArray("indices");if(indices!=null)for(int j=0;j<indices.length();j++)db.execSQL(indices.getJSONObject(j).getString("createSql").replace("${TABLE_NAME}",e.getString("tableName")));}
    JSONArray setup=schema.getJSONArray("setupQueries");for(int n=0;n<setup.length();n++)db.execSQL(setup.getString(n));db.setVersion(version);
    insert(db,"exercises",row("id",id("exercise"),"name","Ready exercise","muscle_category_id",1,"description","Synthetic fixture","is_active",1,"created_at",TIME,"updated_at",TIME));
    seed(db,owner,"A");seed(db,other,"B");
    if(intentPayload!=null){JSONObject intent=new JSONObject(intentPayload);insert(db,"template_write_intents",row("key",intent.getString("p_request_id"),"user_id",owner,"template_id",id("Atemplate"),"payload",intentPayload));}
    if(scenario.equals("wal")){
     db.enableWriteAheadLogging();try(android.database.Cursor checkpoint=db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",null)){while(checkpoint.moveToNext()){} }db.execSQL("PRAGMA wal_autocheckpoint=0");
     heldReader=SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READONLY);heldReader.execSQL("BEGIN");heldReader.rawQuery("SELECT count(*) FROM users",null).moveToFirst();
     String draft=new JSONObject().put("id",id("active")).put("templateId",id("Atemplate")).put("templateName","Ready WAL active").put("startTime",TIME).put("exerciseLogs",new JSONArray()).toString();
     db.beginTransaction();try{insert(db,"active_sessions",row("id",id("active"),"user_id",owner,"template_id",id("Atemplate"),"template_name","Ready WAL active","start_time",TIME,"current_exercise_index",0,"session_data_json",draft,"created_at",TIME,"updated_at",TIME));db.setTransactionSuccessful();}finally{db.endTransaction();}
    }
    db.close();
   }
  }
  Log.i("IMFITReadyFixture","event=fixture_ready writerClosed=true pid="+android.os.Process.myPid()+" fixture="+fixture+" launch="+getIntent().getStringExtra("launchId"));
 }catch(Throwable error){Log.e("IMFITReadyFixture","event=fixture_failed",error);}},"fixture-writer").start();}
}
'''


def build_fixture(directory, sdk):
    directory = Path(directory)
    version_text = (ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8")
    agp = re.search(r'^agp\s*=\s*"([^"]+)"', version_text, re.MULTILINE)
    if not agp:
        raise RuntimeError("Cannot identify exact repository AGP version")
    (directory / "settings.gradle.kts").write_text('pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\ndependencyResolutionManagement { repositories { google(); mavenCentral() } }\nrootProject.name="OnlineReadyFixture"\ninclude(":app")\n')
    (directory / "build.gradle.kts").write_text('plugins { id("com.android.application") version "' + agp.group(1) + '" apply false }\n')
    app = directory / "app"
    (app / "src/main/java/com/diajarkoding/imfit").mkdir(parents=True)
    (app / "src/main/assets/schemas").mkdir(parents=True)
    for schema in (6, 7, 9):
        shutil.copy2(SCHEMA_ROOT / f"{schema}.json", app / f"src/main/assets/schemas/{schema}.json")
    (app / "src/main/java/com/diajarkoding/imfit/FixtureActivity.java").write_text(JAVA)
    (app / "src/main/AndroidManifest.xml").write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:label="IMFIT disposable fixture" android:allowBackup="false"><activity android:name=".FixtureActivity" android:exported="true" /></application></manifest>')
    (app / "build.gradle.kts").write_text('plugins { id("com.android.application") }\nandroid { namespace="com.diajarkoding.imfit"; compileSdk=37; defaultConfig { applicationId="com.diajarkoding.imfit"; minSdk=24; targetSdk=37; versionCode=1; versionName="fixture" } }\n')
    (directory / "local.properties").write_text("sdk.dir=" + str(sdk).replace("\\", "/") + "\n")
    shutil.copy2(ROOT / "gradle.properties", directory / "gradle.properties")
    shutil.copytree(ROOT / "gradle/wrapper", directory / "gradle/wrapper")
    daemon = ROOT / "gradle/gradle-daemon-jvm.properties"
    if daemon.is_file():
        shutil.copy2(daemon, directory / "gradle/gradle-daemon-jvm.properties")
    shutil.copy2(ROOT / "gradlew", directory / "gradlew")
    shutil.copy2(ROOT / "gradlew.bat", directory / "gradlew.bat")
    wrapper = directory / ("gradlew.bat" if os.name == "nt" else "gradlew")
    command = [str(wrapper), ":app:assembleDebug", "--console=plain"]
    result = subprocess.run(command, cwd=directory, capture_output=True, text=True, timeout=1200)
    if result.returncode:
        raise RuntimeError("Temporary fixture APK build failed:\n" + result.stdout + result.stderr)
    return app / "build/outputs/apk/debug/app-debug.apk", result.stdout + result.stderr
