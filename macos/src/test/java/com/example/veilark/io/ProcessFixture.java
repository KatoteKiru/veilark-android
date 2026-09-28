package com.example.veilark.io;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class ProcessFixture {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("echo")) { System.out.print("ok"); return; }
        if (args[0].equals("flood")) {
            while (true) System.out.print("x".repeat(1024));
        }
        Path pidFile = Path.of(args[1]);
        Path temporary = Files.createTempFile(pidFile.getParent(), "process-pid-", ".tmp");
        Files.writeString(temporary, Long.toString(ProcessHandle.current().pid()));
        Files.move(temporary, pidFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        Thread.sleep(60_000);
    }
}
