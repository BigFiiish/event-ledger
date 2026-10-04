package io.github.bigfiiish.eventledger;

import java.util.*;

/** Readable allocation-heavy baseline: boxed order map and sorted price levels. */
public final class ReferenceBook extends Book {
    private record Order(Event.Side side, int price, int quantity) {}
    private final Map<Integer,Order> orders=new HashMap<>();
    private final TreeMap<Integer,Long> bids=new TreeMap<>(), asks=new TreeMap<>();
    public ReferenceBook(int capacity,int ticks) { super(capacity,ticks); }
    private TreeMap<Integer,Long> levels(Event.Side s) { return s==Event.Side.BID?bids:asks; }
    protected void update(Event e) {
        Order old=orders.get(e.id());
        int next;
        if(e.kind()==Event.Kind.ADD) {
            if(old!=null) throw new IllegalArgumentException("Duplicate active ID");
            next=e.quantity();
        } else {
            validateExisting(e, old==null?0:old.quantity,old==null?0:old.price,old==null?null:old.side);
            next=e.kind()==Event.Kind.DELETE?0:old.quantity-e.quantity();
        }
        var levels=levels(e.side());
        long total=Math.addExact(levels.getOrDefault(e.price(),0L),(long)next-(old==null?0:old.quantity));
        if(total==0) levels.remove(e.price()); else levels.put(e.price(),total);
        if(next==0) orders.remove(e.id()); else orders.put(e.id(),new Order(e.side(),e.price(),next));
    }
    public int quantity(int id) { var o=orders.get(id); return o==null?0:o.quantity; }
    public int price(int id) { var o=orders.get(id); return o==null?0:o.price; }
    public Event.Side side(int id) { var o=orders.get(id); return o==null?null:o.side; }
    public long depth(Event.Side s,int price) { return levels(s).getOrDefault(price,0L); }
    public int best(Event.Side s) { var m=levels(s); return m.isEmpty()?-1:s==Event.Side.BID?m.lastKey():m.firstKey(); }
}
