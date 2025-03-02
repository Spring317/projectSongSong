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
        try (Socket socket = new Socket(source.getHost(), source.getPort());
             DataInputStream in = new DataInputStream(socket.getInputStream());
             DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {
            
            // Request a small chunk to verify file exists
            out.writeUTF(filename);
            out.writeLong(0);  // Start from beginning
            out.writeInt(1);   // Just request 1 byte to check if file exists
            out.flush();
            
            int response = in.readInt();
            if (response <= 0) {
                return -1; // File not found or other error
            }
            
            // Skip the byte we requested
            in.readByte();
            
            // Now get the full file size with a second connection
            try (Socket sizeSocket = new Socket(source.getHost(), source.getPort());
                 DataInputStream sizeIn = new DataInputStream(sizeSocket.getInputStream());
                 DataOutputStream sizeOut = new DataOutputStream(sizeSocket.getOutputStream())) {
                
                // Get file contents to determine size
                sizeOut.writeUTF(filename);
                sizeOut.writeLong(0);  // Start from beginning
                sizeOut.writeInt(8192); // Request a reasonable chunk size
                sizeOut.flush();
                
                int chunkSize = sizeIn.readInt();
                if (chunkSize <= 0) {
                    return -1; // Error response
                }
                
                // Check if we got the entire file
                byte[] buffer = new byte[chunkSize];
                int bytesRead = 0;
                int totalRead = 0;
                
                while (totalRead < chunkSize && 
                      (bytesRead = sizeIn.read(buffer, totalRead, chunkSize - totalRead)) != -1) {
                    totalRead += bytesRead;
                }
                
                // If we got less than 8192 bytes, this is the whole file size
                if (totalRead < 8192) {
                    return totalRead;
                }
                
                // Otherwise, try to get more accurate size by requesting a large range
                // This is more reliable than the previous approach
                try (Socket finalSizeSocket = new Socket(source.getHost(), source.getPort());
                     DataInputStream finalSizeIn = new DataInputStream(finalSizeSocket.getInputStream());
                     DataOutputStream finalSizeOut = new DataOutputStream(finalSizeSocket.getOutputStream())) {
                    
                    // This time request the file info by checking at larger offset
                    finalSizeOut.writeUTF(filename);
                    finalSizeOut.writeLong(8192);  // Start after our previous chunk
                    finalSizeOut.writeInt(16384);  // Request another reasonable chunk
                    finalSizeOut.flush();
                    
                    int remainingSize = finalSizeIn.readInt();
                    if (remainingSize <= 0) {
                        // No more data after first chunk, so first chunk was the full file
                        return chunkSize;
                    } else {
                        // We have more data, add it to our total
                        return 8192 + remainingSize;
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Error getting file size: " + e.getMessage());
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