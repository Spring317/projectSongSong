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
import java.util.List;
import java.util.concurrent.*;

public class DownloadManager {
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
            
            // Calculate chunks
            int numSources = sources.size();
            long chunkSize = fileSize / numSources;
            if (chunkSize == 0) {
                chunkSize = 1;
            }
            
            // Create download tasks
            CompletionService<Boolean> completionService = 
                new ExecutorCompletionService<>(threadPool);
            int taskCount = 0;
            
            for (int i = 0; i < numSources; i++) {
                ClientInfo source = sources.get(i);
                long startOffset = i * chunkSize;
                long endOffset = (i == numSources - 1) ? fileSize : (i + 1) * chunkSize;
                
                completionService.submit(() -> downloadChunk(
                    source, filename, startOffset, (int)(endOffset - startOffset), outputFile));
                taskCount++;
            }
            
            // Wait for all downloads to complete
            boolean success = true;
            for (int i = 0; i < taskCount; i++) {
                Future<Boolean> result = completionService.take();
                success = success && result.get();
            }
            
            outputFile.close();
            
            if (success) {
                System.out.println("Downloaded " + filename + " successfully");
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