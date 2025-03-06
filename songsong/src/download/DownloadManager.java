package download;

import directory.DirectoryInterface;
import model.ClientInfo;

import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

public class DownloadManager {
    // Download timer field
    private final ConcurrentHashMap<String, Long> downloadStartTimes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> downloadEndTimes = new ConcurrentHashMap<>();
    
    public Map<String, Integer> listAvailableFiles() {
    try {
        if (directoryService == null) {
            connect();
        }
        
        return directoryService.listAvailableFiles();
    } catch (Exception e) {
        System.err.println("Error listing available files: " + e.getMessage());
        return new HashMap<>();
    }
}

public void displayAvailableFiles() {
    try {
        Map<String, Integer> files = listAvailableFiles();
        
        if (files.isEmpty()) {
            System.out.println("No files available for download.");
            return;
        }
        
        System.out.println("\nAvailable files:");
        System.out.println("--------------------------------------------------");
        System.out.printf("%-30s | %-10s\n", "FILENAME", "SOURCES");
        System.out.println("--------------------------------------------------");
        
        files.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                System.out.printf("%-30s | %-10d\n", entry.getKey(), entry.getValue());
            });
        
        System.out.println("--------------------------------------------------");
    } catch (Exception e) {
        System.err.println("Error displaying available files: " + e.getMessage());
        }
    }
    
    private void startDownloadTimer(String filename) {
        downloadStartTimes.put(filename, System.currentTimeMillis());
    }
    
    /**
     * Stops the download timer for a specific file
     */
    private void stopDownloadTimer(String filename) {
        downloadEndTimes.put(filename, System.currentTimeMillis());
    }
    
    /**
     * Gets the download statistics as a formatted string
     */
    public String getDownloadStats(String filename, long fileSize) {
        Long startTime = downloadStartTimes.get(filename);
        Long endTime = downloadEndTimes.get(filename);
        
        if (startTime != null && endTime != null) {
            long timeMs = endTime - startTime;
            double seconds = timeMs / 1000.0;
            double speed = (fileSize * 1000.0) / timeMs; // bytes per second
            
            String speedStr;
            if (speed >= 1_048_576) { // 1 MB/s
                speedStr = String.format("%.2f MB/s", speed / 1_048_576);
            } else if (speed >= 1024) { // 1 KB/s
                speedStr = String.format("%.2f KB/s", speed / 1024);
            } else {
                speedStr = String.format("%.2f B/s", speed);
            }
            
            return String.format("Download completed in %.2f seconds (Speed: %s)", seconds, speedStr);
        }
        
        return "Download time not available";
    }
    private final String directoryHost;
    private final int directoryPort;
    private final Path downloadDirectory;
    private final ExecutorService threadPool;
    private DirectoryInterface directoryService;

    public DownloadManager(String directoryHost, int directoryPort, String downloadDirectoryPath) {
        this.directoryHost = directoryHost;
        this.directoryPort = directoryPort;
        this.downloadDirectory = Paths.get(downloadDirectoryPath);
        this.threadPool = Executors.newFixedThreadPool(10);
        
        // Create download directory if it doesn't exist
        try {
            Files.createDirectories(downloadDirectory);
        } catch (IOException e) {
            System.err.println("Error creating download directory: " + e.getMessage());
        }
    }

    public void connect() throws Exception {
        Registry registry = LocateRegistry.getRegistry(directoryHost, directoryPort);
        directoryService = (DirectoryInterface) registry.lookup("DirectoryService");
        System.out.println("Connected to Directory Service");
    }

    public boolean downloadFile(String filename) {
        try {
            if (directoryService == null) {
                connect();
            }
            
            // Get clients that have this file
            List<ClientInfo> sources = directoryService.getFileLocations(filename);
            if (sources.isEmpty()) {
                System.out.println("No sources found for file: " + filename);
                return false;
            }
            
            System.out.println("Found " + sources.size() + " source(s) for file: " + filename);
            
            // Get the file size from the first source
            long fileSize = getFileSize(sources.get(0), filename);
            if (fileSize <= 0) {
                System.out.println("Could not determine file size for: " + filename);
                return false;
            }
            
            // Create output file
            Path outputPath = downloadDirectory.resolve(filename);
            RandomAccessFile outputFile = new RandomAccessFile(outputPath.toFile(), "rw");
            outputFile.setLength(fileSize);
            
            // Start timing the download
            startDownloadTimer(filename);
            
            // Calculate chunks
            // Calculate chunks with a maximum size limit
        int numSources = sources.size();
        final long MAX_CHUNK_SIZE = 1024 * 1024; // 1MB chunk size limit
        int numChunks = (int)Math.ceil((double)fileSize / MAX_CHUNK_SIZE);

        // Ensure we have at least as many chunks as sources
        numChunks = Math.max(numChunks, numSources);
        long chunkSize = (fileSize + numChunks - 1) / numChunks; // Ceiling division

        // Create download tasks - distributing chunks among available sources
        CompletionService<Boolean> completionService = 
            new ExecutorCompletionService<>(threadPool);
        int taskCount = 0;

        for (int i = 0; i < numChunks; i++) {
            ClientInfo source = sources.get(i % numSources); // Round-robin distribution
            long startOffset = i * chunkSize;
            long endOffset = Math.min((i + 1) * chunkSize, fileSize);
            
            completionService.submit(() -> downloadChunk(
                source, filename, startOffset, (int)(endOffset - startOffset), outputFile));
            taskCount++;
        }
            // Wait for all downloads to complete
            boolean success = true;
            for (int i = 0; i < taskCount; i++) {
                Future<Boolean> result = completionService.take();
                try {
                    if (!result.get()) {
                        success = false;
                    }
                } catch (ExecutionException e) {
                    success = false;
                    System.err.println("Chunk download failed: " + e.getCause().getMessage());
                }
            }
            
            outputFile.close();
            
            if (success) {
                System.out.println("Downloaded " + filename + " successfully");
                stopDownloadTimer(filename);
                System.out.println(getDownloadStats(filename, fileSize));
            } else {
                System.out.println("Download incomplete for " + filename);
                Files.deleteIfExists(outputPath);
            }
            
            return success;
        } catch (Exception e) {
            System.err.println("Error downloading file: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private long getFileSize(ClientInfo source, String filename) {
        try {
            // First check if file exists
            try (Socket socket = new Socket(source.getHost(), source.getPort());
                 DataInputStream in = new DataInputStream(socket.getInputStream());
                 DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {
                
                out.writeUTF(filename);
                out.writeLong(0);
                out.writeInt(1);
                out.flush();
                
                int response = in.readInt();
                if (response <= 0) {
                    return -1; // File doesn't exist
                }
                in.readByte(); // Skip the byte
            }
            
            // Find approximate file size with exponential probing
            long start = 0;
            long end = 1024; // Start with 1KB
            
            while (true) {
                try (Socket socket = new Socket(source.getHost(), source.getPort());
                     DataInputStream in = new DataInputStream(socket.getInputStream());
                     DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {
                    
                    out.writeUTF(filename);
                    out.writeLong(end);
                    out.writeInt(1);
                    out.flush();
                    
                    int response = in.readInt();
                    if (response <= 0) {
                        break; // Found a position beyond the file
                    }
                    
                    in.readByte(); // Skip data
                    start = end;
                    end *= 2; // Double each time
                    
                    // if (end > 1_073_741_824L) { // Cap at 1GB for safety
                    //     break;
                    // }
                }
            }
            
            // Binary search for exact file size
            while (end - start > 1) {
                long mid = start + (end - start) / 2;
                
                try (Socket socket = new Socket(source.getHost(), source.getPort());
                     DataInputStream in = new DataInputStream(socket.getInputStream());
                     DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {
                    
                    out.writeUTF(filename);
                    out.writeLong(mid);
                    out.writeInt(1);
                    out.flush();
                    
                    int response = in.readInt();
                    if (response > 0) {
                        in.readByte();
                        start = mid;
                    } else {
                        end = mid;
                    }
                }
            }
            
            return start + 1; // This should be the exact file size
        } catch (IOException e) {
            System.err.println("Error getting file size: " + e.getMessage());
            e.printStackTrace();
            return -1;
        }
    }
    
    // Remove the getFileSizeRetry method as it's no longer needed

    private boolean downloadChunk(ClientInfo source, String filename, long offset, int length, 
                                  RandomAccessFile outputFile) {
        try (Socket socket = new Socket(source.getHost(), source.getPort());
             DataInputStream in = new DataInputStream(socket.getInputStream());
             DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {
            
            System.out.println("Downloading chunk from " + source.getHost() + ":" + source.getPort() +
                               " (offset=" + offset + ", length=" + length + ")");
            
            // Send request
            out.writeUTF(filename);
            out.writeLong(offset);
            out.writeInt(length);
            out.flush();
            
            // Read response
            int responseLength = in.readInt();
            
            if (responseLength <= 0) {
                System.out.println("Error response: " + responseLength);
                return false;
            }
            
            // Read chunk data
            byte[] buffer = new byte[4096];
            int bytesRead;
            int totalRead = 0;
            
            synchronized (outputFile) {
                outputFile.seek(offset);
                
                while (totalRead < responseLength && (bytesRead = in.read(buffer, 0, 
                       Math.min(buffer.length, responseLength - totalRead))) != -1) {
                    outputFile.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;
                }
            }
            
            System.out.println("Downloaded " + totalRead + " bytes from offset " + offset);
            return totalRead == responseLength;
        } catch (IOException e) {
            System.err.println("Error downloading chunk: " + e.getMessage());
            return false;
        }
    }

    public void shutdown() {
        threadPool.shutdown();
        System.out.println("Download Manager shutdown");
    }
}

// cd /home/spring/OS/songsong/src
// javac download/DownloadManager.java  