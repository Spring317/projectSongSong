import daemon.ClientDaemon;
import directory.DirectoryServer;
import download.DownloadManager;

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
            } catch (NumberFormatException e) {
                System.out.println("Invalid port number. Using default: " + port);
            }
        }
        
        System.out.println("Starting Client Daemon...");
        ClientDaemon daemon = new ClientDaemon(host, port, directoryHost, directoryPort, sharedDir);
        daemon.start();
    }
    
    private static void startDownload(String[] args) {
        String directoryHost = "localhost";
        int directoryPort = 1099;
        String downloadDir = "./downloads";
        
        // Parse arguments if provided
        if (args.length > 1) {
            directoryHost = args[1];
            
            if (args.length > 2) {
                try {
                    directoryPort = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    System.out.println("Invalid port number. Using default: " + directoryPort);
                }
                
                if (args.length > 3) {
                    downloadDir = args[3];
                }
            }
        }
        
        try {
            DownloadManager manager = new DownloadManager(directoryHost, directoryPort, downloadDir);
            manager.connect();
            
            Scanner scanner = new Scanner(System.in);
            boolean running = true;
            
            while (running) {
                System.out.println("\nDownload Manager");
                System.out.println("1. Download file");
                System.out.println("0. Exit");
                System.out.print("Choice: ");
                
                String choice = scanner.nextLine();
                
                switch (choice) {
                    case "1":
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
            
            manager.shutdown();
            scanner.close();
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}