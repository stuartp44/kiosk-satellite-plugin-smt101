package com.stuartp44.smt101;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

final class ShellSystemPropertyReader implements SystemPropertyReader {
    @Override
    public String read(String propertyName) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("getprop", propertyName).redirectErrorStream(true).start();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String value = reader.readLine();
            process.waitFor();
            return value == null ? "" : value.trim();
        } finally {
            process.destroy();
        }
    }
}
