package daemon;

import directory.DirectoryInterface;
import model.ClientInfo;
import model.FileInfo;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ClientDaemon {
    private final String clientId;
    private final String host;
    private final int port;
    private final String directoryHost;
    private final int directoryPort;
    private final Path sharedDirectory;
    private final ExecutorService threadPool;
    private DirectoryInterface directoryService;
    private ServerSocket serverSocket;
    private boolean running;

    public ClientDaemon(String host, int port, String directoryHost, int directoryPort, String sharedDirectoryPath) {
        this.clientId = UUID.randomUUID().toString();
        this.host = host;
        this.port = port;
        this.directoryHost = directoryHost;
        this.directoryPort = directoryPort;
        this.sharedDirectory = Paths.get(sharedDirectoryPath);
        this.threadPool = Executors.newFixedThreadPool(10);
        this.running = false;
        try {
            Files.createDirectories(this.sharedDirectory);
            System.out.println("Shared directory created/verified: " + sharedDirectoryPath);
        } catch (IOException e) {
            System.err.println("Error creating shared directory: " + e.getMessage());
        }
    }

    public void start() {
        try {
            // Connect to directory service
            Registry registry = LocateRegistry.getRegistry(directoryHost, directoryPort);
            directoryService = (DirectoryInterface) registry.lookup("DirectoryService");
            
            // Register client
            ClientInfo clientInfo = new ClientInfo(clientId, host, port);
            directoryService.registerClient(clientInfo);
            
            // Register shared files
            registerSharedFiles();
            
            // Start serving files
            try {
                serverSocket = new ServerSocket(port);
            } catch (IOException e) {
                System.out.println("Port " + port + " is already in use. Trying an alternative port...");
                // Try to find an available port
                for (int altPort = port + 1; altPort < port + 100; altPort++) {
                    try {
                        serverSocket = new ServerSocket(altPort);
                        // Update our port in the directory
                        clientInfo = new ClientInfo(clientId, host, altPort);
                        directoryService.registerClient(clientInfo);
                        System.out.println("Using alternative port: " + altPort);
                        break;
                    } catch (IOException ignored) {
                        // Try next port
                    }
                }
                if (serverSocket == null) {
                    throw new IOException("No available ports found");
                }
            }
    
            running = true;
            
            System.out.println("Client daemon started on port " + serverSocket.getLocalPort());
            
            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
            
            // Listen for incoming connections
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    threadPool.submit(() -> handleRequest(socket));
                } catch (IOException e) {
                    if (running) {
                        System.err.println("Error accepting connection: " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Client daemon error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void registerSharedFiles() {
        try {
            Files.list(sharedDirectory).filter(Files::isRegularFile).forEach(file -> {
                try {
                    FileInfo fileInfo = new FileInfo(file.getFileName().toString(), Files.size(file));
                    directoryService.registerFile(clientId, fileInfo);
                    System.out.println("Registered file: " + file.getFileName());
                } catch (Exception e) {
                    System.err.println("Error registering file " + file.getFileName() + ": " + e.getMessage());
                }
            });
        } catch (IOException e) {
            System.err.println("Error scanning shared directory: " + e.getMessage());
        }
    }

    private void handleRequest(Socket socket) {
        try (
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream())
        ) {
            // Read filename and chunk info
            String filename = in.readUTF();
            long offset = in.readLong();
            int length = in.readInt();
            
            System.out.println("Received request for " + filename + " (offset=" + offset + ", length=" + length + ")");
            
            // Find and read file
            Path filePath = sharedDirectory.resolve(filename);
            if (!Files.exists(filePath)) {
                out.writeInt(-1); // File not found
                return;
            }
            
            long fileSize = Files.size(filePath);
            if (offset >= fileSize) {
                out.writeInt(-2); // Invalid offset
                return;
            }
            
            // Adjust length if needed
            if (offset + length > fileSize) {
                length = (int)(fileSize - offset);
            }
            
            // Send chunk size
            out.writeInt(length);
            
            // Send file chunk
            try (RandomAccessFile file = new RandomAccessFile(filePath.toFile(), "r")) {
                file.seek(offset);
                byte[] buffer = new byte[4096];
                int bytesRead;
                int totalSent = 0;
                
                while (totalSent < length && (bytesRead = file.read(buffer, 0, Math.min(buffer.length, length - totalSent))) != -1) {
                    out.write(buffer, 0, bytesRead);
                    totalSent += bytesRead;
                }
                
                out.flush();
                System.out.println("Sent " + totalSent + " bytes for " + filename);
            }
        } catch (IOException e) {
            System.err.println("Error handling client request: " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                System.err.println("Error closing socket: " + e.getMessage());
            }
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            if (directoryService != null) {
                directoryService.unregisterClient(clientId);
            }
            threadPool.shutdown();
            System.out.println("Client daemon stopped");
        } catch (Exception e) {
            System.err.println("Error stopping client daemon: " + e.getMessage());
        }
    }
}