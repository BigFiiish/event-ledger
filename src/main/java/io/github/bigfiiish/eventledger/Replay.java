package io.github.bigfiiish.eventledger;

import java.nio.file.*;

public final class Replay {
    public static void main(String[] args) throws Exception {
        if(args.length!=2) throw new IllegalArgumentException("generate|replay <journal.csv>");
        Path path=Path.of(args[1]);
        if(args[0].equals("generate")) Journal.write(path,Workload.generate(100_000,4096,8192,42));
        else if(args[0].equals("replay")) {
            Book b=Journal.replay(path,new ArrayBook(4096,8192));
            System.out.printf("sequence=%d bestBid=%d bestAsk=%d digest=%d%n",b.sequence(),b.best(Event.Side.BID),b.best(Event.Side.ASK),b.digest());
        } else throw new IllegalArgumentException("Unknown mode");
    }
}
