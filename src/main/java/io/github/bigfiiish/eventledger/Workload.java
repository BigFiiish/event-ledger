package io.github.bigfiiish.eventledger;

import java.util.SplittableRandom;

/** Synthetic, valid add/reduce/delete feed; no exchange provenance is implied. */
public final class Workload {
    public static Event[] generate(int count,int capacity,int ticks,long seed) {
        var random=new SplittableRandom(seed);
        Book model=new ArrayBook(capacity,ticks);
        Event[] events=new Event[count];
        for(int i=0;i<count;i++) {
            int id=random.nextInt(capacity),q=model.quantity(id);
            Event e;
            if(q==0) {
                var side=random.nextBoolean()?Event.Side.BID:Event.Side.ASK;
                int half=ticks/2;
                int p=side==Event.Side.BID?random.nextInt(half):half+random.nextInt(ticks-half);
                e=new Event(i+1,Event.Kind.ADD,id,side,p,1+random.nextInt(1000));
            } else {
                var kind=random.nextInt(4)==0?Event.Kind.DELETE:Event.Kind.REDUCE;
                e=new Event(i+1,kind,id,model.side(id),model.price(id),kind==Event.Kind.DELETE?0:1+random.nextInt(q));
            }
            model.apply(e); events[i]=e;
        }
        return events;
    }
}
