package io.github.bigfiiish.eventledger;

/** Single-writer book. Invalid events do not advance sequence or mutate state. */
public abstract class Book {
    protected final int capacity, ticks;
    protected long sequence;
    protected Book(int capacity, int ticks) {
        if (capacity <= 0 || ticks < 2) throw new IllegalArgumentException("Invalid bounds");
        this.capacity=capacity; this.ticks=ticks;
    }
    public final void apply(Event e) {
        if (e.sequence() != sequence + 1) throw new IllegalStateException("Sequence gap, duplicate, or out-of-order event");
        if (e.id() >= capacity || e.price() >= ticks) throw new IllegalArgumentException("Outside configured bounds");
        update(e);
        sequence=e.sequence();
    }
    protected abstract void update(Event e);
    public abstract int quantity(int id);
    public abstract int price(int id);
    public abstract Event.Side side(int id);
    public abstract long depth(Event.Side side, int price);
    public abstract int best(Event.Side side);
    public long sequence() { return sequence; }
    public long digest() {
        long h=sequence;
        for (int id=0;id<capacity;id++) if(quantity(id)>0) {
            h=31*h+id; h=31*h+quantity(id); h=31*h+price(id); h=31*h+side(id).ordinal();
        }
        return h;
    }
    protected void validateExisting(Event e, int q, int p, Event.Side s) {
        if(q==0 || p!=e.price() || s!=e.side()) throw new IllegalArgumentException("Unknown order or mismatched attributes");
        if(e.kind()==Event.Kind.REDUCE && e.quantity()>q) throw new IllegalArgumentException("Over-reduction");
    }
}
