package io.github.bigfiiish.eventledger;

/** Normalized single-instrument L3 events. Prices are integer ticks, quantities are units. */
public record Event(long sequence, Kind kind, int id, Side side, int price, int quantity) {
    public enum Kind { ADD, REDUCE, DELETE }
    public enum Side { BID, ASK }
    public Event {
        if (sequence <= 0 || kind == null || id < 0 || side == null || price < 0 || quantity < 0)
            throw new IllegalArgumentException("Invalid normalized event");
        if (kind != Kind.DELETE && quantity == 0) throw new IllegalArgumentException("Zero quantity");
        if (kind == Kind.DELETE && quantity != 0) throw new IllegalArgumentException("DELETE quantity must be zero");
    }
    public String csv() { return sequence+","+kind+","+id+","+side+","+price+","+quantity; }
    public static Event parse(String row) {
        String[] f = row.split(",", -1);
        if (f.length != 6) throw new IllegalArgumentException("Expected six fields");
        return new Event(Long.parseLong(f[0]), Kind.valueOf(f[1]), Integer.parseInt(f[2]),
            Side.valueOf(f[3]), Integer.parseInt(f[4]), Integer.parseInt(f[5]));
    }
}
