package com.shizuposed.manager.utils;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

public class FileUtils {
    private static final String TAG = "FileUtils";
    
    public static boolean writeFile(File file, String content) {
        try {
            if (!file.exists()) {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                file.createNewFile();
            }
            
            FileWriter writer = new FileWriter(file);
            writer.write(content);
            writer.flush();
            writer.close();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to write file: " + e.getMessage());
            return false;
        }
    }
    
    public static String readFile(File file) {
        try {
            if (!file.exists()) {
                return null;
            }
            
            StringBuilder content = new StringBuilder();
            BufferedReader reader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            reader.close();
            return content.toString();
        } catch (IOException e) {
            Log.e(TAG, "Failed to read file: " + e.getMessage());
            return null;
        }
    }
    
    public static boolean deleteFile(File file) {
        if (file == null || !file.exists()) {
            return true;
        }
        return file.delete();
    }
    
    public static void deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
        }
        dir.delete();
    }
    
    public static List<File> listFiles(File dir) {
        List<File> files = new ArrayList<>();
        if (dir != null && dir.exists() && dir.isDirectory()) {
            File[] fileList = dir.listFiles();
            if (fileList != null) {
                for (File file : fileList) {
                    files.add(file);
                }
            }
        }
        return files;
    }
    
    public static boolean copyFile(File source, File dest) {
        try {
            if (!source.exists()) {
                return false;
            }
            
            if (!dest.exists()) {
                File parent = dest.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                dest.createNewFile();
            }
            
            FileInputStream inputStream = new FileInputStream(source);
            FileOutputStream outputStream = new FileOutputStream(dest);
            FileChannel inputChannel = inputStream.getChannel();
            FileChannel outputChannel = outputStream.getChannel();
            
            inputChannel.transferTo(0, inputChannel.size(), outputChannel);
            
            inputChannel.close();
            outputChannel.close();
            inputStream.close();
            outputStream.close();
            
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy file: " + e.getMessage());
            return false;
        }
    }
    
    public static String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0 && lastDot < fileName.length() - 1) {
            return fileName.substring(lastDot + 1).toLowerCase();
        }
        return "";
    }
    
    public static String getFileNameWithoutExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0) {
            return fileName.substring(0, lastDot);
        }
        return fileName;
    }
    
    public static boolean isDirectoryWritable(File dir) {
        if (dir == null) {
            return false;
        }
        
        if (!dir.exists()) {
            return dir.mkdirs();
        }
        
        if (dir.isDirectory()) {
            File testFile = new File(dir, ".test_write");
            try {
                if (testFile.createNewFile()) {
                    testFile.delete();
                    return true;
                }
                return false;
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }
}