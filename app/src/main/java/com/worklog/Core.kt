package com.worklog
import android.app.*
import android.content.*
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.time.ZonedDateTime
import java.util.zip.*

object Fmt{ fun h(x:Int)="${if(x%12==0)12 else x%12} ${if(x<12)"AM" else "PM"}"; fun slot(s:Int)="${h(s)} to ${h(s+1)}" }
object P{
 fun sp(c:Context)=c.getSharedPreferences("p",0)
 fun start(c:Context)=sp(c).getInt("start",9); fun end(c:Context)=sp(c).getInt("end",18)
 fun lang(c:Context)=sp(c).getString("lang","en-IN")!!; fun email(c:Context)=sp(c).getString("email","")!!
}
@Entity(primaryKeys=["date","hour"]) data class Entry(val date:String,val hour:Int,val text:String="",val loggedAt:Long=0,val audio:String?=null,val status:String="Pending")
@Dao interface EDao{
 @Query("SELECT * FROM Entry WHERE date=:d") fun day(d:String):Flow<List<Entry>>
 @Query("SELECT * FROM Entry WHERE date BETWEEN :a AND :b ORDER BY date,hour") suspend fun range(a:String,b:String):List<Entry>
 @Query("SELECT DISTINCT date FROM Entry ORDER BY date DESC") fun dates():Flow<List<String>>
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun put(e:Entry)
}
@Database(entities=[Entry::class],version=1,exportSchema=false) abstract class Db:RoomDatabase(){
 abstract fun dao():EDao
 companion object{ @Volatile var i:Db?=null
  fun get(c:Context):Db=i?:synchronized(this){i?:Room.databaseBuilder(c.applicationContext,Db::class.java,"log.db").build().also{i=it}} }
}
object Notif{
 const val CH="log"
 fun build(c:Context,t:String):Notification{
  c.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CH,"Work log",NotificationManager.IMPORTANCE_HIGH))
  return Notification.Builder(c,CH).setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Hourly Work Log").setContentText(t).build()
 }
 fun tap(c:Context,slot:Int){ // fallback when the system blocks background microphone start
  val pi=PendingIntent.getForegroundService(c,slot,Intent(c,LogService::class.java).putExtra("slot",slot),PendingIntent.FLAG_IMMUTABLE)
  val n=Notification.Builder(c,CH).setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Work log due").setContentText("Tap to record ${Fmt.slot(slot)}").setContentIntent(pi).setAutoCancel(true).build()
  c.getSystemService(NotificationManager::class.java).notify(10+slot,n)
 }
}
object Scheduler{
 private fun pi(c:Context,hour:Int,retry:Boolean)=PendingIntent.getBroadcast(c,if(retry)100+hour else hour,Intent(c,Alarm::class.java).putExtra("hour",hour).putExtra("retry",retry),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
 private fun set(c:Context,ms:Long,p:PendingIntent){
  val am=c.getSystemService(AlarmManager::class.java)
  if(Build.VERSION.SDK_INT>=31&&!am.canScheduleExactAlarms())return
  am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,ms,p)
 }
 fun next(c:Context){ // schedules the next on-the-hour alarm (Mon-Fri, start+1..end)
  val now=ZonedDateTime.now(); var best:ZonedDateTime?=null
  for(d in 0..7){
   val day=now.toLocalDate().plusDays(d.toLong()); if(day.dayOfWeek.value>5)continue
   for(h in P.start(c)+1..P.end(c)){ val t=day.atTime(h,0).atZone(now.zone); if(t.isAfter(now)&&(best==null||t.isBefore(best)))best=t }
   if(best!=null)break
  }
  best?.let{set(c,it.toInstant().toEpochMilli(),pi(c,it.hour,false))}
 }
 fun retry(c:Context,slot:Int)=set(c,System.currentTimeMillis()+5*60_000,pi(c,slot+1,true))
}
class Alarm:BroadcastReceiver(){ override fun onReceive(c:Context,i:Intent){
 val hr=i.getIntExtra("hour",0); val retry=i.getBooleanExtra("retry",false)
 if(!retry)Scheduler.next(c)
 try{c.startForegroundService(Intent(c,LogService::class.java).putExtra("slot",hr-1).putExtra("retry",retry))}catch(e:Exception){Notif.tap(c,hr-1)}
}}
class Boot:BroadcastReceiver(){ override fun onReceive(c:Context,i:Intent){Scheduler.next(c)} }

object Export{
 private fun esc(s:String)=s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
 suspend fun run(c:Context,a:String,b:String):Uri?{
  val rows=Db.get(c).dao().range(a,b)
  val sb=StringBuilder("""<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
  fun row(v:List<String>){sb.append("<row>");v.forEach{sb.append("<c t=\"inlineStr\"><is><t>${esc(it)}</t></is></c>")};sb.append("</row>")}
  row(listOf("Date","Hour Slot","Work Activity","Logged At","Voice Note File"))
  val tf=java.text.SimpleDateFormat("hh:mm a",java.util.Locale.getDefault())
  rows.forEach{row(listOf(it.date,Fmt.slot(it.hour),if(it.status=="Missed"&&it.text.isBlank())"(Missed)" else it.text,if(it.loggedAt>0)tf.format(it.loggedAt) else "",it.audio?.substringAfterLast('/')?:""))}
  sb.append("</sheetData></worksheet>")
  val cv=ContentValues().apply{put(MediaStore.MediaColumns.DISPLAY_NAME,"WorkLog_${a}_to_$b.xlsx");put(MediaStore.MediaColumns.MIME_TYPE,"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");put(MediaStore.MediaColumns.RELATIVE_PATH,"Documents/WorkLog")}
  val uri=c.contentResolver.insert(MediaStore.Files.getContentUri("external"),cv)?:return null
  c.contentResolver.openOutputStream(uri)?.use{o->ZipOutputStream(o).use{z->
   fun f(n:String,s:String){z.putNextEntry(ZipEntry(n));z.write(s.toByteArray());z.closeEntry()}
   val ns="http://schemas.openxmlformats.org/"
   f("[Content_Types].xml","""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""")
   f("_rels/.rels","""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="r1" Type="${ns}officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
   f("xl/workbook.xml","""<workbook xmlns="${ns}spreadsheetml/2006/main" xmlns:r="${ns}officeDocument/2006/relationships"><sheets><sheet name="Work log" sheetId="1" r:id="rId1"/></sheets></workbook>""")
   f("xl/_rels/workbook.xml.rels","""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="${ns}officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""")
   f("xl/worksheets/sheet1.xml",sb.toString())
  }}
  val view=Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  val send=Intent(Intent.ACTION_SEND).setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet").putExtra(Intent.EXTRA_STREAM,uri).putExtra(Intent.EXTRA_SUBJECT,"Work log $a to $b").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  if(P.email(c).isNotBlank())send.putExtra(Intent.EXTRA_EMAIL,arrayOf(P.email(c)))
  val n=Notification.Builder(c,Notif.CH).setSmallIcon(android.R.drawable.ic_menu_save).setContentTitle("Work log report ready").setContentText("Saved in Documents/WorkLog")
   .setContentIntent(PendingIntent.getActivity(c,1,view,PendingIntent.FLAG_IMMUTABLE))
   .addAction(Notification.Action.Builder(null,"Share",PendingIntent.getActivity(c,2,Intent.createChooser(send,"Share report"),PendingIntent.FLAG_IMMUTABLE)).build()).setAutoCancel(true).build()
  c.getSystemService(NotificationManager::class.java).notify(99,n)
  return uri
 }
}
