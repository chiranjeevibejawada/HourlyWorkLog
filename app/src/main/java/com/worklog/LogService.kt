package com.worklog
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import android.speech.*
import kotlinx.coroutines.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import java.util.Locale

class Wav(private val f:File){
 @Volatile private var on=true
 private val t=Thread{
  val bs=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)*2
  @Suppress("MissingPermission") val ar=AudioRecord(MediaRecorder.AudioSource.MIC,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,bs)
  val o=RandomAccessFile(f,"rw"); o.setLength(0); o.write(ByteArray(44)); val b=ByteArray(bs); ar.startRecording()
  while(on){val n=ar.read(b,0,b.size); if(n>0)o.write(b,0,n)}
  ar.stop(); ar.release()
  val len=(o.length()-44).toInt()
  val h=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
  h.put("RIFF".toByteArray()).putInt(36+len).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(len)
  o.seek(0); o.write(h.array()); o.close()
 }
 fun start()=t.start()
 fun stop(){on=false; t.join(3000)}
}

class LogService:Service(),TextToSpeech.OnInitListener{
 private var tts:TextToSpeech?=null; private var sr:SpeechRecognizer?=null; private var wav:Wav?=null
 private var slot=9; private var retry=false; private var finished=false
 private val h=Handler(Looper.getMainLooper()); private var file:File?=null
 override fun onBind(i:Intent?):IBinder?=null
 override fun onStartCommand(i:Intent?,f:Int,s:Int):Int{
  slot=i?.getIntExtra("slot",9)?:9; retry=i?.getBooleanExtra("retry",false)==true; finished=false
  try{startForeground(1,Notif.build(this,"Logging ${Fmt.slot(slot)}"),ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)}
  catch(e:Exception){Notif.tap(this,slot); stopSelf(); return START_NOT_STICKY}
  val am=getSystemService(AudioManager::class.java)
  if(am.mode==AudioManager.MODE_IN_CALL||am.mode==AudioManager.MODE_RINGTONE){ // phone call: try again in 5 minutes
   if(!retry)Scheduler.retry(this,slot); stopSelf(); return START_NOT_STICKY
  }
  tts=TextToSpeech(this,this); return START_NOT_STICKY
 }
 override fun onInit(st:Int){
  val t=tts?:return
  t.language=Locale.forLanguageTag(P.lang(this))
  t.setOnUtteranceProgressListener(object:UtteranceProgressListener(){
   override fun onStart(id:String?){}
   override fun onDone(id:String?){h.post{listen()}}
   @Deprecated("x") override fun onError(id:String?){h.post{listen()}}
  })
  t.speak("Please enter the work activity between ${Fmt.h(slot)} and ${Fmt.h(slot+1)}.",TextToSpeech.QUEUE_FLUSH,null,"p")
 }
 private fun listen(){
  file=File(getExternalFilesDir("voice"),"${LocalDate.now()}_$slot.wav")
  try{wav=Wav(file!!).also{it.start()}}catch(e:Exception){wav=null}
  val onDev=Build.VERSION.SDK_INT>=33&&SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
  sr=if(onDev)SpeechRecognizer.createOnDeviceSpeechRecognizer(this) else SpeechRecognizer.createSpeechRecognizer(this)
  sr?.setRecognitionListener(object:RecognitionListener{
   override fun onResults(b:Bundle?){done(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?:"")}
   override fun onError(e:Int){done("")}
   override fun onReadyForSpeech(p:Bundle?){} override fun onBeginningOfSpeech(){} override fun onRmsChanged(v:Float){}
   override fun onBufferReceived(b:ByteArray?){} override fun onEndOfSpeech(){} override fun onPartialResults(b:Bundle?){} override fun onEvent(t:Int,b:Bundle?){}
  })
  sr?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
   .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
   .putExtra(RecognizerIntent.EXTRA_LANGUAGE,P.lang(this))
   .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,6000L)
   .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,6000L))
  h.postDelayed({sr?.stopListening()},120_000) // 2 minute maximum
 }
 private fun done(text:String){
  if(finished)return; finished=true
  h.removeCallbacksAndMessages(null); sr?.destroy(); tts?.shutdown()
  val ok=text.isNotBlank(); val date=LocalDate.now().toString(); val s=slot; val r=retry; val f=file
  CoroutineScope(Dispatchers.IO).launch{
   wav?.stop()
   val dao=Db.get(applicationContext).dao()
   val old=dao.range(date,date).find{it.hour==s}
   val keep=if(ok)text else old?.text.orEmpty()
   val status=if(ok||keep.isNotBlank())"Done" else if(r)"Missed" else "Pending"
   dao.put(Entry(date,s,keep,System.currentTimeMillis(),f?.takeIf{it.exists()&&it.length()>44}?.absolutePath,status))
   if(status=="Pending")Scheduler.retry(applicationContext,s)
   if(status!="Pending"&&s+1==P.end(applicationContext))Export.run(applicationContext,date,date)
   stopSelf()
  }
 }
 override fun onDestroy(){h.removeCallbacksAndMessages(null);tts?.shutdown();super.onDestroy()}
}
