package io.github.bigfiiish.eventledger;

import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/** A transparent custom harness, not a substitute for JMH or exchange/network measurements. */
public final class Benchmark {
    static volatile long blackhole;
    static final int CAPACITY=4096,TICKS=8192;
    static Book book(String type) { return type.equals("array")?new ArrayBook(CAPACITY,TICKS):new ReferenceBook(CAPACITY,TICKS); }
    public static long percentile(long[] sorted,double p) { return sorted[Math.max(0,(int)Math.ceil(sorted.length*p)-1)]; }
    static long allocation() {
        var bean=ManagementFactory.getThreadMXBean();
        if(bean instanceof com.sun.management.ThreadMXBean b && b.isThreadAllocatedMemorySupported()) {
            if(!b.isThreadAllocatedMemoryEnabled()) b.setThreadAllocatedMemoryEnabled(true);
            return b.getThreadAllocatedBytes(Thread.currentThread().threadId());
        }
        return -1;
    }
    static long gcCount() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(x->Math.max(0,x.getCollectionCount())).sum(); }
    static String row(String mode,String type,int round,int count,long wall,long[] times,long allocated,long gc,long blocked,long digest) {
        Arrays.sort(times);
        return String.format(Locale.ROOT,"%s,%s,%d,%d,%d,%.3f,%d,%d,%d,%d,%.3f,%d,%d,%d%n",mode,type,round,count,wall,count*1e9/wall,
            percentile(times,.5),percentile(times,.95),percentile(times,.99),times[times.length-1],allocated<0?-1.0:(double)allocated/count,gc,blocked,digest);
    }
    static String direct(String type,int round,Event[] events,long expected) {
        Book b=book(type); long[] ns=new long[events.length]; long gc=gcCount(),alloc=allocation(),start=System.nanoTime();
        for(int i=0;i<events.length;i++) { long t=System.nanoTime(); b.apply(events[i]); blackhole=b.best(Event.Side.BID)+b.best(Event.Side.ASK); ns[i]=System.nanoTime()-t; }
        long wall=System.nanoTime()-start,used=allocation(); long digest=b.digest();
        if(digest!=expected) throw new AssertionError("Replay mismatch");
        return row("service",type,round,events.length,wall,ns,alloc<0?-1:used-alloc,gcCount()-gc,0,digest);
    }
    record Delivery(Event event,long due,long enqueued) {}
    static String queued(String type,int round,Event[] events,int rate,long expected) throws Exception {
        var queue=new ArrayBlockingQueue<Delivery>(1024);
        long[] service=new long[events.length],endToEnd=new long[events.length],queueWait=new long[events.length],lag=new long[events.length];
        var failure=new AtomicReference<Throwable>(); Book b=book(type);
        long[] consumerAllocation={-1};
        Thread consumer=Thread.ofPlatform().name("book-writer").start(()->{
            long a=allocation();
            try {
                for(int i=0;i<events.length;i++) {
                    Delivery d=queue.poll(10,TimeUnit.SECONDS);
                    if(d==null) throw new IllegalStateException("Producer timeout");
                    long begin=System.nanoTime(); b.apply(d.event); blackhole=b.best(Event.Side.BID)+b.best(Event.Side.ASK); long done=System.nanoTime();
                    service[i]=done-begin; endToEnd[i]=done-d.due; queueWait[i]=begin-d.enqueued; lag[i]=d.enqueued-d.due;
                }
            } catch(Throwable t) { failure.set(t); }
            consumerAllocation[0]=a<0?-1:allocation()-a;
        });
        long blocked=0,gc=gcCount(),start=System.nanoTime()+1_000_000;
        for(int i=0;i<events.length;i++) {
            long due=start+(long)i*1_000_000_000/rate;
            while(System.nanoTime()<due) { long remaining=due-System.nanoTime(); if(remaining>100_000) LockSupport.parkNanos(remaining-50_000); else Thread.onSpinWait(); }
            Delivery delivery=new Delivery(events[i],due,System.nanoTime());
            // Timestamp precedes offer: queue wait explicitly includes enqueue blocking time.
            if(!queue.offer(delivery)) { blocked++; if(!queue.offer(delivery,10,TimeUnit.SECONDS)) { consumer.interrupt(); throw new IllegalStateException("Backpressure timeout"); } }
            if(failure.get()!=null) throw new IllegalStateException("Consumer failed",failure.get());
        }
        consumer.join(10_000); if(consumer.isAlive()) { consumer.interrupt(); throw new IllegalStateException("Consumer timeout"); }
        if(failure.get()!=null) throw new IllegalStateException("Consumer failed",failure.get());
        long wall=System.nanoTime()-start,digest=b.digest(); if(digest!=expected) throw new AssertionError("Queue lost/reordered events");
        String prefix="offered_"+rate+"_";
        return row(prefix+"service",type,round,events.length,wall,service,consumerAllocation[0],gcCount()-gc,blocked,digest)
            +row(prefix+"scheduled_to_done",type,round,events.length,wall,endToEnd,-1,0,blocked,digest)
            +row(prefix+"enqueue_to_start",type,round,events.length,wall,queueWait,-1,0,blocked,digest)
            +row(prefix+"producer_lag",type,round,events.length,wall,lag,-1,0,blocked,digest);
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1) throw new IllegalArgumentException("Output CSV required");
        Event[] events=Workload.generate(300_000,CAPACITY,TICKS,42),queued=Workload.generate(60_000,CAPACITY,TICKS,42);
        Book expected=book("reference"),expectedQ=book("reference"); for(var e:events) expected.apply(e); for(var e:queued) expectedQ.apply(e);
        for(int w=0;w<5;w++) for(String type:new String[]{"reference","array"}) direct(type,-1,events,expected.digest());
        // Warm both producer/consumer paths before reported rounds.
        for(String type:new String[]{"reference","array"}) queued(type,-1,queued,100_000,expectedQ.digest());
        StringBuilder csv=new StringBuilder("mode,implementation,round,events,wall_ns,events_per_second,p50_ns,p95_ns,p99_ns,max_ns,consumer_bytes_per_event,gc_collections,backpressure_events,digest\n");
        for(int r=0;r<4;r++) {
            String[] order=r%2==0?new String[]{"reference","array"}:new String[]{"array","reference"};
            for(String type:order) csv.append(direct(type,r,events,expected.digest()));
            for(int rate:new int[]{100_000,500_000}) for(String type:order) csv.append(queued(type,r,queued,rate,expectedQ.digest()));
        }
        Files.writeString(Path.of(args[0]),csv);
        System.out.println("Verified and wrote "+args[0]);
    }
}
