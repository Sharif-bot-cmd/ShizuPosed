package com.shizuposed.manager.utils;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class ShellUtils {
    private static final String TAG = "ShellUtils";
    
    public static boolean isRootAvailable() {
        try {
            Process process = Runtime.getRuntime().exec("su -c id");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String output = reader.readLine();
            process.waitFor();
            reader.close();
            return output != null && output.contains("uid=0");
        } catch (Exception e) {
            return false;
        }
    }
    
    public static CommandResult executeCommand(String command) {
        return executeCommand(new String[]{"sh", "-c", command});
    }
    
    public static CommandResult executeCommand(String[] commands) {
        return executeCommand(commands, false);
    }
    
    public static CommandResult executeCommand(String[] commands, boolean useRoot) {
        CommandResult result = new CommandResult();
        
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(commands);
            if (useRoot) {
                processBuilder.command("su");
            }
            
            Process process = processBuilder.start();
            
            // Write commands if using root
            if (useRoot && commands.length > 1) {
                DataOutputStream outputStream = new DataOutputStream(process.getOutputStream());
                for (String cmd : commands) {
                    outputStream.writeBytes(cmd + "\n");
                }
                outputStream.writeBytes("exit\n");
                outputStream.flush();
                outputStream.close();
            }
            
            // Read output
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            
            String line;
            while ((line = reader.readLine()) != null) {
                result.stdout.add(line);
            }
            while ((line = errorReader.readLine()) != null) {
                result.stderr.add(line);
            }
            
            result.exitCode = process.waitFor();
            
            reader.close();
            errorReader.close();
            
        } catch (Exception e) {
            Log.e(TAG, "Command execution failed: " + e.getMessage());
            result.stderr.add(e.getMessage());
            result.exitCode = -1;
        }
        
        return result;
    }
    
    public static boolean runAsShell(String command) {
        CommandResult result = executeCommand(command);
        return result.exitCode == 0;
    }
    
    public static boolean runAsRoot(String command) {
        if (!isRootAvailable()) {
            return false;
        }
        CommandResult result = executeCommand(new String[]{command}, true);
        return result.exitCode == 0;
    }
    
    public static String getProcessName(int pid) {
        CommandResult result = executeCommand("cat /proc/" + pid + "/cmdline");
        if (result.exitCode == 0 && !result.stdout.isEmpty()) {
            String name = result.stdout.get(0);
            if (name != null && !name.isEmpty()) {
                return name.replace("\0", "").trim();
            }
        }
        return null;
    }
    
    public static List<Integer> getRunningPids() {
        List<Integer> pids = new ArrayList<>();
        CommandResult result = executeCommand("ls -d /proc/[0-9]* | cut -d '/' -f 3");
        if (result.exitCode == 0) {
            for (String line : result.stdout) {
                try {
                    pids.add(Integer.parseInt(line.trim()));
                } catch (NumberFormatException e) {
                    // Skip invalid entries
                }
            }
        }
        return pids;
    }
    
    public static class CommandResult {
        public List<String> stdout = new ArrayList<>();
        public List<String> stderr = new ArrayList<>();
        public int exitCode = -1;
        
        public boolean isSuccess() {
            return exitCode == 0;
        }
        
        public String getStdoutString() {
            return String.join("\n", stdout);
        }
        
        public String getStderrString() {
            return String.join("\n", stderr);
        }
    }
}