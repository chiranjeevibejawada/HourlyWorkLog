package com.worklog
import android.Manifest
import android.content.*
import android.media.MediaPlayer
import android.net.Uri
import android.os.*
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.time.LocalDate

class MainActivity:ComponentActivity(){
 override fun onCreate(s:Bundle?){
  super.onCreate(s)
  val perm=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){Scheduler.next(this)}
  perm.launch(arrayOf(Manifest.permission.RECORD_AUDIO,Manifest.permission.POST_NOTIFICATIONS))
  Scheduler.next(this)
  setContent{MaterialTheme{App(this)}}
 }
}
@Composable fun App(c:Context){
 var tab by remember{mutableStateOf(0)}
 Scaffold(bottomBar={NavigationBar{listOf("Today","History","Settings").forEachIndexed{i,t->NavigationBarItem(tab==i,{tab=i},{},label={Text(t)})}}}){pad->
  Box(Modifier.padding(pad).statusBarsPadding()){when(tab){0->Today(c);1->History(c);else->Config(c)}}
 }
}
@Composable fun Today(c:Context){
 val d=LocalDate.now().toString(); val list by Db.get(c).dao().day(d).collectAsState(emptyList()); val sc=rememberCoroutineScope()
 LazyColumn(Modifier.padding(12.dp)){
  item{Text("Today, $d",style=MaterialTheme.typography.titleLarge)}
  items((P.start(c) until P.end(c)).toList()){hr->
   val e=list.find{it.hour==hr}; var t by remember(e?.text){mutableStateOf(e?.text?:"")}
   Card(Modifier.fillMaxWidth().padding(vertical=6.dp)){Column(Modifier.padding(12.dp)){
    Text("${Fmt.slot(hr)}  -  ${e?.status?:"Not logged"}",fontWeight=FontWeight.Bold)
    OutlinedTextField(t,{t=it},Modifier.fillMaxWidth(),label={Text("Work activity")})
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
     Button({sc.launch(Dispatchers.IO){Db.get(c).dao().put((e?:Entry(d,hr)).copy(text=t,loggedAt=System.currentTimeMillis(),status="Done"))}}){Text("Save")}
     OutlinedButton({c.startForegroundService(Intent(c,LogService::class.java).putExtra("slot",hr).putExtra("retry",true))}){Text("Record")}
     e?.audio?.let{p->OutlinedButton({MediaPlayer().apply{setDataSource(p);prepare();start()}}){Text("Play")}}
    }
   }}
  }
 }
}
@Composable fun History(c:Context){
 val dates by Db.get(c).dao().dates().collectAsState(emptyList()); val sc=rememberCoroutineScope()
 var a by remember{mutableStateOf(LocalDate.now().minusDays(7).toString())}; var b by remember{mutableStateOf(LocalDate.now().toString())}
 LazyColumn(Modifier.padding(12.dp)){
  item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
   OutlinedTextField(a,{a=it},Modifier.weight(1f),label={Text("From yyyy-mm-dd")});OutlinedTextField(b,{b=it},Modifier.weight(1f),label={Text("To yyyy-mm-dd")})}
   Button({sc.launch(Dispatchers.IO){Export.run(c,a,b)}},Modifier.padding(vertical=8.dp)){Text("Export range to Excel")}}
  items(dates){d->
   var open by remember{mutableStateOf(false)}
   val rows by produceState(emptyList<Entry>(),open){if(open)value=Db.get(c).dao().range(d,d)}
   Card(Modifier.fillMaxWidth().padding(vertical=4.dp),onClick={open=!open}){Column(Modifier.padding(12.dp)){
    Text(d,fontWeight=FontWeight.Bold); if(open)rows.forEach{Text("${Fmt.slot(it.hour)}: ${it.text.ifBlank{"(${it.status})"}}")}
   }}
  }
 }
}
@Composable fun Config(c:Context){
 val sp=P.sp(c); var s by remember{mutableStateOf(P.start(c).toString())}; var e by remember{mutableStateOf(P.end(c).toString())}
 var l by remember{mutableStateOf(P.lang(c))}; var m by remember{mutableStateOf(P.email(c))}
 fun go(i:Intent)=c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
 Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
  Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
   OutlinedTextField(s,{s=it},Modifier.weight(1f),label={Text("Start hour (24h)")});OutlinedTextField(e,{e=it},Modifier.weight(1f),label={Text("End hour (24h)")})}
  OutlinedTextField(l,{l=it},Modifier.fillMaxWidth(),label={Text("Language (en-IN)")})
  OutlinedTextField(m,{m=it},Modifier.fillMaxWidth(),label={Text("Email for report")})
  Button({sp.edit().putInt("start",s.toIntOrNull()?:9).putInt("end",e.toIntOrNull()?:18).putString("lang",l).putString("email",m).apply();Scheduler.next(c)}){Text("Save settings")}
  Text("Setup",fontWeight=FontWeight.Bold)
  OutlinedButton({go(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${c.packageName}")))}){Text("Microphone and notification permissions")}
  if(Build.VERSION.SDK_INT>=31)OutlinedButton({go(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:${c.packageName}")))}){Text("Allow exact alarms")}
  OutlinedButton({go(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))}){Text("Turn off battery optimization")}
 }
}
