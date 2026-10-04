package io.github.bigfiiish.eventledger;

import java.util.BitSet;

/** Bounded dense IDs and tick range trade memory/flexibility for less hot-path allocation. */
public final class ArrayBook extends Book {
    private final int[] quantities,prices;
    private final Event.Side[] sides;
    private final long[][] levels;
    private final BitSet[] occupied;
    public ArrayBook(int capacity,int ticks) {
        super(capacity,ticks); quantities=new int[capacity]; prices=new int[capacity]; sides=new Event.Side[capacity];
        levels=new long[2][ticks]; occupied=new BitSet[]{new BitSet(ticks),new BitSet(ticks)};
    }
    protected void update(Event e) {
        int id=e.id(),old=quantities[id],next;
        if(e.kind()==Event.Kind.ADD) {
            if(old!=0) throw new IllegalArgumentException("Duplicate active ID");
            next=e.quantity();
        } else {
            validateExisting(e,old,prices[id],sides[id]);
            next=e.kind()==Event.Kind.DELETE?0:old-e.quantity();
        }
        int s=e.side().ordinal(),p=e.price();
        long total=Math.addExact(levels[s][p],(long)next-old);
        levels[s][p]=total; occupied[s].set(p,total>0);
        quantities[id]=next; prices[id]=next==0?0:p; sides[id]=next==0?null:e.side();
    }
    public int quantity(int id) { return quantities[id]; }
    public int price(int id) { return prices[id]; }
    public Event.Side side(int id) { return sides[id]; }
    public long depth(Event.Side side,int price) { return levels[side.ordinal()][price]; }
    public int best(Event.Side side) { return side==Event.Side.BID?occupied[0].previousSetBit(ticks-1):occupied[1].nextSetBit(0); }
}
