package io.github.bigfiiish.eventledger;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.io.*;
import java.util.HexFormat;

/** Offline durable replay file. Hashes detect damage, not malicious rewriting of the whole chain. */
public final class Journal {
    private static String hash(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    public static void write(Path path,Event[] events) throws IOException {
        try(var w=Files.newBufferedWriter(path,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)) {
            String previous="ROOT";
            for(Event e:events) { String payload=e.csv(); previous=hash(previous+"|"+payload); w.write(payload+","+previous+"\n"); }
        }
    }
    public static Book replay(Path path,Book empty) throws IOException {
        if(empty.sequence()!=0) throw new IllegalArgumentException("Replay requires empty state");
        try(var r=Files.newBufferedReader(path,StandardCharsets.UTF_8)) {
            String previous="ROOT",line;
            while((line=r.readLine())!=null) {
                int split=line.lastIndexOf(',');
                if(split<0) throw new IOException("Truncated journal record");
                String payload=line.substring(0,split),next=hash(previous+"|"+payload);
                if(!next.equals(line.substring(split+1))) throw new IOException("Journal hash mismatch");
                empty.apply(Event.parse(payload)); previous=next;
            }
        }
        return empty;
    }
}
