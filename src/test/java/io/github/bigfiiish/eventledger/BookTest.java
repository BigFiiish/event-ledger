package io.github.bigfiiish.eventledger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.bigfiiish.eventledger.Event.*;

class BookTest {
    Book book(boolean fast) { return fast?new ArrayBook(16,100):new ReferenceBook(16,100); }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void levelsAggregateAndBestMoves(boolean fast) {
        Book b=book(fast);
        b.apply(new Event(1,Kind.ADD,1,Side.BID,40,10));
        b.apply(new Event(2,Kind.ADD,2,Side.BID,40,20));
        b.apply(new Event(3,Kind.ADD,3,Side.BID,41,5));
        b.apply(new Event(4,Kind.ADD,4,Side.ASK,52,7));
        assertEquals(30,b.depth(Side.BID,40)); assertEquals(41,b.best(Side.BID)); assertEquals(52,b.best(Side.ASK));
        b.apply(new Event(5,Kind.REDUCE,3,Side.BID,41,5));
        assertEquals(40,b.best(Side.BID)); assertEquals(0,b.quantity(3));
        b.apply(new Event(6,Kind.DELETE,4,Side.ASK,52,0)); assertEquals(-1,b.best(Side.ASK));
        b.apply(new Event(7,Kind.ADD,4,Side.ASK,51,2)); assertEquals(51,b.best(Side.ASK));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void rejectedEventsCannotMutateState(boolean fast) {
        Book b=book(fast); b.apply(new Event(1,Kind.ADD,1,Side.BID,40,10)); long h=b.digest();
        Event[] invalid={new Event(3,Kind.ADD,2,Side.BID,40,1),new Event(1,Kind.ADD,2,Side.BID,40,1),
            new Event(2,Kind.ADD,1,Side.BID,40,1),new Event(2,Kind.REDUCE,1,Side.BID,40,11),
            new Event(2,Kind.REDUCE,1,Side.ASK,40,1),new Event(2,Kind.DELETE,2,Side.BID,40,0),
            new Event(2,Kind.DELETE,1,Side.BID,41,0),new Event(2,Kind.ADD,16,Side.BID,40,1),
            new Event(2,Kind.ADD,2,Side.BID,100,1)};
        for(Event e:invalid) { assertThrows(RuntimeException.class,()->b.apply(e)); assertEquals(h,b.digest()); assertEquals(10,b.depth(Side.BID,40)); }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void aggregateUsesLongAndBoundaryPrices(boolean fast) {
        Book b=book(fast); b.apply(new Event(1,Kind.ADD,1,Side.BID,0,Integer.MAX_VALUE));
        b.apply(new Event(2,Kind.ADD,2,Side.BID,0,Integer.MAX_VALUE));
        b.apply(new Event(3,Kind.ADD,3,Side.ASK,99,1));
        assertEquals(2L*Integer.MAX_VALUE,b.depth(Side.BID,0)); assertEquals(0,b.best(Side.BID)); assertEquals(99,b.best(Side.ASK));
    }
    @Test void parserRejectsBadInputs() {
        for(String row:new String[]{"1,ADD,0,BID,2,0","0,ADD,0,BID,2,1","1,DELETE,0,BID,2,1","1,ADD,-1,BID,2,1","1,FOO,0,BID,2,1","1,ADD"})
            assertThrows(IllegalArgumentException.class,()->Event.parse(row));
        var e=new Event(1,Kind.ADD,0,Side.BID,2,1); assertEquals(e,Event.parse(e.csv()));
    }
    @ParameterizedTest @ValueSource(longs={1,7,42,991,2026})
    void randomizedDifferentialEveryEvent(long seed) {
        Book reference=new ReferenceBook(128,256),fast=new ArrayBook(128,256);
        for(Event e:Workload.generate(20_000,128,256,seed)) {
            reference.apply(e); fast.apply(e);
            assertEquals(reference.digest(),fast.digest());
            for(Side s:Side.values()) { assertEquals(reference.best(s),fast.best(s)); assertEquals(reference.depth(s,e.price()),fast.depth(s,e.price())); }
        }
        for(Side s:Side.values()) for(int p=0;p<256;p++) assertEquals(reference.depth(s,p),fast.depth(s,p));
    }
    @Test void journalReplayAndCorruption(@TempDir Path dir) throws Exception {
        var events=Workload.generate(1000,16,100,42); var path=dir.resolve("events.csv"); Journal.write(path,events);
        assertEquals(Journal.replay(path,book(false)).digest(),Journal.replay(path,book(true)).digest());
        assertThrows(java.nio.file.FileAlreadyExistsException.class,()->Journal.write(path,events));
        String content=Files.readString(path); Files.writeString(path,content.replaceFirst("ADD","BAD"));
        assertThrows(java.io.IOException.class,()->Journal.replay(path,book(true)));
    }
    @Test void journalRefusesMissingSequence(@TempDir Path dir) throws Exception {
        var path=dir.resolve("gap.csv"); Journal.write(path,new Event[]{new Event(2,Kind.ADD,1,Side.BID,1,1)});
        assertThrows(IllegalStateException.class,()->Journal.replay(path,book(true)));
    }
    @Test void quantilesUseNearestRank() {
        assertEquals(99,Benchmark.percentile(new long[]{1,2,3,99},.99));
        assertEquals(2,Benchmark.percentile(new long[]{1,2,3,99},.5));
    }
    @ParameterizedTest @ValueSource(strings={"reference","array"})
    void concurrentDeliveryPreservesAllEvents(String type) throws Exception {
        var events=Workload.generate(20_000,Benchmark.CAPACITY,Benchmark.TICKS,91);
        Book oracle=Benchmark.book("reference"); for(var e:events) oracle.apply(e);
        String rows=Benchmark.queued(type,0,events,1_000_000,oracle.digest());
        assertEquals(4,rows.lines().count());
        assertTrue(rows.contains("scheduled_to_done"));
    }
}
