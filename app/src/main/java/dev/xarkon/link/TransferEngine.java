package dev.xarkon.link;

import android.content.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class TransferEngine {
    public interface Events { void note(String s); void progress(String s,long current,long total,double speed); }
    private final Context context;
    private final Events events;
    private final ExecutorService handlers=Executors.newFixedThreadPool(16);
    private final ConcurrentHashMap<String,Incoming> incoming=new ConcurrentHashMap<>();
    private static final class BadPin {
        long first, lockedUntil;
        int count;
    }
    private final ConcurrentHashMap<String,BadPin> lastBadPin=new ConcurrentHashMap<>();
    private volatile boolean alive;
    private volatile boolean sendingCancelled;
    /** Cancels sending, not the pairing or receiver. */
    public void cancelSending(){sendingCancelled=true;}
    private volatile String sessionKey="";
    private ServerSocket socket;
    private DatagramSocket discovery;
    private Thread acceptThread,discoverThread;
    private static final String SESSIONS="incoming_sessions";
    private static final String BASE=Environment.DIRECTORY_DOWNLOADS+"/QR LINK/";
    public TransferEngine(Context c,Events e){context=c.getApplicationContext();events=e;restore();}
    public boolean receiving(){return alive;}
    public String key(){return sessionKey;}
    public synchronized void start(String chosenKey)throws Exception{
        if(alive)return;
        sessionKey=chosenKey;
        socket=new ServerSocket();socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(Util.PORT),32);
        alive=true;
        acceptThread=new Thread(()->{
            while(alive)try{
                Socket s=socket.accept();s.setSoTimeout(90000);s.setTcpNoDelay(true);s.setReceiveBufferSize(1024*1024);
                handlers.execute(()->serve(s));
            }catch(Exception ex){if(alive)events.note("Сервер: "+ex.getMessage());}
        },"xarkon-accept");acceptThread.start();
        discoverThread=new Thread(this::discoveryLoop,"xarkon-discovery");discoverThread.start();
        events.note("Приём включён, порт "+Util.PORT);
    }
    public synchronized void stop(){
        alive=false;sessionKey="";try{if(socket!=null)socket.close();}catch(Exception ignored){}
        if(discovery!=null)discovery.close();
        for(Incoming item:incoming.values())closeDestination(item,false);
    }
    public void shutdown(){stop();handlers.shutdownNow();}
    private void discoveryLoop(){
        try(DatagramSocket ds=new DatagramSocket(null)){
            discovery=ds;ds.setReuseAddress(true);ds.bind(new InetSocketAddress(Util.DISCOVERY_PORT));
            ds.setSoTimeout(2500);byte[]buf=new byte[1024];
            while(alive){
                try{
                    DatagramPacket p=new DatagramPacket(buf,buf.length);ds.receive(p);
                    String msg=new String(p.getData(),p.getOffset(),p.getLength(),StandardCharsets.UTF_8);
                    if(msg.equals("XARKON_DISCOVER_1")){
                        String response="XARKON_LINK_1|"+android.os.Build.MANUFACTURER+" "+android.os.Build.MODEL+"|"+Util.PORT;
                        byte[] data=response.getBytes(StandardCharsets.UTF_8);
                        ds.send(new DatagramPacket(data,data.length,p.getAddress(),p.getPort()));
                    }
                }catch(SocketTimeoutException ignored){}
            }
        }catch(Exception e){if(alive)events.note("Поиск устройств: "+e.getMessage());}
    }
    private static String line(InputStream in)throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream();int x;
        while((x=in.read())!=-1){if(x=='\n')break;if(x!='\r')b.write(x);if(b.size()>8192)throw new IOException("Заголовок слишком длинный");}
        if(x==-1&&b.size()==0)return null;
        return b.toString(StandardCharsets.ISO_8859_1.name());
    }
    private static void reply(OutputStream out,int code,String text,JSONObject j)throws IOException{
        byte[] body=j.toString().getBytes(StandardCharsets.UTF_8);
        String header="HTTP/1.1 "+code+" "+text+"\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
    }
    private static JSONObject error(String why){JSONObject j=new JSONObject();try{j.put("error",why);}catch(Exception ignored){}return j;}
    private void serve(Socket sock){
        try(Socket s=sock; InputStream in=new BufferedInputStream(s.getInputStream(),256*1024);
               OutputStream out=new BufferedOutputStream(s.getOutputStream(),32*1024)){
            String first=line(in);if(first==null)return;
            String[]pieces=first.split(" ");
            if(pieces.length!=3){reply(out,400,"Bad Request",error("bad request"));return;}
            Map<String,String>heads=new HashMap<>();String ln;
            int num=0;
            while((ln=line(in))!=null&&!ln.isEmpty()){
                if(++num>40)throw new IOException("Слишком много заголовков");
                int at=ln.indexOf(':');if(at>0)heads.put(ln.substring(0,at).trim().toLowerCase(Locale.US),ln.substring(at+1).trim());
            }
            String from=s.getInetAddress().getHostAddress();
            BadPin block=lastBadPin.computeIfAbsent(from,k->new BadPin());
            synchronized(block){
                long now=System.currentTimeMillis();
                if(now<block.lockedUntil){reply(out,429,"Try Later",error("Слишком много попыток подключения"));return;}
                if(!sessionKey.equals(heads.get("x-xarkon-key"))){
                    if(now-block.first>60000){block.first=now;block.count=0;}
                    if(++block.count>=5)block.lockedUntil=now+60000;
                    reply(out,403,"Forbidden",error("Неверный ключ сеанса"));return;
                }
                block.count=0;block.lockedUntil=0;
            }
            String path=pieces[1];String method=pieces[0];
            if(method.equals("GET")&&path.equals("/hello")){
                reply(out,200,"OK",new JSONObject().put("app","XARKON LINK")
                    .put("version",1).put("sha256",true)
                    .put("deviceName",context.getSharedPreferences("settings",0)
                        .getString("device_name",android.os.Build.MANUFACTURER+" "+android.os.Build.MODEL)));return;
            }
            long contentLength;
            try{contentLength=Long.parseLong(heads.getOrDefault("content-length","-1"));}
            catch(Exception e){reply(out,411,"Length Required",error("Нужен Content-Length"));return;}
            if(method.equals("POST")&&path.equals("/offer")){
                if(contentLength<2||contentLength>16384){reply(out,413,"Too Large",error("Неверный размер предложения"));return;}
                byte[]data=new byte[(int)contentLength];readFully(in,data);
                JSONObject offer=new JSONObject(new String(data,StandardCharsets.UTF_8));
                try{JSONObject result=offer(offer);reply(out,200,"OK",result);}
                catch(Exception e){reply(out,400,"Bad Request",error(e.getMessage()));}
                return;
            }
            if(method.equals("PUT")&&path.startsWith("/chunk/")){
                String[]segments=path.split("/");
                if(segments.length!=4){reply(out,404,"Not Found",error("Wrong path"));return;}
                Incoming inc=incoming.get(segments[2]);
                int index=Integer.parseInt(segments[3]);
                if(inc==null){reply(out,404,"Not Found",error("Сначала отправьте /offer"));return;}
                if(index<0||index>=inc.count){reply(out,400,"Bad Request",error("Индекс блока"));return;}
                long start=(long)index*Util.CHUNK;
                long expected=Math.min(Util.CHUNK,inc.size-start);
                if(contentLength!=expected){reply(out,400,"Bad Request",error("Неверная длина блока"));return;}
                boolean already, fullyReceived;
                synchronized(inc){
                    already=inc.done.get(index);
                    fullyReceived=inc.done.cardinality()==inc.count;
                    if(!already){
                        if(inc.writing.get(index)){reply(out,409,"Conflict",error("Блок уже загружается"));return;}
                        inc.writing.set(index);
                    }
                }
                if(already){
                    // The final chunk may have been saved but publication failed.
                    // Retry publishing before acknowledging a previously received
                    // chunk, so the sender cannot report a false success.
                    if(fullyReceived)complete(inc);
                    reply(out,200,"OK",new JSONObject().put("already",true)
                        .put("sha256",storedSha256(inc,index)));return;
                }
                long partial=0;
                try {
                    // Android MediaStore descriptor is opened once PER FILE, rather
                    // than once per 8 MiB chunk. All positional writes are independent.
                    FileChannel channel=destination(inc);
                    MessageDigest checksum=MessageDigest.getInstance("SHA-256");
                    byte[] buffer=new byte[256*1024];
                    while(partial<contentLength){
                        int n=in.read(buffer,0,(int)Math.min(buffer.length,contentLength-partial));
                        if(n<0)throw new EOFException("Соединение оборвалось");
                        checksum.update(buffer,0,n);
                        ByteBuffer bb=ByteBuffer.wrap(buffer,0,n);
                        while(bb.hasRemaining()){
                            int w=channel.write(bb,start+partial);
                            if(w<=0)throw new IOException("Не удалось записать блок");
                            partial+=w;
                            inc.activeBytes.addAndGet(w);
                        }
                        reportReceive(inc,false);
                    }
                    boolean finished;
                    synchronized(inc){
                        // Replace the in-flight count with a committed chunk atomically
                        // before reporting. Retries never count unfinished chunks as done.
                        inc.done.set(index);
                        inc.activeBytes.addAndGet(-partial);
                        persist(inc);
                        finished=inc.done.cardinality()==inc.count;
                    }
                    if(finished)complete(inc);
                    reportReceive(inc,true);
                    reply(out,200,"OK",new JSONObject().put("ok",true)
                        .put("sha256",hex(checksum.digest())));return;
                } finally {
                    synchronized(inc){inc.writing.clear(index);}
                    if(partial>0 && !inc.done.get(index)){
                        inc.activeBytes.addAndGet(-partial);
                        reportReceive(inc,true);
                    }
                }
            }
            if(method.equals("PUT")&&path.equals("/bench")){
                if(contentLength<0||contentLength>16L*1024*1024){reply(out,413,"Too Large",error("Maximum 16 MiB"));return;}
                byte[]b=new byte[256*1024];long remaining=contentLength;
                while(remaining>0){int n=in.read(b,0,(int)Math.min(remaining,b.length));if(n<0)throw new EOFException();remaining-=n;}
                reply(out,200,"OK",new JSONObject().put("received",contentLength));return;
            }
            reply(out,404,"Not Found",error("Неизвестный запрос"));
        }catch(Exception e){events.note("Входящее соединение: "+e.getMessage());}
    }
    private String storedSha256(Incoming inc,int index)throws Exception{
        long position=(long)index*Util.CHUNK;
        long remaining=Math.min(Util.CHUNK,inc.size-position);
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(ParcelFileDescriptor fd=context.getContentResolver().openFileDescriptor(inc.uri,"r")){
            if(fd==null)throw new IOException("Нет доступа к сохранённому блоку");
            try(FileInputStream stream=new FileInputStream(fd.getFileDescriptor())){
                FileChannel channel=stream.getChannel();
                byte[] buf=new byte[256*1024];
                while(remaining>0){
                    ByteBuffer bytes=ByteBuffer.wrap(buf,0,(int)Math.min(remaining,buf.length));
                    int n=channel.read(bytes,position);
                    if(n<=0)throw new EOFException("Сохранённый блок неполный");
                    digest.update(buf,0,n);position+=n;remaining-=n;
                }
            }
        }
        return hex(digest.digest());
    }
    private static final class ChecksumMismatchException extends IOException{
        ChecksumMismatchException(String reason){super(reason);}
    }
    private static void readFully(InputStream in,byte[] buf)throws IOException{
        int pos=0;while(pos<buf.length){int n=in.read(buf,pos,buf.length-pos);if(n<0)throw new EOFException();pos+=n;}
    }
    private JSONObject offer(JSONObject j)throws Exception{
        String id=j.getString("id");String name=Util.safeName(j.getString("name"));
        String path=Util.safePath(j.optString("path",""));
        String mime=j.optString("mime","application/octet-stream");
        long size=j.getLong("size");
        if(!id.matches("[0-9a-fA-F-]{36}")||size<0||size>1000L*1024*1024*1024*10||name.length()==0)
            throw new IOException("Некорректные параметры файла");
        int count=(int)Math.max(1,((size+Util.CHUNK-1)/Util.CHUNK));
        Incoming inc;
        synchronized(incoming){
            inc=incoming.get(id);
            if(inc==null){
                ContentValues cv=new ContentValues();cv.put(MediaStore.Downloads.DISPLAY_NAME,name);
                cv.put(MediaStore.Downloads.MIME_TYPE,mime);
                cv.put(MediaStore.Downloads.RELATIVE_PATH,BASE+(path.isEmpty()?"":path+"/"));
                cv.put(MediaStore.Downloads.IS_PENDING,1);
                Uri uri=context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,cv);
                if(uri==null)throw new IOException("Не удалось создать файл в загрузках");
                inc=new Incoming(id,name,path,mime,uri,size,count);
                incoming.put(id,inc);persist(inc);
                events.note("Получение: "+(path.isEmpty()?"":path+"/")+name+" ("+Util.readable(size)+")");
            }
        }
        if(inc.size!=size||!inc.name.equals(name)||!inc.path.equals(path)||!inc.mime.equals(mime))
            throw new IOException("Конфликт ID файла: имя, путь, размер или формат изменились");
        JSONObject r=new JSONObject();r.put("chunk",Util.CHUNK);
        boolean allDone;
        synchronized(inc){r.put("done",Base64.encodeToString(inc.done.toByteArray(),Base64.NO_WRAP));allDone=inc.done.cardinality()==inc.count;}
        if(allDone)complete(inc);
        return r;
    }
    private static class Incoming {
        final String id,name,path,mime; final Uri uri;final long size;final int count;final BitSet done=new BitSet();
        boolean completed;
        final BitSet writing=new BitSet();
        final AtomicLong activeBytes=new AtomicLong();
        final AtomicLong lastReport=new AtomicLong();
        ParcelFileDescriptor outputFd;
        FileOutputStream outputStream;
        FileChannel outputChannel;
        Incoming(String i,String n,String p,String m,Uri u,long s,int c){id=i;name=n;path=p;mime=m;uri=u;size=s;count=c;}
        long bytesDone(){long sum=0;synchronized(this){for(int i=done.nextSetBit(0);i>=0;i=done.nextSetBit(i+1))sum+=Math.min(Util.CHUNK,size-(long)i*Util.CHUNK);}return sum;}
    }
    private FileChannel destination(Incoming inc)throws IOException{
        synchronized(inc){
            if(inc.outputChannel!=null&&inc.outputChannel.isOpen())return inc.outputChannel;
            ParcelFileDescriptor pfd=context.getContentResolver().openFileDescriptor(inc.uri,"rw");
            if(pfd==null)throw new IOException("Нет доступа к файлу назначения");
            try{
                FileOutputStream out=new FileOutputStream(pfd.getFileDescriptor());
                inc.outputFd=pfd;
                inc.outputStream=out;
                inc.outputChannel=out.getChannel();
                return inc.outputChannel;
            }catch(Exception e){
                try{pfd.close();}catch(IOException ignored){}
                throw new IOException("Не удалось открыть файл назначения",e);
            }
        }
    }
    private void closeDestination(Incoming inc,boolean flush){
        synchronized(inc){
            if(inc.outputChannel!=null){
                if(flush)try{inc.outputChannel.force(true);}catch(IOException ignored){}
                try{inc.outputChannel.close();}catch(IOException ignored){}
            }
            if(inc.outputStream!=null)try{inc.outputStream.close();}catch(IOException ignored){}
            if(inc.outputFd!=null)try{inc.outputFd.close();}catch(IOException ignored){}
            inc.outputChannel=null;inc.outputStream=null;inc.outputFd=null;
        }
    }
    /** Throttled live byte count, not completed-chunk jumps. */
    private void reportReceive(Incoming inc,boolean force){
        long now=android.os.SystemClock.elapsedRealtime();
        if(!force){
            long previous=inc.lastReport.get();
            if(now-previous<90L||!inc.lastReport.compareAndSet(previous,now))return;
        }else inc.lastReport.set(now);
        long current=inc.bytesDone()+Math.max(0L,inc.activeBytes.get());
        events.progress("Приём: "+inc.name,Math.min(inc.size,current),inc.size,0);
    }
    private synchronized void persist(Incoming inc){
        try{
            JSONObject obj=new JSONObject();obj.put("name",inc.name).put("path",inc.path).put("mime",inc.mime)
                .put("uri",inc.uri.toString()).put("size",inc.size)
                .put("bits",Base64.encodeToString(inc.done.toByteArray(),Base64.NO_WRAP));
            context.getSharedPreferences(SESSIONS,0).edit().putString(inc.id,obj.toString()).apply();
        }catch(Exception ignored){}
    }
    private void restore(){
        Map<String,?> prefs=context.getSharedPreferences(SESSIONS,0).getAll();
        for(Map.Entry<String,?>e:prefs.entrySet())try{
            JSONObject j=new JSONObject(String.valueOf(e.getValue()));long size=j.getLong("size");
            int count=(int)Math.max(1,(size+Util.CHUNK-1)/Util.CHUNK);
            Incoming in=new Incoming(e.getKey(),j.getString("name"),j.optString("path"),j.getString("mime"),Uri.parse(j.getString("uri")),size,count);
            in.done.or(BitSet.valueOf(Base64.decode(j.optString("bits",""),Base64.DEFAULT)));
            incoming.put(in.id,in);
        }catch(Exception ignored){}
    }
    private void complete(Incoming inc)throws IOException{
        synchronized(inc){
            if(inc.completed)return;
            try{
                // A saved chunk is not a published download. Keep completion
                // retryable until the final sync and MediaStore update succeed.
                if(inc.outputChannel!=null){
                    inc.outputChannel.force(true);
                    closeDestination(inc,false);
                }else try(ParcelFileDescriptor sync=context.getContentResolver().openFileDescriptor(inc.uri,"rw")){
                    if(sync==null)throw new IOException("Файл назначения недоступен");
                    try{sync.getFileDescriptor().sync();}
                    catch(java.io.SyncFailedException unsupported){
                        // Some Android storage providers do not implement fsync.
                    }
                }
                ContentValues cv=new ContentValues();
                cv.put(MediaStore.Downloads.IS_PENDING,0);
                if(context.getContentResolver().update(inc.uri,cv,null,null)!=1)
                    throw new IOException("MediaStore не подтвердил публикацию файла");
                inc.completed=true;
            }catch(Exception failure){
                throw new IOException("Не удалось завершить приём "+inc.name+": "+failure.getMessage(),failure);
            }
        }
        events.note("Получено: "+inc.name+" ("+Util.readable(inc.size)+")");
    }

    public void send(String host,int port,String userPin,List<FileCatalog.Item> jobs,int lanes)throws Exception{
        sendingCancelled=false;
        JSONObject hello=new JSONObject(Util.http(host,port,userPin,"/hello","GET",null));
        if(!"XARKON LINK".equals(hello.optString("app")))throw new IOException("Это не совместимый приёмник QR LINK");
        final boolean verifySha=hello.optBoolean("sha256",false);
        int failed=0;
        for(FileCatalog.Item job:jobs){
            if(sendingCancelled||Thread.currentThread().isInterrupted()){
                events.note("Передача остановлена. Очередь сохранена.");return;
            }
            if(job.size<0){failed++;FileCatalog.setStatus(context,job.id,"Ошибка: неизвестный размер");events.note("Пропуск: неизвестный размер файла "+job.name);continue;}
            try{sendOne(host,port,userPin,job,lanes,verifySha);FileCatalog.setStatus(context,job.id,"Передан");events.note("Отправлено: "+job.name);}
            catch(Exception e){
                if(sendingCancelled){FileCatalog.setStatus(context,job.id,"Приостановлен");
                    events.note("Передача остановлена. Можно продолжить позже.");return;}
                failed++;FileCatalog.setStatus(context,job.id,"Ошибка: "+e.getMessage());
                events.note("Ошибка отправки "+job.name+": "+e.getMessage());
            }
        }
        if(failed>0)throw new IOException("Не удалось передать "+failed+" из "+jobs.size()+" файлов. Проверьте очередь.");
    }
    private void sendOne(String host,int port,String userPin,FileCatalog.Item job,int lanes,boolean verifySha)throws Exception{
        JSONObject offer=new JSONObject().put("id",job.id).put("name",job.name)
            .put("path",job.path).put("mime",job.mime).put("size",job.size);
        JSONObject result=new JSONObject(Util.http(host,port,userPin,"/offer","POST",offer));
        if(result.getInt("chunk")!=Util.CHUNK)throw new IOException("Несовместимая версия протокола");
        int blocks=(int)Math.max(1,(job.size+Util.CHUNK-1)/Util.CHUNK);
        BitSet completed=BitSet.valueOf(Base64.decode(result.optString("done",""),Base64.DEFAULT));
        ArrayList<Integer>todo=new ArrayList<>();long already=0;
        for(int i=0;i<blocks;i++){
            if(completed.get(i))already+=Math.min(Util.CHUNK,job.size-(long)i*Util.CHUNK);else todo.add(i);
        }
        AtomicLong progress=new AtomicLong(already);
        AtomicLong inFlight=new AtomicLong();
        AtomicLong lastReport=new AtomicLong();
        long start=Util.now(), initial=already;
        events.progress("Отправка: "+job.name,already,job.size,0);
        // Keep only the configured number of chunks queued. Large files must not
        // allocate a Future and a runnable for every 8 MiB block at once.
        int parallel=Math.max(1,Math.min(lanes,8));
        ExecutorService pool=Executors.newFixedThreadPool(parallel);
        CompletionService<Void> completions=new ExecutorCompletionService<>(pool);
        // Open the Android content URI ONCE per file, not once per 8 MiB block.
        // Positional reads let workers share a seekable descriptor without skip(offset).
        ParcelFileDescriptor sourceDescriptor=null;
        FileInputStream sourceStream=null;
        FileChannel sourceChannel=null;
        try{
            try {
                sourceDescriptor=context.getContentResolver().openFileDescriptor(Uri.parse(job.uri),"r");
                if(sourceDescriptor!=null){
                    sourceStream=new FileInputStream(sourceDescriptor.getFileDescriptor());
                    sourceChannel=sourceStream.getChannel();
                    // Some providers offer pipes, not seekable file descriptors.
                    sourceChannel.position(0);
                }
            }catch(Exception notSeekable){
                if(sourceStream!=null)try{sourceStream.close();}catch(Exception ignored){}
                if(sourceDescriptor!=null)try{sourceDescriptor.close();}catch(Exception ignored){}
                sourceStream=null;sourceDescriptor=null;sourceChannel=null;
            }
            final FileChannel sharedSource=sourceChannel;
            Iterator<Integer> pending=todo.iterator();
            int active=0;
            while(pending.hasNext()||active>0){
                if(sendingCancelled)throw new IOException("Передача остановлена");
                while(active<parallel&&pending.hasNext()){
                    final int ix=pending.next();
                    completions.submit(()->{
                    Exception last=null;
                    for(int attempt=0;attempt<3;attempt++){
                        if(sendingCancelled)throw new IOException("Передача остановлена");
                        try{
                            AtomicLong thisAttempt=new AtomicLong();
                            try{
                                sendChunk(host,port,userPin,job,ix,sharedSource,verifySha,n->{
                                    thisAttempt.addAndGet(n);
                                    inFlight.addAndGet(n);
                                    reportSend(job,progress,inFlight,lastReport,false);
                                });
                                long bytes=Math.min(Util.CHUNK,job.size-(long)ix*Util.CHUNK);
                                progress.addAndGet(bytes);
                                inFlight.addAndGet(-thisAttempt.get());
                                reportSend(job,progress,inFlight,lastReport,true);
                                return null;
                            }catch(Exception failure){
                                inFlight.addAndGet(-thisAttempt.get());
                                reportSend(job,progress,inFlight,lastReport,true);
                                throw failure;
                            }
                        }catch(Exception e){
                            last=e;
                            // A verified mismatch cannot be repaired by retrying a
                            // completed block under the same ID: report an error.
                            if(e instanceof ChecksumMismatchException)break;
                            try{Thread.sleep(250L*(attempt+1));}
                            catch(InterruptedException ie){Thread.currentThread().interrupt();break;}
                        }
                    }
                    throw last;
                    });
                    active++;
                }
                // Refill as soon as any worker completes. On the first failure,
                // stop scheduling more blocks and cancel remaining work in finally.
                try{completions.take().get();}
                catch(ExecutionException failure){
                    Throwable cause=failure.getCause();
                    if(cause instanceof Exception)throw (Exception)cause;
                    throw new IOException("Сбой передачи блока",cause);
                }
                active--;
            }
        }finally{
            pool.shutdownNow();
            if(sourceStream!=null)try{sourceStream.close();}catch(IOException ignored){}
            if(sourceDescriptor!=null)try{sourceDescriptor.close();}catch(IOException ignored){}
        }
        events.progress("Готово: "+job.name,job.size,job.size,Math.max(0,job.size-initial)/Math.max(.001,(Util.now()-start)/1000.0));
    }
    /** SHA-256 acknowledgement is optional for older receivers (protocol v1 compatible). */
    private void sendChunk(String host,int port,String userPin,FileCatalog.Item job,int index,
                           FileChannel sharedSource,boolean verifySha,java.util.function.IntConsumer delivered)throws Exception{
        long offset=(long)index*Util.CHUNK;
        long length=Math.min(Util.CHUNK,job.size-offset);
        URL u=new URL("http",host,port,"/chunk/"+job.id+"/"+index);
        HttpURLConnection con=(HttpURLConnection)u.openConnection();
        try{
            con.setRequestMethod("PUT");con.setDoOutput(true);
            con.setConnectTimeout(5000);con.setReadTimeout(90000);
            con.setRequestProperty("X-Xarkon-Key",userPin);
            con.setFixedLengthStreamingMode(length);
            con.setRequestProperty("Connection","close");
            MessageDigest checksum=MessageDigest.getInstance("SHA-256");
            try(OutputStream out=new BufferedOutputStream(con.getOutputStream(),256*1024)){
                byte[] buf=new byte[256*1024];long rem=length;
                if(sharedSource!=null){
                    long position=offset;
                    while(rem>0){
                        if(sendingCancelled||Thread.currentThread().isInterrupted())throw new IOException("Передача остановлена");
                        ByteBuffer b=ByteBuffer.wrap(buf,0,(int)Math.min(buf.length,rem));
                        int n=sharedSource.read(b,position);
                        if(n<=0)throw new EOFException("Не удалось прочитать исходный файл");
                        checksum.update(buf,0,n);
                        out.write(buf,0,n);delivered.accept(n);position+=n;rem-=n;
                    }
                }else{
                    // Some document providers expose nonseekable streams.
                    try(InputStream raw=context.getContentResolver().openInputStream(Uri.parse(job.uri))){
                        if(raw==null)throw new IOException("Нет доступа к файлу");
                        position(raw,offset);
                        while(rem>0){
                            if(sendingCancelled||Thread.currentThread().isInterrupted())throw new IOException("Передача остановлена");
                            int n=raw.read(buf,0,(int)Math.min(buf.length,rem));
                            if(n<=0)throw new EOFException("Файл источника укорочен");
                            checksum.update(buf,0,n);
                            out.write(buf,0,n);delivered.accept(n);rem-=n;
                        }
                    }
                }
            }
            int response=con.getResponseCode();
            if(response!=200){
                String detail="";
                try(InputStream err=con.getErrorStream()){
                    if(err!=null)detail=Util.readText(err,4096);
                }catch(Exception ignored){}
                throw new IOException("HTTP "+response+": "+detail);
            }
            JSONObject answer;
            try(InputStream responseBody=con.getInputStream()){
                answer=new JSONObject(Util.readText(responseBody,4096));
            }
            String remote=answer.optString("sha256","");
            if(!remote.isEmpty()){
                if(!remote.matches("[0-9a-fA-F]{64}")||
                   !remote.equalsIgnoreCase(hex(checksum.digest())))
                    throw new ChecksumMismatchException("Контрольная сумма блока не совпала. Повторно добавьте файл в очередь.");
            }else if(verifySha){
                throw new ChecksumMismatchException("Получатель не подтвердил контрольную сумму блока");
            }
        }finally{
            // Disconnect even when the source stream or a socket write fails.
            con.disconnect();
        }
    }
    private static String hex(byte[] digest){
        char[] digits="0123456789abcdef".toCharArray();
        char[] out=new char[digest.length*2];
        for(int i=0;i<digest.length;i++){
            int v=digest[i]&255;
            out[2*i]=digits[v>>>4];
            out[2*i+1]=digits[v&15];
        }
        return new String(out);
    }
    private void reportSend(FileCatalog.Item job,AtomicLong confirmed,AtomicLong inFlight,
                            AtomicLong lastReport,boolean force){
        long now=android.os.SystemClock.elapsedRealtime();
        if(!force){
            long previous=lastReport.get();
            if(now-previous<90L||!lastReport.compareAndSet(previous,now))return;
        }else lastReport.set(now);
        long bytes=confirmed.get()+Math.max(0L,inFlight.get());
        events.progress("Отправка: "+job.name,Math.min(job.size,bytes),job.size,0);
    }
    private static void position(InputStream stream,long offset)throws IOException{
        if(stream instanceof FileInputStream){
            try{((FileInputStream)stream).getChannel().position(offset);return;}catch(Exception ignored){}
        }
        long remain=offset;
        while(remain>0){long s=stream.skip(remain);if(s==0){if(stream.read()<0)throw new EOFException();s=1;}remain-=s;}
    }
    public static double benchmark(String host,int port,String pin,int lanes)throws Exception{
        JSONObject hello=new JSONObject(Util.http(host,port,pin,"/hello","GET",null));
        if(!"XARKON LINK".equals(hello.optString("app")))throw new IOException("Недоступный сервер");
        ExecutorService pool=Executors.newFixedThreadPool(Math.max(1,Math.min(lanes,8)));
        long start=Util.now();ArrayList<Future<?>>calls=new ArrayList<>();
        try{
            for(int i=0;i<32;i++)calls.add(pool.submit(()->{
                try{
                    HttpURLConnection conn=(HttpURLConnection)new URL("http",host,port,"/bench").openConnection();
                    conn.setDoOutput(true);conn.setRequestMethod("PUT");conn.setConnectTimeout(5000);conn.setReadTimeout(30000);
                    conn.setRequestProperty("X-Xarkon-Key",pin);conn.setFixedLengthStreamingMode(16*1024*1024);
                    byte[]b=new byte[256*1024];new java.security.SecureRandom().nextBytes(b);
                    try(OutputStream out=conn.getOutputStream()){for(int k=0;k<64;k++)out.write(b);}
                    if(conn.getResponseCode()!=200)throw new IOException("HTTP "+conn.getResponseCode());
                    conn.disconnect();
                }catch(Exception e){throw new RuntimeException(e);}
            }));
            for(Future<?> f:calls)f.get();
            double seconds=Math.max(.001,(Util.now()-start)/1000.0);
            return (512d*1024*1024)/seconds;
        }finally{pool.shutdownNow();}
    }
}
