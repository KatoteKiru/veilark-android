package com.example.veilark.io;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ProcessFixture {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("echo")) { System.out.print("ok"); return; }
        if (args[0].equals("flood")) {
            while (true) System.out.print("x".repeat(1024));
        }
        Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
        Thread.sleep(60_000);
    }
}
