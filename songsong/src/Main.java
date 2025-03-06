import daemon.ClientDaemon;
import directory.DirectoryServer;
import download.DownloadManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.io.IOException;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage: java Main [directory|daemon|download]");
            return;
        }

        String mode = args[0].toLowerCase();
        
        switch (mode) {
            case "directory":
                startDirectory();
                break;
            case "daemon":
                startDaemon(args);
                break;
            case "download":
                startDownload(args);
                                break;
                            default:
                                System.out.println("Unknown mode: " + mode);
                                System.out.println("Usage: java Main [directory|daemon|download]");
                                break;
                        }
                    }
                    
                    private static void startDownload(String[] args) {
                        String directoryHost = "localhost";
    int directoryPort = 1099;
    String downloadDir = "./downloads";
    
    // Parse arguments if provided
    if (args.length > 1) {
        try {
            directoryHost = args[1];
            
            if (args.length > 2) {
                directoryPort = Integer.parseInt(args[2]);
            }
            
            if (args.length > 3) {
                downloadDir = args[3];
            }
        } catch (NumberFormatException e) {
            System.out.println("Invalid port number. Using default: " + directoryPort);
        }
    }
    
    System.out.println("Starting Download Manager...");
    DownloadManager manager = new DownloadManager(directoryHost, directoryPort, downloadDir);
    
    Scanner scanner = new Scanner(System.in);
    boolean running = true;
    
    try {
        manager.connect();
        
        while (running) {
            System.out.println("\nDownload Manager");
            System.out.println("1. List available files");
            System.out.println("2. Download file");
            System.out.println("0. Exit");
            System.out.print("Choice: ");
            
            String choice = scanner.nextLine();
            
            switch (choice) {
                case "1":
                    manager.displayAvailableFiles();
                    break;
                case "2":
                    System.out.print("Enter file name to download: ");
                    String filename = scanner.nextLine();
                    if (!filename.isEmpty()) {
                        boolean success = manager.downloadFile(filename);
                        System.out.println(success ? "Download successful!" : "Download failed.");
                    }
                    break;
                case "0":
                    running = false;
                    break;
                default:
                    System.out.println("Invalid choice.");
                    break;
            }
        }
    } catch (Exception e) {
        System.err.println("Error connecting to Directory Service: " + e.getMessage());
    } finally {
        scanner.close();
        manager.shutdown();
    }
            ;
                    }
                
                    private static void startDirectory() {
        System.out.println("Starting Directory Server...");
        DirectoryServer.main(new String[0]);
    }
    
    private static void startDaemon(String[] args) {
        String host = "localhost";
        int port = 8000;
        String directoryHost = "localhost";
        int directoryPort = 1099;
        String sharedDir = "./shared";
        
        // Network simulation parameters
        int minLatency = 0;
        int maxLatency = 0;
        double packetLoss = 0.0;
        int bandwidth = 0;
        
        // Parse arguments if provided
        if (args.length > 1) {
            try {
                port = Integer.parseInt(args[1]);
                
                if (args.length > 2) {
                    sharedDir = args[2];
                }
                
                if (args.length > 3) {
                    directoryHost = args[3];
                }
                
                if (args.length > 4) {
                    directoryPort = Integer.parseInt(args[4]);
                }
                
                // Parse network simulation parameters if provided
                if (args.length > 5) {
                    minLatency = Integer.parseInt(args[5]);
                }
                
                if (args.length > 6) {
                    maxLatency = Integer.parseInt(args[6]);
                }
                
                if (args.length > 7) {
                    packetLoss = Double.parseDouble(args[7]);
                }
                
                if (args.length > 8) {
                    bandwidth = Integer.parseInt(args[8]);
                }
            } catch (NumberFormatException e) {
                System.out.println("Invalid number format in arguments. Using defaults.");
            }
        }
        
        System.out.println("Starting Client Daemon...");
        if (minLatency > 0 || maxLatency > 0 || packetLoss > 0 || bandwidth > 0) {
            System.out.println("Network Simulation Enabled:");
            System.out.println("  Latency: " + minLatency + " to " + maxLatency + " ms");
            System.out.println("  Packet Loss: " + (packetLoss * 100) + "%");
            System.out.println("  Bandwidth Limit: " + (bandwidth > 0 ? bandwidth + " KB/s" : "Unlimited"));
        }
        
        // Create daemon with network simulation settings
        ClientDaemon daemon = new ClientDaemon(host, port, directoryHost, directoryPort, sharedDir);
        
        // Start daemon in a separate thread
        Thread daemonThread = new Thread(daemon::start);
        daemonThread.setDaemon(false); // Keep running even if main thread exits
        daemonThread.start();
        

        // Handle interactive commands
        Scanner scanner = new Scanner(System.in);
        boolean running = true;
        
        while (running) {
            System.out.println("\nClient Daemon Console");
            System.out.println("1. Update network simulation settings");
            System.out.println("2. Upload a file");
            System.out.println("3. List shared files");
            System.out.println("0. Exit");
            System.out.print("Choice: ");
            
            String choice = scanner.nextLine();
            
            switch (choice) {
                case "1":
                    // This feature requires implementation in ClientDaemon first
                    System.out.println("Network simulation feature not implemented yet.");
                    break;
                    
                case "2":
                    System.out.print("Enter path to file to upload: ");
                    String filePath = scanner.nextLine();
                    if (!filePath.isEmpty()) {
                        // Call upload method if implemented
                        try {
                            Path source = Paths.get(filePath);
                            if (!Files.exists(source)) {
                                System.out.println("File does not exist: " + filePath);
                                break;
                            }
                            
                            String filename = source.getFileName().toString();
                            Path destination = Paths.get(sharedDir).resolve(filename);
                            
                            System.out.println("Copying file to shared directory...");
                            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                            
                            System.out.println("File copied successfully. Registering with directory service...");
                            daemon.registerFileWithDirectory(filename);
                            
                            System.out.println("File uploaded and registered: " + filename);
                        } catch (Exception e) {
                            System.err.println("Error uploading file: " + e.getMessage());
                        }
                    }
                    break;
                    
                case "3":
                    // List files in shared directory
                    try {
                        System.out.println("\nShared files:");
                        Files.list(Paths.get(sharedDir))
                            .filter(Files::isRegularFile)
                            .forEach(p -> System.out.println(p.getFileName()));
                    } catch (IOException e) {
                        System.err.println("Error listing files: " + e.getMessage());
                    }
                    break;
                    
                case "0":
                    running = false;
                    daemon.stop();
                    break;       
                default:
                    System.out.println("Invalid choice.");
                    break;
            }
        }
        
        scanner.close();
    }
}